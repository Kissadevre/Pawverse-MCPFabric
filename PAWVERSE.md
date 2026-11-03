# Pawverse build

Local build of mcpfabric 0.7.0 (MIT, upstream https://github.com/denfry/mcpfabric) used to automate
in-game tests of our plugins as a player would run them. Repository
https://github.com/Kissadevre/Pawverse-MCPFabric, branch `main`, based on upstream tag `v0.7.0`. Remote `origin`
is this repository and `upstream` the original project, whose `main` is the local branch `upstream-main`.
Only Minecraft 26.3 is supported here; upstream's other versions are not maintained on this branch.

## What it adds

**Real input** (`dev.mcpfabric.client.pawverse.PlayerInput`, needs `enableUiControl`). The tools call the
handlers the window feeds with a physical keyboard and mouse, so any key binding, any screen and any
custom-drawn part of a screen behaves as it does for a player.

| Tool | Reaches |
|---|---|
| `press_key` | Any key or key binding, also of other mods: inventory, F (offhand), Q, Tab, F3, F5, Esc, Enter. Tap, hold, release. |
| `type_text` | Chat, signs, books, anvils, search boxes, plugin dialogs. No length limit of its own. |
| `mouse_button` | Attack and use at the crosshair, also in the air; hold to mine or to block with a shield. On a screen: creative tabs, recipe book, enchanting rows, trades, anything drawn. |
| `mouse_move`, `mouse_drag`, `mouse_scroll` | Hover states, sliders, scroll bars, hotbar wheel. |
| `release_all_input`, `get_input_state`, `get_keybinds` | Cleanup and state. Call `release_all_input` when a test ends. |

**Senses on any server** (`PlayerSenses`, needs `enableVision`). They read the client, so they work on a
remote server, where `get_block`, `query_entities` and the other server-side tools do not.

| Tool | Reads |
|---|---|
| `get_hud` | Action bar, title, subtitle, boss bars, sidebar, tab list, crosshair target, items in hand. |
| `poll_events` | New events `title`, `actionbar`, `bossbar`, for what vanishes between two reads. |
| `get_chat_lines`, `click_chat` | Chat with click actions and hover texts; clicks as the chat screen does, confirmation screens included. |
| `get_command_suggestions` | What Tab offers, including what the plugins suggest. |
| `get_entities_client` | Names, equipment, passengers and the content of text, item and block displays. |
| `get_block_client` | Full block state, sign text, block entity data the client received. |
| `get_sounds` | The last 256 sounds played. |

**Inventory and interaction.**

- `get_container`: title, cursor stack, names, tooltips (lore, enchantments), raw components, the slot's
  position on screen for `mouse_button`, and villager offers. `includeInventory` adds the player's slots.
- `click_slot`: every click type (also `clone`, `pickup_all`, `quick_craft`) and slot `-999` (outside).
- `drag_slots`: spreads the cursor stack over several slots.
- `interact_block`: right click at an exact face and point, with either hand, on any block.
- `hand` (`main`, `off`) on `use_item`, `use_entity`, `place_block`; `force` on `attack_entity`,
  `use_entity`, `place_block` sends an action beyond reach and lets the server decide, to test what a
  plugin or anti-cheat does with a modified client.

**Fewer refusals.** Every screen can be driven; `set_text_field` has no length limit; `get_ui` lists up to
5000 widgets and reports `screenClass` and `guiScale`; `join_world` takes any `address`; Quit Game only
needs `allowQuit`, since it also ends the bridge.

## What stays limited, on purpose

- The bridge keeps its token, `requireAuth` and the `127.0.0.1` binding. They protect the machine, not the game.
- The server still decides everything: permissions, reach, anti-cheat and plugins answer as they would to a
  player. That is what makes the test worth having.
- `get_blocks_region`, `find_blocks`, `query_entities` and the rest of the server-side tools keep their size
  caps, which protect the server. They only run with an integrated server.
- Automated play is evidence, not human acceptance: it does not judge how something feels, sounds or looks.

## Known gaps

- Particles have no text reading; use `screenshot`.
- Key state a screen reads directly from the OS (`InputConstants.isKeyDown`, used by a few screens for Shift)
  cannot be simulated. Pass `shift`, `ctrl` or `alt` on the input call, or use `quick_move` for shift clicks.
- The physical mouse and keyboard stay active: moving them during a test moves the same cursor.
- The HUD and chat values live in private fields, read by name. A game update can move them; the tool then
  lists them in `unavailable` and the rest keeps working.

## Build and install

```
./gradlew :26.3-neoforge:build -x test
cd mcp-server && npm ci && npm run build
```

Jar: `versions/26.3-neoforge/build/libs/`. It goes to `mods/` of both instances, Prism `Pawverse` and SKLauncher
`pawverse` (account Zulu), because both MCP servers share one copy of the MCP server, at
`~/.local/share/pawverse-automation/mcp-server/`: copy `dist/` and `src/` there. Close the games first, and
reconnect the MCP servers afterwards to see the new tools.

`src/client/java/dev/mcpfabric/client/pawverse/` is excluded from nodes older than 26.3 in
`gradle/common.gradle`. Compiled on this branch: `26.3-neoforge` and `26.3`. `1.21.1-neoforge` fails because
`UiHandlers` imports `DialogScreen`, which it lacks, since the first Pawverse commit; other nodes are not checked.
