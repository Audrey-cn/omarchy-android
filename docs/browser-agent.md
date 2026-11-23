# 浏览器与 Agent 接入

基础镜像默认使用 **Omarchy Browser（WebKitGTK 4.1）**，用于普通网页和 WebApp。需要浏览器自动化时，再按需安装 Playwright 浏览器。本仓提供运行时、安装入口和诊断命令；Agent、MCP 服务及其他自动化框架需自行配置。

## K20 当前实机边界（2026-11-23）

本次受控验收使用 K20 上的免 Root Ubuntu 24.04 ARM64 guest。以下区分基线测试与最终 `e876b3b` 一体 APK 复测，具体产物、提交及边界见[云构建与设备验证记录](reports/cloud-k20-validation-20261123.md)：

| 检查 | 当前结果 |
| --- | --- |
| 共用 Node 24.15.0 | 官方归档下载、SHA-256 校验、版本执行通过 |
| Playwright 1.65.1 `install headless` | 安装完成，取得 Chromium 159.0.8154.6 / revision 1255 |
| Chromium headless 探针 | **未通过**；可读浏览器版本，但在 `browserContext.newPage` 阶段连接关闭 |
| 对齐实际 PRoot 启动配置后的重测 | 仍失败；日志确认子进程及最终浏览器收到 `SIGTRAP`，伴随 GPU 重启与 NetworkService 崩溃记录 |
| Playwright Firefox 159 / revision 1572 headless 探针 | **基线与最终 `e876b3b` 均通过**；DOM、点击、中文填值、cookie、下载和截图检查成功 |
| Playwright Firefox 159 Wayland 探针 | **基线与最终 `e876b3b` 均通过**；同一组完整探针检查成功 |
| Firefox 独立 GUI launcher | **已实测通过**；基线与最终 APK 均打开 GUI，最终包的安装门槛、桌面入口、独立持久 profile 及初始 URL 失败时保留窗口均已复测 |
| Chromium 图形模式 / CDP | 尚未通过验收，不能由安装成功或读取版本推定可用 |
| 真实 Agent 框架接入 | **尚未证明**；Firefox 探针通过不等于 Agent 接入通过 |
| Playwright WebKit | 尚未完成替代引擎验收，需运行相同探针 |

基线 Firefox 的 guest 报告目录为：

```text
/root/.local/state/omarchy/playwright/2026-11-23T05-26-53-906Z-firefox-headless-13222/
/root/.local/state/omarchy/playwright/2026-11-23T05-27-31-652Z-firefox-wayland-14279/
```

最终 `e876b3b` APK 的 Firefox 报告目录另为：

```text
/root/.local/state/omarchy/playwright/2026-11-23T06-22-56-751Z-firefox-headless-7794/
/root/.local/state/omarchy/playwright/2026-11-23T06-23-04-337Z-firefox-wayland-8269/
```

两份最终报告均为 `status=passed`。基线报告产生于安装 Codex 等可能额外引入系统依赖的软件包之前；最终包复测单独记录，不能把两个环境的依赖状态混为一谈。改变依赖或 PRoot 版本后仍需重测。日常 WebKitGTK 浏览器与自动化引擎分别验收。

Chromium 159 的基线对照曾核对 kernel-release 参数、`/dev/shm`、`/proc/self/fd` 绑定与容器环境；单独设置 `PROOT_NO_SECCOMP=1` 后仍收到 `SIGTRAP`，该变量未写入默认配置。现有 Chromium 159 记录证明启动/建页失败及 GPU、NetworkService 崩溃，尚未取得该版本原始 trap 的同级精确定位；GPU 重启记录不能单独证明 GPU 硬件故障。CLI 保留失败退出码与 `status: failed` 报告。

后续 **Codex 捆绑 Chromium 158** 已完成寄存器、反汇编与源码对齐：首个错误内核版本解析问题已修复；其后子进程 trap 定位到只读共享内存访问模式检查。PRoot 请求 `O_RDONLY` 却返回 `O_RDWR`，真实 reopen 对照又返回 `EACCES`。这些精确证据来自 Codex 158，不能仅凭相同 `SIGTRAP` 就认定 Chromium 159 的每次崩溃都属于同一位置。详见[原生启动诊断](reports/codex-native-startup-20261123.md)。上游 PRoot 语义修复也不能替代本机真实权限与浏览器验收。

## 选择浏览器

| 入口 | 用途 | 与自动化的关系 |
| --- | --- | --- |
| Omarchy Browser / `omarchy-browser` | 默认图形浏览器，WebKitGTK 4.1 | 能打开网页不代表可被 Playwright 控制 |
| Agent 浏览器：Firefox (Playwright) / `omarchy-playwright-firefox` | Agent 浏览器菜单的默认选择，使用 Playwright 专用 Firefox | 基线与最终 APK 的完整双模式 probe、最终独立 GUI 与持久 profile 复测通过；实际 Agent 接入仍需单独验收 |
| Chromium (Playwright，实验性) / `omarchy-playwright-chromium` | Playwright 下载的完整 Chrome for Testing | 当前 K20 尚未通过；双模式 probe 通过后才生成桌面入口 |
| 底层命令 `install headless` / `probe chromium headless` | 安装并诊断 Chromium headless shell | 无浏览器窗口；当前 K20 未通过 |
| Firefox (Mozilla DEB) | Mozilla 官方 APT 来源的桌面 Firefox | 与 Playwright 专用 Firefox 分开安装和验收 |

`omarchy-chromium` 是兼容入口，可能转到 WebKitGTK 或宿主浏览器，并不保证启动 Chromium。**不要把它配置成 CDP 或 Agent 的浏览器可执行文件。** 需要真实路径时，使用下文的 `browser-path`。

Playwright 依赖匹配版本的浏览器二进制；其中 Firefox 使用专用补丁，不能用 Mozilla 桌面 Firefox 替代。普通 WebKitGTK 浏览器同样不能替代 Playwright WebKit。参见 [Playwright 浏览器说明](https://playwright.dev/docs/browsers)。

安装菜单仅为已实现的浏览器提供对应入口。Chrome、Brave、Edge、Zen 等其他品牌目前未实现，不应把 WebKitGTK 或 Chromium 安装成功显示成该品牌安装成功。

## Chromium 12x 锁版候选（讨论中，尚未实测/未默认内置）

历史会话 steps 2680/2683 讨论过 Playwright 与 Chromium `120–132` 的兼容候选，但上轮没有补做该范围的设备实测。当前“锁定旧版并考虑内置”仍是讨论方向，尚未采纳或实现。

可核对的上游发行组合为 **Playwright `1.46.1` + Chromium `128.0.6613.18` / revision `1129`**，对应 [官方 browsers.json](https://github.com/microsoft/playwright/blob/v1.46.1/packages/playwright-core/browsers.json#L3-L8)。该版本的[下载 registry](https://github.com/microsoft/playwright/blob/v1.46.1/packages/playwright-core/src/server/registry/index.ts#L72-L85) 为 Ubuntu 22.04/24.04 ARM64 注册了 Chromium ARM64 构建；这是发行文件与平台映射证据，不是 Android PRoot 或 K20 已可用的证明。

若后续决定试验，建议整对固定 Playwright 版本、浏览器 revision 和实际归档校验值，并与当前安装隔离。分别通过 GUI/Wayland、headless、CDP 连接与页面操作、真实 Agent 完整任务、清洁安装后的冷启动回归，再评估是否内置；不能只凭版本号、成功下载或出现窗口决定替换。

现有安装命令仍使用 Playwright `1.65.1`；本节没有新增 `1.46.1` 安装接口，也没有将 Chromium 12x 写入镜像。基础 WebKitGTK 浏览器与已验收的 Playwright Firefox 默认选择保持不变。

## 按需安装

在 Omarchy 容器内运行，首次安装需要联网并能写入 `/opt`：

```bash
# 在已启动 Sway 的终端内安装并验收 Agent 浏览器，默认选择 Firefox
omarchy-install-playwright-browser
# 等价的显式选择
omarchy-install-playwright-browser firefox

# 实验性 Chromium：同样要求两种模式均通过
omarchy-install-playwright-browser chromium
```

此入口安装选定引擎，然后依次运行 headless 和 Wayland probe；两项均通过才生成 `Firefox-Playwright.desktop` 或 `Chromium-Playwright.desktop` 并显示通过。任一步失败保留非零退出码，且本次不会生成桌面入口。菜单中的 Agent 浏览器默认选择 Playwright Firefox；日常 **Firefox (Mozilla DEB)** 仍是独立选项，基础镜像的默认 WebKitGTK 浏览器保持不变。

只安装浏览器文件或做单项诊断时，使用底层命令：

```bash
# 完整 Chrome for Testing + headless shell，安装成功仍需运行探针
omarchy-playwright install chromium

# 只装 Chromium headless shell，不含完整图形浏览器
omarchy-playwright install headless

# 其他 Playwright 专用浏览器，按需选择
omarchy-playwright install firefox
omarchy-playwright install webkit
```

`install headless` 对应 Playwright 的 `--only-shell`；完整 Chromium 和 headless shell 是不同二进制，见 [官方 headless shell 说明](https://playwright.dev/docs/browsers#chromium-headless-shell)。仅装 headless 后，如需图形窗口，再执行 `install chromium`。

运行时固定为以下独立安装，不替换系统 `node`：

| 项目 | 固定版本 / 路径 |
| --- | --- |
| Node.js | `24.15.0`，`/opt/omarchy-node` |
| Playwright | `1.65.1`，`/opt/omarchy-playwright` |
| 浏览器缓存 | `/opt/omarchy-playwright/browsers` |

`omarchy-node-runtime` 从 Node.js 官方下载 Linux ARM64 归档，并按同版本的 [SHASUMS256.txt](https://nodejs.org/dist/v24.15.0/SHASUMS256.txt) 校验；成功时 stdout 只输出 `node` 的绝对路径，可供其他安装器复用。Playwright 浏览器版本以 [v1.65.1 的浏览器清单](https://github.com/microsoft/playwright/blob/v1.65.1/packages/playwright-core/browsers.json) 为准。

Mozilla 桌面 Firefox 通过菜单中的 **Firefox (Mozilla DEB)** 安装，来源参见 [Mozilla 官方 Linux 安装说明](https://support.mozilla.org/en-US/kb/install-firefox-linux)。它不占用 Playwright 的 Firefox 缓存。

## 安装后检查

先检查版本与实际二进制，再分别诊断运行模式：

```bash
node_bin="$(omarchy-node-runtime)"
"$node_bin" --version
omarchy-playwright cli --version

omarchy-playwright browser-path chromium
omarchy-playwright browser-path firefox
omarchy-playwright browser-path webkit

omarchy-playwright probe chromium headless
omarchy-playwright probe firefox headless
omarchy-playwright probe webkit headless
```

只检查自己已安装的浏览器；没有安装某项时，路径查询或诊断失败是预期结果。Node 应输出 `v24.15.0`，Playwright 应输出 `Version 1.65.1`。

在已启动 Sway 的容器终端中检查图形模式：

```bash
omarchy-playwright probe firefox wayland
omarchy-playwright-firefox https://example.com
```

新 Firefox GUI launcher 通过同一固定版本的 Playwright Firefox，以 `headless: false` 的 persistent context 启动；独立 profile 位于 `$HOME/.local/share/omarchy-playwright/firefox-desktop`，并设置 `GDK_BACKEND=wayland`、`MOZ_ENABLE_WAYLAND=1`，避免直接启动 raw Firefox 与 Playwright 默认选项的差异。**该入口已在最终 `e876b3b` APK 中完成实机复测；初始 URL 连接失败时保留窗口的行为也已验证。** 这不替代实际 Agent 框架的接入验收。

同样可运行 `probe chromium wayland` 或 `probe webkit wayland`。Wayland 检查需要当前会话的显示环境；从没有 Wayland 会话的终端执行失败，不能单独证明浏览器二进制不可用。无头成功也不能替代图形模式验收。实验性 Chromium 的图形启动命令为 `omarchy-playwright-chromium [URL]`，当前 K20 没有通过记录。

## 接入已有 Agent

使用 Playwright 库的 CommonJS（`.cjs`）脚本可通过以下入口运行；`run` 会设置 `NODE_PATH`，外部脚本可用 `require('playwright')`。ESM 的模块查找规则不同，不承诺同样适用。其他参数透传给 Playwright CLI：

```bash
omarchy-playwright run /absolute/path/to/agent-browser.cjs
omarchy-playwright cli --help
```

使用 Playwright Firefox 的外部脚本应选择 `playwright.firefox`；仅支持 Chromium/CDP 的 Agent 不能直接使用 Firefox 替代，当前 K20 的 Chromium/CDP 路线仍未通过。需要配置 `executablePath` 或浏览器路径时，用 `omarchy-playwright browser-path firefox` 查询相应完整浏览器路径，并按 Agent 的文档接入。

`browser-path` 仅接受 `chromium|firefox|webkit`，不查询 headless shell；使用 headless shell 的 Playwright 脚本由库选择二进制。Agent 若自行启动 Chromium/CDP，仍需按其文档配置启动参数与连接方式，路径查询本身不会启动 CDP 服务。

安装后先保留 Agent 自己的浏览器配置，确认它使用本次安装的浏览器及正确缓存。不要仅凭桌面快捷方式名称、网页 User-Agent 或系统 `node --version` 判断接入成功。

## 日志与故障定位

`probe` 默认将结果保存到 `$HOME/.local/state/omarchy/playwright/<timestamp>-<engine>-<mode>-<pid>/`，包含 `result.json`；成功时还有 `page.png`、`download.txt` 和 `trace.zip`。启动失败会保留失败报告。可设置 `OMARCHY_PLAYWRIGHT_RESULTS` 覆盖结果根目录。

将安装或诊断的 stdout、stderr 和退出码保留下来，便于区分下载、依赖、启动和页面操作故障。例如：

```bash
mkdir -p "$HOME/browser-check"
omarchy-playwright install chromium >"$HOME/browser-check/install.log" 2>&1
echo "$?" >"$HOME/browser-check/install.exit"
DEBUG=pw:browser omarchy-playwright probe chromium headless >"$HOME/browser-check/probe.log" 2>&1
echo "$?" >"$HOME/browser-check/probe.exit"
```

同时记录镜像/提交版本、手机型号与 Android 版本、PRoot 或 chroot 模式，以及 `uname -m`、Node/Playwright 版本、真实二进制路径。下载或校验失败先检查网络和磁盘空间；缺失共享库保留完整错误；进程收到 `SIGTRAP`、沙箱或权限错误时保留启动命令和 stderr，不把重新下载成功当作修复。

[Playwright 官方系统要求](https://playwright.dev/docs/intro#system-requirements)列出受支持的 Linux 发行版与 ARM64，但没有承诺 Android PRoot 环境兼容。这里的安装入口也不构成该兼容性承诺。应在目标手机、当前镜像和实际运行模式分别验证。

## 真实 Agent 单独验收

`probe` 成功只证明该诊断在当前环境通过。交付 Agent 接入前，使用最终选定的 Agent 和它实际使用的浏览器执行以下任务，并保存日志与产物：

1. 打开受控测试页，读取 DOM 并定位一个有明确文本或标签的控件。
2. 通过 Agent 点击该控件，确认页面状态或 URL 按预期改变。
3. 在输入框填入中文，例如 `浏览器测试 你好`，提交后读取页面并核对完整文本。若目标是桌面人工操作，还要另测中文输入法；DOM 填值不能证明输入法可用。
4. 下载一个已知文件，确认下载完成、实际保存路径和文件内容。
5. 保存截图，人工打开核对页面内容、中文显示和图像有效性。

验收记录写明 Agent 名称/版本、浏览器/模式、目标设备、测试页、各步骤结果及下载和截图路径。无头、Wayland、不同浏览器分别记录；任何一项未通过时，明确列为未验证或失败。
