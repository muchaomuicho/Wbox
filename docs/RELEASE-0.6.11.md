# Wbox 0.6.11

本次基于 0.6.10 修复，没有整体回滚。已核对 Wbox 历史对话及 0.2.0–0.6.10 源码包中的 README。

- 节点工作区恢复独立节点列表，双击／Enter 连接单个节点；只装载当前节点和明确声明的前置代理。Android 首次导入明确保存当前节点，订阅重排后保留选择。切换失败仍恢复原连接。
- 配置工作区继续提供完整策略组和路由；节点库不再显示成虚构的 Selector 策略组。
- 移除 Android IPv6 开关，忽略旧版关闭状态，VPN 和节点模板 DNS 默认启用 IPv6。完整配置自身的 DNS／路由选项仍按原配置处理。
- Android 配置卡片操作入口直接打开独立编辑页，集中名称、订阅 URL、每订阅自动更新、配置代码编辑、导出和更新操作。
- 两端远程配置与节点订阅支持 `Subscription-Userinfo` 的 upload、download、total、expire，显示进度、剩余量和有效期。缺失和无效字段不伪造数值；更新失败保留旧数据；更改 URL 清除旧流量信息。
- Windows 节点表和配置代理卡片加入闪电／延迟点击区，转圈期间只测试这个节点；嵌套策略组只测试当前选中的叶节点。
- Windows 测速圆圈和延迟点击区保持透明背景，延迟文字使用配置工作区的 11px 字体与原有延迟分色。

保留 sing-box 1.14.4-wbox.1 的二次请求计时、mihomo 1.19.33、26 份离线规则、分流模板、托盘、自启、双内核离线安装、体积压缩和 Android 前后台采样策略。Android 使用原发布签名，可覆盖升级。

验证记录见 `verification/0.6.11-*.json`：Windows 145 项核心检查、打包版 22 项交互检查、Android 18 项运行检查全部通过。Android 单元测试执行 81 项、跳过 4 项，Lint 无错误（保留 4 项已有警告）。Windows 测试使用隔离数据与本地代理服务，Android 使用 API 35 x86_64 模拟器和 R8 调试包；发布 APK 为 ARM64。未新增真机耗电或 16 KiB 页设备验证。

本轮 Windows 交互检查可通过 `tools/prepare-regression-cert.ps1` 准备本地测试证书，再运行 `node tools/desktop-tests/regressions-0611.mjs`；设置 `WBOX_EXE` 可检查打包程序。Android 检查脚本为 `tools/android-regressions-0611.py`，使用隔离的 `emulator-5556`。

源码保留上游许可和来源说明；README 第一行按项目要求注明生成声明。
