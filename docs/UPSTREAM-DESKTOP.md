# Wbox Desktop source provenance

Wbox 0.5.0 is a derivative of SagerNet's desktop client, not an official SagerNet release.

- Desktop: https://github.com/SagerNet/sing-box-for-desktop
  - Commit: `8aab8eab022366e1032e952d499c9252b833e6c3`
- Dashboard: https://github.com/SagerNet/sing-box-dashboard
  - Commit: `3e4bf08e5bf5b9417cf9c36e5a2d5abcb21bdd34`
- iTerm2 color schemes: `8c84dd1a859f36ec8601b082bd97b4ec888f0f4d`, MIT.

The desktop and dashboard LICENSE files are retained. Both specify GPL-3.0-or-later and a naming/association restriction. Wbox uses its own name and icon. Original copyright notices remain in source.

The original `App.tsx` shell, toolbar, overview, groups, connections, logs, dialogs, controls, typography, and CSS are used directly. The tray reuses `TrayMenu.tsx` and `TrayMenu.module.css`, including cascading group/node menus. Windows popup placement in `wbox/tray-window.ts` follows the upstream tray menu implementation, with on-demand creation and release after dismissal. Wbox additions live in `desktop/wbox` and `dashboard/src/WboxViews.*`. A ConnectRPC transport adapts the existing dual-core backend to the original dashboard protocol. Upstream daemon installation and service names are not used; Wbox does not connect to or alter another sing-box installation.

Proxy lifecycle references read during implementation:

- sing-box v1.14.4: `common/settings/proxy_windows.go`, `experimental/boxdd/server.go` (`stopServiceLocked`). WinINet per-connection options and reset-before-close ordering.
- mihomo v1.19.33: `hub/route/connections.go`, `hub/route/proxies.go`. Connection snapshots, closing connections, selecting outbounds, URL delay tests.

Runtime core sources remain in `resources/source`; their respective licenses apply.

Android lifecycle/layout reference: https://github.com/SagerNet/sing-box-for-android at `a548a7903e96266a0046018562c73b192edbfc18`, especially `bg/VPNService.kt`, `bg/BoxService.kt`, and the Compose dashboard. Wbox Android uses Kotlin Compose with Java service code and borrowed-fd JNI tunnel ownership; it shares desktop semantic colors without embedding Electron/WebView. See `UPSTREAM-ANDROID.md` for component references.

## Build

Use Windows x64 with .NET 8+ SDK, Node compatible with the pinned frontend dependencies, and pnpm 11. Run `pnpm install --ignore-scripts --frozen-lockfile` in `desktop` and `desktop/dashboard`, then `node node_modules/electron/install.js` in `desktop`. This repository and the source archive include the modified desktop/dashboard source and generated protobuf/theme files directly; submodule initialization is not required. Upstream `.gitmodules` files are retained as provenance. Regenerating terminal themes separately requires the upstream iTerm2 color-scheme source at the pinned revision above.

Run `tools/build-desktop.ps1`. It publishes the backend with its .NET runtime, packages Electron with the local runtime, and builds the Inno Setup installer. No .NET installation is needed on the target machine.

UI tests: `npm ci` in `tools/desktop-tests`, then `node tools/desktop-tests/smoke.mjs` from the repository root. They use synthetic profiles, localhost resources, and a separate data directory. Set `WBOX_EXE` to test the packaged executable.

Native proxy test: `dotnet windows/Wbox.Tests/bin/Release/net8.0-windows/Wbox.Tests.dll --proxy-native`. This explicitly changes WinINet settings briefly and restores the captured state in `finally`; it refuses to run when another system proxy is active.
