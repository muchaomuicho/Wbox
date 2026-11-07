# Wbox

Wbox 是 Windows / Android 双内核代理客户端。当前版本为 0.6.7。Windows 基于官方 sing-box desktop / dashboard 前端源码改造；Android 使用 Compose / Material 3，实现同样的灰白／深色主题和两个独立工作区。运行时只启动所选内核，固定集成 mihomo 1.19.33 与 sing-box 1.14.4，品牌图标为黑底白色 W。

0.6.7 两端常设“代理”页面，按配置顺序排列组标签，右下角悬浮测速，支持未连接时临时内核测速。Windows 加固静默自启与托盘恢复；保留离线双内核并缩减资源及安装后的占用。见 [发布说明](docs/RELEASE-0.6.7.md) 和 [上游来源及构建说明](docs/UPSTREAM-DESKTOP.md)。

## 安装与使用

交付文件在 `dist/`：

- `Wbox-0.6.7-windows-x64-setup.exe`：Windows 10 / 11 x64 安装程序，自带 Electron 与 .NET 运行库，安装到当前用户目录并进行系统透明压缩。
- `Wbox-0.6.7-android-arm64-v8a.apk`：Android 8.0+ ARM64 签名安装包。
- `Wbox-0.6.7-source.zip`：Wbox 完整源码、上游前端、原生隧道、固定版本内核源码与构建脚本。
- `SHA256SUMS.txt`：以上文件的校验值。

默认以 `clash.meta/1.19.33`（mihomo）UA 下载配置与节点订阅，根据返回内容识别内核。

0.6.6 内置指定模板的 26 份规则集；两端节点工作区使用脱敏模板生成 DNS、FakeIP、广告拦截和应用分流，只加入当前节点及其显式依赖。已知规则来源从安装包加载，启动无需下载，固定快照随软件更新。详见 [内置模板与规则说明](docs/BUNDLED-RULES.md)。

0.6.4 为两端已连接按钮加入绿色底色与实心停止图标，宽度随时长文字自然变化。打开连接／代理组立即获取数据，加载期间显示状态。大代理组按可见区域创建节点卡片，Android 折叠延迟方块使用分块画布。连接行按需显示来源应用图标；Android 10+ 使用系统公开 API，无法识别或共享 UID 时显示通用图标。

先在顶部选择工作区。配置工作区直接添加远程 URL 或导入本地 JSON / YAML，保存完整代理组、DNS 和路由，支持多份配置、切换、更新和移除。节点工作区单独管理订阅地址与节点链接，按来源分组、搜索、选择节点；两端均可在未连接时临时启动内核测速，也可在连接后测试。两工作区分别保留配置选择和节点选择；切换前需要断开连接。连接期间可以添加、编辑及更新配置，新增配置不会改变当前选择，保存更改在下次连接生效。


Windows 0.6.6 在配置／节点卡片内使用 `+` 添加。节点工作区可直接 `Ctrl+V` 导入，自动识别订阅地址与节点链接；单击选中，双击或 Enter 启用，右键或 `Ctrl+D` 编辑节点。右键还可克隆为手动节点、移除。连接期间修改节点会重启节点内核，失败时恢复原数据和连接。订阅更新会覆盖该来源的编辑，长期保留请先克隆。

Android 0.6.6 同步卡片内添加、统一订阅地址／节点链接入口、剪贴板导入、节点编辑及测试地址恢复默认；修改节点时仍保持原生界面与列表复用。两端保持同一功能方向，触屏使用长按菜单对应鼠标右键。

Windows 支持开机自启、静默启动、自动连接和托盘菜单；开关在“设置”中保存。关闭主窗口收起到托盘，退出请使用托盘菜单。托盘直接复用官方级联菜单，支持连接开关、代理组与节点选择、配置切换、测速及关闭所有连接。

Windows 默认使用系统代理，只接管遵循 Windows 系统代理设置的应用。默认在运行副本中用 Wbox 受管 mixed 入站替换原配置入站，保留首个 mixed 标签。需要原生入站或全流量接管时，在设置中启用“保留配置中的原生入站”，再以管理员身份运行 Wbox；配置本身需要包含 TUN 入站。受管端口冲突时自动改用空闲端口，原生入站冲突会说明具体端口。停止连接会根据持久备份恢复系统代理；若其它软件已经修改这些设置，Wbox 不覆盖它们。

Android 第一次连接需要授权系统 VPN。设置页可选择全部应用、只代理所选应用或绕过所选应用；Wbox 自身始终绕过 VPN，避免内核上游连接回环。IPv6 默认开启。Android 的系统 VPN 由 Wbox 管理，导入配置中的 TUN 入站会在运行副本中移除，并增加本地 mixed 入站。原配置中的 TUN 入站匹配会映射到 `wbox-in`。JSON/YAML 内的本地外部资源应放到应用可访问的位置；只导入文本文件不会自动导入旁边的规则资源。

## 已实现的功能

双内核切换；远程与本地 JSON/YAML 完整配置库；独立的节点订阅库；HTTP(S) 订阅、Base64 节点列表、原生 outbounds/proxies 提取；SS（含 SS2022）、VMess、VLESS/Reality、Trojan、Hysteria、Hysteria2、TUIC、AnyTLS、SOCKS、HTTP(S)、WireGuard 分享链接；Windows 另支持 NaiveProxy。原生订阅中的 SSH 等内核节点也能原样导入，但需要对应内核。

已支持 ALPN、SS obfs-local/simple-obfs/v2ray-plugin、Hysteria2 salamander 混淆、TUIC congestion_control/udp_relay_mode，以及 WS/gRPC/HTTP2 传输。代理组与节点切换、内核 HTTP 延迟测试、有界日志、活动连接明细、入站/出站逻辑连接数、本次流量、60 秒波形、内存显示和 Android 分应用 VPN 均保留。

两端支持配置重命名，更新远程配置后保留名称。常设代理页按配置顺序切换组标签，节点使用网格卡片；日志按级别与连接编号着色。代理组测速解析嵌套选择并按实际节点去重，Windows 和 Android 最多 4 个并发；独立节点测速 Windows 最多 2 个临时内核、Android 1 个，单次内核请求超时 4 秒。

默认延迟测试 URL 为 `https://www.apple.com/library/test/success.html`。sing-box 1.14.4 上游 API 会替换 HTTP 测试地址，因此 Wbox 要求该内核自定义测速使用 HTTPS；mihomo 支持 HTTP 与 HTTPS。两端的配置代理组和独立节点均支持未连接时测速，临时内核不启用系统代理、TUN 或 VPN。Android 节点列表支持单击选中、双击启用、长按编辑／克隆／移除，外接键盘可用 Ctrl+V、Ctrl+D 和 Enter。

未支持的分享格式或传输（例如 XHTTP、ECH 分享参数）会明确报错并保留原节点列表，可改用内核支持的完整配置。TLS SOCKS 需要 mihomo；WireGuard 分享链接的 dns 参数需使用 mihomo 或完整 sing-box 配置。NaiveProxy 仅 Windows 可用：当前官方 Android CLI 未编入该功能，不会把它标作可连接节点。订阅更新只替换该来源的节点，保留手动导入及其他订阅。原生节点不会跨内核做有损转换，Android 在纯原生订阅导入时自动选择对应内核；Windows 可在节点卡片中选择内核。

连接数表示内核报告的活动逻辑代理流，一个流对应一条入站与一条出站，因此两项通常相同；它们不是操作系统的 socket 数或复用后的 TCP 连接数。Windows 分别显示界面及后台的私有内存、内核的 Working Set；Android 分别显示应用 PSS 和子进程内核 RSS。它们与其他客户端只显示 Go 堆或内核内存的读数不能直接比较。

## 轻量与省电设计

0.6.7 保留离线双内核。Windows 安装目录使用 LZX 透明压缩，文件内容与功能不变。Android 随附数据库使用 XZ 无损压缩及按需解压；内置广告规则转为 MRS，固定规则在配置间共享磁盘数据。体积测量见 `docs/verification/0.6.7-size.json`；Android 安装占用还受 ART 编译、配置与运行数据影响。

Windows 使用官方 Electron 前端。二维界面改用软件渲染；主窗口隐藏 1.5 秒后销毁页面；托盘菜单缓存 60 秒以便快速复用，之后销毁页面，后台和所选内核继续运行。同机空闲对比中，GPU 进程私有内存从约 123 MiB 降为约 29 MiB。应用私有内存统计排除了各进程共享页面的重复累计；它不包含单独显示的内核。测量原始记录见 `docs/verification/memory-hardware.json` 和 `memory-software.json`，这是独立测试实例，并非所有配置下的固定占用。

Android 已改为 Kotlin / Compose / Material 3，直接移植官方流量图并参考其仪表盘、配置选择和底部状态栏。启动按钮位于右下角，连接后的停止按钮在底部。节点、连接、日志使用 LazyColumn，长列表仅组合可见行。发布版启用 R8 与资源裁剪。应用不持有唤醒锁，界面只在前台采样，后台停止统计轮询；内核退出和网络变化由事件通知。统计使用流式 JSON，仅连接页保留连接详情，日志最多 350 条，波形保留 60 秒。GOMEMLIMIT=96MiB、GOGC=100 是 GC 提示，不是 RSS 硬上限。

Android 由 VpnService 管理路由并排除自身 UID；sing-box 运行副本关闭无权限使用的 netlink 网卡探测，并移除桌面网卡绑定，保留源文件和代理规则。原 TUN 入站标签会映射到 Android 隧道使用的 mixed 入站。启动阶段错误保留在日志及本机诊断文件中。平台实现与官方参考来源见 `docs/UPSTREAM-ANDROID.md`。

尚未做 Android 真机耗电与低内存设备测试。Android JNI 隧道与 mihomo 的 ELF 支持 16 KiB 页对齐；官方 sing-box Android CLI 使用 4 KiB LOAD 对齐，此版本尚未验证在 16 KiB 页设备上的运行。

## 验证范围

Windows 打包版交互回归记录见 `docs/verification/0.6.6-desktop.json`，包含托盘首次主题、启动按钮自然宽度、窗口释放、远程配置、节点编辑、代理切换与测速取消。本轮核心基础与转发回归见 `docs/verification/0.6.6-windows.json`；此前系统代理恢复回归保留在 0.5.2 验证记录中。

Android 配置、节点编辑、Compose 交互与数据处理的单元测试和 Lint 记录见 `docs/verification/0.6.6-android.json`。离线数据库启动回归见 `docs/verification/0.6.3-android-geodata.json`。本轮双内核 VPN TCP/UDP、网络恢复及重连回归见 `docs/verification/0.6.6-android-templates.json`；真实应用来源识别与 2,000 节点多次打开回归见 `docs/verification/0.6.4-android-groups.json`。模拟器使用启用 R8 的 x86_64 调试构建，发布 APK 仍为 ARM64；这不替代实体手机、耗电和 16 KiB 页设备测试。

两端首次使用默认进入配置工作区，手动切换后保留所选工作区。日志默认自动跟随，向上阅读时暂停跟随，可点击“滚动到底部”恢复。mihomo 默认地理数据库随包提供，首次启动无需下载这些文件；自定义数据库来源和远程规则仍由内核按配置处理。

## 构建

工具：Node/pnpm（见上游构建说明）、 .NET SDK 8+、MinGW-w64 gcc、JDK 21、Android SDK platform 34/build-tools 34、Android NDK 28、Gradle 8.10（wrapper 固定）、Inno Setup 6.7.3。

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
