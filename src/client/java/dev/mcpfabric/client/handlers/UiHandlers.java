package dev.mcpfabric.client.handlers;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.mojang.blaze3d.platform.InputConstants;
import dev.mcpfabric.bridge.RpcContext;
import dev.mcpfabric.bridge.RpcException;
import dev.mcpfabric.bridge.RpcRouter;
import dev.mcpfabric.client.ClientMc;
import dev.mcpfabric.handlers.support.Gates;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.AbstractStringWidget;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Checkbox;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.events.ContainerEventHandler;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.AlertScreen;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.gui.screens.ConfirmLinkScreen;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.DeathScreen;
import net.minecraft.client.gui.screens.DirectJoinServerScreen;
import net.minecraft.client.gui.screens.DisconnectedScreen;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.screens.achievement.StatsScreen;
import net.minecraft.client.gui.screens.dialog.DialogScreen;
import net.minecraft.client.gui.screens.advancements.AdvancementsScreen;
import net.minecraft.client.gui.screens.inventory.AbstractCommandBlockEditScreen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.BookViewScreen;
import net.minecraft.client.gui.screens.inventory.JigsawBlockEditScreen;
import net.minecraft.client.gui.screens.inventory.StructureBlockEditScreen;
import net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen;
import net.minecraft.client.gui.screens.options.OnlineOptionsScreen;
import net.minecraft.client.gui.screens.options.OptionsScreen;
import net.minecraft.client.gui.screens.options.OptionsSubScreen;
import net.minecraft.client.gui.screens.packs.PackSelectionScreen;
import net.minecraft.client.gui.screens.social.SocialInteractionsScreen;
import net.minecraft.client.gui.screens.worldselection.EditWorldScreen;
import net.minecraft.client.gui.screens.worldselection.SelectWorldScreen;
//? if >=1.21.9 {
/*import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.MouseButtonInfo;
*///?}
import net.minecraft.network.chat.contents.TranslatableContents;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;

/**
 * Generic control of the open screen (#14): list its widgets, click one, type into a text field.
 * Locked behind {@code enableUiControl} on top of {@code enablePlayerControl}.
 * <p>
 * Pawverse build: the checks below are off (see {@code PAWVERSE_UNRESTRICTED}); upstream behaves as follows.
 * Vanilla screens are refused unless they are on a short allowlist (pause, title, death, options,
 * advancements, statistics, containers, confirmations and server dialogs), so a screen that deletes worlds, opens links or folders,
 * edits server addresses, changes account settings, reports players, opens the game to LAN or edits
 * command blocks cannot be driven, and a vanilla screen added in a later version is refused until it
 * is reviewed. Screens of other mods are allowed unless they extend one of the refused vanilla
 * screens. Chat and the server and world menus have their own tools ({@code send_chat},
 * {@code join_world}).
 * <p>
 * Only widgets (buttons, checkboxes, cycle buttons, sliders, text fields, labels) are listed;
 * custom-drawn parts of a screen such as inventory slots or creative tabs are not.
 */
public final class UiHandlers {
	private UiHandlers() {}

	private static final int MAX_WIDGETS = 5000;
	/** Only limits what is reported back; what can be typed has no limit of its own. */
	private static final int MAX_TEXT = 4096;

	/**
	 * Refused even when a mod's screen extends them: menus with their own tools, links, folders, servers,
	 * account settings, player reports and command blocks.
	 */
	private static final List<Class<?>> REFUSED = List.of(
			ChatScreen.class, BookViewScreen.class, ConfirmLinkScreen.class,
			JoinMultiplayerScreen.class, DirectJoinServerScreen.class, ConnectScreen.class, DisconnectedScreen.class,
			SelectWorldScreen.class, EditWorldScreen.class, PackSelectionScreen.class, OnlineOptionsScreen.class,
			SocialInteractionsScreen.class, AbstractCommandBlockEditScreen.class, StructureBlockEditScreen.class,
			JigsawBlockEditScreen.class);

	/**
	 * The vanilla screens that may be driven. Confirmations are allowed because the death screen asks
	 * one before leaving to the title screen; the dangerous ones (deleting a world, opening a link)
	 * start from refused screens or are refused themselves.
	 */
	private static final List<Class<?>> VANILLA_ALLOWED = List.of(
			PauseScreen.class, TitleScreen.class, DeathScreen.class, OptionsScreen.class, OptionsSubScreen.class,
			AdvancementsScreen.class, StatsScreen.class, AbstractContainerScreen.class, ConfirmScreen.class,
			AlertScreen.class, DialogScreen.class);

	/** Buttons that end the bridge: asked for explicitly (allowQuit), so a mistaken click does not close the game. */
	private static final Set<String> REFUSED_BUTTONS = Set.of("menu.quit");

	/** Pawverse build: no screen allowlist; Quit Game only needs allowQuit, since it also ends the bridge. */
	private static final boolean PAWVERSE_UNRESTRICTED = true;

	public static void register(RpcRouter router) {
		router.register("ui.widgets", ctx -> ClientMc.call(() -> {
			Gates.uiControl();
			Screen screen = ContainerHandlers.currentScreen();
			JsonObject o = new JsonObject();
			o.addProperty("screen", SessionScreenHandlers.kind(screen));
			if (screen == null) {
				o.add("widgets", new JsonArray());
				return o;
			}
			requireAllowed(screen);
			o.addProperty("title", screen.getTitle().getString());
			o.addProperty("screenClass", screen.getClass().getName());
			o.addProperty("guiScale", ClientMc.mc().getWindow().getGuiScale());
			o.addProperty("width", screen.width);
			o.addProperty("height", screen.height);
			JsonArray widgets = new JsonArray();
			List<AbstractWidget> list = widgets(screen);
			for (int i = 0; i < list.size(); i++) widgets.add(describe(i, list.get(i)));
			o.add("widgets", widgets);
			if (list.size() == MAX_WIDGETS) o.addProperty("truncated", true);
			return o;
		}));

		router.register("ui.click", ctx -> ClientMc.call(() -> {
			Gates.uiControl();
			Screen screen = openScreen();
			AbstractWidget w = find(screen, ctx);
			if (!ctx.optBool("allowQuit", false) && w.getMessage().getContents() instanceof TranslatableContents t && REFUSED_BUTTONS.contains(t.getKey())) {
				throw new RpcException("ui_refused", "This button (" + w.getMessage().getString()
						+ ") closes the game and with it the bridge. Pass allowQuit to click it anyway.");
			}
			if (!w.isActive()) throw new RpcException("inactive", "Widget \"" + w.getMessage().getString() + "\" is disabled.");
			double fraction = Math.max(0, Math.min(1, ctx.optDouble("fraction", 0.5)));
			double x = w.getX() + Math.max(0.5, Math.min(w.getWidth() - 0.5, w.getWidth() * fraction));
			double y = w.getY() + w.getHeight() / 2.0;
			String label = w.getMessage().getString();
			// The left button is 0 up to 26.2 and 1 since 26.3 (SDL input).
			int left = InputConstants.MOUSE_BUTTON_LEFT;
			//? if <1.21.9 {
			boolean handled = w.mouseClicked(x, y, left);
			if (handled) w.mouseReleased(x, y, left);
			//?} else {
			/*MouseButtonEvent event = new MouseButtonEvent(x, y, new MouseButtonInfo(left, 0));
			boolean handled = w.mouseClicked(event, false);
			if (handled) w.mouseReleased(event);
			*///?}
			if (!handled) throw new RpcException("not_clickable", "Widget \"" + label + "\" did not take the click.");
			return afterAction("clicked", label);
		}));

		router.register("ui.setText", ctx -> ClientMc.call(() -> {
			Gates.uiControl();
			String text = ctx.getString("text");
			Screen screen = openScreen();
			AbstractWidget w = find(screen, ctx);
			if (!(w instanceof EditBox box)) {
				throw RpcException.badRequest("Widget \"" + w.getMessage().getString() + "\" is not a text field.");
			}
			boolean focused = box.isFocused();
			box.setFocused(true);
			boolean editable = box.canConsumeInput();
			box.setFocused(focused);
			if (!editable) throw new RpcException("inactive", "Text field \"" + box.getMessage().getString() + "\" is not editable.");
			box.setValue(text);
			JsonObject o = afterAction("set", box.getMessage().getString());
			o.addProperty("value", clip(box.getValue()));
			return o;
		}));
	}

	private static Screen openScreen() throws RpcException {
		Screen screen = ContainerHandlers.currentScreen();
		if (screen == null) throw new RpcException("no_screen", "No screen is open.");
		requireAllowed(screen);
		return screen;
	}

	private static void requireAllowed(Screen screen) throws RpcException {
		// Pawverse build: every screen can be driven so in-game tests can be automated end to end.
		if (PAWVERSE_UNRESTRICTED) return;
		for (Class<?> refused : REFUSED) {
			if (refused.isInstance(screen)) throw refusedScreen(screen);
		}
		if (!isVanilla(screen)) return;
		for (Class<?> allowed : VANILLA_ALLOWED) {
			if (allowed.isInstance(screen)) return;
		}
		throw refusedScreen(screen);
	}

	private static RpcException refusedScreen(Screen screen) {
		return new RpcException("ui_refused", "This screen (" + SessionScreenHandlers.kind(screen)
				+ ") is not open to UI control. Use get_screen, close_screen, list_worlds, join_world or send_chat instead.");
	}

	/** Minecraft's own classes; in production on Fabric they are named net.minecraft.class_NNN. */
	private static boolean isVanilla(Screen screen) {
		String name = screen.getClass().getName();
		return name.startsWith("net.minecraft.") || name.startsWith("com.mojang.");
	}

	/** Visible widgets in focus order, depth first; containers such as lists are walked, not listed. */
	static List<AbstractWidget> widgets(Screen screen) {
		List<AbstractWidget> out = new ArrayList<>();
		collect(screen, out, Collections.newSetFromMap(new IdentityHashMap<>()));
		return out;
	}

	private static void collect(GuiEventListener listener, List<AbstractWidget> out, Set<GuiEventListener> seen) {
		if (out.size() >= MAX_WIDGETS || !seen.add(listener)) return;
		if (listener instanceof ContainerEventHandler container) {
			if (listener instanceof AbstractWidget w && !w.visible) return;
			for (GuiEventListener child : container.children()) collect(child, out, seen);
		} else if (listener instanceof AbstractWidget w && w.visible) {
			out.add(w);
		}
	}

	private static AbstractWidget find(Screen screen, RpcContext ctx) throws RpcException {
		JsonElement ref = ctx.params().get("widget");
		if (!(ref instanceof JsonPrimitive p)) throw RpcException.badRequest("widget (an id or a label from get_ui) is required.");
		List<AbstractWidget> list = widgets(screen);
		if (p.isNumber()) {
			int id = p.getAsInt();
			if (id < 0 || id >= list.size()) throw RpcException.notFound("No widget " + id + " on this screen; call get_ui again.");
			return list.get(id);
		}
		String label = p.getAsString().trim();
		AbstractWidget match = null;
		for (AbstractWidget w : list) {
			if (!w.getMessage().getString().trim().equalsIgnoreCase(label)) continue;
			if (match != null) throw RpcException.badRequest("More than one widget is labelled \"" + label + "\"; pass its id from get_ui.");
			match = w;
		}
		if (match == null) throw RpcException.notFound("No widget labelled \"" + label + "\" on this screen; call get_ui.");
		return match;
	}

	private static JsonObject describe(int id, AbstractWidget w) {
		JsonObject o = new JsonObject();
		o.addProperty("id", id);
		o.addProperty("type", type(w));
		o.addProperty("text", clip(w.getMessage().getString()));
		o.addProperty("active", w.isActive());
		if (w instanceof Checkbox box) o.addProperty("checked", box.selected());
		if (w instanceof EditBox box) {
			o.addProperty("value", clip(box.getValue()));
			o.addProperty("focused", box.isFocused());
		}
		o.addProperty("x", w.getX());
		o.addProperty("y", w.getY());
		o.addProperty("width", w.getWidth());
		o.addProperty("height", w.getHeight());
		return o;
	}

	private static String type(AbstractWidget w) {
		if (w instanceof EditBox) return "text_field";
		if (w instanceof Checkbox) return "checkbox";
		if (w instanceof CycleButton<?>) return "cycle";
		if (w instanceof AbstractButton) return "button";
		if (w instanceof AbstractSliderButton) return "slider";
		if (w instanceof AbstractStringWidget) return "text";
		return "other";
	}

	/** The screen after an action, which may have opened another one or closed it. */
	private static JsonObject afterAction(String action, String label) {
		Screen now = ContainerHandlers.currentScreen();
		JsonObject o = new JsonObject();
		o.addProperty(action, clip(label));
		o.addProperty("screen", SessionScreenHandlers.kind(now));
		if (now != null) o.addProperty("title", now.getTitle().getString());
		return o;
	}

	private static String clip(String s) {
		return s.length() <= MAX_TEXT ? s : s.substring(0, MAX_TEXT) + "...";
	}
}
