# Wbox Windows

原生 .NET 8 WPF 客户端，使用 `sing-box 1.14.4` 与 `mihomo 1.19.33`。只有 YamlDotNet 外部托管依赖；代理内核运行时按需启动。

```powershell
dotnet build windows/Wbox/Wbox.csproj -c Release
dotnet publish windows/Wbox/Wbox.csproj -c Release -r win-x64 --self-contained true -o windows/publish
```

独立发布目录 `windows/publish` 包含 Wbox.exe、.NET 运行库、图标和 `cores/`。安装程序必须复制整个目录，尤其是 sing-box 同目录的 `libcronet.dll`。正式发布不包含测试程序。

配置与订阅保存到 `%LOCALAPPDATA%\Wbox`；`--silent` 参数直接收起至托盘，`WBOX_DATA_DIR` 环境变量可指定独立数据目录用于开发验证；该模式不更改 Windows 开机启动项。完整配置的运行副本暂时写在原配置目录，停止后删除，以便保留相对路径资源；原文件不修改。Windows 主模式为系统 HTTP/SOCKS 代理，需要全流量 TUN 时可加载内核原生 TUN 配置并以管理员权限运行。

分享链接支持 SS、VMess、VLESS（含 Reality）、Trojan、Hysteria2 与 TUIC。带 SS plugin、Hysteria2 obfs 或复杂 TUIC 参数的链接会提示使用原生配置，避免丢失必要参数。来自原生 YAML/JSON 订阅的节点使用对应内核。sing-box API 会把 HTTP 测速地址换成默认地址，因此界面明确要求其测速使用 HTTPS；mihomo 支持 HTTP 与 HTTPS。

验证：

```powershell
dotnet build windows/Wbox.Tests/Wbox.Tests.csproj -c Release
$env:WBOX_DATA_DIR = 'D:\LL\Wbox\windows\test-runtime'
dotnet windows/Wbox.Tests/bin/Release/net8.0-windows/Wbox.Tests.dll --integration
dotnet build windows/Wbox.UiSmoke/Wbox.UiSmoke.csproj -c Release
dotnet windows/Wbox.UiSmoke/bin/Release/net8.0-windows/Wbox.UiSmoke.dll windows/ui-preview.png
```

集成验证覆盖 33 项检查，包括两内核真实启动、6 协议生成配置、代理组切换、匿名控制接口拒绝、IPv6 代理传输、本地 HTTP 字节传输和流量/连接/内存统计。系统代理恢复采用持久备份与所有权比较；测试覆盖比较逻辑，集成测试不改用户正在使用的系统代理。测试入口捕获异常并退出 1，不触发 Windows 错误对话框。UI smoke 在不展示窗口的情况下构造并渲染全部六页，便于检查颜色与布局。
