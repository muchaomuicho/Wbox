# 0.6.6 修复依据与限制

2026-11-04 核对本地固定版本源码，并检索上游资料。

## 延迟测试

- [sing-box Clash API](https://github.com/SagerNet/sing-box/blob/v1.14.4/experimental/clashapi/proxies.go) 的 `getProxyDelay` 清空以 `http://` 开头的参数；[URLTest](https://github.com/SagerNet/sing-box/blob/v1.14.4/common/urltest/urltest.go) 随后使用默认 HTTPS 地址。Wbox 因此要求 sing-box 自定义链接使用 HTTPS。没有修改内核二进制，也没有把 HTTP 偷换成另一个地址。
- [mihomo URLTest](https://github.com/MetaCubeX/mihomo/blob/v1.19.33/adapter/adapter.go) 的 `unified-delay` 会在连接上再次发送 HEAD，成功时改用第二次请求的开始时间。sing-box 通常包含建连、握手及 HEAD；部分支持复用的出站另有预热。这能解释数字差异，但不能证明任何特定节点实际更快。
- Wbox 的独立节点测速原来加载完整路由模板并启动多个临时内核。精简测速配置省去规则集、FakeIP、嗅探和进程探测，Windows 限制两个、Android 一个临时内核。这减少启动成本和峰值占用，不保证改变网络本身的延迟。

## 内存与电量

- 参考 [sing-box Android 客户端](https://github.com/SagerNet/sing-box-for-android) 的服务连接和命令客户端生命周期，将界面采样绑定可见状态。Wbox 后台停止轮询，用进程退出等待和系统网络回调维持服务管理。
- [Go GC 指南](https://go.dev/doc/gc-guide#Memory_limit) 说明过紧的内存限制可能增加 GC 开销。Android 保留 `GOMEMLIMIT=96MiB`，`GOGC` 从 75 调回 100；这不是 RSS 上限，也不是已测得的电量节省比例。
- 上游 [#4537](https://github.com/SagerNet/sing-box/issues/4537) 有 Android 1.14.x 内存增大的用户报告。这只是排查线索，不能据此认定 Wbox 内核有同一个缺陷或直接降级内核。
- Windows 复用近期托盘窗口，缓存期间约多保留一个页面的内存，60 秒闲置后销毁。关闭主窗口仍在 1.5 秒后销毁。复测等待页面进程退出后，无残留窗口。
- 包体主要由 Electron、.NET、双内核与离线规则构成。裁剪 .NET 时显式保留 Wbox 后台与 YamlDotNet，打包版导入、保存、节点编辑、内核启动回归通过。Android ZIP 重新压缩逐项验证内容哈希，随后重新对齐并用既有证书签名。不会降低运行时内存，也未删减协议。

## Windows 启动与安装

- [微软任务安全上下文说明](https://learn.microsoft.com/en-us/windows/win32/taskschd/security-contexts-for-running-tasks)：管理员自启需要合适的任务运行级别。Wbox 管理员运行时登记当前用户 `InteractiveToken` / `HighestAvailable` 登录任务；普通运行仍支持用户 Run 项。
- 本机生成的任务 XML 已由系统计划任务接受并执行独立测试程序。当前测试进程没有管理员权限，未验证提升后的登录任务，也没有重启用户电脑。
- [Inno Setup Run 说明](https://jrsoftware.org/ishelp/topic_runsection.htm)：`shellexec` 使用系统 ShellExecute；完成页保留原用户上下文，由系统处理可执行文件的管理员兼容性要求。代码已修复并完成编译；安装后 UAC 场景仍需交互实测。
- 简体中文文件来自 [Inno Setup](https://github.com/jrsoftware/issrc/blob/main/Files/Languages/ChineseSimplified.isl)。安装器实际窗口标题为“安装 - Wbox 版本 0.6.6”；本次窗口工具截图失败，因此未宣称完整视觉验证通过。

实际测量见 `verification/0.6.6-performance.json`、`0.6.6-subscriptions.json` 和 `0.6.6-android-templates.json`。Android 使用 Android 15 x86_64 模拟器，签名发行包为 ARM64；实体手机整夜耗电、16 KiB 页设备和长期内存变化仍未测量。
