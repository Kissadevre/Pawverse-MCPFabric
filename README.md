<div align="center">
  <img src="docs/assets/mcpfabric-icon-512.png" alt="MCP Fabric grass block, AI network, and bridge emblem" width="180" height="180">

# Pawverse MCPFabric

**A fork of [MCP Fabric](https://github.com/denfry/mcpfabric) that lets an AI test Minecraft the way a player uses it, through 80+ tools.**

[![License: MIT](https://img.shields.io/badge/license-MIT-blue)](LICENSE)
[![Upstream: denfry/mcpfabric](https://img.shields.io/badge/upstream-denfry%2Fmcpfabric-informational?logo=github)](https://github.com/denfry/mcpfabric)

[What this fork adds](#what-this-fork-adds) ·
[Installation](#quick-start) ·
[Tools](#tools-80) ·
[Fork notes](PAWVERSE.md) ·
[Security](SECURITY.md)
</div>

> [!NOTE]
> This is an independent fork maintained by [Zibuu](https://zibuu.net) for testing the Pawverse plugins.
> It is not affiliated with or supported by the original project. All credit for MCP Fabric goes to
> [denfry](https://github.com/denfry) and its contributors; it stays under the MIT license.

MCP Fabric is a local-first Minecraft mod and Model Context Protocol server that gives AI agents
structured observation and controlled access to Minecraft. This fork starts from upstream 0.7.0 and
widens what the agent can do and perceive as a player, on any server.

- **Play through natural language:** move, look, navigate, mine, build, fight, and use inventory.
- **See the game:** inspect blocks, entities, players, status, chat, events, and screenshots.
- **Operate servers:** run commands, edit worlds, manage entities, and administer players.
- **Bring your own AI:** works with Claude Desktop, Claude Code, and other MCP-compatible hosts.
- **Stay local by default:** loopback-only HTTP bridge, bearer authentication, and capability gates.
- **No hidden telemetry:** the mod sends no usage analytics.

## What this fork adds

| | MCP Fabric 0.7.0 | This fork |
|---|---|---|
| **Keyboard and mouse** | Movement keys and widget clicks | Any key or binding, typing, and mouse clicks, drags and wheel through the game's own input handlers |
| **Screens** | Allowlist; custom-drawn parts unreachable | Every screen, including creative tabs, signs and plugin dialogs |
| **Hands and clicks** | Main hand; right click where the tool decides | Either hand, exact block clicks, held attack and use, clicks in the air |
| **Menus** | Item id and count | Names, lore, components, cursor stack, slot positions, villager offers; every click type and drags |
| **HUD on a remote server** | Not readable | Action bar, titles, boss bars, sidebar and tab list, also as events |
| **Chat** | Plain text | Click actions, hover text and tab completion, including plugin suggestions |
| **World on a remote server** | Block ids and basic entity data | Block states, signs, entity names and equipment, text displays, sounds |
| **Joining servers** | Saved server list | Any address |

The server still has the last word: permissions, reach and anti-cheat answer as they would to a player,
which is what makes the tests worth having. The bridge keeps its token and loopback binding.
[PAWVERSE.md](PAWVERSE.md) lists every tool, what stays limited and the known gaps.

**Where upstream goes further:** this fork targets **Minecraft 26.3 only**, while upstream supports 1.21.1
through 26.3 on both loaders, and it does not include what upstream added after 0.7.0, such as the supervised
mode of 0.8.0. If you need those, use [the original project](https://github.com/denfry/mcpfabric).

## Quick start

1. Install [Fabric Loader](https://fabricmc.net/use/installer/) and
   [Fabric API](https://modrinth.com/mod/fabric-api), or [NeoForge](https://neoforged.net/).
2. Build the jar with `./gradlew :26.3-neoforge:build -x test` and copy it from
   `versions/26.3-neoforge/build/libs/` to `mods/`. The jars on Modrinth are upstream's and do not have this
   fork's tools.
3. Launch Minecraft once, then copy `token` from `config/mcpfabric.config.json`. Set `enableUiControl` to
   `true` there for the real input tools.
4. Build the MCP server with `cd mcp-server && npm ci && npm run build`.
5. Add it to your MCP client using the [ready-to-copy examples](#3-connect-to-claude).

> [!CAUTION]
> MCP Fabric can grant an AI operator-level control. Keep the bridge on `127.0.0.1`, keep
> authentication enabled, and disable capability groups you do not need.

## How it works

`mcpfabric` has two parts: a Fabric / NeoForge mod that embeds a local HTTP bridge in Minecraft, and a small
TypeScript MCP server that exposes the bridge as discoverable tools.

```
Claude / any MCP client
        │  MCP (stdio or streamable HTTP)
   mcp-server  (Node / TypeScript)
        │  HTTP  POST /rpc (JSON-RPC) + GET /events (SSE),  bearer token, 127.0.0.1 only
   mod "mcpfabric" on Fabric or NeoForge  (HTTP server embedded in Minecraft)
        │  all game access goes through the main-thread executor (server.execute / Minecraft.execute)
   ┌── common (env *) ─────────────┐   ┌── client (env client) ─────────────────┐
   │ info  world  entities         │   │ player  control  interact               │
   │ players  command  chat events │   │ inventory  vision  navigation  chat     │
   └───────────────────────────────┘   └─────────────────────────────────────────┘
```

Reads use Minecraft's native API (structured data); writes (`setblock` / `summon` / `give` / `tp` /
effects / weather / time) go through the command dispatcher with output capture. Player control uses
`KeyMapping` (integrating with the vanilla input pipeline), screenshots use the vanilla
`Screenshot` / `NativeImage`, and navigation is a custom A\*.

---

## Supported Minecraft versions

> [!IMPORTANT]
> This fork is built and tested on **26.3 NeoForge** only; 26.3 Fabric compiles but has not been run in the
> game. The rest of this section describes the upstream source tree it inherits.

A single source tree targets many Minecraft versions using
[Stonecutter](https://stonecutter.kikugie.dev/). Each version below ships its own jar:

| Line     | Versions (one jar each)                                          | Java |
|----------|-----------------------------------------------------------------|------|
| 1.21.x   | 1.21.1, 1.21.2, 1.21.3, 1.21.4, 1.21.5, 1.21.6, 1.21.7, 1.21.8, 1.21.9, 1.21.10, 1.21.11 | 21 |
| 26.x     | 26.1.2 (installs on 26.1–26.1.2), 26.2, 26.3                     | 25   |

14 Fabric jars are produced, each named `mcpfabric-<modVersion>+<mcVersion>.jar` (e.g.
`mcpfabric-0.3.0+1.21.8.jar`). The 26.1.2 jar declares compatibility with the whole 26.1 line.
They require **Fabric Loader ≥ 0.19.5** and the matching **Fabric API** build.

13 NeoForge jars cover the same versions except 1.21.2 (NeoForge only shipped two abandoned betas
for it), each named `mcpfabric-neoforge-<modVersion>+<mcVersion>.jar`:

| Minecraft | Minimum NeoForge |
|-----------|------------------|
| 1.21.1    | 21.1.251         |
| 1.21.3    | 21.3.97          |
| 1.21.4    | 21.4.157         |
| 1.21.5    | 21.5.98          |
| 1.21.6    | 21.6.20-beta     |
| 1.21.7    | 21.7.25-beta     |
| 1.21.8    | 21.8.54          |
| 1.21.9    | 21.9.16-beta     |
| 1.21.10   | 21.10.64         |
| 1.21.11   | 21.11.45         |
| 26.1.2    | 26.1.2.109       |
| 26.2      | 26.2.0.88        |
| 26.3      | 26.3.0.10-beta   |

NeoForge for 1.21.6, 1.21.7, 1.21.9 and 26.3 only exists as beta builds.

The MCP server needs **Node.js ≥ 22.16** (the agent runtime uses the built-in `node:sqlite`).

---

## 1. Build the mod

```bash
# Build a single version (the one currently active in stonecutter.gradle)
./gradlew build           # Windows: gradlew.bat build

# Build a specific version (Fabric nodes are named after the version, NeoForge nodes <mc>-neoforge)
./gradlew ":1.21.8:build"
./gradlew ":1.21.1-neoforge:build"

# Build every supported version at once
./gradlew chiseledBuild
```

Per-version jars land in `versions/<node>/build/libs/`.

> **JDK note.** 1.21.x builds need **JDK 21**; the 26.x line needs **JDK 25**. Loom requires the
> Gradle daemon to run on a JDK at least as new as the Minecraft version, so to build 26.x (or
> `chiseledBuild`) run Gradle on JDK 25 with JDK 21 also installed. See
> [CONTRIBUTING.md](CONTRIBUTING.md#jdk-requirements).

> **Network note.** If the first build fails with `Remote host terminated the handshake` while
> downloading dependencies from `maven.fabricmc.net` (this happens behind TLS-inspecting
> antivirus/firewalls), `gradle.properties` already pins TLS 1.2 and sequential downloads. Just
> re-run the build — the download cache persists.

### Install

Drop the jar for your Minecraft version and loader into your `mods/` folder (on Fabric, together
with **Fabric API**):

- **Client** (AI plays as you): the `mods/` folder of your Fabric / NeoForge instance.
- **Server** (AI as admin): the `mods/` folder of your dedicated Fabric / NeoForge server.
- Both sides at once is fine.

On first launch the mod creates `config/mcpfabric.config.json` and logs where to find the token:

```
[mcpfabric] ready — bridge http://127.0.0.1:25599 (token: .../config/mcpfabric.config.json)
```

Copy the `token` value from that file — the MCP server needs it. The token is intentionally not
printed to logs.

### Mod config (`config/mcpfabric.config.json`)

```json
{
  "host": "127.0.0.1",
  "port": 25599,
  "token": "generated automatically",
  "requireAuth": true,
  "callTimeoutMs": 8000,
  "worldIdKey": "generated automatically",
  "enableWorldWrite": true,
  "enableCommands": true,
  "enablePlayerControl": true,
  "enableVision": true,
  "enableUiControl": false
}
```

The `enable*` flags let you switch off dangerous capability groups. `enableUiControl` is the one
that starts off: it lets the model click buttons and type into text fields of game screens
(`get_ui`, `click_widget`, `set_text_field`), which reaches settings and actions the other tools
never expose. In this fork every screen can be driven, and it also unlocks the real keyboard and mouse tools, so turn it on
only when you need it, for example to test a mod's UI. It also needs `enablePlayerControl`. `requireAuth` (default `true`)
gates every request behind the bearer token — only set it to `false` if you understand that it
removes the sole authentication on an operator-level bridge. Keep `host` on `127.0.0.1` unless you
fully understand the consequences — the bridge grants operator-level power. `worldIdKey` turns a
server address into the opaque world id that `session.info` reports; like the token, it is never
sent over the bridge.

---

## 2. MCP server

```bash
cd mcp-server
npm install
npm run build      # compiles to dist/
```

Usually your MCP client launches it for you (see below). Manually:

```bash
MCPFABRIC_URL=http://127.0.0.1:25599 MCPFABRIC_TOKEN=<token> node dist/index.js
```

### Environment variables

| Variable               | Default                  | Description                                       |
|------------------------|--------------------------|---------------------------------------------------|
| `MCPFABRIC_URL`        | `http://127.0.0.1:25599` | Address of the mod's HTTP bridge.                 |
| `MCPFABRIC_TOKEN`      | —                        | Bearer token from the mod config (required by default). |
| `MCPFABRIC_TIMEOUT_MS` | `15000`                  | Per-call timeout to the bridge.                   |
| `MCPFABRIC_TRANSPORT`  | `stdio`                  | `stdio` or `http`.                                |
| `MCPFABRIC_HTTP_PORT`  | `25600`                  | Port for the `http` transport (`/mcp`).           |

---

## 3. Connect to Claude

### Claude Desktop

`claude_desktop_config.json` (see `examples/claude_desktop_config.json`):

```json
{
  "mcpServers": {
    "mcpfabric": {
      "command": "node",
      "args": ["/absolute/path/to/mcpfabric/mcp-server/dist/index.js"],
      "env": {
        "MCPFABRIC_URL": "http://127.0.0.1:25599",
        "MCPFABRIC_TOKEN": "paste-the-token-from-the-mod-log"
      }
    }
  }
}
```

### Claude Code

Use the `.mcp.json` in the project root (see `examples/mcp.json`) or:

```bash
claude mcp add mcpfabric -- node /absolute/path/to/mcpfabric/mcp-server/dist/index.js
```

Then set `MCPFABRIC_TOKEN` in the server's environment.

---

## Tools (80+)

**info** — `get_status`, `list_capabilities`
**world (read)** — `get_block`, `get_blocks_region`, `find_blocks`, `get_time_and_weather`, `list_dimensions`, `raycast`
**world (write)** — `set_block`, `fill_blocks`, `set_time`, `set_weather`
**entities** — `query_entities`, `get_entity`, `summon_entity`, `remove_entity`
**players (admin)** — `list_players`, `get_player`, `teleport_player`, `set_gamemode`, `give_item`, `apply_effect`, `message_player`, `kick_player`
**command** — `run_command` (any operator-level command, with output capture)
**chat** — `send_chat`, `get_recent_chat`
**player (local, client)** — `get_self`, `get_inventory`, `get_equipment`, `get_status_effects`
**control (client)** — `set_movement`, `stop_movement`, `look`, `look_at`, `jump`, `start_using_item`, `stop_using_item`
**interact (client)** — `break_block`, `place_block`, `use_item`, `attack_entity`, `use_entity`, `drop_held_item`
**inventory (client)** — `select_hotbar_slot`, `drop_slot`, `swap_slots`
**vision (client)** — `screenshot` (PNG for vision models), `describe_scene`
**navigation (client)** — `navigate_to` (A\*), `navigation_status`, `stop_navigation`
**session (client)** — `get_screen`, `close_screen` (pause menu, chat, container; respawns on the death screen), `list_worlds`, `join_world` (rejoin after a kick, a server restart or a game restart)
**ui (client, off by default: `enableUiControl`)** — `get_ui` (widgets of the open screen), `click_widget`, `set_text_field`
**containers (client)** — `get_container`, `click_slot`, `drag_slots`
**real input (client, Pawverse build, needs `enableUiControl`)** — `press_key`, `type_text`, `mouse_button`, `mouse_move`, `mouse_drag`, `mouse_scroll`, `release_all_input`, `get_input_state`, `get_keybinds`
**senses (client, Pawverse build, any server)** — `get_hud`, `get_chat_lines`, `click_chat`, `get_command_suggestions`, `get_entities_client`, `get_block_client`, `get_sounds`, `interact_block`
**events** — `poll_events` (recent damage, deaths, chat, spawns, player join/leave)

**agent runtime** ([docs/AGENT.md](docs/AGENT.md)) — persistent per-world memory, goals, map and background jobs:
`agent_brief`, `observe`, `map_view`, `remember`, `recall`, `memory_update`, `memory_verify`,
`goal_add`, `goal_update`, `goal_list`, `plan_craft`, `get_recipes`, `explore_next`,
jobs `travel_to` / `explore` / `collect_blocks` / `craft_item` with `job_status` / `job_cancel`,
and `open_container` / `container_transfer` / `close_container`

Server tools require a running server (integrated on the client or dedicated). Client tools
(`control` / `interact` / `vision` / `navigation` / `player` / `inventory`) only work on the client.
Call `get_status` first — it reports which side you are on and which groups are available.

---

## Example prompts

- "Look around and describe what's nearby" → `get_self` + `describe_scene` (+ `screenshot` for a vision model).
- "Walk to these coordinates and mine diamonds" → `find_blocks` → `navigate_to` → `break_block`.
- "Get a stone pickaxe" → `plan_craft` → `collect_blocks` (logs, stone) → `craft_item`; progress is
  kept in the goal tree and memory, so a later session resumes with `agent_brief`.
- "What's happening on the server" → `list_players` + `poll_events`.
- "Build a wall" → `fill_blocks` or a series of `set_block` / `run_command`.

---

## Security

- The bridge listens on **`127.0.0.1` only** and requires a **bearer token**.
- Operator-level capabilities (`run_command`, world writes, player control) are **on by default** —
  this gives the AI full control. Turn off groups with the `enable*` flags if you need to.
- Do not expose `host` externally without understanding the risk and putting an authenticated
  reverse proxy in front of it. See [SECURITY.md](SECURITY.md).

---

## Project structure

```
mcpfabric/
├─ settings.gradle, stonecutter.gradle                 # Stonecutter nodes (version × loader)
├─ build.gradle, build.neoforge.gradle                 # Fabric Loom / NeoForge ModDevGradle builds
├─ gradle/common.gradle, gradle/modrinth.gradle        # shared by both loaders
├─ gradle.properties                                   # shared build config
├─ versions/<node>/gradle.properties                   # per-node Minecraft + loader versions
├─ src/main/java/dev/mcpfabric/                         # common (env *): bridge + server handlers
│  ├─ McpFabric.java, ServerHolder.java
│  ├─ platform/    (Platform: the loader services the shared code needs)
│  ├─ fabric/, neoforge/   (loader entrypoints; only the node's own loader is compiled)
│  ├─ bridge/      (HttpBridgeServer, RpcRouter, MainThread, SseHub, Json, ...)
│  ├─ config/      (McpConfig)
│  ├─ events/      (EventBus, GameEvent)
│  └─ handlers/    (Info/World/Entity/PlayerAdmin/Command/Chat + support/CommandRunner, Levels)
├─ src/client/java/dev/mcpfabric/client/                # client (env client): bot + client handlers
│  ├─ McpFabricClient.java, ClientMc.java, BotController.java, ClientEvents.java
│  ├─ fabric/, neoforge/   (loader client entrypoints)
│  ├─ nav/AStarPathfinder.java
│  └─ handlers/    (LocalPlayer/Control/Interact/Inventory/Vision/Nav/ClientChat)
└─ mcp-server/                                          # MCP server (TypeScript)
   ├─ src/index.ts, bridge.ts, tools.ts, config.ts
   └─ package.json, tsconfig.json
```

See [CONTRIBUTING.md](CONTRIBUTING.md) for the multi-version workflow and how to add a Minecraft
version.

## License

[MIT](LICENSE).
