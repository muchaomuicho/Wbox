# Wbox 0.5.2 — Windows / Android

本次修复内核停止与缓存生命周期，并同步更新两端日志、测速、代理组和配置命名。

## 连接修复

用户日志中的 `missing fakeip record` 出现在快速重启后。原配置已开启缓存，但 Windows 旧实现直接杀死内核；sing-box 的 FakeIP 元数据采用延迟保存，正常关闭时才会立即写入。0.5.2 新增约 20 KB 的原生进程管理器，给每个内核分配独立隐藏控制台：先恢复系统代理，再通知内核正常退出，5 秒无响应才强制关闭。Job 对象继续负责父进程意外退出时回收子进程。固定官方内核二进制保持原样。

Windows 节点工作区按内核分离运行目录，临时测速使用独立目录；避免两个内核竞争同名缓存文件。Android 按内核、配置分离运行目录和 FakeIP 缓存，继续使用 SIGTERM 正常关闭进程。两端开启 sing-box 的缓存持久化。

已使用当前配置的隔离副本验证受管 mixed 入站的 HTTPS 转发：Apple 返回 200，Google 返回 204。原日志另有 SSH 服务器 TCP 超时，当前 TCP 探测可达；该历史超时无法仅凭日志确定全部成因。实际 Windows TUN 接管与 Android 真机 VPN 未在本轮验证，不能由本地代理测试替代。

首次从旧版升级时，旧进程已经丢失的 FakeIP 映射无法重建；若浏览器仍持有旧虚拟地址，需让该应用重新解析域名（例如重新启动浏览器）。后续正常停止与重启会保留映射。

## 两端同步

- 日志按 INFO、WARN、ERROR 等级着色，同一连接编号使用同一颜色。复制与导出保留纯文本。
- 测速按实际节点去重，每次最多 8 个并发，内核超时为 4 秒。结果逐个显示，不再等全部结束才刷新。Windows 节点批量测速独立执行，不阻塞停止命令；Android 可在测速时停止连接。
- 代理组每次连接默认仅展开第一组，其余收起。折叠方块使用官方延迟区间：低于 800 ms、800–1499 ms、1500 ms 及以上；未测试为灰色，失败单独显示。Android 同步方块及分组测速。
- 启动和停止过程显示进度动画，按钮宽度保持稳定；Windows 等待实际服务状态更新后切换图标。
- 配置支持创建时命名、已有配置重命名。远程更新和内容编辑保留自定义名称。

## 验证

- Windows 72 项基础／核心检查、7 项新增转发检查、11 项真实 WinINet 恢复检查通过。新增回归在 FakeIP 元数据定时保存前停止并重启，验证旧 FakeIP 仍可转发；两个内核均通过真实 SOCKS 上游转发。
- Android 45 项通过、1 项外部真实订阅测试跳过，Lint 无错误。包括新增日志着色、延迟区间、分组折叠、启动状态、命名保留及缓存隔离测试。HTTP/SOCKS5 UDP、IPv4/IPv6 通过 Android 生成配置与固定 Windows 核心验证。
- 桌面打包版界面检查与 Android 签名、ABI 结果记录在 `verification/0.5.2-desktop.json`、`verification/0.5.2-android.json`。截图位于 `previews/0.5.2-*.png`。
- Android 签名证书保持不变，仅包含 arm64-v8a 库。真机耗电、VPN 和 16 KiB 页设备兼容性仍未验证。

Windows 安装程序可覆盖升级并保留数据；安装前从旧版托盘退出 Wbox。Android APK 可沿用原签名覆盖升级。源码包不包含订阅凭据、用户配置或签名私钥。

## 上游依据

- sing-box v1.14.4：`dns/transport/fakeip/store.go`、`experimental/cachefile/fakeip.go`、`cmd/sing-box/cmd_run.go`、`experimental/clashapi/proxies.go`。
- mihomo v1.19.33：`component/profile/cachefile/cache.go`、`hub/executor/executor.go`、`hub/route/proxies.go`。
- sing-box-dashboard：沿用 `LogsView` ANSI 渲染和 `GroupsView` 延迟区间、折叠布局；Android 采用同一语义配色。

构建 Windows 新增依赖 MinGW-w64 `gcc`（需在 PATH 中）；`tools/build-desktop.ps1` 会自动从 `native/core-host.c` 编译管理器。该辅助进程不包含 .NET 或浏览器运行时。
