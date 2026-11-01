# Wbox Windows

0.5.0 的界面和托盘使用 `desktop/` 内的官方 sing-box desktop/dashboard 前端。`Wbox.Backend/` 是不依赖 WPF 的 .NET 8 后台，通过受限的 stdio JSON 通道管理双内核。`Wbox/` 的旧 WPF 界面保留为历史源码，不作为本次安装包入口。

## 构建

按 `docs/UPSTREAM-DESKTOP.md` 安装前端依赖，再运行：

```powershell
./tools/build-desktop.ps1 -InnoCompiler '<Inno Setup>/ISCC.exe'
```

`windows/desktop-publish/win-unpacked/` 包含 Wbox.exe、Electron、后台 .NET 运行库、两内核与 sing-box 的 libcronet.dll。必须整体分发；安装程序输出到 `dist/`。

数据继续保存在 `%LOCALAPPDATA%\Wbox`。`WBOX_DATA_DIR` 隔离测试数据、实例和 Chromium 存储，并禁用测试实例写入开机启动项。`--silent` 不创建主窗口。主窗口关闭后收起托盘并在 30 秒后释放；托盘菜单关闭后 5 秒释放。

完整配置的运行副本位于原配置目录，以保留相对资源路径，停止后删除。Windows 默认使用受管 mixed 入站和系统代理。原生入站/TUN 可在设置启用，TUN 配置需要管理员身份。停止前必须成功恢复 WinINet 代理，恢复失败保留运行内核和持久记录。

## 验证

```powershell
dotnet build windows/Wbox.Tests -c Release
dotnet windows/Wbox.Tests/bin/Release/net8.0-windows/Wbox.Tests.dll --integration
node tools/desktop-tests/smoke.mjs
```

集成验证 61 项；桌面自动化使用独立虚拟配置和本地 HTTP 服务，不启用系统代理。设置 `WBOX_EXE` 可验证已打包程序。托盘测试覆盖级联菜单、节点选择、关闭连接、配置切换及隐藏后的进程释放。

真实代理恢复测试另用 `--proxy-native`，会短暂修改 WinINet 设置并在 finally 中还原，已有代理启用时拒绝运行。11 项覆盖恢复失败、绕过列表漂移、失效旧代理和还原一致性。本次已完成此验证；不需要为普通 UI 改动重复修改真实网络设置。