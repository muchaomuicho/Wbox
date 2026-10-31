# Wbox

Wbox 是原生 Windows / Android 双内核代理客户端。界面以黑白为主，连接按钮使用绿色，图标为黑底白色 W。运行时只启动所选内核；固定集成 mihomo 1.19.33 与 sing-box 1.14.4。

0.2.0 重新设计了两端界面，具体改动和预览见 [发布说明](docs/RELEASE-0.2.0.md)。

## 安装与使用

交付文件在 `dist/`：

- `Wbox-0.2.0-windows-x64-setup.exe`：Windows 10 / 11 x64 安装程序，自带 .NET 运行库，安装到当前用户目录。
- `Wbox-0.2.0-android-arm64-v8a.apk`：Android 8.0+ ARM64 签名安装包。
- `Wbox-0.2.0-source.zip`：Wbox 完整源码、原生隧道源码、固定版本内核源码与构建脚本。
- `SHA256SUMS.txt`：以上文件的校验值。

第一次使用请导入订阅 URL、节点分享链接，或内核原生配置。单节点模式生成可选择节点的 Wbox 代理组；完整配置模式保留原文件和主要路由设置。JSON 对应 sing-box，YAML 对应 mihomo。Windows 支持开机自启、静默启动、自动连接和托盘菜单；这些开关在“设置”中保存。关闭 Windows 主窗口会收起到托盘，退出请使用托盘菜单或设置页“退出 Wbox”。

Windows 默认使用系统代理，只接管遵循 Windows 系统代理设置的应用。需要全流量接管时，导入内核原生 TUN 配置并以管理员身份运行。停止连接会根据持久备份恢复系统代理；若其它软件已经修改这些设置，Wbox 不覆盖它们。

Android 第一次连接需要授权系统 VPN。设置页可选择全部应用、只代理所选应用或绕过所选应用；Wbox 自身始终绕过 VPN，避免内核上游连接回环。IPv6 默认开启。Android 的系统 VPN 由 Wbox 管理，导入配置中的 TUN 入站会在运行副本中移除，并增加本地 mixed 入站。原配置规则若按入站标签匹配，需要使用 `wbox-in` 标签。JSON/YAML 内的本地外部资源应放到应用可访问的位置；只导入文本文件不会自动导入旁边的规则资源。

## 已实现的功能

双内核切换；JSON/YAML 配置；配置文件/单节点两种模式；HTTP(S) 订阅和 Base64 节点列表；SS、VMess、VLESS（含 Reality）、Trojan、Hysteria2、TUIC 分享链接；节点选择和代理组查看；自定义 URL 的内核 HTTP 延迟测试；有界日志；活动连接明细；入站/出站逻辑连接数；本次上传/下载字节；60 秒实时波形图；内存显示；Windows 自启/静默/托盘；Android 分应用代理。

默认延迟测试 URL 为 `https://www.apple.com/library/test/success.html`。sing-box 1.14.4 上游 API 会替换 HTTP 测试地址，因此 Wbox 要求该内核自定义测速使用 HTTPS；mihomo 支持 HTTP 与 HTTPS。Android 延迟测试在连接后使用内核控制接口。Android 节点列表长按可删除离线节点。

复杂分享参数不会悄悄丢弃：SS 插件、Hysteria2 混淆、自定义 TUIC 参数等请使用完整内核配置。Android 分享导入暂不转换带 `alpn` 或其它未支持传输参数的链接；完整原生 JSON/YAML 配置可以保留这些设置。更新订阅会替换该订阅的旧节点，并保留手动导入及其它订阅的节点。

连接数表示内核报告的活动逻辑代理流，一个流对应一条入站与一条出站，因此两项通常相同；它们不是操作系统的 socket 数或复用后的 TCP 连接数。Windows 内存显示应用与核心的 Working Set 总和；Android 显示应用 PSS 加子进程内核 RSS，作为近似总占用。

## 轻量与省电设计

Windows 使用 WPF，Android 使用原生 View，均未集成 Electron/WebView 或后台网页。Android 不持有唤醒锁：前台每秒更新统计，后台每 10 秒读取一次，通知每 30 秒刷新；前台代理组每 5 秒刷新；内存前台每 5 秒、后台每 30 秒读取。日志最多保存 Windows 1000 行、Android 350 行。HEV 隧道使用协程，限制会话数量与缓冲区；Android 内核设定 Go GC 内存目标 96 MiB。该目标是 GC 提示，不是进程 RSS 的硬上限。

尚未做 Android 真机耗电与低内存设备测试，不能据此给出待机耗电、峰值 RSS 或 CPU 占用的实测承诺。Android JNI 隧道与 mihomo 的 ELF 支持 16 KiB 页对齐；官方 sing-box Android CLI 使用 4 KiB LOAD 对齐，此版本尚未验证在 16 KiB 页设备上的运行，需要此类设备时应重编译该内核。

## 验证范围

Windows：33 项核心/集成检查，包括两内核真实运行、六协议配置生成、节点切换、控制接口鉴权、真实 HTTP 字节转发、IPv6 目标、连接与流量计数；六个 WPF 页面构造/渲染检查通过，并检查了真实窗口导航与下拉菜单。正式安装包不包含测试程序。

Android：11 项导入/配置检查、3 项界面与历史测试通过，包含六协议与 Reality 原生配置校验；用 Android 实际生成的两种配置启动官方 Windows 内核，验证 IPv4/IPv6 HTTP 与 SOCKS5 UDP 代理转发、selector 切换及上传/下载计数；Android Lint 通过；APK 签名和 arm64 ELF 架构已检查。原生 JNI 生命周期检查覆盖提前取消、运行时停止、并发启动拒绝和 1000 次取消竞态。以上不等同于 Android 真机 VPN 转发测试；当前机器未连接 Android 设备，也没有可用 ARM64 模拟器。

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
