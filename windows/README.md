# Wbox Windows

原生 .NET 8 WPF 客户端，使用 `sing-box 1.14.4` 与 `mihomo 1.19.33`。只有 YamlDotNet 外部托管依赖；代理内核运行时按需启动。

```powershell
dotnet build windows/Wbox/Wbox.csproj -c Release
dotnet publish windows/Wbox/Wbox.csproj -c Release -r win-x64 --self-contained true -o windows/publish
```

独立发布目录 `windows/publish` 包含 Wbox.exe、.NET 运行库、图标和 `cores/`。安装程序必须复制整个目录，尤其是 sing-box 同目录的 `libcronet.dll`。正式发布不包含测试程序。

配置与订阅保存到 `%LOCALAPPDATA%\Wbox`；`--silent` 参数直接收起至托盘，`WBOX_DATA_DIR` 环境变量可指定独立数据目录用于开发验证；该模式不更改 Windows 开机启动项。完整配置的运行副本暂时写在原配置目录，停止后删除，以便保留相对路径资源；原文件不修改。Windows 主模式为系统 HTTP/SOCKS 代理，需要全流量 TUN 时，先在概览选择“配置原生入站 / TUN”，再在设置中以管理员权限重启；系统代理模式使用受管 mixed 入站，保留 DNS、路由、出站和首个 mixed 标签。

分享链接与平台限制见根 README。配置工作区支持多份远程/本地完整配置；节点工作区支持独立订阅分组、搜索、选择和批量测速。原生订阅自动选择匹配内核，分享链接保留插件、混淆、ALPN 等支持的参数。`WBOX_DATA_DIR` 同时隔离实例互斥锁与数据，便于在已有 Wbox 运行时进行不设置系统代理的验证。

验证：

```powershell
dotnet build windows/Wbox.Tests/Wbox.Tests.csproj -c Release
$env:WBOX_DATA_DIR = 'D:\LL\Wbox\windows\test-runtime'
dotnet windows/Wbox.Tests/bin/Release/net8.0-windows/Wbox.Tests.dll --integration
dotnet build windows/Wbox.UiSmoke/Wbox.UiSmoke.csproj -c Release
dotnet windows/Wbox.UiSmoke/bin/Release/net8.0-windows/Wbox.UiSmoke.dll windows/ui-preview.png
```

集成验证覆盖 53 项检查，包括两内核真实启动、12 类分享协议与原生配置校验、代理组切换、匿名控制接口拒绝、IPv6 代理传输、本地 HTTP 字节传输和流量/连接/内存统计。系统代理恢复采用持久备份与所有权比较；测试覆盖比较逻辑，集成测试不改用户正在使用的系统代理。测试入口捕获异常并退出 1，不触发 Windows 错误对话框。UI smoke 在不展示窗口的情况下构造并渲染全部六页，便于检查颜色与布局。
