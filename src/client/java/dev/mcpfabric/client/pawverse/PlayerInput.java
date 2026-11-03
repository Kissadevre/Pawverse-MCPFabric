package dev.mcpfabric.client.pawverse;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.platform.Window;
import dev.mcpfabric.bridge.RpcContext;
import dev.mcpfabric.bridge.RpcException;
import dev.mcpfabric.bridge.RpcRouter;
import dev.mcpfabric.client.ClientMc;
import dev.mcpfabric.client.handlers.ContainerHandlers;
import dev.mcpfabric.client.handlers.SessionScreenHandlers;
import dev.mcpfabric.handlers.support.Gates;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.MouseButtonInfo;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Pawverse build: drives the game through the same entry points the window feeds with a physical
 * keyboard and mouse ({@code KeyboardHandler.keyPress}, {@code charTyped}, {@code MouseHandler.onButton},
 * {@code onMove}, {@code onScroll}). Whatever a player reaches with those devices is reachable here:
 * any key binding (also those of other mods), any screen, custom-drawn parts of a screen included.
 * <p>
 * Locked behind {@code enableUiControl}: it reaches everything the screen tools reach, chat commands too.
 * Game state is touched on the render thread only ({@link ClientMc#call}).
 */
public final class PlayerInput {
	private PlayerInput() {}

	/** An input the bridge holds down, released after {@code ticks} (never while negative). */
	private static final class Held {
		final InputConstants.Key key;
		final int modifiers;
		int ticks;

		Held(InputConstants.Key key, int modifiers, int ticks) {
			this.key = key;
			this.modifiers = modifiers;
			this.ticks = ticks;
		}
	}

	// Render thread only.
	private static final Map<String, Held> HELD = new LinkedHashMap<>();

	public static void register(RpcRouter router) {
		router.register("input.key", ctx -> ClientMc.call(() -> {
			Gates.uiControl();
			InputConstants.Key key = resolve(ctx.getString("key"));
			applyAction(key, modifiers(ctx), ctx);
			return state("key", key.getName());
		}));

		router.register("input.type", ctx -> ClientMc.call(() -> {
			Gates.uiControl();
			Minecraft mc = ClientMc.mc();
			String text = ctx.getString("text");
			long window = mc.getWindow().handle();
			text.codePoints().forEach(cp -> mc.keyboardHandler.charTyped(window, new CharacterEvent(cp)));
			return state("typed", text.length() + " characters");
		}));

		router.register("input.mouseMove", ctx -> ClientMc.call(() -> {
			Gates.uiControl();
			moveTo(ctx.getDouble("x"), ctx.getDouble("y"));
			return state("moved", "cursor");
		}));

		router.register("input.mouseButton", ctx -> ClientMc.call(() -> {
			Gates.uiControl();
			if (ctx.has("x") || ctx.has("y")) moveTo(ctx.getDouble("x"), ctx.getDouble("y"));
			InputConstants.Key button = mouseButton(ctx.optString("button", "left"));
			int clicks = Math.max(1, ctx.optInt("clicks", 1));
			int mods = modifiers(ctx);
			// All but the last click of a double click are complete press + release pairs.
			for (int i = 1; i < clicks; i++) {
				send(button, mods, InputConstants.PRESS);
				send(button, mods, InputConstants.RELEASE);
			}
			applyAction(button, mods, ctx);
			return state("button", button.getName());
		}));

		router.register("input.mouseDrag", ctx -> ClientMc.call(() -> {
			Gates.uiControl();
			Minecraft mc = ClientMc.mc();
			InputConstants.Key button = mouseButton(ctx.optString("button", "left"));
			int mods = modifiers(ctx);
			double fromX = ctx.getDouble("fromX"), fromY = ctx.getDouble("fromY");
			double toX = ctx.getDouble("toX"), toY = ctx.getDouble("toY");
			int steps = Math.max(1, ctx.optInt("steps", 8));
			moveTo(fromX, fromY);
			send(button, mods, InputConstants.PRESS);
			double lastX = fromX, lastY = fromY;
			for (int i = 1; i <= steps; i++) {
				double x = fromX + (toX - fromX) * i / steps, y = fromY + (toY - fromY) * i / steps;
				moveTo(x, y);
				Screen screen = ContainerHandlers.currentScreen();
				// An active window replays the move as a drag each frame; a window without focus never does.
				if (screen != null && !mc.isWindowActive()) {
					screen.mouseDragged(new MouseButtonEvent(x, y, new MouseButtonInfo(button.getValue(), mods)), x - lastX, y - lastY);
				}
				lastX = x;
				lastY = y;
			}
			send(button, mods, InputConstants.RELEASE);
			return state("dragged", button.getName());
		}));

		router.register("input.mouseScroll", ctx -> ClientMc.call(() -> {
			Gates.uiControl();
			Minecraft mc = ClientMc.mc();
			if (ctx.has("x") || ctx.has("y")) moveTo(ctx.getDouble("x"), ctx.getDouble("y"));
			mc.mouseHandler.onScroll(mc.getWindow().handle(), ctx.optDouble("deltaX", 0), ctx.optDouble("deltaY", 0));
			return state("scrolled", "wheel");
		}));

		router.register("input.releaseAll", ctx -> ClientMc.call(() -> {
			releaseAll();
			return state("released", "all");
		}));

		router.register("input.state", ctx -> ClientMc.call(() -> {
			Gates.uiControl();
			return state(null, null);
		}));

		router.register("input.keybinds", ctx -> ClientMc.call(() -> {
			Gates.uiControl();
			JsonArray out = new JsonArray();
			for (KeyMapping mapping : ClientMc.mc().options.keyMappings) {
				JsonObject o = new JsonObject();
				o.addProperty("id", mapping.getName());
				o.addProperty("key", mapping.saveString());
				o.addProperty("down", mapping.isDown());
				out.add(o);
			}
			JsonObject o = new JsonObject();
			o.add("keybinds", out);
			return o;
		}));
	}

	/** End of every client tick. */
	public static void onClientTick(Minecraft mc) {
		if (HELD.isEmpty()) return;
		if (mc.level == null) {
			releaseAll();
			return;
		}
		boolean inWorld = ContainerHandlers.currentScreen() == null;
		Iterator<Held> it = HELD.values().iterator();
		while (it.hasNext()) {
			Held held = it.next();
			if (held.ticks > 0 && --held.ticks == 0) {
				it.remove();
				send(held.key, held.modifiers, InputConstants.RELEASE);
			} else if (inWorld) {
				// Closing a screen resets the key mappings to the physical keys, which are not down.
				KeyMapping.set(held.key, true);
			}
		}
		if (inWorld) keepMining(mc);
	}

	/**
	 * Vanilla keeps mining only while the mouse is grabbed, which a window without focus never does,
	 * so a test would behave differently depending on what the desktop has focused.
	 */
	private static void keepMining(Minecraft mc) {
		LocalPlayer p = mc.player;
		if (p == null || mc.gameMode == null || mc.mouseHandler.isMouseGrabbed() || !mc.options.keyAttack.isDown()) return;
		if (mc.hitResult instanceof BlockHitResult hit && hit.getType() == HitResult.Type.BLOCK) {
			mc.gameMode.continueDestroyBlock(hit.getBlockPos(), hit.getDirection());
			p.swing(InteractionHand.MAIN_HAND, p.getMainHandItem().getAttackAnimation(), false);
		}
	}

	private static void applyAction(InputConstants.Key key, int mods, RpcContext ctx) throws RpcException {
		String action = ctx.optString("action", "tap").toLowerCase(Locale.ROOT);
		switch (action) {
			case "tap" -> {
				send(key, mods, InputConstants.PRESS);
				HELD.put(key.getName(), new Held(key, mods, Math.max(1, ctx.optInt("holdTicks", 1))));
			}
			case "down" -> {
				send(key, mods, InputConstants.PRESS);
				HELD.put(key.getName(), new Held(key, mods, -1));
			}
			case "up" -> {
				HELD.remove(key.getName());
				send(key, mods, InputConstants.RELEASE);
			}
			default -> throw RpcException.badRequest("action must be tap, down or up.");
		}
	}

	private static void releaseAll() {
		List<Held> held = List.copyOf(HELD.values());
		HELD.clear();
		for (Held h : held) send(h.key, h.modifiers, InputConstants.RELEASE);
	}

	/** One press or release, through the handler the window would call. */
	private static void send(InputConstants.Key key, int mods, int action) {
		Minecraft mc = Minecraft.getInstance();
		long window = mc.getWindow().handle();
		if (key.getType() == InputConstants.Type.MOUSE) {
			mc.mouseHandler.onButton(window, new MouseButtonInfo(key.getValue(), mods), action);
		} else {
			mc.keyboardHandler.keyPress(window, action, new KeyEvent(key.getValue(), key.getValue(), mods));
		}
	}

	/** Moves the cursor to a position in GUI coordinates, the ones {@code get_ui} reports. */
	private static void moveTo(double x, double y) {
		Minecraft mc = Minecraft.getInstance();
		Window w = mc.getWindow();
		double px = x * w.getScreenWidth() / w.getGuiScaledWidth();
		double py = y * w.getScreenHeight() / w.getGuiScaledHeight();
		mc.mouseHandler.onMove(w.handle(), px, py, 0, 0);
	}

	/** {@code key.keyboard.e}, {@code key.mouse.left}, a key binding such as {@code key.inventory}, or just {@code e}. */
	private static InputConstants.Key resolve(String raw) throws RpcException {
		String name = raw.trim();
		KeyMapping mapping = KeyMapping.get(name);
		if (mapping != null) {
			if (mapping.isUnbound()) throw RpcException.badRequest("Key binding " + name + " has no key assigned.");
			return known(mapping.saveString(), raw);
		}
		String lower = name.toLowerCase(Locale.ROOT);
		return known(lower.startsWith("key.") ? lower : "key.keyboard." + lower, raw);
	}

	private static InputConstants.Key known(String name, String raw) throws RpcException {
		InputConstants.Key key;
		try {
			key = InputConstants.getKey(name);
		} catch (IllegalArgumentException e) {
			key = InputConstants.UNKNOWN;
		}
		if (key == InputConstants.UNKNOWN) {
			throw RpcException.badRequest("Unknown key \"" + raw + "\". Use a name such as e, escape, enter, left.shift, f3, "
					+ "key.mouse.left, or a binding id from get_keybinds such as key.inventory.");
		}
		return key;
	}

	private static InputConstants.Key mouseButton(String name) throws RpcException {
		return InputConstants.Type.MOUSE.getOrCreate(switch (name.toLowerCase(Locale.ROOT)) {
			case "left" -> InputConstants.MOUSE_BUTTON_LEFT;
			case "right" -> InputConstants.MOUSE_BUTTON_RIGHT;
			case "middle" -> InputConstants.MOUSE_BUTTON_MIDDLE;
			case "4" -> InputConstants.MOUSE_BUTTON_4;
			case "5" -> InputConstants.MOUSE_BUTTON_5;
			default -> throw RpcException.badRequest("button must be left, right, middle, 4 or 5.");
		});
	}

	private static int modifiers(RpcContext ctx) throws RpcException {
		int mods = 0;
		if (ctx.optBool("shift", false)) mods |= InputConstants.MOD_SHIFT;
		if (ctx.optBool("ctrl", false)) mods |= InputConstants.MOD_CONTROL;
		if (ctx.optBool("alt", false)) mods |= InputConstants.MOD_ALT;
		return mods;
	}

	/** The result of every input call: what the screen is now, where the cursor is and what stays held. */
	private static JsonObject state(String action, String what) {
		Minecraft mc = Minecraft.getInstance();
		JsonObject o = new JsonObject();
		if (action != null) o.addProperty(action, what);
		Screen screen = ContainerHandlers.currentScreen();
		o.addProperty("screen", SessionScreenHandlers.kind(screen));
		if (screen != null) {
			o.addProperty("title", screen.getTitle().getString());
			o.addProperty("screenClass", screen.getClass().getName());
		}
		Window w = mc.getWindow();
		o.addProperty("cursorX", mc.mouseHandler.xpos() * w.getGuiScaledWidth() / w.getScreenWidth());
		o.addProperty("cursorY", mc.mouseHandler.ypos() * w.getGuiScaledHeight() / w.getScreenHeight());
		o.addProperty("guiScale", w.getGuiScale());
		o.addProperty("windowActive", mc.isWindowActive());
		JsonArray held = new JsonArray();
		HELD.keySet().forEach(held::add);
		o.add("held", held);
		return o;
	}
}
