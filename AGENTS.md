# Wbox 项目规则

## 发布与官网同步

- 每次发布 Wbox 新版本，都必须同步更新官网 `https://wbox.wucnebssss.hidns.co/`，这是发版完成条件。
- GitHub 正式版发布后，在 `website/` 运行 `node refresh-release.mjs`，更新离线版本回退；按实际功能同步介绍、更新说明与 FAQ。
- 在 `website/` 运行 `node build.mjs`，按 `website/README.md` 部署 Cloudflare Worker。涉及页面改动时运行相应页面检查。
- 部署后检查公开页面与 `/api/release`：版本、Windows / Android / 源码下载链接和 SHA256 文件必须对应最新正式版。
- 官网改动提交并推送；保持客户端发布 tag 与源码包对应。官网同步未完成时，不宣称整个发版完成。
