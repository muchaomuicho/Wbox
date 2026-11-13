本项目完全由GPT-6 Astra生成

<div align="center">

# Wbox

**让连接更从容，让配置更清晰。**

Windows · Android ｜ mihomo · sing-box

[![Release](https://img.shields.io/github/v/release/muchaomuicho/Wbox?style=flat-square&color=333333)](https://github.com/muchaomuicho/Wbox/releases/latest)
[![License](https://img.shields.io/badge/license-GPL--3.0-555555?style=flat-square)](LICENSE)
[![Android](https://img.shields.io/badge/Android-8.0%2B-555555?style=flat-square)](https://github.com/muchaomuicho/Wbox/releases/latest)
[![Windows](https://img.shields.io/badge/Windows-10%20%2F%2011-555555?style=flat-square)](https://github.com/muchaomuicho/Wbox/releases/latest)

[下载最新版本](https://github.com/muchaomuicho/Wbox/releases/latest) · [本次更新](docs/RELEASE-0.7.1.md) · [全部历史版本](https://github.com/muchaomuicho/Wbox/releases)

</div>

Wbox 是一款面向 Windows 与 Android 的双内核代理客户端。以黑白为基调，把完整配置与独立节点整理为两个工作区：需要细致分流时保留原配置，需要轻装连接时只运行所选节点。内核、规则与必要资源随包提供，日常使用不必反复准备环境。

## 界面一览

<div align="center">
<img src="docs/previews/0.7.1-windows-profiles.png" alt="Windows 配置卡片与操作菜单" width="96%" />
<br/><sub>Windows：配置集中管理，保存后立即应用。</sub>
<br/><br/>
<img src="docs/previews/0.7.1-android-profiles-light.png" alt="Android 浅色配置卡片" width="32%" />
<img src="docs/previews/0.7.1-android-profiles-menu.png" alt="Android 配置操作菜单" width="32%" />
<img src="docs/previews/0.7.1-android-profiles-dark.png" alt="Android 深色配置卡片" width="32%" />
<br/><sub>Android：大卡片、清晰的流量信息与随手可达的操作。</sub>
</div>

## 0.7.1：每一步都有回应

测速时可以启动内核、选择节点和停止连接。编辑、同步或切换当前配置后，运行连接立即应用更改；新配置无法启动时恢复原数据和连接。测速任务与配置事务独立管理，避免不必要的“处理中”拦截。

Android 配置与节点订阅使用大圆角卡片，显示已用／总流量、剩余量、有效期和更新时间。逐项菜单提供编辑、预览、同步、更多及删除；右上角支持全部更新与排序。浅色和深色主题覆盖主界面与各编辑页，系统状态栏衔接一致。Windows 同步提供卡片菜单、预览、全部更新和排序。

## 两个工作区，各自完整

- **配置工作区**：导入远程 URL 或本地 JSON / YAML，保留完整代理组、DNS 与路由；支持多份配置、代码编辑、同步、导出、在线切换及代理组管理。
- **节点工作区**：独立管理订阅与节点链接，按来源筛选、搜索、编辑与克隆；只加载当前节点和显式依赖，继续使用内置 DNS、FakeIP、广告拦截及应用分流模板。

两工作区分别保存选择。Windows 单击选中、双击或 Enter 连接，右键或 Ctrl+D 编辑，Ctrl+V 导入；Android 使用单击、双击和长按，也支持外接键盘快捷键。点击闪电仅测试延迟，保留当前选择；离线测速使用临时内核，不启用系统代理、TUN 或 VPN。

## 下载与安装

在 [GitHub Releases](https://github.com/muchaomuicho/Wbox/releases/latest) 下载：

- `Wbox-0.7.1-windows-x64-setup.exe`：Windows 10 / 11 x64 安装程序，自带 Electron、.NET 运行库与双内核。
- `Wbox-0.7.1-android-arm64-v8a.apk`：Android 8.0+ ARM64 安装包，延续原签名，可覆盖同签名旧版。
- `Wbox-0.7.1-source.zip`：完整源码、上游来源、原生隧道、固定版本内核源码与构建脚本。
- `SHA256SUMS-0.7.1.txt`：发布文件 SHA256 校验值。

Windows 关闭窗口后保留托盘，左键恢复主界面，右键打开菜单；支持开机自启、静默启动与自动连接。托盘可切换运行策略组、配置、测速及关闭连接。Android 首次连接授权系统 VPN，支持全部应用、只代理所选应用或绕过所选应用，IPv6 默认开启。

## 功能与边界

固定集成 **mihomo 1.19.33** 与 **sing-box 1.14.4-wbox.1**，只启动当前所选内核。默认以 `clash.meta/1.19.33` UA 下载订阅，并根据内容识别内核。

支持 HTTP(S) 订阅、Base64 节点列表与原生 outbounds/proxies 提取；分享链接包括 SS／SS2022、VMess、VLESS／Reality、Trojan、Hysteria／Hysteria2、TUIC、AnyTLS、SOCKS、HTTP(S)、WireGuard，Windows 另支持 NaiveProxy。原生节点不做跨内核有损转换。暂不支持的 XHTTP、ECH 等分享参数会明确报错，可使用内核支持的完整配置。

订阅读取标准 `Subscription-Userinfo`，缺失字段标为“未提供”，下载失败保留上次数据。每个订阅可设置自动更新，间隔 15–10080 分钟；Android 后台任务由系统安排，断网或省电状态可能延后。

延迟测试默认使用 `https://www.apple.com/library/test/success.html`。两内核先建立连接，再测实际请求延迟；sing-box 使用 HTTPS，mihomo 支持 HTTP 与 HTTPS。代理组按实际节点去重，最多 4 并发；独立节点测速 Windows 最多 2 个临时内核，Android 1 个，单次内核请求超时 4 秒。

连接页提供活动逻辑流、来源应用、流量及连接详情；日志有级别颜色和自动跟随，概览显示 60 秒波形与应用／内核内存。连接数是内核报告的逻辑代理流，内存读数包含所标明的进程范围，不与只报告 Go 堆的数值直接比较。

Windows 默认接管遵循系统代理设置的应用。需要原生入站或 TUN 时启用“保留配置中的原生入站”，配置本身须包含 TUN，并以管理员身份运行。停止时恢复持久备份的系统代理；其它软件已改动时不覆盖其设置。Android VPN 排除 Wbox 自身，运行副本将配置内 TUN 映射为受管 mixed 入站。完整配置引用的外部本地资源需要另行提供。

## 轻量设计

Windows 安装目录使用 LZX 透明压缩，主窗口隐藏后释放界面，托盘预加载以缩短首次打开等待。Android 使用 Kotlin、Compose 与 Material 3，长列表按可见区域创建，发布版启用 R8 和资源裁剪；数据库无损压缩，固定规则共享磁盘缓存。后台停止界面采样，不持有唤醒锁。内置 [26 份规则集与分流模板](docs/BUNDLED-RULES.md) 随版本固定，首次启动无需下载这些资源。

## 验证与历史归档

本轮覆盖双内核核心逻辑、实际转发、测速期间连接与选择、在线配置应用及失败恢复；Android 界面与运行验证使用隔离 API35 模拟器。发布 APK 另核对 ARM64 ABI、原签名、内核和离线资源。记录位于 [验证目录](docs/verification)，本轮以 `0.7.1-` 开头。

历史安装包与现存源码归档在 [各版本 Release](https://github.com/muchaomuicho/Wbox/releases)，文件核验见 [历史归档清单](docs/verification/history-archive.json)。**0.1.0 当时的源码包未留存**，该版本仅保存现存安装包和明确的缺失说明；其余历史源码以各版本随附的原始 source ZIP 为准。

尚未完成 Android 真机耗电、低内存与 16 KiB 页设备运行验证。JNI 和内核 ELF LOAD 已为 16 KiB 对齐；模拟器结果不代替这些实机验证。

## 从源码构建

需要 Node/pnpm、.NET SDK 8+、**Go 1.25.5+**、MinGW-w64 gcc、**JDK 21**、Android SDK 34/build-tools 34、NDK 28、Gradle wrapper 8.10 与 Inno Setup 6.7.3。前端依赖与上游来源见 [Windows](docs/UPSTREAM-DESKTOP.md) 和 [Android](docs/UPSTREAM-ANDROID.md) 构建说明。

```powershell
./tools/native/fetch-cores.ps1
./tools/native/build-tunnel.ps1 -NdkPath '<Android SDK>/ndk/28.0.13004108'
./tools/native/build-singbox.ps1 -Go '<Go>/bin/go.exe' -NdkPath '<Android SDK>/ndk/28.0.13004108'
./tools/build-desktop.ps1 -InnoCompiler '<Inno Setup>/ISCC.exe'
./tools/build-android.ps1 -JavaHome '<JDK 21>' -SdkPath '<Android SDK>' -Test
./tools/package-source.ps1
```

Android 签名密钥与密码位于本地 `signing/`，不包含在 GitHub 或源码包中。**请单独保留这个目录**：后续 APK 使用同一密钥才能覆盖已有安装。历史安装包归档不能替代签名密钥备份。

项目遵循 GPL-3.0；[第三方声明](THIRD-PARTY-NOTICES.md)、[Windows 实现](windows/README.md) 与 [原生隧道说明](native/README.md) 保留来源、许可证和构建细节。
