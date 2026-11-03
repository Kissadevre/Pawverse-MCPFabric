/**
 * The full mcpfabric tool catalogue.
 *
 * This table is the single source of truth for the RPC contract on the TypeScript side: every
 * entry maps an MCP tool (snake_case name) to a bridge RPC `method` (namespaced, dotted) plus a
 * zod input schema. `src/index.ts` registers each entry generically. Keep this in sync with the
 * Java handler registry in the mod (`dev.mcpfabric.handlers.*`).
 */
import { z } from "zod";

/**
 * MCP tool hints. Every tool states all four, so clients never fall back to the spec defaults
 * (which assume a destructive, non-idempotent, open-world tool).
 */
export interface ToolAnnotations {
  readOnlyHint: boolean;
  destructiveHint: boolean;
  idempotentHint: boolean;
  /** The game counts as a closed world; true only where the call reaches other people (chat, kicks, servers). */
  openWorldHint: boolean;
}

/** Only reads the game. */
export const READ: ToolAnnotations = { readOnlyHint: true, destructiveHint: false, idempotentHint: true, openWorldHint: false };
/** Changes the world or inventories in a way that cannot simply be undone; repeating it acts again. */
export const WRITE: ToolAnnotations = { readOnlyHint: false, destructiveHint: true, idempotentHint: false, openWorldHint: false };
/** Replaces state with the given value; repeating the same call changes nothing more. */
export const OVERWRITE: ToolAnnotations = { ...WRITE, idempotentHint: true };
/** Acts without destroying anything (moves the player, moves items, adds a note); repeating it acts again. */
export const ACT: ToolAnnotations = { readOnlyHint: false, destructiveHint: false, idempotentHint: false, openWorldHint: false };
/** Sets player input or session state; repeating the same call changes nothing more. */
export const STEER: ToolAnnotations = { ...ACT, idempotentHint: true };

export interface ToolDef {
  /** MCP tool name exposed to the model. */
  name: string;
  /** Bridge RPC method this tool forwards to. */
  method: string;
  /** Short human title. */
  title: string;
  /** Description shown to the model — be precise about behaviour and side requirements. */
  description: string;
  /** zod raw shape describing the tool arguments. */
  inputSchema: z.ZodRawShape;
  /** Hints surfaced to MCP clients. */
  annotations: ToolAnnotations;
  /** Rendering: "json" (default) returns text + structuredContent; "image" returns an image block. */
  kind?: "json" | "image";
}

// ----- reusable schema fragments -------------------------------------------------------------

const vec3 = () => ({
  x: z.number().describe("X coordinate (east/west)."),
  y: z.number().describe("Y coordinate (height)."),
  z: z.number().describe("Z coordinate (north/south)."),
});

const dimensionOpt = {
  dimension: z
    .string()
    .optional()
    .describe('Dimension id, e.g. "minecraft:overworld", "minecraft:the_nether". Defaults to the current/overworld dimension.'),
};

const handOpt = {
  hand: z.enum(["main", "off"]).optional().describe("Hand that acts. Default main."),
};

const forceOpt = {
  force: z.boolean().optional().describe("Send it even if the client thinks it is out of reach, as a modified client would, and let the server decide."),
};

const modifierOpts = {
  shift: z.boolean().optional().describe("Shift held for this input."),
  ctrl: z.boolean().optional().describe("Ctrl held for this input."),
  alt: z.boolean().optional().describe("Alt held for this input."),
};

const inputAction = {
  action: z.enum(["tap", "down", "up"]).optional().describe("tap presses and releases after holdTicks (default); down keeps it held until up or release_all_input; up releases it."),
  holdTicks: z.number().int().min(1).optional().describe("Ticks a tap stays held (20 per second). Default 1."),
};

const playerRef = {
  player: z
    .string()
    .describe('Target player by name or UUID. Use "@all" where broadcasting is meaningful.'),
};

// ----- catalogue ------------------------------------------------------------------------------

export const TOOLS: ToolDef[] = [
  // ===== info ================================================================================
  {
    name: "get_status",
    method: "info.status",
    title: "Server/client status",
    description:
      "Get the current state of the running game: mod & Minecraft version, which side this bridge runs on (client / dedicated_server), whether an integrated server is present, whether the player is in a world, current dimension, and the list of available capability groups. Call this first to learn what you can do right now.",
    inputSchema: {},
    annotations: READ,
  },
  {
    name: "list_capabilities",
    method: "info.capabilities",
    title: "List capabilities",
    description:
      "List every capability group and whether it is currently available on this side (e.g. control/interact/vision/nav are client-only; players/command admin need a server). Useful to decide which tools will work.",
    inputSchema: {},
    annotations: READ,
  },

  // ===== world (read) ========================================================================
  {
    name: "get_block",
    method: "world.getBlock",
    title: "Get block at position",
    description:
      "Read the block at an exact integer position: its block id, blockstate properties, whether it is air/fluid/solid, light levels, and hardness. Requires a loaded chunk.",
    inputSchema: { ...vec3(), ...dimensionOpt },
    annotations: READ,
  },
  {
    name: "get_blocks_region",
    method: "world.getBlocks",
    title: "Scan a cuboid region",
    description:
      "Scan all blocks in the cuboid between two corners (inclusive) and return their ids. Regions above 16,777,216 positions (e.g. 256x256x256) are refused to protect the server: split larger areas. The answer is capped at maxBlocks (default 32768, truncated=true when hit); air is omitted unless includeAir is true.",
    inputSchema: {
      from: z.object(vec3()).describe("One corner of the cuboid."),
      to: z.object(vec3()).describe("Opposite corner of the cuboid."),
      includeAir: z.boolean().optional().default(false).describe("Include air blocks in the result."),
      maxBlocks: z.number().int().min(1).max(200000).optional().describe("Override the per-call block cap."),
      ...dimensionOpt,
    },
    annotations: READ,
  },
  {
    name: "find_blocks",
    method: "world.findBlocks",
    title: "Find nearby blocks by id",
    description:
      "Search a spherical radius around a center point for blocks matching any of the given ids (e.g. minecraft:diamond_ore). Searches outward from the center, so the nearest blocks are always covered; a scan budget of 250,000 positions may stop it early (truncated=true, searchedRadius tells how far it got). Returns matches sorted by distance. Only searches loaded chunks.",
    inputSchema: {
      center: z.object(vec3()).describe("Center of the search sphere."),
      radius: z.number().int().min(1).max(128).describe("Search radius in blocks."),
      blockIds: z.array(z.string()).min(1).describe('Block ids to match, e.g. ["minecraft:diamond_ore","minecraft:ancient_debris"].'),
      maxResults: z.number().int().min(1).max(1024).optional().default(64).describe("Maximum matches to return."),
      ...dimensionOpt,
    },
    annotations: READ,
  },
  {
    name: "get_time_and_weather",
    method: "world.getTimeAndWeather",
    title: "Time & weather",
    description:
      "Get the current day-time (0-24000), total game-time, day count, and weather (raining/thundering) for a dimension.",
    inputSchema: { ...dimensionOpt },
    annotations: READ,
  },
  {
    name: "list_dimensions",
    method: "world.getDimensions",
    title: "List dimensions",
    description: "List all dimensions present on the server and which one the player is currently in.",
    inputSchema: {},
    annotations: READ,
  },
  {
    name: "raycast",
    method: "world.raycast",
    title: "Raycast from a point",
    description:
      "Cast a ray and report the first block and/or entity it hits. Blocks are hit by their outline shape, as the crosshair sees them (a ray above a bottom slab passes), and the ray stops at unloaded chunks. Provide either an explicit direction vector or yaw/pitch angles. Great for 'what am I looking at' and line-of-sight checks.",
    inputSchema: {
      origin: z.object(vec3()).describe("Ray start position (usually an eye position)."),
      direction: z.object(vec3()).optional().describe("Ray direction vector (need not be normalized). Use this OR yaw/pitch."),
      yaw: z.number().optional().describe("Yaw in degrees (Minecraft convention). Use with pitch instead of direction."),
      pitch: z.number().optional().describe("Pitch in degrees (-90 up .. 90 down)."),
      maxDistance: z.number().min(0.1).max(256).optional().default(32).describe("Maximum ray length in blocks."),
      includeFluids: z.boolean().optional().default(false).describe("Treat fluids as hittable."),
      includeEntities: z.boolean().optional().default(true).describe("Also test entities along the ray."),
      ...dimensionOpt,
    },
    annotations: READ,
  },

  // ===== world (write) =======================================================================
  {
    name: "set_block",
    method: "world.setBlock",
    title: "Set a block",
    description:
      "Place/replace the block at an exact position with the given block id (optionally with blockstate properties as a string like 'minecraft:oak_log[axis=y]'). Requires a server (integrated client or dedicated).",
    inputSchema: { ...vec3(), blockId: z.string().describe('Block id, optionally with state, e.g. "minecraft:stone" or "minecraft:oak_stairs[facing=east]".'), ...dimensionOpt },
    annotations: OVERWRITE,
  },
  {
    name: "fill_blocks",
    method: "world.fill",
    title: "Fill a region with a block",
    description:
      "Fill the cuboid between two corners with one block id. Volume is capped for safety. Requires a server.",
    inputSchema: {
      from: z.object(vec3()),
      to: z.object(vec3()),
      blockId: z.string().describe("Block id to fill with."),
      ...dimensionOpt,
    },
    annotations: OVERWRITE,
  },
  {
    name: "set_time",
    method: "world.setTime",
    title: "Set time of day",
    description: "Set the day-time (0-24000; 0=dawn, 6000=noon, 12000=dusk, 18000=midnight). Requires a server.",
    inputSchema: { time: z.number().int().min(0).max(24000).describe("Day-time in ticks (0-24000).") },
    annotations: OVERWRITE,
  },
  {
    name: "set_weather",
    method: "world.setWeather",
    title: "Set weather",
    description: "Set the weather. Requires a server.",
    inputSchema: {
      weather: z.enum(["clear", "rain", "thunder"]).describe("Target weather."),
      durationSeconds: z.number().int().min(1).optional().describe("How long the weather should last."),
    },
    annotations: OVERWRITE,
  },

  // ===== entities ============================================================================
  {
    name: "query_entities",
    method: "entities.query",
    title: "Query entities",
    description:
      "List entities, optionally filtered by a sphere (center+radius), entity type ids, living-only, and whether to include players. Returns position, type, name, health and key flags for each.",
    inputSchema: {
      center: z.object(vec3()).optional().describe("Center of the search sphere; omit to use the position of the only player in that dimension (the world spawn when there is none or several; centerSource says which)."),
      radius: z.number().min(1).max(256).optional().default(32).describe("Search radius in blocks."),
      types: z.array(z.string()).optional().describe('Entity type ids to match, e.g. ["minecraft:zombie","minecraft:cow"].'),
      includePlayers: z.boolean().optional().default(true),
      onlyLiving: z.boolean().optional().default(false),
      maxResults: z.number().int().min(1).max(1000).optional().default(100),
      ...dimensionOpt,
    },
    annotations: READ,
  },
  {
    name: "get_entity",
    method: "entities.get",
    title: "Get entity details",
    description: "Get detailed info about a single entity by UUID: type, position, velocity, health, equipment, NBT-derived attributes.",
    inputSchema: { uuid: z.string().describe("Entity UUID.") },
    annotations: READ,
  },
  {
    name: "summon_entity",
    method: "entities.summon",
    title: "Summon entity",
    description: "Summon an entity of the given type at a position, optionally with SNBT data. Requires a server.",
    inputSchema: {
      type: z.string().describe('Entity type id, e.g. "minecraft:armor_stand".'),
      ...vec3(),
      nbt: z.string().optional().describe("Optional SNBT, e.g. '{NoGravity:1b}'."),
      ...dimensionOpt,
    },
    annotations: WRITE,
  },
  {
    name: "remove_entity",
    method: "entities.remove",
    title: "Remove entity",
    description: "Discard (remove) a non-player entity by UUID. Requires a server.",
    inputSchema: { uuid: z.string().describe("Entity UUID to remove.") },
    annotations: OVERWRITE,
  },

  // ===== players (admin, server-side) ========================================================
  {
    name: "list_players",
    method: "players.list",
    title: "List online players",
    description: "List all online players with name, UUID, position, dimension, health, food, game mode and ping. Requires a server.",
    inputSchema: {},
    annotations: READ,
  },
  {
    name: "get_player",
    method: "players.get",
    title: "Get player details",
    description: "Get detailed state for one online player. Requires a server.",
    inputSchema: { ...playerRef },
    annotations: READ,
  },
  {
    name: "teleport_player",
    method: "players.teleport",
    title: "Teleport player",
    description: "Teleport a player to coordinates (and optionally another dimension / facing). Requires a server.",
    inputSchema: { ...playerRef, ...vec3(), yaw: z.number().optional(), pitch: z.number().optional(), ...dimensionOpt },
    annotations: OVERWRITE,
  },
  {
    name: "set_gamemode",
    method: "players.setGameMode",
    title: "Set player game mode",
    description: "Set a player's game mode. Requires a server.",
    inputSchema: { ...playerRef, mode: z.enum(["survival", "creative", "adventure", "spectator"]) },
    annotations: OVERWRITE,
  },
  {
    name: "give_item",
    method: "players.give",
    title: "Give item to player",
    description: "Give an item stack to a player. Requires a server.",
    inputSchema: {
      ...playerRef,
      itemId: z.string().describe('Item id, e.g. "minecraft:diamond".'),
      count: z.number().int().min(1).max(6400).optional().default(1),
      nbt: z.string().optional().describe("Optional SNBT components."),
    },
    annotations: WRITE,
  },
  {
    name: "apply_effect",
    method: "players.applyEffect",
    title: "Apply status effect",
    description: "Apply a potion/status effect to a player. Requires a server.",
    inputSchema: {
      ...playerRef,
      effectId: z.string().describe('Effect id, e.g. "minecraft:speed".'),
      durationSeconds: z.number().int().min(1).optional().default(30),
      amplifier: z.number().int().min(0).max(255).optional().default(0),
      showParticles: z.boolean().optional().default(true),
    },
    annotations: WRITE,
  },
  {
    name: "message_player",
    method: "players.message",
    title: "Send system message",
    description: 'Send a system/chat message to a player ("@all" to broadcast). Requires a server.',
    inputSchema: { ...playerRef, text: z.string() },
    annotations: { ...ACT, openWorldHint: true },
  },
  {
    name: "kick_player",
    method: "players.kick",
    title: "Kick player",
    description: "Kick a player from the server. Requires a dedicated server.",
    inputSchema: { ...playerRef, reason: z.string().optional() },
    annotations: { ...OVERWRITE, openWorldHint: true },
  },

  // ===== command =============================================================================
  {
    name: "run_command",
    method: "command.run",
    title: "Run a server command",
    description:
      "Execute an arbitrary Minecraft command at operator permission level 4 (do NOT include the leading slash) and capture its feedback output. This is extremely powerful (/setblock, /summon, /give, /tp, /gamerule, /execute, datapacks, ...). Requires a server.",
    inputSchema: { command: z.string().describe('Command without the leading slash, e.g. "time set day".') },
    annotations: { ...WRITE, openWorldHint: true },
  },

  // ===== chat ================================================================================
  {
    name: "send_chat",
    method: "chat.send",
    title: "Send chat message",
    description:
      "Send a chat message. On a client this is sent as the local player (a leading '/' runs a command as that player, and needs enableCommands); on a dedicated server it is broadcast.",
    inputSchema: { message: z.string() },
    annotations: { ...WRITE, openWorldHint: true },
  },
  {
    name: "get_recent_chat",
    method: "chat.getRecent",
    title: "Get recent chat",
    description: "Return recently observed chat & system messages (most recent last).",
    inputSchema: { limit: z.number().int().min(1).max(500).optional().default(50) },
    annotations: READ,
  },

  // ===== player (client local player) ========================================================
  {
    name: "get_self",
    method: "player.getState",
    title: "Get local player state",
    description:
      "Client-only. Full state of YOUR player: position, yaw/pitch, motion, health, food, saturation, air, XP, game mode, on-ground, in-fluid, selected hotbar slot, dimension.",
    inputSchema: {},
    annotations: READ,
  },
  {
    name: "get_inventory",
    method: "player.getInventory",
    title: "Get inventory",
    description: "Client-only. Full inventory: main slots, hotbar, armor, offhand, and the selected slot. Each item reports id, count and durability.",
    inputSchema: {},
    annotations: READ,
  },
  {
    name: "get_equipment",
    method: "player.getEquipment",
    title: "Get equipment",
    description: "Client-only. Currently equipped items: main hand, off hand, helmet, chestplate, leggings, boots.",
    inputSchema: {},
    annotations: READ,
  },
  {
    name: "get_status_effects",
    method: "player.getStatusEffects",
    title: "Get active effects",
    description: "Client-only. Active status effects on your player with amplifier and remaining duration.",
    inputSchema: {},
    annotations: READ,
  },

  // ===== control (client) ====================================================================
  {
    name: "set_movement",
    method: "control.setInput",
    title: "Set movement input",
    description:
      "Client-only. Set held movement inputs as booleans; they persist until changed (like holding keys). Any omitted field is left unchanged. Combine with look/look_at to walk somewhere. Use stop_movement to release everything.",
    inputSchema: {
      forward: z.boolean().optional(),
      back: z.boolean().optional(),
      left: z.boolean().optional(),
      right: z.boolean().optional(),
      jump: z.boolean().optional(),
      sneak: z.boolean().optional(),
      sprint: z.boolean().optional(),
    },
    annotations: STEER,
  },
  {
    name: "stop_movement",
    method: "control.stop",
    title: "Release all movement",
    description: "Client-only. Release all movement inputs (stop walking/jumping/sneaking/sprinting).",
    inputSchema: {},
    annotations: STEER,
  },
  {
    name: "look",
    method: "control.look",
    title: "Set/adjust look angles",
    description:
      "Client-only. Set absolute yaw/pitch, or apply relative deltas. Yaw: 0=south,-90=east,90=west,180=north. Pitch: -90=up, 90=down.",
    inputSchema: {
      yaw: z.number().optional().describe("Absolute yaw in degrees."),
      pitch: z.number().optional().describe("Absolute pitch in degrees (-90..90)."),
      deltaYaw: z.number().optional().describe("Relative yaw change in degrees."),
      deltaPitch: z.number().optional().describe("Relative pitch change in degrees."),
    },
    annotations: ACT,
  },
  {
    name: "look_at",
    method: "control.lookAt",
    title: "Look at a point",
    description: "Client-only. Rotate the player to face a world coordinate.",
    inputSchema: { ...vec3() },
    annotations: STEER,
  },
  {
    name: "jump",
    method: "control.jumpOnce",
    title: "Jump once",
    description: "Client-only. Perform a single jump.",
    inputSchema: {},
    annotations: ACT,
  },
  {
    name: "start_using_item",
    method: "control.startUsing",
    title: "Start using held item",
    description: "Client-only. Begin using/holding the right-click action of the held item (eat, draw bow, block with shield, etc.).",
    inputSchema: {},
    annotations: WRITE,
  },
  {
    name: "stop_using_item",
    method: "control.stopUsing",
    title: "Stop using held item",
    description: "Client-only. Release the right-click use action.",
    inputSchema: {},
    annotations: STEER,
  },

  // ===== interact (client) ===================================================================
  {
    name: "break_block",
    method: "interact.breakBlock",
    title: "Break a block",
    description:
      "Client-only. Break the block at a position. mode 'instant' breaks it at once: in creative through the normal break packet; in survival only on an integrated server with world writes enabled (the server breaks it). 'broke' is what the integrated server reports (null on a remote server). 'survival' performs realistic timed mining (must be reachable, ~within 5 blocks); it stops by itself when the block cannot break and reports a mining_finished event (see poll_events).",
    inputSchema: { ...vec3(), mode: z.enum(["instant", "survival"]).optional().default("survival") },
    annotations: WRITE,
  },
  {
    name: "place_block",
    method: "interact.placeBlock",
    title: "Place held block",
    description:
      "Client-only. Place the currently held block against the given position/face. Out of reach, nothing is sent (result OUT_OF_REACH). 'result' is the client's prediction; 'placed' is what the integrated server did (null on a remote server). Equip the desired block first with select_hotbar_slot.",
    inputSchema: { ...vec3(), face: z.enum(["up", "down", "north", "south", "east", "west"]).optional().default("up"), ...handOpt, ...forceOpt },
    annotations: WRITE,
  },
  {
    name: "use_item",
    method: "interact.useItem",
    title: "Use item / right-click",
    description: "Client-only. Perform a right-click with the held item like the use key does: on the entity or block under the crosshair first, then the item on its own (e.g. eat, throw). 'target' says which one acted (entity, block or air).",
    inputSchema: { ...handOpt },
    annotations: WRITE,
  },
  {
    name: "attack_entity",
    method: "interact.attackEntity",
    title: "Attack entity",
    description: "Client-only. Attack (left-click) an entity by UUID. Out of reach, nothing is sent (result OUT_OF_REACH). With an integrated server, the answer reports what the server did: removed, and for living targets damaged and serverHealth.",
    inputSchema: { uuid: z.string(), ...forceOpt },
    annotations: WRITE,
  },
  {
    name: "use_entity",
    method: "interact.useEntity",
    title: "Interact with entity",
    description: "Client-only. Right-click/interact with an entity by UUID (e.g. trade with a villager, mount a horse). Out of reach, nothing is sent (result OUT_OF_REACH); otherwise 'result' is the client's prediction.",
    inputSchema: { uuid: z.string(), ...handOpt, ...forceOpt },
    annotations: WRITE,
  },
  {
    name: "drop_held_item",
    method: "interact.dropItem",
    title: "Drop held item",
    description: "Client-only. Drop the held item (one, or the whole stack).",
    inputSchema: { wholeStack: z.boolean().optional().default(false) },
    annotations: ACT,
  },

  // ===== inventory (client) ==================================================================
  {
    name: "select_hotbar_slot",
    method: "inventory.selectHotbar",
    title: "Select hotbar slot",
    description: "Client-only. Select a hotbar slot (0-8) as the held item.",
    inputSchema: { slot: z.number().int().min(0).max(8) },
    annotations: STEER,
  },
  {
    name: "drop_slot",
    method: "inventory.dropSlot",
    title: "Drop a specific slot",
    description: "Client-only. Drop the contents of a specific inventory slot.",
    inputSchema: { slot: z.number().int().min(0).max(40).describe("Inventory slot index (0-8 hotbar, 9-35 main, 36-39 armor, 40 offhand)."), wholeStack: z.boolean().optional().default(true) },
    annotations: ACT,
  },
  {
    name: "swap_slots",
    method: "inventory.swapSlots",
    title: "Swap two inventory slots",
    description: "Client-only. Swap the items in two inventory slots via container clicks (player inventory must be the active screen-less context).",
    inputSchema: { slotA: z.number().int().min(0).max(45), slotB: z.number().int().min(0).max(45) },
    annotations: ACT,
  },

  // ===== vision (client) =====================================================================
  {
    name: "screenshot",
    method: "vision.screenshot",
    title: "Capture screenshot",
    description:
      "Client-only. Capture the current game framebuffer as a PNG image (at the game's current resolution) so a vision-capable model can literally see what the player sees.",
    inputSchema: {},
    annotations: READ,
    kind: "image",
  },
  {
    name: "describe_scene",
    method: "vision.describeScene",
    title: "Describe visible scene",
    description:
      "Client-only. Produce a structured, text description of what is visible: the block/entity directly under the crosshair, a grid of raycasts across the field of view, and nearby visible entities. A cheap alternative to a screenshot for non-vision models.",
    inputSchema: {
      maxDistance: z.number().min(1).max(128).optional().default(48),
      rayColumns: z.number().int().min(1).max(33).optional().default(9),
      rayRows: z.number().int().min(1).max(33).optional().default(5),
    },
    annotations: READ,
  },

  // ===== navigation (client, A*) =============================================================
  {
    name: "navigate_to",
    method: "nav.pathTo",
    title: "Navigate to a position",
    description:
      "Client-only. Asynchronously walk the player to a target position using A* pathfinding (handles walking, jumping up 1 block, and dropping down). Returns immediately; poll navigation_status to track progress and stop_navigation to cancel.",
    inputSchema: {
      ...vec3(),
      reachRadius: z.number().min(0).max(16).optional().default(1).describe("Stop when within this many blocks of the target."),
      sprint: z.boolean().optional().default(false),
      timeoutSeconds: z.number().int().min(1).max(600).optional().default(60),
    },
    annotations: STEER,
  },
  {
    name: "navigation_status",
    method: "nav.status",
    title: "Navigation status",
    description: "Client-only. Report whether navigation is active, the target, remaining distance/steps, and whether the bot appears stuck.",
    inputSchema: {},
    annotations: READ,
  },
  {
    name: "stop_navigation",
    method: "nav.stop",
    title: "Stop navigation",
    description: "Client-only. Cancel any active navigation and release movement.",
    inputSchema: {},
    annotations: STEER,
  },

  // ===== session / menus (client) ============================================================
  {
    name: "get_screen",
    method: "screen.get",
    title: "Current screen",
    description:
      "Client-only. Report where the client is: inWorld, the open screen as a coarse kind (none, pause, chat, container, death, title, multiplayer, singleplayer, disconnected, connecting, loading, other), whether the game is paused, whether join_world can run now (canJoin), and the id of the world joined last in this game run. Call it when client tools fail with no_client_player or the player stops reacting.",
    inputSchema: {},
    annotations: READ,
  },
  {
    name: "close_screen",
    method: "screen.close",
    title: "Close the open screen",
    description:
      "Client-only, in a world. Close the open screen: leaves the pause menu, chat or a container, and respawns the player on the death screen. Fails while a world is loading and outside a world (use join_world there).",
    inputSchema: {},
    annotations: ACT,
  },
  {
    name: "list_worlds",
    method: "session.worlds",
    title: "List worlds and servers",
    description:
      "Client-only. List what join_world can join: saved singleplayer worlds (worldId sp:<folder>, name, lastPlayed in ms, joinable), newest first, and the server list entries as opaque mp: ids (the same ids session/agent memory uses; addresses and entry names are never returned). Also returns lastWorldId, the world joined last in this game run.",
    inputSchema: {},
    annotations: READ,
  },
  {
    name: "join_world",
    method: "session.join",
    title: "Join a world or server",
    description:
      "Client-only, from the menus (title, multiplayer, disconnected, ... screens). Open a singleplayer world or connect to a server by worldId from list_worlds; without worldId, rejoin the world played last in this game run (after a kick, a server restart or a lost connection). Only servers in the server list, or the one played last, can be joined. Returns immediately: poll get_screen until inWorld is true.",
    inputSchema: {
      worldId: z.string().optional().describe('World id from list_worlds or get_screen, e.g. "sp:New World" or "mp:3f2a...". Omit to rejoin the last world.'),
      address: z.string().optional().describe('Pawverse build: any server address (host:port) to connect to, even if it is not in the server list. Takes precedence over worldId.'),
    },
    annotations: { ...ACT, openWorldHint: true },
  },

  // ===== containers (Pawverse build) =========================================================
  {
    name: "get_container",
    method: "container.inspect",
    title: "Read the open container menu",
    description:
      "Client-only. Pawverse build. Read the open menu (chests and plugin menus alike) as a player sees it: title, type, the stack on the cursor (carried), and each slot with item id, count, name, tooltip lines (lore, enchantments), raw components, and its centre on screen (screenX, screenY) for mouse_button. Villager menus add their offers.",
    inputSchema: {
      includeInventory: z.boolean().optional().describe("Also list the player's inventory slots of the menu. Default false."),
    },
    annotations: READ,
  },
  {
    name: "click_slot",
    method: "container.click",
    title: "Click a slot of the open menu",
    description:
      "Client-only. Pawverse build. Click one slot of the open menu with a click packet: mode pickup (normal click; button 0 left, 1 right), quick_move (shift-click), throw (drop; button 1 drops the stack), swap (button = hotbar slot 0-8, 40 offhand), clone (creative middle click), pickup_all (double click, gathers the carried item) or quick_craft (one step of a drag; drag_slots does the whole drag). Slot -999 clicks outside the window. Returns the menu after the click; plugin menus may redraw a moment later, so read it again with get_container. Creative inventory tabs are client-side: use mouse_button there.",
    inputSchema: {
      slot: z.number().int().min(-999).describe("Slot index from get_container, or -999 for outside the window."),
      mode: z.enum(["pickup", "quick_move", "throw", "swap", "clone", "pickup_all", "quick_craft"]).optional().describe("Default pickup."),
      button: z.number().int().min(0).optional().describe("Default 0 (left click)."),
    },
    annotations: ACT,
  },
  {
    name: "drag_slots",
    method: "container.drag",
    title: "Drag the carried stack over slots",
    description:
      "Client-only. Pawverse build. Spread the stack on the cursor over several slots of the open menu, like dragging with the mouse: left splits it evenly, right drops one per slot, middle fills stacks (creative). Pick the stack up first with click_slot.",
    inputSchema: {
      slots: z.array(z.number().int().min(0)).min(1).describe("Slot indexes from get_container, in drag order."),
      button: z.enum(["left", "right", "middle"]).optional().describe("Default left."),
    },
    annotations: ACT,
  },

  // ===== real input (Pawverse build, needs enableUiControl) ==================================
  {
    name: "press_key",
    method: "input.key",
    title: "Press any key",
    description:
      "Client-only, needs enableUiControl. Pawverse build. Press a key through the game's own keyboard handler, exactly as a physical keyboard does, in the world or on any open screen. Takes a key name (e, escape, enter, tab, f3, left.shift, up, key.mouse.left) or a key binding id, which uses the key assigned to it (key.inventory, key.swapOffhand, key.pickItem, key.drop, key.chat, key.playerlist, key.togglePerspective; see get_keybinds, which also lists other mods' bindings). A key binding acts on the next tick, so a screen it opens shows up in get_screen a moment later. Returns the current screen and what stays held.",
    inputSchema: {
      key: z.string().describe("Key name or key binding id."),
      ...inputAction,
      ...modifierOpts,
    },
    annotations: ACT,
  },
  {
    name: "type_text",
    method: "input.type",
    title: "Type text",
    description:
      "Client-only, needs enableUiControl. Pawverse build. Type characters into the focused field of the open screen, like a keyboard: chat, signs, books, anvils, search boxes, plugin dialogs. Open the screen first; send the text with press_key enter. Typing a command in chat this way runs it with the player's permissions, like any player.",
    inputSchema: { text: z.string() },
    annotations: ACT,
  },
  {
    name: "mouse_button",
    method: "input.mouseButton",
    title: "Press a mouse button",
    description:
      "Client-only, needs enableUiControl. Pawverse build. Press a mouse button through the game's own mouse handler. With a screen open it clicks at x,y (GUI coordinates as in get_ui and get_container; screenshot pixels divided by guiScale), so it reaches custom-drawn parts: creative tabs, recipe book, enchanting rows, trades. Without a screen it is the real attack/use click at the crosshair, also in the air; action down on left keeps mining and on right keeps using, for as long as a player would hold it.",
    inputSchema: {
      button: z.enum(["left", "right", "middle", "4", "5"]).optional().describe("Default left."),
      x: z.number().optional().describe("GUI x; needs y. Omit to click where the cursor is."),
      y: z.number().optional().describe("GUI y; needs x."),
      clicks: z.number().int().min(1).optional().describe("2 for a double click. Default 1."),
      ...inputAction,
      ...modifierOpts,
    },
    annotations: WRITE,
  },
  {
    name: "mouse_move",
    method: "input.mouseMove",
    title: "Move the cursor",
    description: "Client-only, needs enableUiControl. Pawverse build. Move the cursor on the open screen to GUI coordinates, for hover states and tooltips (see screenshot). To turn the player in the world, use look.",
    inputSchema: { x: z.number(), y: z.number() },
    annotations: STEER,
  },
  {
    name: "mouse_drag",
    method: "input.mouseDrag",
    title: "Drag with the mouse",
    description: "Client-only, needs enableUiControl. Pawverse build. Press at one point of the open screen, move to another and release: sliders, scroll bars, the advancements map.",
    inputSchema: {
      fromX: z.number(),
      fromY: z.number(),
      toX: z.number(),
      toY: z.number(),
      button: z.enum(["left", "right", "middle", "4", "5"]).optional().describe("Default left."),
      steps: z.number().int().min(1).optional().describe("Intermediate moves. Default 8."),
      ...modifierOpts,
    },
    annotations: ACT,
  },
  {
    name: "mouse_scroll",
    method: "input.mouseScroll",
    title: "Scroll the wheel",
    description: "Client-only, needs enableUiControl. Pawverse build. Turn the mouse wheel: in the world it changes the hotbar slot; on a screen it scrolls what is under x,y.",
    inputSchema: {
      deltaY: z.number().optional().describe("Vertical clicks; positive scrolls up."),
      deltaX: z.number().optional().describe("Horizontal clicks."),
      x: z.number().optional().describe("GUI x; needs y."),
      y: z.number().optional().describe("GUI y; needs x."),
    },
    annotations: ACT,
  },
  {
    name: "release_all_input",
    method: "input.releaseAll",
    title: "Release everything held",
    description: "Client-only. Pawverse build. Release every key and button that press_key and mouse_button hold down. Always call it when a test ends. Movement from set_movement is released by stop_movement.",
    inputSchema: {},
    annotations: STEER,
  },
  {
    name: "get_input_state",
    method: "input.state",
    title: "Input state",
    description: "Client-only, needs enableUiControl. Pawverse build. The open screen and its class, the cursor in GUI coordinates, guiScale, whether the window has focus, and what stays held.",
    inputSchema: {},
    annotations: READ,
  },
  {
    name: "get_keybinds",
    method: "input.keybinds",
    title: "List key bindings",
    description: "Client-only, needs enableUiControl. Pawverse build. Every key binding of the game and of other mods: id, assigned key, and whether it is down.",
    inputSchema: {},
    annotations: READ,
  },

  // ===== senses (Pawverse build, work on any server) =========================================
  {
    name: "get_hud",
    method: "hud.get",
    title: "Read the HUD",
    description:
      "Client-only, needs vision. Pawverse build. What the HUD shows now on any server: actionBar, title, subtitle, bossBars, sidebar (scoreboard title and lines), tabList (header, footer, players), crosshair target, heldItem and offhandItem. Titles and action bars vanish quickly: poll_events also reports each one as a title, actionbar or bossbar event.",
    inputSchema: {},
    annotations: READ,
  },
  {
    name: "get_chat_lines",
    method: "chat.lines",
    title: "Read chat with its clickable parts",
    description:
      "Client-only, needs vision. Pawverse build. The chat window's lines, oldest first, on any server: ref, text, and for each part that reacts to the mouse its click action and hover text. Pass ref and part to click_chat.",
    inputSchema: { limit: z.number().int().min(1).optional().describe("Newest lines to return. Default 50.") },
    annotations: READ,
  },
  {
    name: "click_chat",
    method: "chat.click",
    title: "Click a chat line",
    description:
      "Client-only. Pawverse build. Click a clickable part of a chat line, as the chat screen does: it runs the command (needs enableCommands), shows the dialog or opens the confirmation screen for a link. A suggested command opens in the chat box. A copy action returns the value instead of touching the desktop clipboard.",
    inputSchema: {
      ref: z.number().int().describe("Line ref from get_chat_lines."),
      part: z.number().int().min(0).optional().describe("Part from get_chat_lines; needed when the line has several clickable parts."),
    },
    annotations: { ...WRITE, openWorldHint: true },
  },
  {
    name: "get_command_suggestions",
    method: "chat.suggest",
    title: "Tab-complete a command",
    description:
      "Client-only. Pawverse build. What pressing Tab offers for a partly typed command, including the suggestions the server's plugins send and their tooltips. Runs nothing.",
    inputSchema: { text: z.string().describe('The command so far, e.g. "/pawcore re".') },
    annotations: READ,
  },
  {
    name: "get_entities_client",
    method: "entities.client",
    title: "Entities the client knows",
    description:
      "Client-only, needs vision. Pawverse build. Entities near the player on any server, nearest first: uuid, type, display name, position, health, equipment, vehicle and passengers, dropped items, and the content of text, item and block displays (holograms). 'total' counts all matches.",
    inputSchema: {
      radius: z.number().min(0).optional().describe("Blocks around the player. Default 32."),
      types: z.array(z.string()).optional().describe('Only these entity types, e.g. ["minecraft:text_display"].'),
      maxResults: z.number().int().min(1).optional().describe("Default 200."),
    },
    annotations: READ,
  },
  {
    name: "get_block_client",
    method: "block.inspect",
    title: "Block as the client sees it",
    description:
      "Client-only, needs vision. Pawverse build. A block on any server: id, full state with properties, hardness, block entity type, sign text on both sides, and the block entity data the server sent to the client.",
    inputSchema: { ...vec3() },
    annotations: READ,
  },
  {
    name: "interact_block",
    method: "interact.block",
    title: "Right-click a block",
    description:
      "Client-only. Pawverse build. Right-click a block at an exact face and point, with either hand, whatever the block is and however far it is: levers, buttons, doors, plugin blocks. It sends what the click would send and the server decides. Sneak first with set_movement for a sneaking click.",
    inputSchema: {
      ...vec3(),
      face: z.enum(["up", "down", "north", "south", "east", "west"]).optional().describe("Default up."),
      hitX: z.number().optional().describe("World x of the clicked point. Default: centre of the face."),
      hitY: z.number().optional().describe("World y of the clicked point."),
      hitZ: z.number().optional().describe("World z of the clicked point."),
      ...handOpt,
    },
    annotations: WRITE,
  },
  {
    name: "get_sounds",
    method: "sound.recent",
    title: "Sounds the client played",
    description: "Client-only, needs vision. Pawverse build. The last sounds played (at most 256): id, sound, source, position. Pass sinceId to get only newer ones.",
    inputSchema: { sinceId: z.number().int().min(0).optional() },
    annotations: READ,
  },

  // ===== ui (client, needs enableUiControl) ==================================================
  {
    name: "get_ui",
    method: "ui.widgets",
    title: "List the widgets of the open screen",
    description:
      "Client-only, needs enableUiControl. List the widgets of the open screen in focus order: id, type (button, checkbox, cycle, slider, text_field, text, other), text, active, checked (checkboxes), value (text fields) and position in GUI coordinates. Also reports screenClass and guiScale. Pawverse build: works on every screen, including server dialogs (login, forms). Custom-drawn parts are not widgets: use the container tools for slots and mouse_button for the rest.",
    inputSchema: {},
    annotations: READ,
  },
  {
    name: "click_widget",
    method: "ui.click",
    title: "Click a widget",
    description:
      "Client-only, needs enableUiControl. Left-click a widget of the open screen, by id from get_ui or by its exact label (case-insensitive, must be unique). Returns the screen after the click. Refuses disabled widgets, and Quit Game unless allowQuit is set, since it also ends the bridge. For sliders, fraction picks the position (0 = left, 1 = right).",
    inputSchema: {
      widget: z.union([z.number().int().min(0), z.string()]).describe("Widget id from get_ui, or its exact label."),
      fraction: z.number().min(0).max(1).optional().describe("Where to click across the widget, 0..1 (default 0.5); sets a slider's value."),
      allowQuit: z.boolean().optional().describe("Allow clicking Quit Game, which closes the game and the bridge."),
    },
    annotations: ACT,
  },
  {
    name: "set_text_field",
    method: "ui.setText",
    title: "Type into a text field",
    description:
      "Client-only, needs enableUiControl. Replace the text of a text field on the open screen (by id from get_ui or by label), for example a search box or an anvil's name field. Returns the value the field accepted (fields can cut or filter text).",
    inputSchema: {
      widget: z.union([z.number().int().min(0), z.string()]).describe("Widget id from get_ui, or its exact label."),
      text: z.string(),
    },
    annotations: STEER,
  },

  // ===== events ==============================================================================
  {
    name: "poll_events",
    method: "events.getRecent",
    title: "Poll recent game events",
    description:
      "Return recently observed game events from the in-mod ring buffer (damage taken, entity spawn/death, block break/place, chat, dimension change, etc.). Filter by type and/or pass sinceId to get only events newer than one you've already seen.",
    inputSchema: {
      limit: z.number().int().min(1).max(500).optional().default(50),
      types: z.array(z.string()).optional().describe('Event type filter, e.g. ["chat","player_damage","entity_death"].'),
      sinceId: z.number().int().min(0).optional().describe("Only return events with id greater than this."),
    },
    annotations: READ,
  },
];
