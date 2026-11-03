package dev.mcpfabric.client.pawverse;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.ParseResults;
import com.mojang.brigadier.suggestion.Suggestion;
import com.mojang.brigadier.suggestion.Suggestions;
import dev.mcpfabric.McpFabric;
import dev.mcpfabric.bridge.Json;
import dev.mcpfabric.bridge.RpcException;
import dev.mcpfabric.bridge.RpcRouter;
import dev.mcpfabric.client.ClientMc;
import dev.mcpfabric.client.handlers.ContainerHandlers;
import dev.mcpfabric.client.handlers.InteractHandlers;
import dev.mcpfabric.client.handlers.SessionScreenHandlers;
import dev.mcpfabric.handlers.support.Gates;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Hud;
import net.minecraft.client.gui.components.BossHealthOverlay;
import net.minecraft.client.gui.components.ChatComponent;
import net.minecraft.client.gui.components.LerpingBossEvent;
import net.minecraft.client.gui.components.PlayerTabOverlay;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.ClientSuggestionProvider;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.multiplayer.chat.GuiMessage;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.MerchantMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.trading.MerchantOffer;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.entity.SignTextSlot;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.scores.DisplaySlot;
import net.minecraft.world.scores.Objective;
import net.minecraft.world.scores.PlayerScoreEntry;
import net.minecraft.world.scores.PlayerTeam;
import net.minecraft.world.scores.Scoreboard;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Pawverse build: what a player perceives on any server, read from the client's own state, so it
 * works on a remote server, where the {@code world.*} and {@code entities.*} tools do not: the HUD
 * (action bar, titles, boss bars, sidebar, tab list), clickable chat, command suggestions, item
 * names and lore, entity names and text displays, block states and signs, and the sounds played.
 * <p>
 * The HUD keeps these values in private fields. They are read by name, which holds on the unobfuscated
 * 26.x line this package is built for; a field that moved is reported in {@code unavailable}.
 */
public final class PlayerSenses {
	private PlayerSenses() {}

	private static final int SOUND_HISTORY = 256;
	private static final long SUGGESTION_TIMEOUT_MS = 5_000;

	public static void register(RpcRouter router) {
		router.register("hud.get", ctx -> ClientMc.call(() -> {
			Gates.vision();
			return hud(ClientMc.mc(), ClientMc.player());
		}));

		router.register("container.inspect", ctx -> ClientMc.call(() -> {
			Gates.playerControl();
			return inspectContainer(ClientMc.player(), ctx.optBool("includeInventory", false));
		}));

		router.register("container.drag", ctx -> ClientMc.call(() -> {
			Gates.playerControl();
			LocalPlayer p = ClientMc.player();
			AbstractContainerMenu menu = p.containerMenu;
			int type = switch (ctx.optString("button", "left").toLowerCase(Locale.ROOT)) {
				case "left" -> AbstractContainerMenu.QUICKCRAFT_TYPE_CHARITABLE;
				case "right" -> AbstractContainerMenu.QUICKCRAFT_TYPE_GREEDY;
				case "middle" -> AbstractContainerMenu.QUICKCRAFT_TYPE_CLONE;
				default -> throw RpcException.badRequest("button must be left, right or middle.");
			};
			int[] slots = ints(ctx.params().get("slots"));
			if (slots.length == 0) throw RpcException.badRequest("slots needs at least one slot.");
			var gm = ClientMc.gameMode();
			var input = net.minecraft.world.inventory.ContainerInput.QUICK_CRAFT;
			int outside = AbstractContainerMenu.SLOT_CLICKED_OUTSIDE;
			gm.handleContainerInput(menu.containerId, outside, AbstractContainerMenu.getQuickcraftMask(AbstractContainerMenu.QUICKCRAFT_HEADER_START, type), input, p);
			for (int slot : slots) {
				gm.handleContainerInput(menu.containerId, slot, AbstractContainerMenu.getQuickcraftMask(AbstractContainerMenu.QUICKCRAFT_HEADER_CONTINUE, type), input, p);
			}
			gm.handleContainerInput(menu.containerId, outside, AbstractContainerMenu.getQuickcraftMask(AbstractContainerMenu.QUICKCRAFT_HEADER_END, type), input, p);
			return inspectContainer(p, false);
		}));

		router.register("interact.block", ctx -> ClientMc.call(() -> {
			Gates.playerControl();
			LocalPlayer p = ClientMc.player();
			BlockPos pos = BlockPos.containing(ctx.getDouble("x"), ctx.getDouble("y"), ctx.getDouble("z"));
			Direction face = Direction.byName(ctx.optString("face", "up").toLowerCase(Locale.ROOT));
			if (face == null) throw RpcException.badRequest("face must be up, down, north, south, east or west.");
			InteractionHand hand = InteractHandlers.hand(ctx.optString("hand", "main"));
			Vec3 hit = new Vec3(
					ctx.optDouble("hitX", pos.getX() + 0.5 + face.getStepX() * 0.5),
					ctx.optDouble("hitY", pos.getY() + 0.5 + face.getStepY() * 0.5),
					ctx.optDouble("hitZ", pos.getZ() + 0.5 + face.getStepZ() * 0.5));
			InteractionResult result = ClientMc.gameMode().useItemOn(p, hand, new BlockHitResult(hit, face, pos, false));
			p.swing(hand, p.getItemInHand(hand).getInteractAnimation(), false);
			JsonObject o = new JsonObject();
			o.addProperty("result", String.valueOf(result));
			o.addProperty("note", "'result' is the client's prediction; the server decides. Check get_block_client, get_container or get_hud.");
			return o;
		}));

		router.register("chat.lines", ctx -> ClientMc.call(() -> {
			Gates.vision();
			List<GuiMessage> all = chatMessages();
			int limit = Math.max(1, ctx.optInt("limit", 50));
			JsonArray out = new JsonArray();
			// The list holds the newest message first; answer oldest first, like get_recent_chat.
			for (int i = Math.min(limit, all.size()) - 1; i >= 0; i--) out.add(chatLine(all.get(i)));
			JsonObject o = new JsonObject();
			o.add("lines", out);
			return o;
		}));

		router.register("chat.click", ctx -> ClientMc.call(() -> {
			Gates.playerControl();
			int ref = ctx.getInt("ref");
			int part = ctx.optInt("part", -1);
			for (GuiMessage message : chatMessages()) {
				if (System.identityHashCode(message) != ref) continue;
				return click(message, part);
			}
			throw RpcException.notFound("No chat line " + ref + "; read get_chat_lines again.");
		}));

		router.register("chat.suggest", ctx -> {
			Gates.playerControl();
			String text = ctx.getString("text");
			String command = text.startsWith("/") ? text.substring(1) : text;
			CompletableFuture<Suggestions> future = ClientMc.call(() -> {
				ClientPacketListener connection = ClientMc.player().connection;
				CommandDispatcher<ClientSuggestionProvider> dispatcher = connection.getCommands();
				ParseResults<ClientSuggestionProvider> parse = dispatcher.parse(command, connection.getSuggestionsProvider());
				return dispatcher.getCompletionSuggestions(parse);
			});
			Suggestions suggestions;
			try {
				suggestions = future.get(SUGGESTION_TIMEOUT_MS, TimeUnit.MILLISECONDS);
			} catch (TimeoutException e) {
				throw new RpcException("timeout", "The server did not answer the suggestions within " + SUGGESTION_TIMEOUT_MS + "ms.");
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				throw new RpcException("interrupted", "Interrupted while waiting for suggestions.");
			} catch (ExecutionException e) {
				throw new RpcException("internal", "Could not read suggestions: " + e.getCause());
			}
			JsonArray out = new JsonArray();
			for (Suggestion s : suggestions.getList()) {
				JsonObject o = new JsonObject();
				o.addProperty("text", s.getText());
				if (s.getTooltip() != null) o.addProperty("tooltip", s.getTooltip().getString());
				out.add(o);
			}
			JsonObject o = new JsonObject();
			o.add("suggestions", out);
			return o;
		});

		router.register("entities.client", ctx -> ClientMc.call(() -> {
			Gates.vision();
			return clientEntities(ClientMc.level(), ClientMc.player(), ctx.optDouble("radius", 32),
					ctx.getStringList("types"), Math.max(1, ctx.optInt("maxResults", 200)));
		}));

		router.register("block.inspect", ctx -> ClientMc.call(() -> {
			Gates.vision();
			return inspectBlock(ClientMc.level(), BlockPos.containing(ctx.getDouble("x"), ctx.getDouble("y"), ctx.getDouble("z")));
		}));

		router.register("sound.recent", ctx -> ClientMc.call(() -> {
			Gates.vision();
			long since = ctx.optLong("sinceId", 0);
			JsonArray out = new JsonArray();
			for (JsonObject sound : SOUNDS) {
				if (sound.get("id").getAsLong() > since) out.add(sound);
			}
			JsonObject o = new JsonObject();
			o.add("sounds", out);
			return o;
		}));
	}

	// --- watcher ------------------------------------------------------------------------------

	// Render thread only.
	private static final Deque<JsonObject> SOUNDS = new ArrayDeque<>();
	private static long soundSeq;
	private static boolean soundListening;
	private static String lastTitle, lastSubtitle, lastActionBar;
	private static final Map<UUID, String> LAST_BOSSES = new HashMap<>();

	/**
	 * End of every client tick. Titles, action bars and boss bars come and go between two polls, so
	 * each change becomes an event ({@code title}, {@code actionbar}, {@code bossbar}) for poll_events.
	 */
	public static void onClientTick(Minecraft mc) {
		if (!soundListening) {
			mc.getSoundManager().addListener((sound, events, range) -> recordSound(sound));
			soundListening = true;
		}
		if (mc.level == null) return;
		Hud hud = mc.gui.hud;
		String title = text(activeTitle(hud, "title"));
		String subtitle = text(activeTitle(hud, "subtitle"));
		if (title != null && !title.equals(lastTitle) || subtitle != null && !subtitle.equals(lastSubtitle)) {
			JsonObject d = new JsonObject();
			if (title != null) d.addProperty("title", title);
			if (subtitle != null) d.addProperty("subtitle", subtitle);
			McpFabric.events().emit("title", d);
		}
		lastTitle = title;
		lastSubtitle = subtitle;

		String actionBar = text(activeActionBar(hud));
		if (actionBar != null && !actionBar.equals(lastActionBar)) {
			JsonObject d = new JsonObject();
			d.addProperty("text", actionBar);
			McpFabric.events().emit("actionbar", d);
		}
		lastActionBar = actionBar;

		Map<UUID, LerpingBossEvent> bosses = bossEvents(hud);
		if (bosses == null) return;
		bosses.forEach((id, boss) -> {
			String name = boss.getName().getString();
			if (!name.equals(LAST_BOSSES.get(id))) bossEvent(LAST_BOSSES.containsKey(id) ? "changed" : "added", name);
		});
		LAST_BOSSES.forEach((id, name) -> {
			if (!bosses.containsKey(id)) bossEvent("removed", name);
		});
		LAST_BOSSES.clear();
		bosses.forEach((id, boss) -> LAST_BOSSES.put(id, boss.getName().getString()));
	}

	private static void bossEvent(String action, String name) {
		JsonObject d = new JsonObject();
		d.addProperty("action", action);
		d.addProperty("name", name);
		McpFabric.events().emit("bossbar", d);
	}

	/** Kept out of the event bus: ambient sounds alone would push chat and game events out of its ring. */
	private static void recordSound(net.minecraft.client.resources.sounds.SoundInstance sound) {
		JsonObject o = new JsonObject();
		o.addProperty("id", ++soundSeq);
		o.addProperty("sound", String.valueOf(sound.getIdentifier()));
		o.addProperty("source", sound.getSource().name().toLowerCase(Locale.ROOT));
		o.addProperty("x", sound.getX());
		o.addProperty("y", sound.getY());
		o.addProperty("z", sound.getZ());
		SOUNDS.addLast(o);
		while (SOUNDS.size() > SOUND_HISTORY) SOUNDS.removeFirst();
	}

	// --- hud ----------------------------------------------------------------------------------

	private static JsonObject hud(Minecraft mc, LocalPlayer p) {
		Hud hud = mc.gui.hud;
		JsonObject o = new JsonObject();
		JsonArray unavailable = new JsonArray();

		Component actionBar = activeActionBar(hud);
		if (actionBar != null) o.addProperty("actionBar", actionBar.getString());
		Component title = activeTitle(hud, "title");
		if (title != null) o.addProperty("title", title.getString());
		Component subtitle = activeTitle(hud, "subtitle");
		if (subtitle != null) o.addProperty("subtitle", subtitle.getString());

		Map<UUID, LerpingBossEvent> bosses = bossEvents(hud);
		if (bosses == null) {
			unavailable.add("bossBars");
		} else {
			JsonArray bars = new JsonArray();
			for (LerpingBossEvent boss : bosses.values()) {
				JsonObject b = new JsonObject();
				b.addProperty("name", boss.getName().getString());
				b.addProperty("progress", boss.getProgress());
				b.addProperty("color", boss.getColor().name().toLowerCase(Locale.ROOT));
				b.addProperty("overlay", boss.getOverlay().name().toLowerCase(Locale.ROOT));
				bars.add(b);
			}
			o.add("bossBars", bars);
		}

		JsonObject sidebar = sidebar(p);
		if (sidebar != null) o.add("sidebar", sidebar);

		o.add("tabList", tabList(hud, p, unavailable));
		o.add("crosshair", crosshair(mc));
		o.add("heldItem", itemJson(p.getMainHandItem(), p));
		o.add("offhandItem", itemJson(p.getOffhandItem(), p));
		o.addProperty("hudHidden", hud.isHidden());
		o.addProperty("screen", SessionScreenHandlers.kind(ContainerHandlers.currentScreen()));
		if (!unavailable.isEmpty()) o.add("unavailable", unavailable);
		return o;
	}

	private static JsonObject sidebar(LocalPlayer p) {
		Scoreboard board = p.level().getScoreboard();
		PlayerTeam team = board.getPlayersTeam(p.getScoreboardName());
		Objective objective = null;
		if (team != null) {
			// A team color has its own sidebar slot, which wins over the general one.
			Optional<?> color = team.getColor();
			if (color.isPresent() && color.get() instanceof Enum<?> c) {
				try {
					objective = board.getDisplayObjective(DisplaySlot.valueOf("TEAM_" + c.name()));
				} catch (IllegalArgumentException ignored) {
					// A color without a slot of its own.
				}
			}
		}
		if (objective == null) objective = board.getDisplayObjective(DisplaySlot.SIDEBAR);
		if (objective == null) return null;
		JsonObject o = new JsonObject();
		o.addProperty("title", objective.getDisplayName().getString());
		List<PlayerScoreEntry> entries = new ArrayList<>(board.listPlayerScores(objective));
		entries.removeIf(PlayerScoreEntry::isHidden);
		entries.sort(Comparator.comparingInt(PlayerScoreEntry::value).reversed().thenComparing(PlayerScoreEntry::owner));
		JsonArray lines = new JsonArray();
		// The game draws the 15 highest scores.
		for (PlayerScoreEntry entry : entries.subList(0, Math.min(15, entries.size()))) {
			JsonObject line = new JsonObject();
			line.addProperty("text", PlayerTeam.formatNameForTeam(board.getPlayersTeam(entry.owner()), entry.ownerName()).getString());
			line.addProperty("score", entry.value());
			lines.add(line);
		}
		o.add("lines", lines);
		return o;
	}

	private static JsonObject tabList(Hud hud, LocalPlayer p, JsonArray unavailable) {
		JsonObject o = new JsonObject();
		PlayerTabOverlay tab = field(hud, Hud.class, "tabList");
		if (tab == null) {
			unavailable.add("tabList.header");
		} else {
			String header = text(PlayerSenses.<Component>field(tab, PlayerTabOverlay.class, "header"));
			String footer = text(PlayerSenses.<Component>field(tab, PlayerTabOverlay.class, "footer"));
			if (header != null) o.addProperty("header", header);
			if (footer != null) o.addProperty("footer", footer);
		}
		JsonArray players = new JsonArray();
		for (PlayerInfo info : p.connection.getListedOnlinePlayers()) {
			JsonObject e = new JsonObject();
			e.addProperty("name", info.getProfile().name());
			if (info.getTabListDisplayName() != null) e.addProperty("displayName", info.getTabListDisplayName().getString());
			e.addProperty("gameMode", String.valueOf(info.getGameMode()));
			e.addProperty("latency", info.getLatency());
			players.add(e);
		}
		o.add("players", players);
		return o;
	}

	private static JsonObject crosshair(Minecraft mc) {
		JsonObject o = new JsonObject();
		HitResult hit = mc.hitResult;
		if (hit instanceof BlockHitResult block && hit.getType() == HitResult.Type.BLOCK) {
			o.addProperty("type", "block");
			BlockPos pos = block.getBlockPos();
			o.addProperty("x", pos.getX());
			o.addProperty("y", pos.getY());
			o.addProperty("z", pos.getZ());
			o.addProperty("face", block.getDirection().getName());
			if (mc.level != null) o.addProperty("block", mc.level.getBlockState(pos).toString());
		} else if (hit instanceof EntityHitResult entity) {
			o.addProperty("type", "entity");
			o.addProperty("uuid", entity.getEntity().getUUID().toString());
			o.addProperty("name", entity.getEntity().getDisplayName().getString());
		} else {
			o.addProperty("type", "miss");
		}
		return o;
	}

	private static Component activeTitle(Hud hud, String field) {
		Integer time = field(hud, Hud.class, "titleTime");
		return time == null || time <= 0 ? null : field(hud, Hud.class, field);
	}

	private static Component activeActionBar(Hud hud) {
		Integer time = field(hud, Hud.class, "overlayMessageTime");
		return time == null || time <= 0 ? null : field(hud, Hud.class, "overlayMessageString");
	}

	private static Map<UUID, LerpingBossEvent> bossEvents(Hud hud) {
		BossHealthOverlay overlay = field(hud, Hud.class, "bossOverlay");
		return overlay == null ? null : field(overlay, BossHealthOverlay.class, "events");
	}

	// --- containers and items -----------------------------------------------------------------

	private static JsonObject inspectContainer(LocalPlayer p, boolean includeInventory) {
		AbstractContainerMenu menu = p.containerMenu;
		JsonObject o = new JsonObject();
		boolean open = menu != p.inventoryMenu;
		o.addProperty("open", open);
		o.addProperty("containerId", menu.containerId);
		if (open) {
			try {
				o.addProperty("type", String.valueOf(BuiltInRegistries.MENU.getKey(menu.getType())));
			} catch (UnsupportedOperationException ignored) {
				// Menus without a registered type throw here.
			}
		}
		Screen screen = ContainerHandlers.currentScreen();
		if (screen != null) {
			o.addProperty("title", screen.getTitle().getString());
			o.addProperty("screenClass", screen.getClass().getName());
		}
		Integer left = screen instanceof AbstractContainerScreen<?> ? field(screen, AbstractContainerScreen.class, "leftPos") : null;
		Integer top = screen instanceof AbstractContainerScreen<?> ? field(screen, AbstractContainerScreen.class, "topPos") : null;
		o.add("carried", itemJson(menu.getCarried(), p));

		JsonArray slots = new JsonArray();
		int size = 0;
		for (Slot slot : menu.slots) {
			boolean inventory = slot.container == p.getInventory();
			if (!inventory) size++;
			if (inventory && !includeInventory) continue;
			if (!slot.hasItem() && (inventory || left == null)) continue;
			JsonObject s = slot.hasItem() ? itemJson(slot.getItem(), p).getAsJsonObject() : new JsonObject();
			s.addProperty("slot", slot.index);
			if (inventory) s.addProperty("playerInventory", true);
			if (left != null && top != null) {
				// The centre of the slot, in the coordinates mouse_button takes.
				s.addProperty("screenX", left + slot.x + 8);
				s.addProperty("screenY", top + slot.y + 8);
			}
			slots.add(s);
		}
		o.addProperty("size", size);
		o.add("slots", slots);

		if (menu instanceof MerchantMenu merchant) {
			JsonArray offers = new JsonArray();
			for (MerchantOffer offer : merchant.getOffers()) {
				JsonObject t = new JsonObject();
				t.add("costA", itemJson(offer.getCostA(), p));
				t.add("costB", itemJson(offer.getCostB(), p));
				t.add("result", itemJson(offer.getResult(), p));
				t.addProperty("uses", offer.getUses());
				t.addProperty("maxUses", offer.getMaxUses());
				t.addProperty("outOfStock", offer.isOutOfStock());
				offers.add(t);
			}
			o.add("offers", offers);
		}
		return o;
	}

	/** Everything a player reads off an item: id, count, name, tooltip lines; plus its raw components. */
	private static JsonElement itemJson(ItemStack stack, Player p) {
		if (stack.isEmpty()) return new JsonObject();
		JsonObject o = new JsonObject();
		o.addProperty("id", String.valueOf(BuiltInRegistries.ITEM.getKey(stack.getItem())));
		o.addProperty("count", stack.getCount());
		o.addProperty("name", stack.getHoverName().getString());
		if (stack.getCustomName() != null) o.addProperty("customName", stack.getCustomName().getString());
		if (stack.isDamageableItem()) {
			o.addProperty("damage", stack.getDamageValue());
			o.addProperty("maxDamage", stack.getMaxDamage());
		}
		JsonArray tooltip = new JsonArray();
		for (Component line : stack.getTooltipLines(Item.TooltipContext.of(p.level()), p, TooltipFlag.NORMAL)) {
			tooltip.add(line.getString());
		}
		o.add("tooltip", tooltip);
		if (!stack.getComponentsPatch().isEmpty()) o.addProperty("components", stack.getComponentsPatch().toString());
		return o;
	}

	// --- chat ---------------------------------------------------------------------------------

	private static List<GuiMessage> chatMessages() throws RpcException {
		List<GuiMessage> all = field(ClientMc.mc().gui.hud.getChat(), ChatComponent.class, "allMessages");
		if (all == null) throw RpcException.unavailable("This game version keeps its chat lines elsewhere.");
		return all;
	}

	private static JsonObject chatLine(GuiMessage message) {
		JsonObject o = new JsonObject();
		o.addProperty("ref", System.identityHashCode(message));
		o.addProperty("text", message.content().getString());
		JsonArray parts = new JsonArray();
		List<Component> flat = message.content().toFlatList();
		for (int i = 0; i < flat.size(); i++) {
			Component part = flat.get(i);
			ClickEvent click = part.getStyle().getClickEvent();
			HoverEvent hover = part.getStyle().getHoverEvent();
			if (click == null && hover == null) continue;
			JsonObject p = new JsonObject();
			p.addProperty("part", i);
			p.addProperty("text", part.getString());
			if (click != null) p.addProperty("click", click.toString());
			if (hover instanceof HoverEvent.ShowText show) p.addProperty("hover", show.value().getString());
			else if (hover != null) p.addProperty("hover", hover.toString());
			parts.add(p);
		}
		if (!parts.isEmpty()) o.add("parts", parts);
		return o;
	}

	/** Clicks a part of a chat line as the chat screen does, confirmation screens included. */
	private static JsonObject click(GuiMessage message, int part) throws RpcException {
		List<Component> flat = message.content().toFlatList();
		ClickEvent event = null;
		if (part >= 0) {
			if (part >= flat.size()) throw RpcException.notFound("That line has no part " + part + ".");
			event = flat.get(part).getStyle().getClickEvent();
		} else {
			for (Component c : flat) {
				if (c.getStyle().getClickEvent() == null) continue;
				if (event != null) throw RpcException.badRequest("That line has several clickable parts: pass part from get_chat_lines.");
				event = c.getStyle().getClickEvent();
			}
		}
		if (event == null) throw RpcException.badRequest("That part of the line is not clickable.");
		JsonObject o = Json.ok("clicked");
		o.addProperty("event", event.toString());
		Minecraft mc = ClientMc.mc();
		Screen screen = ContainerHandlers.currentScreen();
		switch (event) {
			case ClickEvent.RunCommand ignored -> {
				Gates.commands();
				ClickBridge.run(event, mc, screen);
			}
			// The copied value is in the answer; the desktop's clipboard belongs to the user.
			case ClickEvent.CopyToClipboard copy -> o.addProperty("value", copy.value());
			case ClickEvent.SuggestCommand suggest -> mc.gui.setScreen(new ChatScreen(suggest.command(), false));
			default -> ClickBridge.run(event, mc, screen);
		}
		Screen now = ContainerHandlers.currentScreen();
		o.addProperty("screen", SessionScreenHandlers.kind(now));
		return o;
	}

	/** The vanilla click handling is protected to screens. */
	private static final class ClickBridge extends Screen {
		private ClickBridge() {
			super(Component.empty());
		}

		static void run(ClickEvent event, Minecraft mc, Screen screen) {
			defaultHandleGameClickEvent(event, mc, screen);
		}
	}

	// --- entities and blocks ------------------------------------------------------------------

	private static JsonObject clientEntities(ClientLevel level, LocalPlayer p, double radius, List<String> types, int max) {
		List<Entity> list = new ArrayList<>();
		for (Entity e : level.entitiesForRendering()) {
			if (e == p || !e.isAlive() || e.distanceTo(p) > radius) continue;
			if (!types.isEmpty() && !types.contains(String.valueOf(BuiltInRegistries.ENTITY_TYPE.getKey(e.getType())))) continue;
			list.add(e);
		}
		list.sort(Comparator.comparingDouble(e -> e.distanceToSqr(p)));
		JsonArray out = new JsonArray();
		for (Entity e : list.subList(0, Math.min(max, list.size()))) {
			JsonObject o = new JsonObject();
			o.addProperty("uuid", e.getUUID().toString());
			o.addProperty("type", String.valueOf(BuiltInRegistries.ENTITY_TYPE.getKey(e.getType())));
			o.addProperty("name", e.getDisplayName().getString());
			if (e.hasCustomName()) o.addProperty("customNameVisible", e.isCustomNameVisible());
			o.addProperty("x", e.getX());
			o.addProperty("y", e.getY());
			o.addProperty("z", e.getZ());
			o.addProperty("distance", e.distanceTo(p));
			if (e.isInvisible()) o.addProperty("invisible", true);
			if (e.getVehicle() != null) o.addProperty("vehicle", e.getVehicle().getUUID().toString());
			if (!e.getPassengers().isEmpty()) {
				JsonArray riders = new JsonArray();
				e.getPassengers().forEach(r -> riders.add(r.getUUID().toString()));
				o.add("passengers", riders);
			}
			if (e instanceof LivingEntity living) {
				o.addProperty("health", living.getHealth());
				o.addProperty("maxHealth", living.getMaxHealth());
				JsonObject gear = new JsonObject();
				for (EquipmentSlot slot : EquipmentSlot.values()) {
					ItemStack stack = living.getItemBySlot(slot);
					if (!stack.isEmpty()) gear.add(slot.getName(), itemJson(stack, p));
				}
				if (!gear.isEmpty()) o.add("equipment", gear);
			}
			if (e instanceof ItemEntity item) o.add("item", itemJson(item.getItem(), p));
			if (e instanceof Display.TextDisplay display) {
				Component text = call(display, Display.TextDisplay.class, "getText");
				if (text != null) o.addProperty("text", text.getString());
			}
			if (e instanceof Display.ItemDisplay display) {
				ItemStack stack = call(display, Display.ItemDisplay.class, "getItemStack");
				if (stack != null) o.add("item", itemJson(stack, p));
			}
			if (e instanceof Display.BlockDisplay display) {
				BlockState state = call(display, Display.BlockDisplay.class, "getBlockState");
				if (state != null) o.addProperty("block", state.toString());
			}
			out.add(o);
		}
		JsonObject o = new JsonObject();
		o.add("entities", out);
		o.addProperty("total", list.size());
		return o;
	}

	private static JsonObject inspectBlock(ClientLevel level, BlockPos pos) {
		JsonObject o = new JsonObject();
		o.addProperty("x", pos.getX());
		o.addProperty("y", pos.getY());
		o.addProperty("z", pos.getZ());
		boolean loaded = level.hasChunkAt(pos);
		o.addProperty("loaded", loaded);
		if (!loaded) return o;
		BlockState state = level.getBlockState(pos);
		o.addProperty("id", String.valueOf(BuiltInRegistries.BLOCK.getKey(state.getBlock())));
		o.addProperty("state", state.toString());
		o.addProperty("air", state.isAir());
		o.addProperty("fluid", !state.getFluidState().isEmpty());
		o.addProperty("hardness", state.getDestroySpeed(level, pos));
		BlockEntity entity = level.getBlockEntity(pos);
		if (entity != null) {
			o.addProperty("blockEntity", String.valueOf(BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(entity.getType())));
			if (entity instanceof SignBlockEntity sign) {
				o.add("signFront", signLines(sign.getText(SignTextSlot.FRONT).getMessages(false)));
				o.add("signBack", signLines(sign.getText(SignTextSlot.BACK).getMessages(false)));
			}
			try {
				o.addProperty("nbt", entity.saveCustomOnly(level.registryAccess()).toString());
			} catch (RuntimeException e) {
				o.addProperty("nbtError", e.getClass().getSimpleName());
			}
		}
		return o;
	}

	private static JsonArray signLines(List<Component> lines) {
		JsonArray out = new JsonArray();
		lines.forEach(line -> out.add(line.getString()));
		return out;
	}

	// --- helpers ------------------------------------------------------------------------------

	private static int[] ints(JsonElement element) throws RpcException {
		if (element == null || !element.isJsonArray()) throw RpcException.badRequest("slots must be an array of slot numbers.");
		JsonArray array = element.getAsJsonArray();
		int[] out = new int[array.size()];
		for (int i = 0; i < out.length; i++) out[i] = array.get(i).getAsInt();
		return out;
	}

	private static String text(Component component) {
		if (component == null) return null;
		String s = component.getString();
		return s.isEmpty() ? null : s;
	}

	private static final Map<String, Field> FIELDS = new HashMap<>();

	/** A private field by name, or null when this game version does not have it or denies access. */
	@SuppressWarnings("unchecked")
	private static <T> T field(Object owner, Class<?> type, String name) {
		try {
			String key = type.getName() + "#" + name;
			Field f = FIELDS.get(key);
			if (f == null) {
				f = type.getDeclaredField(name);
				f.setAccessible(true);
				FIELDS.put(key, f);
			}
			return (T) f.get(owner);
		} catch (ReflectiveOperationException | RuntimeException e) {
			return null;
		}
	}

	/** A private no-argument method by name, or null when this game version does not have it. */
	@SuppressWarnings("unchecked")
	private static <T> T call(Object owner, Class<?> type, String name) {
		try {
			Method m = type.getDeclaredMethod(name);
			m.setAccessible(true);
			return (T) m.invoke(owner);
		} catch (ReflectiveOperationException | RuntimeException e) {
			return null;
		}
	}
}
