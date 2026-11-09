# Wbox 0.6.9

本次同步更新 Windows 与 Android 的逐订阅设置，调整 Android 代理页面，并修复 Windows 连接详情和托盘交互。

- 每个远程配置、节点订阅均可单独编辑名称、URL、自动更新开关及间隔（15–10080 分钟）。旧版全局设置仅迁移一次到已有订阅；新订阅默认手动更新。更换 URL 保留当前内容，更新成功后替换；后台结果不会覆盖随后修改或移除的来源。
- Android 节点卡片和底部策略组标签更紧凑，未测速用闪电表示；底部连接状态栏移除重复的代理入口。Windows 未测速时留空，失败仍显示 Timeout。
- Windows 连接详情改为贴合窗口的侧栏，首次打开和重开均回到顶部，统计刷新保留用户滚动位置。顶部工作区按钮不再保留额外的拉伸空白。
- Windows 托盘菜单按图标上下可用空间定位，打开时清除上次展开的子菜单，避免指针直接落在“组”上并自动展开。
- 双端集成 `sing-box 1.14.4-wbox.1`：先完成一次 HEAD 请求建立代理和 TLS 会话，再用同一连接的第二次请求计时，类似 mihomo unified-delay。连接不能复用时保留首次完成的测量，取消和首请求失败仍返回错误；没有固定扣减延迟。测速 API 保留内核证书上下文和请求取消，TLS 验证继续生效。真实网络中的降低幅度取决于连接建立耗时，不表示线路带宽或真实网络 RTT 得到改善。

## 验证

Windows 回归覆盖逐订阅编辑与独立到期调度、未测速留空、真实 HTTPS 第二次请求计时、连接详情滚动以及托盘初开/重开。Android 在项目专用 API 35 模拟器上验证紧凑卡片、闪电、底部面板、离线选择、HTTPS 测速与取消、连接状态栏、两类订阅编辑及后台任务。测试使用独立数据和本地服务，不改用户现有配置。

本地 HTTPS 测试人为加入 300 ms 代理建连等待和 60 ms 服务端响应；两端报告第二次请求的实测值，具体读数见 `docs/verification/0.6.9-desktop.json` 与 `docs/verification/0.6.9-android-runtime.json`。

Windows 基础检查 112 项通过。Android JVM 测试共 80 项，76 项通过、4 项既有跳过，Lint 无错误。定制内核的 4 项回归覆盖连接复用、不可复用时回退、取消/失败及成功值不为零。Android 发布包仍为 ARM64，模拟器运行的是同源码 x86_64 测试构建；未进行实体手机耗电或 16 KiB 页设备实测。

## 构建与来源

原始 sing-box 源码、全部覆盖文件和构建脚本分别位于 `resources/source/sing-box-v1.14.4.tar.gz`、`native/singbox/`、`tools/native/build-singbox.ps1`。Windows 和 Android 使用相同测速修改。自编译 ARM64 内核的 ELF LOAD 对齐为 16 KiB；来源与哈希见 `resources/cores/manifest.json`，许可证见 `THIRD-PARTY-NOTICES.md`。

Android 尺寸与逐订阅字段参考 FlClash（commit `be84736605527034689c95bf27828ff27d912c5e`）的 `card.dart`、策略组标签和配置编辑界面，由 Wbox Compose 实现。Windows 保留 sing-box desktop/dashboard 的界面结构。Wbox 是独立衍生应用。

可运行 `tools/prepare-regression-cert.ps1` 生成仅供本地测试的临时证书，再运行 `node tools/desktop-tests/regressions-069.mjs` 和 `python tools/android-regressions-069.py`。证书不安装到系统信任库，测试完可删除 `.tools/regressions-069-{cert,key}.pem`。
