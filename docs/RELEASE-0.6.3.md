# Wbox 0.6.3

- Windows / Android 默认工作区改为配置模式；已有用户手动选择的工作区继续保留。
- 两端日志默认开启自动滚动，新增“滚动到底部”。向上阅读时暂停跟随，手动回到底部或点击按钮后恢复；“暂停日志更新”独立控制日志快照。
- mihomo 配置启动前准备随包的默认 MMDB、GEOSITE / GEOIP 数据，避免首次启动依赖 GitHub 下载。按模式选择文件，完整缓存不重复复制；检测并替换常见下载中断的残缺文件，保留非默认 geox-url 配置。
- 两端等待控制 API 和本地代理端口后再报告连接就绪。Android 失败提示包含本次启动的 fatal 日志，并标明每次启动边界。

数据来源、固定 SHA256 和文件大小见 `resources/geodata/manifest.json`，许可证随包提供。复制时流式校验 SHA256；已有文件仅做快速截断检查，数据库内容语义仍由 mihomo 验证。远程 rule-provider、自定义数据库仍按用户配置下载，不属于随包默认数据。

验证证据位于 `docs/verification/0.6.3-*`。Android 运行回归使用 Android 15 x86_64 项目模拟器，发布 APK 为 ARM64；实体手机、耗电及 16 KiB 页设备仍需实机验证。

安装包：`Wbox-0.6.3-windows-x64-setup.exe`、`Wbox-0.6.3-android-arm64-v8a.apk`。Android 沿用正式签名，可覆盖安装。
