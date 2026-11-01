# Wbox

Wbox 是 Windows / Android 双内核代理客户端。Windows 0.5.1 直接基于官方 sing-box desktop / dashboard 前端源码改造；Android 0.5.1 保留原生 View，实现同样的灰白／深色主题和两个独立工作区。运行时只启动所选内核，固定集成 mihomo 1.19.33 与 sing-box 1.14.4，品牌图标为黑底白色 W。

Windows 0.5.1 复用官方导航、概览、代理组、连接详情和日志，增加两个独立工作区与双内核适配。通过 WinINet 修复停止后遗留系统代理的问题，支持远程 JSON / YAML、订阅与单节点。见 [发布说明](docs/RELEASE-0.5.1.md) 和 [上游来源及构建说明](docs/UPSTREAM-DESKTOP.md)。

## 安装与使用

交付文件在 `dist/`：

- `Wbox-0.5.1-windows-x64-setup.exe`：Windows 10 / 11 x64 安装程序，自带 Electron 与 .NET 运行库，安装到当前用户目录。
- `Wbox-0.5.1-android-arm64-v8a.apk`：Android 8.0+ ARM64 签名安装包。
- `Wbox-0.5.1-source.zip`：Wbox 完整源码、上游前端、原生隧道、固定版本内核源码与构建脚本。
- `SHA256SUMS.txt`：以上文件的校验值。

先在顶部选择工作区。配置工作区直接添加远程 URL 或导入本地 JSON / YAML，保存完整代理组、DNS 和路由，支持多份配置、切换、更新和移除。节点工作区单独管理订阅地址与节点链接，按来源分组、搜索、选择节点；两端均可在未连接时临时启动内核测速，也可在连接后测试。两工作区分别保留配置选择和节点选择；切换前需要断开连接。


Windows 0.5.1 在配置／节点卡片内使用 `+` 添加。节点工作区可直接 `Ctrl+V` 导入，自动识别订阅地址与节点链接；单击选中，双击或 Enter 启用，右键或 `Ctrl+D` 编辑节点。右键还可克隆为手动节点、移除。连接期间修改节点会重启节点内核，失败时恢复原数据和连接。订阅更新会覆盖该来源的编辑，长期保留请先克隆。

Android 0.5.1 同步卡片内添加、统一订阅地址／节点链接入口、剪贴板导入、节点编辑及测试地址恢复默认；修改节点时仍保持原生界面与列表复用。两端保持同一功能方向，触屏使用长按菜单对应鼠标右键。

Windows 支持开机自启、静默启动、自动连接和托盘菜单；开关在“设置”中保存。关闭主窗口收起到托盘，退出请使用托盘菜单。托盘直接复用官方级联菜单，支持连接开关、代理组与节点选择、配置切换、测速及关闭所有连接。

Windows 默认使用系统代理，只接管遵循 Windows 系统代理设置的应用。默认在运行副本中用 Wbox 受管 mixed 入站替换原配置入站，保留首个 mixed 标签。需要原生入站或全流量接管时，在设置中启用“保留配置中的原生入站”，再以管理员身份运行 Wbox；配置本身需要包含 TUN 入站。受管端口冲突时自动改用空闲端口，原生入站冲突会说明具体端口。停止连接会根据持久备份恢复系统代理；若其它软件已经修改这些设置，Wbox 不覆盖它们。

Android 第一次连接需要授权系统 VPN。设置页可选择全部应用、只代理所选应用或绕过所选应用；Wbox 自身始终绕过 VPN，避免内核上游连接回环。IPv6 默认开启。Android 的系统 VPN 由 Wbox 管理，导入配置中的 TUN 入站会在运行副本中移除，并增加本地 mixed 入站。原配置规则若按入站标签匹配，需要使用 `wbox-in` 标签。JSON/YAML 内的本地外部资源应放到应用可访问的位置；只导入文本文件不会自动导入旁边的规则资源。

## 已实现的功能

双内核切换；远程与本地 JSON/YAML 完整配置库；独立的节点订阅库；HTTP(S) 订阅、Base64 节点列表、原生 outbounds/proxies 提取；SS（含 SS2022）、VMess、VLESS/Reality、Trojan、Hysteria、Hysteria2、TUIC、AnyTLS、SOCKS、HTTP(S)、WireGuard 分享链接；Windows 另支持 NaiveProxy。原生订阅中的 SSH 等内核节点也能原样导入，但需要对应内核。

已支持 ALPN、SS obfs-local/simple-obfs/v2ray-plugin、Hysteria2 salamander 混淆、TUIC congestion_control/udp_relay_mode，以及 WS/gRPC/HTTP2 传输。代理组与节点切换、内核 HTTP 延迟测试、有界日志、活动连接明细、入站/出站逻辑连接数、本次流量、60 秒波形、内存显示和 Android 分应用 VPN 均保留。

默认延迟测试 URL 为 `https://www.apple.com/library/test/success.html`。sing-box 1.14.4 上游 API 会替换 HTTP 测试地址，因此 Wbox 要求该内核自定义测速使用 HTTPS；mihomo 支持 HTTP 与 HTTPS。Android 配置工作区的代理组需要连接后测速；节点工作区支持离线测速。Android 节点列表支持单击选中、双击启用、长按编辑／克隆／移除，外接键盘可用 Ctrl+V、Ctrl+D 和 Enter。

未支持的分享格式或传输（例如 XHTTP、ECH 分享参数）会明确报错并保留原节点列表，可改用内核支持的完整配置。TLS SOCKS 需要 mihomo；WireGuard 分享链接的 dns 参数需使用 mihomo 或完整 sing-box 配置。NaiveProxy 仅 Windows 可用：当前官方 Android CLI 未编入该功能，不会把它标作可连接节点。订阅更新只替换该来源的节点，保留手动导入及其他订阅。原生节点不会跨内核做有损转换，Android 在纯原生订阅导入时自动选择对应内核；Windows 可在节点卡片中选择内核。

连接数表示内核报告的活动逻辑代理流，一个流对应一条入站与一条出站，因此两项通常相同；它们不是操作系统的 socket 数或复用后的 TCP 连接数。Windows 分别显示界面与内核的 Working Set；Android 分别显示应用 PSS 和子进程内核 RSS。它们与其他客户端只显示 Go 堆或内核内存的读数不能直接比较。

## 轻量与省电设计

Windows 0.5.1 使用官方 Electron 前端。主窗口隐藏后暂停页面采样，30 秒后销毁窗口；托盘菜单按需创建，关闭 5 秒后释放。后台和所选内核继续运行。内核几十 MiB 的读数不代表整个软件，界面常驻内存未证明低于旧 WPF。页面显示的“界面与后台内存”为工作集之和，会重复计入共享页面，实测见 `docs/verification/0.5.1-desktop.json`。

Android 使用原生 View，不持有唤醒锁。前台每秒采样，后台每 30 秒采样并更新通知，回到前台立即刷新。后台不采集 PSS；前台每 5 次采样读取内存。统计使用流式 JSON，只在连接页读取详情，离开后释放。节点解析缓存、列表行复用、最多 350 条日志、有限的波形历史和 HEV 缓冲限制减少持续分配。双内核的 GOMEMLIMIT=96MiB、GOGC=75 是 GC 提示，不是 RSS 硬上限。

尚未做 Android 真机耗电与低内存设备测试。Android JNI 隧道与 mihomo 的 ELF 支持 16 KiB 页对齐；官方 sing-box Android CLI 使用 4 KiB LOAD 对齐，此版本尚未验证在 16 KiB 页设备上的运行。

## 验证范围

Windows：69 项配置与双内核集成检查通过；0.5.0 的 11 项真实 WinINet 代理恢复回归已通过，本轮保留该实现。桌面自动化覆盖远程导入、国旗、节点与配置切换、真实连接、顶部停止、官方托盘菜单及窗口释放。结果见 `docs/verification/0.5.1-desktop.json`。

Android：39 项测试通过，1 项需要外部真实订阅的可选测试跳过。覆盖 360/412 宽度页面、深浅主题、配置编辑、400 节点/连接列表复用、搜索、国旗、分组折叠、2000 连接流式计数与连接关闭。生成的配置使用固定版本的官方 Windows 内核验证 HTTP/SOCKS5 UDP 和 IPv4/IPv6 转发；这不等同于 Android 真机 VPN 验证。Lint 无错误，APK 签名和 ABI 单独校验。

## 构建

工具：Node/pnpm（见上游构建说明）、.NET SDK 8+、JDK 21、Android SDK platform 34/build-tools 34、Android NDK 28、Gradle 8.10（wrapper 固定）、Inno Setup 6.7.3。

```powershell
# 下载固定官方内核并验证 SHA256
./tools/native/fetch-cores.ps1
# 编译 Android ARM64 JNI 隧道
./tools/native/build-tunnel.ps1 -NdkPath '<Android SDK>/ndk/28.0.13004108'
# 构建客户端与安装包
./tools/build-desktop.ps1 -InnoCompiler '<Inno Setup>/ISCC.exe'
./tools/build-android.ps1 -JavaHome '<JDK 21>' -SdkPath '<Android SDK>'
```

首次 Android 构建由脚本在 `signing/` 创建专用签名密钥与随机密码；本次发布的原始密钥和密码保存在当前工作区 `signing/`，不包含于源码 ZIP。请保留原始密钥以便发布可直接升级的后续 APK；在另一台机器生成新密钥的包无法覆盖此签名的已有安装。

Windows 详细代码说明与测试命令见 `windows/README.md`，JNI 所有权/取消协议见 `native/README.md`。源代码包中保留上游固定版本的完整源码压缩包和许可证。Wbox 遵循 GPL-3.0；第三方声明见 `THIRD-PARTY-NOTICES.md`。
