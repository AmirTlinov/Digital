# Digital plugin for Codex

```sh
distribution/codex/build.sh
distribution/codex/install.sh
```

The build uses a self-contained macOS arm64 Node 22+ distribution with npm,
locks dependencies with `package-lock.json`, bundles the MCP server,
and verifies its real stdio transport with a minimal PATH.
`NODE_BIN` and `CODEX_CMD` select the build and installation executables.

The installed plugin has its own Node runtime and needs no shell configuration.
Its first tool call connects to `/Applications/Digital.app`, launching it if necessary.
Closing an MCP session leaves Digital running. Tools edit the visible application's
own circuit and use revisions to protect changes made between inspections.

The plugin is GPL-3.0-only. The bundle includes the project's `LICENSE`,
Node's `NODE-LICENSE`, and licenses of bundled SDK dependencies in `runtime/THIRD_PARTY_NOTICES`.
