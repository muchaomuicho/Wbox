# Wbox sing-box derivative

Based on SagerNet/sing-box v1.14.4, source commit `c005f35300ce76427dc285481ec5695f5b179cd2`. The unmodified source archive is `resources/source/sing-box-v1.14.4.tar.gz` (SHA256 `0958ccd23b1ea9a141d715d15478b1e040fd04bcca19d8ff5529636b1b753e18`).

The Go overlays replace/add `common/urltest/urltest.go`, `common/urltest/unified_delay_test.go`, and `experimental/clashapi/proxies.go`. The delay API now inherits the core certificate/time services and honors request cancellation. No other upstream core source is changed. The GPL-3.0-or-later license and upstream naming provisions continue to apply; see `resources/cores/licenses/LICENSE-sing-box`.

The first HEAD establishes the proxy and TLS connection. A second HEAD on the same connection supplies the displayed latency. If the server closes the connection, the completed first measurement is retained without adding the failed second request's wait. Cancellation and initial failure return errors; successful sub-millisecond results are clamped to 1 ms. No fixed subtraction or fabricated latency is applied. Existing multiplex warm-up remains intact.

Rebuild with `tools/native/build-singbox.ps1 -Go <go.exe> -NdkPath <NDK-28.0.13004108> [-Emulator]`. This release uses Go 1.25.5, the unchanged upstream Windows/other-platform feature tags, and the version suffix `1.14.4-wbox.1`. Android uses API 26 and requests 16 KiB ELF LOAD alignment. Windows retains the upstream `libcronet.dll` for NaiveProxy.

The build script verifies the original archive, applies these source files, runs the URLTest regressions, and builds Windows amd64 plus Android arm64. `-Emulator` also builds Android x86_64 for the test-only APK. Run this after fetching the original core archives and before packaging Wbox; fetching originals again replaces the modified binaries.
