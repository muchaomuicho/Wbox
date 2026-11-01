# Android 上游与平台适配

参考项目：<https://github.com/SagerNet/sing-box-for-android>，固定提交 `a548a7903e96266a0046018562c73b192edbfc18`。原始许可证保存在 `resources/cores/licenses/LICENSE-sing-box-for-android`。

- `compose/LineChart.kt` 移植为 `android/app/src/main/java/com/wbox/app/LineChart.kt`，仅更改包名和来源说明。
- `compose/screen/dashboard/UploadTrafficCard.kt` 的组件结构用于 `WboxUi.kt` 中双向共享的 `TrafficCard`。
- `compose/screen/dashboard/DashboardScreen.kt`、`ProfilePickerSheet.kt`、`ProfilesCard.kt` 与 `compose/component/ServiceStatusBar.kt` 作为仪表盘、配置选择、底部状态栏和右下角启动按钮的结构参考。Wbox 将数据源改为双内核模型，配色与桌面一致。
- 0.6.4 参考 `compose/screen/dashboard/GroupsCard.kt` 中的 `GroupDotsGrid`，使用惰性行与分块画布承载大量节点；参考 `compose/screen/connections/ConnectionItem.kt` 显示应用图标。Wbox 的来源连接查询通过 HEV 原始连接映射和公开的 `ConnectivityManager.getConnectionOwnerUid` 实现。
- 导入、节点编辑、订阅、分应用选择和设置使用 Material 3 的输入框、底部面板、菜单与列表；没有保留旧 Java View 页面。

网络实现参考上游 `bg/VPNService.kt` 和 sing-box v1.14.4 的 `route/network.go`。官方 Android 客户端通过 libbox 平台接口实现网卡监测与 socket protect。Wbox 本版仍采用命令行内核 + HEV，使用 Android VpnService 排除自身 UID，由 Android 决定上游默认网络，因此不需要命令行内核的 netlink 网卡探测与 SO_BINDTODEVICE。`AndroidConfig.java` 只调整运行副本的这些系统参数并映射 TUN 入站匹配，原始配置不变。

两种实现的内存口径不同：Wbox 显示应用 PSS 和内核子进程 RSS，不能直接与官方客户端的 Go 堆数值相减。后台不持有唤醒锁，采样降低为 30 秒；发布版启用 R8。耗电需要设备测量。

`src/debug` 中的 RuntimeProbeReceiver 仅用于测试模拟器，发布版不包含该入口。`tools/android-runtime-test.py --minified` 对本机 TCP/UDP 服务进行合成测试，不使用用户节点凭据。
