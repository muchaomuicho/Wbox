# Third-party notices

Wbox source code is distributed under GPL-3.0. No affiliation with upstream projects is implied. The application name is Wbox.

- mihomo 1.19.33 — https://github.com/MetaCubeX/mihomo/tree/v1.19.33 — GPL-3.0; official release executables, unmodified. Full source: `resources/source/mihomo-v1.19.33.tar.gz`. License: `resources/cores/licenses/LICENSE-mihomo`. The upstream name restriction is respected by naming the application Wbox.
- sing-box 1.14.4 — https://github.com/SagerNet/sing-box/tree/v1.14.4 — GPL-3.0-or-later with upstream naming/association condition; official release executables, unmodified. Full source: `resources/source/sing-box-v1.14.4.tar.gz`. License: `resources/cores/licenses/LICENSE-sing-box`. The Windows release also includes its official `libcronet.dll` dependency.
- HEV SOCKS5 Tunnel — https://github.com/heiher/hev-socks5-tunnel — MIT. Pinned source and its submodules are included under `native/hev-socks5-tunnel/`; revisions and Wbox modifications are documented in `native/README.md`. HEV task system, HEV socks5 core and libyaml use MIT; lwIP uses its BSD license. Full license files are retained in source and copied under `resources/cores/licenses/`.
- YamlDotNet 16.3.0 — https://github.com/aaubry/YamlDotNet — MIT.
- SnakeYAML 2.3 — https://bitbucket.org/snakeyaml/snakeyaml — Apache-2.0.
- Android desugar JDK libraries 2.1.4 — https://github.com/google/desugar_jdk_libs — GPL-2.0 with Classpath Exception / upstream component licenses.
- .NET 8 runtime and Windows Desktop runtime — https://github.com/dotnet/runtime — MIT and third-party notices supplied by Microsoft.

Wbox independently implements the UI and import workflow. v2rayN was read as a behavioral reference; no v2rayN UI or application source is copied.

The 258 bundled flag images are from Twitter Twemoji v14.0.2, copyright Twitter, Inc. and other contributors, licensed under CC-BY-4.0. Source: https://github.com/twitter/twemoji/tree/v14.0.2/assets/72x72 . Files are renamed to region codes without image changes. The full license is included as `resources/cores/licenses/LICENSE-Twemoji-Graphics` and in the Windows installer. Wbox 0.4.0 follows the user-provided sing-box layout screenshots with independently written WPF and Android View code.

The sing-box Android dashboard and theme source were read as a layout reference for 0.2.0. Wbox's WPF and Android View interfaces and icons are independently implemented. Robolectric 4.14.1 (MIT) and AndroidX test utilities are used only in JVM tests and are not shipped in the application.

Release asset URLs, exact SHA256 values and binary sizes are recorded in `resources/cores/manifest.json`; Android ELF alignment evidence is in `resources/cores/ANDROID-ELF.txt`.
