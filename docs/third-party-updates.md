# Guest 更新来源

打开桌面菜单中的“更新”，或运行 `omarchy-updates menu`。首次检查需要联网。命令行可用 `omarchy-updates check --json` 刷新并保存结果，`omarchy-updates status --json` 只读缓存，`omarchy-updates sources` 输出当前第三方来源清单。Waybar 显示最近一次显式检查的状态，超过 24 小时标为过期。检查失败时查看对应来源的原因和时间。APT 的输出按已配置的 Ubuntu 和第三方源覆盖实际软件包。

| 项目 | 来源、更新方式与适配范围 |
| --- | --- |
| Android APK | Android 应用管理安装。Guest 读取当前启动环境报告的版本，检查本仓库 APK Release。旧环境无法报告版本时标记未知。 |
| 项目桌面 | 独立桌面组件 Release。安装前显示版本、变化、兼容要求和校验信息。 |
| 图形栈、Chromium 配套 | 统一检查分别显示两组配套的已装版本、可用版本、检查时间与结果。更新中心也可按组安装或恢复。 |
| Claude Desktop、Firefox | APT 检查成功且候选版本确实存在于已登记的厂商源时，显示可比较版本。Firefox 的 Mozilla 优先级规则由显式安装操作配置。 |
| CC Switch | 检查 GitHub Release 的 ARM64 DEB、长度和 SHA256。更新已有包时还校验 DEB 包名、架构和版本，APT 显示实际安装结果。 |
| ChatGPT / Codex Desktop | OpenAI 的独立 DEB。安装版本取自 dpkg；该下载地址没有已验证的版本清单。 |
| Hermes Desktop | 仅支持修复脚本中指纹对应的固定归档。检查版本戳、Electron 和前端源码指纹。显式重装先暂存归档并验证适配；不支持原子交换目录的文件系统会保留原安装并返回失败。 |
| Hermes CLI、Claude Code、Codex CLI、OpenCode | 与桌面程序独立登记。仅探测命令入口，具体升级方式取决于原安装途径。 |
| Doubao Say | Git 检出，显示本地提交。依赖和启动封装须随上游变更测试。 |
| Ollama、OpenClaw | 上游安装器或 npm 全局安装，独立维护。 |
| Grok、Perplexity、Antigravity、Amp | 上游托管网页，探测本地启动文件。网页版本由服务方维护。 |

`current` 表示该来源检查成功。其他常见状态包括可更新 `available`、无已验证自动更新方式 `unsupported`、未安装 `not-installed`、固定适配 `pinned`、未知 `unknown`、检查失败 `failed` 和缓存过期 `stale`。用户自行安装的其他软件可能缺少可识别来源。

APT hook 在 `/usr/local/share/applications/omarchy-android-*.desktop` 补充缺失的项目启动入口。已有同名文件、用户主目录和厂商的桌面文件保持原内容。首次安装同一应用后可能同时看到厂商入口和带有 “(Omarchy)” 的适配入口。旧版安装器曾改写的厂商文件无法通过名称判定原始内容，用户可按需从原软件包恢复。
