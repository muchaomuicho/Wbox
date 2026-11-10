# Wbox 0.6.2

Windows x64 / Android arm64-v8a，同步修复启动与配置保存。

- 修复 Android 约 8 秒就误判内核启动超时的问题。两端等待内核完成远程规则集和服务初始化，最长 120 秒；接口就绪后立即连接，不增加固定等待。
- 启动超过 5 秒提示仍在初始化；启动期间可以取消，内核退出立即报告。Windows 主窗口和托盘可取消启动；系统代理 / Android VPN 仍在内核就绪后开启。
- 修复 Android 导入带国旗等字符的 YAML 时的“保存失败”。SnakeYAML 2.3 在 UTF-16 高代理字符落在 1024 字符缓冲区末尾时越界，升级至包含官方修复的 2.4；保留原字符及节点名称。
- 两端连接期间允许添加、编辑和更新配置。添加不会改变当前配置和内核；编辑保存及远程更新在下次连接生效。切换运行配置与工作区仍需停止连接，托盘切换会重启连接。
- Android 下载和保存错误显示具体原因，消息为空时保留异常类型。
- Android “已添加”等提示条跟随当前主题，暗色模式使用深灰背景和浅色文字。
- JSON 使用独立 JSON 解析路径，兼容 Android 序列化产生的 `\/` 等合法转义字符。
- 默认下载 UA 保持 `clash.meta/1.19.33`，配置与节点工作区独立。

`/data/system/packages.xml: permission denied` 是官方 sing-box CLI 在普通 Android 应用权限下的非致命警告。上游 `route/network.go` 仅记录 Warn 并继续；本次慢规则实测保留此警告且成功连接，不需要 root。

## 验证

详见 `docs/verification/0.6.2-*`。慢规则测试使用本地服务器：Android 旧版在 12 秒响应返回前超时，新版完成启动；Windows 35 秒规则加载也能完成。覆盖缓存重连、启动中取消、真实配置错误及时退出，以及连接期间添加/编辑/更新配置后原连接继续转发。

- Windows：76 项基础、14 项实际转发、63 项打包版交互检查通过；另验证慢启动及取消按钮。
- Android：49 项测试通过，1 项依赖外部私人订阅文件的可选测试跳过；Lint 通过。两内核在模拟器完成 VPN TCP/UDP、停止恢复和重连检查。
- ARM64 APK 版本号 10，签名验证通过并与旧版本证书一致。

测试中曾在受限执行环境启动桌面包时触发 Electron `install_dir_access.cc` 目录权限断言（`0x80000003`）；正常权限下打包版回归通过，没有禁用 Electron 沙箱。这条证据解释本次测试崩溃，不能仅凭相同异常码推断其他崩溃原因。

已在 Android 模拟器以默认 mihomo UA 下载用户反馈的订阅：41,589 字符，旧解析器抛出 `IndexOutOfBoundsException`，新解析器成功识别为 mihomo 配置。验证记录不保存订阅地址令牌或节点内容。

Android 运行验证使用 Android 15 x86_64 模拟器和 R8 调试构建；发布包为 ARM64，并沿用原签名。尚未在用户手机复测，也未做实体设备耗电与 16 KiB 页设备验证。

## 文件

- `dist/Wbox-0.6.2-windows-x64-setup.exe`
- `dist/Wbox-0.6.2-android-arm64-v8a.apk`
- `dist/Wbox-0.6.2-source.zip`
- `dist/SHA256SUMS.txt`
