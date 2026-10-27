# Pawverse build

Local build of mcpfabric 0.7.0 (MIT, upstream https://github.com/denfry/mcpfabric) used to automate
in-game tests of Pawcore. Branch `pawverse`, based on tag `v0.7.0`.

Changes:
- UI control works on every screen, including server dialogs (login, registration, forms); only the
  Quit Game button is refused, since it would also end the bridge.
- `session.join` / `join_world` accept `address` to connect to any server, not only the server list.
- MCP tools `get_container` (`container.state`) and `click_slot` (`container.click`) to read and click
  plugin inventory menus.

The bridge still requires its token and `requireAuth`. Build: `./gradlew :26.3-neoforge:build -x test`
(jar in `versions/26.3-neoforge/build/libs/`); MCP server: `npm run build` in `mcp-server/`.
