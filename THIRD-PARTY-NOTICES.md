# Third-party notices

Wbox source code is distributed under GPL-3.0. No affiliation with upstream projects is implied. The application name is Wbox.

- mihomo 1.19.33 — https://github.com/MetaCubeX/mihomo/tree/v1.19.33 — GPL-3.0; official release executables, unmodified. Full source: `resources/source/mihomo-v1.19.33.tar.gz`. License: `resources/cores/licenses/LICENSE-mihomo`. The upstream name restriction is respected by naming the application Wbox.
- sing-box 1.14.4 — https://github.com/SagerNet/sing-box/tree/v1.14.4 — GPL-3.0-or-later with upstream naming/association condition; official release executables, unmodified. Full source: `resources/source/sing-box-v1.14.4.tar.gz`. License: `resources/cores/licenses/LICENSE-sing-box`. The Windows release also includes its official `libcronet.dll` dependency.
- HEV SOCKS5 Tunnel — https://github.com/heiher/hev-socks5-tunnel — MIT. Pinned source and its submodules are included under `native/hev-socks5-tunnel/`; revisions and Wbox modifications are documented in `native/README.md`. HEV task system, HEV socks5 core and libyaml use MIT; lwIP uses its BSD license. Full license files are retained in source and copied under `resources/cores/licenses/`.
- YamlDotNet 16.3.0 — https://github.com/aaubry/YamlDotNet — MIT.
- SnakeYAML 2.4 — https://bitbucket.org/snakeyaml/snakeyaml — Apache-2.0.
- MetaCubeX meta-rules-dat — https://github.com/MetaCubeX/meta-rules-dat — GPL-3.0. Unmodified default GeoIP / GeoSite databases from the official release are bundled for offline initialization. Exact URLs, SHA256 values and sizes: `resources/geodata/manifest.json`. License: `resources/cores/licenses/meta-rules-dat-LICENSE`. Upstream sources and data attribution: https://github.com/MetaCubeX/meta-rules-dat/blob/master/README.md .
- Android desugar JDK libraries 2.1.4 — https://github.com/google/desugar_jdk_libs — GPL-2.0 with Classpath Exception / upstream component licenses.
- .NET 8 runtime and Windows Desktop runtime — https://github.com/dotnet/runtime — MIT and third-party notices supplied by Microsoft.

Wbox 0.5.0 for Windows is derived from SagerNet/sing-box-for-desktop and SagerNet/sing-box-dashboard, GPL-3.0-or-later with the additional naming/association restriction in their LICENSE files. Original licenses and copyright notices are retained. Wbox is an independent derivative, not an official SagerNet application. The original shell, pages, controls and styles are reused with Wbox branding and a dual-core transport. Exact commits and modified integration points are documented in `docs/UPSTREAM-DESKTOP.md`. v2rayN remains a behavioral reference; no v2rayN application source is copied.

The Windows frontend includes Electron (MIT and Chromium third-party licenses, supplied in the packaged runtime), React (MIT), ConnectRPC (Apache-2.0), protobuf-es (Apache-2.0), Schibsted Grotesk / Source Serif 4 / IBM Plex Mono / Noto Color Emoji (SIL OFL-1.1). Frontend dependency versions and transitive dependencies are pinned in `desktop/pnpm-lock.yaml` and `desktop/dashboard/pnpm-lock.yaml`. Original font licenses are included with the installer. iTerm2-Color-Schemes uses MIT; its license is included.

The 258 bundled flag images are from Twitter Twemoji v14.0.2, copyright Twitter, Inc. and other contributors, licensed under CC-BY-4.0. Source: https://github.com/twitter/twemoji/tree/v14.0.2/assets/72x72 . Files are renamed to region codes without image changes. The full license is included as `resources/cores/licenses/LICENSE-Twemoji-Graphics` and in the Windows installer. Wbox 0.4.0 follows the user-provided sing-box layout screenshots with independently written WPF and Android View code.

The sing-box Android dashboard and lifecycle source were read as references; the 0.5.0 Android interface remains independently implemented with native Java views and desktop semantic colors. Robolectric 4.14.1 (MIT) and AndroidX test utilities are used only in JVM tests and are not shipped in the application.

Release asset URLs, exact SHA256 values and binary sizes are recorded in `resources/cores/manifest.json`; Android ELF alignment evidence is in `resources/cores/ANDROID-ELF.txt`.

Wbox 0.6.0 Android uses Kotlin / AndroidX Compose / Material 3 (Apache-2.0). The LineChart source is adapted directly from SagerNet/sing-box-for-android, commit a548a7903e96266a0046018562c73b192edbfc18; dashboard/card/status-bar layouts are adapted to the Wbox model and monochrome theme. GPL-3.0-or-later and upstream naming condition are retained in resources/cores/licenses/LICENSE-sing-box-for-android. See docs/UPSTREAM-ANDROID.md for exact source mapping.
