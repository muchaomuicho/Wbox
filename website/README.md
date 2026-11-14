# Wbox 官网

公开地址：<https://wbox.wucnebssss.hidns.co/>。

这是 Wbox 的中文官网，使用静态 HTML、CSS、JavaScript；页面运行不依赖第三方 CDN、字体服务或前端框架。Cloudflare Worker 提供同源发布信息接口。

## 本地开发与部署

在本目录执行：

```sh
npm ci
npm run build
npm run dev
```

`build` 将 `index.html`、`styles.css`、`app.js` 与 `public/` 的内容复制到 `dist/`。`dev` 构建页面并启动 Wrangler，本地同时提供静态页面与 `/api/release`。

完成 Cloudflare 授权后使用：

```sh
npm run deploy
```

Wrangler 版本固定在 `package.json` / `package-lock.json`。部署域名和 Worker 配置由 `wrangler.jsonc` 管理。本机也可直接使用已安装的 `D:/LL/blog/node_modules/.bin/wrangler.cmd`，无需修改该项目的依赖。客户端与官网分别构建和发布。

`npm test` 检查发布接口逻辑。`node verify-ui.mjs` 使用 `tools/desktop-tests` 中的 Playwright 与本机 Chrome，启动独立的本地模拟接口，检查桌面、390px、320px、明暗外观、配色、下载、导航、FAQ 和减少动态效果偏好。报告和页面截图输出到 `verification/`，不打开用户浏览器配置。

## 页面与交互

- `index.html`：中文主页面、设备预览、工作区介绍、安装包下载、快速开始与原生 `details` 常见问题。
- `styles.css`：暖白 / 深色主题、黑白 / 雾蓝 / 柔粉页面配色、响应式设备组合及轻量几何动效。
- `app.js`：系统 / 浅色 / 深色外观、平台选择、工作区切换、移动导航、滚动显现、发布信息更新。外观偏好只保存在网站自己的 localStorage 键中。
- `public/`：静态资源、404 页面、搜索引擎文件和响应头。

`prefers-reduced-motion` 会关闭漂浮、过渡和滚动显现动画；不支持 JavaScript 时仍能阅读主要内容、使用 FAQ，并通过 GitHub 发布页下载。工作区选项卡支持方向键，主题菜单支持方向键和 Escape，所有控制均可由键盘操作。

## 发布数据

前端读取 `GET /api/release`，接受 GitHub release 结构：`tag_name`、`published_at`、`html_url`、`assets`。每个 asset 使用 `name`、`size`、`browser_download_url`。

Worker 对发布信息缓存 5 分钟，上游请求 6 秒超时后使用内置回退。浏览器的请求超时为 6.5 秒，失败时保持 GitHub 最新发布页链接，并显示说明。前端不固定客户端版本号：下载链接、版本、日期、文件大小和 SHA256 文件跟随接口更新。Windows 安装包按名称中的 `windows` / `x64` / `.exe` 识别，Android 按 `android` / `arm64`（或 `aarch64`）/ `.apk` 识别。

客户端发布新版后，运行 `npm run release:refresh` 更新 Worker 的离线发布回退，再重新部署官网。正常情况下，在线接口会自动获取最新发布。

所有接口文本通过 `textContent` 写入；链接只接受该 GitHub 仓库的 HTTPS 发布路径，不插入 release body 的 HTML。页面不会收集订阅、节点或用户配置，也不加载分析脚本。

## 图片来源

网站只使用项目资源与已经发布的合成界面预览：

- `assets/wbox.svg`：来自 `resources/wbox.svg`。
- `assets/windows-configurations.webp`：来自 `docs/previews/0.7.7-windows-configurations.png`，裁去窗口底部多余空白。
- `assets/windows-nodes.webp`：来自 `docs/previews/0.7.8-windows-node-menu.png`，裁去窗口底部多余空白。
- `assets/android-nodes.webp`：来自 `docs/previews/0.7.7-android-nodes-connected.png`。
- `assets/social-preview.png`：以上合成预览与品牌文字的分享图组合。

这些预览不包含用户提供的截图、真实订阅地址或真实使用流量。图片保留真实客户端内容，页面上的设备边框为展示用装饰。
