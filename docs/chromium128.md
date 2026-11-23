# Chromium 128 固定入口

固定组合为 Chromium `128.0.6613.18` / revision `1129`、Playwright `1.46.1`、Node `24.15.0`。K20 的两种完整探针、GUI、CDP 与真实 Hermes Agent 任务已通过，本轮选择它作为 PRoot 日常及默认 Agent 浏览器；最终集成 APK 的云编译与固定签名 K20 回归仍待完成，见[第二轮记录](reports/k20-round2-20261123.md)。原 Playwright 1.65.1 Firefox 保留按需回退。

普通壳包和一体包的构建流程已启用固定 browser managed payload 生成；这表示构建配置已接入，不表示最终 APK 的实机安装/升级和冷启动已通过。

默认目录为 `/opt/omarchy-chromium128/`，共享 Node 位于 `/opt/omarchy-node/node-v24.15.0-linux-arm64/`。Android managed 部署会校验每个文件的 SHA256；入口核对来源清单、固定版本、SDK 配对、revision 和实际可执行路径，随后才运行命令。缺少资源会报错，不联网补装或自动升级。

```bash
omarchy-playwright128 check
omarchy-playwright128 browser-path chromium
omarchy-playwright128 probe chromium headless
omarchy-playwright128 probe chromium wayland
omarchy-playwright128 run /path/to/script.cjs [ARGS...]
omarchy-playwright128 cli --version
```

两种 probe 复用仓库现有完整 fixture：中文 DOM/点击、截图、cookie、真实下载和 trace。Wayland probe 应在桌面处于全屏前台时运行；报告写入 `$HOME/.local/state/omarchy/playwright128/`。它们的退出码不会因出现窗口或能读取版本而变为通过。

本轮已复现的 Wayland SIGABRT 来自 `wtype` 临时虚拟键盘退出后的 Sway keymap 状态：新 client 只有 enter/modifiers，Chromium 在空 xkb state 上调用 update_mask 后终止。实机通过同值重发真实 X11 键盘的当前 repeat_delay 触发 reconfigure，即可让原 stock 探针通过；layout/options 不变。这次故障不归因于 Codex 或 Mesa。

`omarchy-restore-x11-keyboard` 保留当前 repeat_delay 与活动 layout index；`wtype` 退出后及 Chromium GUI、Wayland probe、Hermes GUI 启动前会调用它。helper 缺失时保留旧安装兼容；存在但失败时停止启动/自检并报告错误。headless 不调用此恢复，也不添加 GPU 绕过参数。

图形入口使用持久、独立 profile，并逐项保留 URL/参数，不经 shell 二次解析：

```bash
# 普通窗口，不开放 CDP
omarchy-playwright-chromium https://example.com
omarchy-playwright-chromium --app=https://example.com

# Agent 模式，--agent 必须是首个参数
omarchy-playwright-chromium --agent https://example.com
```

普通 profile 为 `$HOME/.local/share/omarchy-chromium128/desktop/`，Agent profile 为同级 `agent/`。只有显式 Agent 模式设置 `127.0.0.1:9222` CDP；调用方不能覆盖监听地址、端口、profile、Wayland 或图形模式。没有附加 GPU 绕过参数。固定入口的 `cli` 拒绝 install/install-deps/uninstall，资源更新由 managed 部署处理。

## Hermes Desktop 2237 的最小连接配置

在保留原模型配置的前提下，主 profile 的浏览器部分可以是：

```yaml
browser:
  backend: "off"
  cdp_url: "http://127.0.0.1:9222"
  allow_private_urls: false
```

先显式运行 `omarchy-playwright-chromium --agent`，再建立新的 Hermes GUI 会话。配置连接地址不会自动启动浏览器或开放端口；普通浏览继续使用不带 `--agent` 的入口。`"off"` 选择内置 `browser_*` 工具，避免切换到 Browser Use CLI。

固定 commit `2237be3` 的 Desktop 默认读取 CLI 工具集并补充 GUI 工具；默认 `hermes-cli` 和 coding posture 都已包含 `browser_cdp`，浏览器也不在默认关闭列表中。没有显式工具集限制时，无需人为覆盖 toolsets。原始 CDP 工具会在已配置 endpoint 且内置浏览器模式可用时进入 schema；实际连接仍要求端口可达。主 profile 保留 `allow_private_urls: false`，本机 HTTP fixture 的许可只放在临时验收 profile。

依据：[Desktop 工具集解析](https://github.com/NousResearch/hermes-agent/blob/2237be355906fbe6065ce1815711eee52b2d646e/tui_gateway/server.py#L1812)、[默认配置](https://github.com/NousResearch/hermes-agent/blob/2237be355906fbe6065ce1815711eee52b2d646e/hermes_cli/config_defaults.py#L26)、[工具集合](https://github.com/NousResearch/hermes-agent/blob/2237be355906fbe6065ce1815711eee52b2d646e/toolsets.py#L20)、[CDP 可用性条件](https://github.com/NousResearch/hermes-agent/blob/2237be355906fbe6065ce1815711eee52b2d646e/tools/browser_cdp_tool.py#L380)。

安装桌面入口使用 `omarchy-install-playwright-browser chromium`：按需补齐系统依赖、检查已内置文件，再依次运行 headless 与 Wayland probe，两项都通过才发布桌面条目。它不会调用 Playwright 1.65.1 下载新 Chromium。`omarchy-install-playwright-browser firefox` 继续使用原来的安装与两种 probe 路线。

新 rootfs 已包含 `libnss3`、`libnspr4`。旧 guest 更新受管文件时不会重新导入 rootfs；如果此前没有浏览器依赖，请点 Chromium“检查并自检”菜单：该显式动作检查两包的 dpkg 安装状态，缺失时才通过 APT 补齐，再检查资源和运行两种 probe。已安装则跳过 APT。依赖安装失败会原样停止，不下载浏览器、不回退 WebKit，也不会在普通启动时自动补包。

不带参数的安装命令现在默认选择 Chromium 128，并发布普通窗口及显式 Agent CDP 两个桌面条目。菜单中也有单独的“启动 Agent 浏览器”操作；它等价于 `omarchy-playwright-chromium --agent`。日常 `omarchy-chromium` 在 PRoot 转到固定 Chromium，Root/Chroot 保留原有宿主转发分支。缺少配套文件时请更新一体包恢复运行时。

隔离验证可通过 `OMARCHY_PLAYWRIGHT128_HOME`、`OMARCHY_NODE_HOME` 指定其他绝对目录；固定版本与来源检查仍然执行。`OMARCHY_PLAYWRIGHT128_RESULTS` 可指定报告目录。外部脚本的 CommonJS `require('playwright')` 会得到此入口提供的 NODE_PATH；如果项目自身另有 node_modules，应显式从 `$OMARCHY_PLAYWRIGHT128_HOME/node_modules/playwright` 加载所需 SDK。
