# Wbox Android VPN native tunnel

`resources/cores/android/arm64-v8a/libwboxtun.so` is built from the complete HEV source in `hev-socks5-tunnel/`, its checked-out submodule sources, and Wbox's `wbox-tun.c` JNI bridge. It is an AArch64 ELF shared library, targeting Android API 23 and supporting 16 KiB memory pages. It uses HEV's coroutine scheduler and lwIP to relay IPv4/IPv6 TCP and UDP from `VpnService` to a local SOCKS5 server.

## Build

From the repository root, run `./tools/native/build-tunnel.ps1 -NdkPath <Android-NDK-directory>`. The verified build used NDK 28.0.13004108. No network download is required when using this vendored source. Windows Git header symlinks are converted to equivalent forwarding includes by the build script. The output is copied to the Android `jniLibs` ABI directory.

## JNI contract and lifecycle

0.6.4 adds a bounded 1024-entry table in `wbox-origin.c`. `hev-socks5-session.c` records original endpoints and the local SOCKS socket port once after the handshake, then removes the entry on session exit. `NativeTunnel.connectionOrigins()` takes a synchronized snapshot on a normal Java thread. No Java/ART calls run on HEV's small coroutine stacks, and packet forwarding does not query PackageManager. Android resolves a unique owner UID only while connection details are requested; unknown/shared owners use a generic icon. TCP and UDP tables are keyed independently by protocol and local port.

Load with `System.loadLibrary("wboxtun")` from `com.wbox.app.NativeTunnel`. Declare `public static native void prepare()`, `public static native int run(String config, int fd)` and `public static native void stop()`. Call `prepare()` once at the beginning of a new VPN session, after the previous worker has been joined. This resets a latched cancellation; it throws `IllegalStateException` if a run is still active. `stop()` latches cancellation even if the worker has not entered `run` yet. `config` is the absolute path of a HEV YAML file, **not** a sing-box JSON or mihomo YAML file. A working IPv4/IPv6 UDP configuration is supplied in `tunnel.yml`. `run` blocks on a dedicated Java worker thread. Return value `0` is a normal shutdown; negative values mean an initialization error; `-125` means cancelled before entering the tunnel, `-16` is a concurrent-run rejection and `-22` means an invalid input/descriptor.

The file descriptor is **borrowed** by JNI and HEV. HEV sets it nonblocking but never closes an externally supplied descriptor. If the app calls `ParcelFileDescriptor.detachFd()`, it must retain the returned integer and close it exactly once in the worker's `finally` block with `ParcelFileDescriptor.adoptFd(fd).close()`. It must not also call `Os.close(fd)` or close it in `stop()`. Stop order: request cancellation, call `NativeTunnel.stop()`, join the running worker, and let the worker's `finally` close the descriptor. The latched native cancellation also protects a worker entering `run` after a stop request. Never clear cancellation with `prepare()` or start a second VPN while the first worker is still alive. A timed-out join must retain the worker reference and prevent another session.

HEV's `quit` uses an atomic stop state and an event socket, including during initialization. Wbox adds one small reset function to `src/hev-socks5-tunnel.c` to clear an unconsumed stop token after a failed initialization or finalization, while holding the JNI state mutex. This prevents a cancellation at that boundary from immediately stopping the next session. The original HEV JNI module is excluded because it registers a different Java class and owns its own thread.

Establish a VpnService interface with the same MTU and addresses as `tunnel.yml`, routes `0.0.0.0/0` and `::/0`, and a real IPv4/IPv6 DNS server reachable through the tunnel. The configured local core must listen on SOCKS5 `127.0.0.1:10808` with TCP and UDP enabled. The app UID must be excluded from the VPN so its spawned core's upstream sockets avoid looping into the TUN. For application allowlists, never add Wbox's own package to the allowlist; for the default/blocklist mode, call `addDisallowedApplication(getPackageName())`.

The official Android core files named `libmihomo.so` and `libsingbox.so` are AArch64 PIE executables, packaged with `.so` names solely to obtain Android's extracted, executable `nativeLibraryDir`. Launch them with `ProcessBuilder`, **never** `System.loadLibrary`. Set `android:extractNativeLibs="true"` and ensure legacy JNI library packaging is enabled. Their upstream licenses are included under `resources/cores/`.

The official sing-box 1.14.4 Android CLI has 4 KiB ELF LOAD alignment. Its compatibility on Android devices configured with 16 KiB pages has not been established. The bundled mihomo CLI and Wbox JNI tunnel use 16 KiB ELF LOAD alignment. `resources/cores/ANDROID-ELF.txt` records the exact ELF headers. The APK requires an arm64 device; no x86 emulator runtime test validates these cores.

## Source and licenses

HEV upstream: https://github.com/heiher/hev-socks5-tunnel

Pinned source revisions:

- HEV tunnel: `c5584feca8f6df70ee53eb2879e034b849a3d15a`
- HEV socks5 core: `dadbb54bf7509f6dea3c1b30e45198efa400679c`
- HEV task system: `d0365c370aadd39129924b563b80751cb63332d7`
- lwIP: `495671e59d736ef59f6cfa7dc9d35b7359921062`
- libyaml: `94441ec8777eb319936735a58b376963d09c73d4`

HEV tunnel, HEV core, HEV task system and libyaml are MIT-licensed. lwIP uses its BSD license. Complete licenses remain in their corresponding source directories and are also collected under `resources/cores/licenses/`. Wbox's forwarding include files and stop-token reset are the only modifications to the upstream source.
