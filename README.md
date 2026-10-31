# Wbox

Wbox 是原生 Windows / Android 双内核代理客户端。界面以黑白为主，顶部使用计时启停按钮，图标为黑底白色 W。运行时只启动所选内核；固定集成 mihomo 1.19.33 与 sing-box 1.14.4。

0.4.0 按 sing-box 截图重写深灰布局，新增可折叠代理组、258 种本地国旗、可搜索连接卡片和分层设置，并修复配置入站与启动诊断。配置与节点继续使用两个独立工作区。支持直接添加、更新无文件后缀的远程 JSON / YAML 地址。具体改动和预览见 [发布说明](docs/RELEASE-0.4.0.md)。

## 安装与使用

交付文件在 `dist/`：

- `Wbox-0.4.0-windows-x64-setup.exe`：Windows 10 / 11 x64 安装程序，自带 .NET 运行库，安装到当前用户目录。
- `Wbox-0.4.0-android-arm64-v8a.apk`：Android 8.0+ ARM64 签名安装包。
- `Wbox-0.4.0-source.zip`：Wbox 完整源码、原生隧道源码、固定版本内核源码与构建脚本。
- `SHA256SUMS.txt`：以上文件的校验值。

先在顶部（Android）或侧栏（Windows）选择工作区。配置工作区直接添加远程 URL 或导入本地 JSON / YAML，保存完整代理组、DNS 和路由，支持多份配置、切换、更新和移除。节点工作区单独管理订阅与分享链接，按来源分组、搜索、选择节点；Windows 支持离线临时启动内核测试所选节点及批量测试，Android 在 VPN 连接后测试。两工作区分别保留配置选择和节点选择；切换前需要断开连接。

Windows 支持开机自启、静默启动、自动连接和托盘菜单；开关在“设置”中保存。关闭主窗口收起到托盘，退出请使用托盘菜单或设置页“退出 Wbox”。

Windows 默认使用系统代理，只接管遵循 Windows 系统代理设置的应用。默认在运行副本中用 Wbox 受管 mixed 入站替换原配置入站，保留首个 mixed 标签。需要原生入站或全流量接管时，在概览选择“配置原生入站 / TUN”；TUN 需要在设置中以管理员身份重启。受管端口冲突时自动改用空闲端口，原生入站冲突会说明具体端口。停止连接会根据持久备份恢复系统代理；若其它软件已经修改这些设置，Wbox 不覆盖它们。

Android 第一次连接需要授权系统 VPN。设置页可选择全部应用、只代理所选应用或绕过所选应用；Wbox 自身始终绕过 VPN，避免内核上游连接回环。IPv6 默认开启。Android 的系统 VPN 由 Wbox 管理，导入配置中的 TUN 入站会在运行副本中移除，并增加本地 mixed 入站。原配置规则若按入站标签匹配，需要使用 `wbox-in` 标签。JSON/YAML 内的本地外部资源应放到应用可访问的位置；只导入文本文件不会自动导入旁边的规则资源。

## 已实现的功能

双内核切换；远程与本地 JSON/YAML 完整配置库；独立的节点订阅库；HTTP(S) 订阅、Base64 节点列表、原生 outbounds/proxies 提取；SS（含 SS2022）、VMess、VLESS/Reality、Trojan、Hysteria、Hysteria2、TUIC、AnyTLS、SOCKS、HTTP(S)、WireGuard 分享链接；Windows 另支持 NaiveProxy。原生订阅中的 SSH 等内核节点也能原样导入，但需要对应内核。

已支持 ALPN、SS obfs-local/simple-obfs/v2ray-plugin、Hysteria2 salamander 混淆、TUIC congestion_control/udp_relay_mode，以及 WS/gRPC/HTTP2 传输。代理组与节点切换、内核 HTTP 延迟测试、有界日志、活动连接明细、入站/出站逻辑连接数、本次流量、60 秒波形、内存显示和 Android 分应用 VPN 均保留。

默认延迟测试 URL 为 `https://www.apple.com/library/test/success.html`。sing-box 1.14.4 上游 API 会替换 HTTP 测试地址，因此 Wbox 要求该内核自定义测速使用 HTTPS；mihomo 支持 HTTP 与 HTTPS。Android 延迟测试在连接后使用内核控制接口。Android 节点列表长按可删除离线节点。

未支持的分享格式或传输（例如 XHTTP、ECH 分享参数）会明确报错并保留原节点列表，可改用内核支持的完整配置。TLS SOCKS 需要 mihomo；WireGuard 分享链接的 dns 参数需使用 mihomo 或完整 sing-box 配置。NaiveProxy 仅 Windows 可用：当前官方 Android CLI 未编入该功能，不会把它标作可连接节点。订阅更新只替换该来源的节点，保留手动导入及其他订阅。原生节点不会跨内核做有损转换，界面在纯原生订阅导入时自动选择对应内核。

连接数表示内核报告的活动逻辑代理流，一个流对应一条入站与一条出站，因此两项通常相同；它们不是操作系统的 socket 数或复用后的 TCP 连接数。Windows 分别显示界面与内核的 Working Set；Android 分别显示应用 PSS 和子进程内核 RSS。它们与其他客户端只显示 Go 堆或内核内存的读数不能直接比较。

## 轻量与省电设计

Windows 使用 WPF，Android 使用原生 View，无 Electron/WebView。Windows 托盘模式每 10 秒读取统计，隐藏时停止图表和控件刷新；连接详情按页面构建，离开连接页释放行数据。日志队列最多 500 条，批量投递；使用流式响应和 JsonDocument 降低解析分配。

Android 不持有唤醒锁：前台每秒采样，后台调度间隔为 10 秒，通知约每 30 秒刷新。统计使用流式 JSON 读取，跳过每条连接详情；用户打开连接页面时才逐条读取记录，离开后释放。节点解析缓存、列表行复用和最多 350 条日志限制持续分配。内存前台每 5 次采样、后台每 3 次采样读取；HEV 隧道限制会话与缓冲。两端内核设置 GOMEMLIMIT=96MiB、GOGC=75，属于 GC 提示，不是 RSS 硬上限。

2026-10-31 本机托盘空闲采样：未连接界面 101.8 MiB；mihomo 连接时界面 116.7 / 内核 25.8 MiB；sing-box 连接时界面 112.7 / 内核 34.9 MiB。测试使用隔离的 DIRECT 配置、系统代理关闭，启动后等待 6 秒再采样 5 秒，界面 CPU 增量均为 0 ms（计时分辨率范围内）。这是单次空闲测量，不能代表真实节点、高并发或长时间峰值。复现脚本：`tools/measure-windows.ps1`。

尚未做 Android 真机耗电与低内存设备测试，不能据此给出待机耗电、峰值 RSS 或 CPU 占用的实测承诺。Android JNI 隧道与 mihomo 的 ELF 支持 16 KiB 页对齐；官方 sing-box Android CLI 使用 4 KiB LOAD 对齐，此版本尚未验证在 16 KiB 页设备上的运行，需要此类设备时应重编译该内核。

## 验证范围

Windows：53 项解析、配置和双内核集成检查；涵盖新增协议、SS2022 多段密钥、TLS/SNI、默认端口、原生配置、失败更新保留旧内容、IPv4/IPv6 转发、延迟与流量统计。两工作区页面均进行了 WPF 渲染检查。

Android：26 项测试通过（18 项配置/导入/转发，8 项界面/流式统计），另 1 项需要外部真实订阅文件的可选测试未执行。包含 400 节点/连接列表复用、连接搜索、国旗、分组折叠和 2000 连接流式计数。生成的配置用固定官方 Windows 内核做检查与回环 HTTP/SOCKS5 UDP 转发。Lint 无错误。APK 签名与 ARM64 架构单独校验。以上不等同于 Android 真机 VPN、耗电和 16 KiB 页设备验证。

## 构建

工具：.NET SDK 8+、JDK 21、Android SDK platform 34/build-tools 34、Android NDK 28、Gradle 8.10（wrapper 固定）、Inno Setup 6.7.3。

```powershell
# 下载固定官方内核并验证 SHA256
./tools/native/fetch-cores.ps1
# 编译 Android ARM64 JNI 隧道
./tools/native/build-tunnel.ps1 -NdkPath '<Android SDK>/ndk/28.0.13004108'
# 构建客户端与安装包
./tools/build-windows.ps1 -InnoCompiler '<Inno Setup>/ISCC.exe'
./tools/build-android.ps1 -JavaHome '<JDK 21>' -SdkPath '<Android SDK>'
```

首次 Android 构建由脚本在 `signing/` 创建专用签名密钥与随机密码；本次发布的原始密钥和密码保存在当前工作区 `signing/`，不包含于源码 ZIP。请保留原始密钥以便发布可直接升级的后续 APK；在另一台机器生成新密钥的包无法覆盖此签名的已有安装。

Windows 详细代码说明与测试命令见 `windows/README.md`，JNI 所有权/取消协议见 `native/README.md`。源代码包中保留上游固定版本的完整源码压缩包和许可证。Wbox 遵循 GPL-3.0；第三方声明见 `THIRD-PARTY-NOTICES.md`。
