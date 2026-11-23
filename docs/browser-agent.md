# 浏览器与 Agent 接入

PRoot 一体应用现在选择固定 **Chromium 128.0.6613.18 / revision 1129 + Playwright 1.46.1 + Node 24.15.0**，用于日常网页、WebApp 和默认 Agent 浏览器。原有 Playwright 1.65.1 Firefox 保留按需回退，WebKitGTK 也保留独立入口。Root/Chroot 的 `omarchy-chromium` 仍走原宿主转发分支。

这一选择基于 K20 Ubuntu 24.04 ARM64 guest 的真实测试。新入口和内置资源的最终云编译、固定签名 APK 安装及冷启动回归仍待完成，不能把已部署源码的 guest 测试写成最终 APK 验收。最新事实见[第二轮收口记录](reports/k20-round2-20261123.md)；固定资源、参数边界与 Hermes 配置见[Chromium 128 说明](chromium128.md)。

## 已取得的证据

| 检查 | 结果与边界 |
| --- | --- |
| 固定 Chromium 128 + Playwright 1.46.1 headless 探针 | 完整通过：导航、DOM、中文填值/点击、cookie、截图、浏览器下载与内容核对 |
| 同组合 Wayland 探针 | Sway 全屏前台下使用原探针参数完整通过，含 fullPage 截图和 trace；后台/画中画时的截图超时保留为环境边界 |
| 真实 Chromium GUI 与 CDP | 独立图形窗口、loopback 9222、连接现有实例、公网页面及受控中文表单/截图均通过 |
| Hermes Agent | 官方 agent commit `2237be355906fbe6065ce1815711eee52b2d646e`，原选定 generic custom 模型 `qwen3.8-flash` / `chat_completions`；最终受控任务 6 次真实 API、5 次真实 CDP 工具调用，中文 DOM、点击、新下载文件/SHA 与 PNG 均通过，截图已人工查看 |
| Playwright Firefox 159 / revision 1572 | 先前基线与 `e876b3b` APK 的双模式完整探针及独立 GUI 通过；作为按需回退保留，不能据此代替 Chromium/CDP 的测试 |
| 最终集成 APK | 云编译、内置固定资源部署、固定签名 K20 安装/升级和冷启动回归仍待验收 |

Qwen 单独 HTTP 200、Electron 子进程存在、读取浏览器版本或仅出现窗口，都不能替代上述真实 Agent 任务。模型配置、key 和私人数据不随仓库或 APK 发布。

本轮切换运行时后出现的 Wayland `SIGABRT` 已定位到**键盘状态**：短命 `wtype` 虚拟键盘退出后，Sway 的新 client 收到 keyboard.enter/modifiers，却没有 keymap；Chromium 128 随后在 `xkb_state_update_mask` 对空状态访问并终止。实机多次 `wtype -k Tab` 可重现，同值重发真实 X11 键盘当前 repeat_delay 触发 reconfigure 后，原 stock Wayland 完整探针通过；没有修改 layout/options，也没有据此添加 Mesa、GPU 或 native shim 绕过。

修复由 `omarchy-restore-x11-keyboard` 读取并重发当前设置、保留活动 layout index；`wtype` 包装器在退出后恢复，Chromium GUI、Wayland probe 和 Hermes GUI 在新 client 启动前再次恢复。helper 存在但失败时明确停止；headless probe 不更改当前键盘。最终包含此恢复链的 APK 仍需按第二轮记录完成实机回归。

历史 Chromium 159 的失败环境、Firefox 原始结果及 Codex 捆绑 Chromium 158 的只读共享内存诊断分别保留在[首轮云编译与 K20 记录](reports/cloud-k20-validation-20261123.md)和[原生启动诊断](reports/codex-native-startup-20261123.md)。不同 Chromium 版本不能仅凭同样的 SIGTRAP 判定为同一处根因，也不能由 128 通过推定其他版本已修复。

## 日常浏览与显式 Agent 模式

```bash
# 日常窗口和 WebApp：PRoot 使用固定 Chromium 128，不开放 CDP
omarchy-chromium https://example.com
omarchy-chromium --app=https://example.com

# 显式 Agent 专用窗口，独立持久 profile，仅监听 127.0.0.1:9222
omarchy-playwright-chromium --agent https://example.com
```

普通 profile 位于 `$HOME/.local/share/omarchy-chromium128/desktop/`；Agent profile 位于同级 `agent/`。`--agent` 必须是第一个参数。调用方不能覆盖 CDP 地址、端口、profile 或图形模式；参数以原始 argv 传递，支持中文 URL、查询字符串和 `--app=URL`。入口不增加 GPU 绕过参数。

浏览器菜单默认选择内置 Chromium 128，并提供独立的“启动 Agent 浏览器（Chromium 128，CDP）”操作。安装/自检通过后，应用网格分别提供普通 Chromium 与 Agent CDP 条目。配置 Hermes 的连接地址本身不会启动浏览器或开放 CDP。

WebKitGTK 的独立入口仍为 `omarchy-browser`。Chrome、Brave、Edge、Zen 等其他品牌没有对应适配，不将一个引擎冒充为其他品牌。

## 固定资源与检查

| 组件 | 版本和目录 |
| --- | --- |
| Chromium | `128.0.6613.18` / rev `1129`，`/opt/omarchy-chromium128/browsers/chromium-1129/chrome-linux/chrome` |
| 配套 Playwright | `1.46.1`，`/opt/omarchy-chromium128/node_modules` |
| 共用 Node | `24.15.0`，`/opt/omarchy-node/node-v24.15.0-linux-arm64` |
| Firefox 回退 SDK/cache | Playwright `1.65.1`，`/opt/omarchy-playwright` |

固定入口只读取配套资源，不自动下载或升级。Android managed 部署校验每个 payload 文件的 SHA256；启动入口另核来源清单、版本配对、revision 与实际执行路径。缺少文件或版本不符时，请更新 Omarchy Android 一体包恢复配套运行时。

两种 Android APK workflow 已接入固定 managed payload 构建，资源不再重复复制进 rootfs；最终产物和实机回归仍以第二轮记录为准。

```bash
omarchy-playwright128 check
omarchy-playwright128 cli --version
omarchy-playwright128 browser-path chromium
omarchy-playwright128 probe chromium headless
omarchy-playwright128 probe chromium wayland

# 检查并创建桌面条目：两种 probe 都成功才发布
omarchy-install-playwright-browser
# 等价的显式选择
omarchy-install-playwright-browser chromium
```

两种模式复用同一完整 probe，失败保留非零退出码和报告。Wayland probe 需要桌面环境并应在全屏前台运行。报告位于 `$HOME/.local/state/omarchy/playwright128/`，含 `result.json`、成功时的 PNG、下载文件及 trace；可用 `OMARCHY_PLAYWRIGHT128_RESULTS` 改报告目录。

官方固定版本来源见 [Playwright 1.46.1 browsers.json](https://github.com/microsoft/playwright/blob/v1.46.1/packages/playwright-core/browsers.json)及[平台下载 registry](https://github.com/microsoft/playwright/blob/v1.46.1/packages/playwright-core/src/server/registry/index.ts)。归档哈希与完整来源记录在仓库 `scripts/browser-bundle-sources.json` 和安装后的 `source-manifest.json` 中。历史 steps 2680/2683 提出的 120–132 范围并未逐版本验收，本轮只确定上表这组固定组合。

## Hermes Desktop 的连接配置

先显式打开 Agent 专用浏览器，再建立新的 Hermes GUI 会话。保留原模型配置，主 profile 的浏览器部分为：

```yaml
browser:
  backend: "off"
  cdp_url: "http://127.0.0.1:9222"
  allow_private_urls: false
```

`"off"` 选择 Hermes 原有 `browser_*` 工具，避免自动切成 Browser Use CLI。固定 agent commit 的 Desktop 默认工具集与 coding posture 已包含 `browser_cdp`，没有显式工具限制时无需额外覆盖 toolsets。受控本机 HTTP fixture 使用独立临时 profile 开启 private URL 许可；主 profile 保持关闭。

本轮原选定配置没有命名 custom provider entry，使用的是 model 级 endpoint。对应路由为 `model.provider: custom`，保留原 `default`、`base_url`、`api_mode` 与 `key_env`。该版本 generic resolver 不直接读取 model.key_env；普通 GUI/CLI 可通过 `model.api_key: "${env:原变量名}"` 的字面引用绑定已有 `.env`，不把明文 key 写入配置或日志。

**下载路径限制：** 该版本 `browser_cdp` 的普通调用使用一次性 WebSocket。本轮虽然 `Browser.setDownloadBehavior` 返回成功，文件仍落在浏览器默认 `$HOME/Downloads/`，并使用 UUID 名称，而不是提示中的目录。最终验收依据运行前文件不存在、新文件内容/55 字节/SHA 匹配及截图，不仅依据工具返回 success。不要承诺 `setDownloadBehavior` 在这种逐次连接模式下能持久固定下载目录；需要固定路径的上层 Agent 应按其连接生命周期另行实现和验收。

## Firefox 按需回退与其他 Agent

```bash
omarchy-install-playwright-browser firefox
omarchy-playwright probe firefox headless
omarchy-playwright probe firefox wayland
omarchy-playwright-firefox https://example.com
```

这条路线继续使用独立 Playwright 1.65.1 与其专用 Firefox，保留原 persistent context、Wayland 环境和初始 URL 失败时保留窗口的行为。Mozilla DEB Firefox 仍是另一种独立安装，不替代 Playwright 的专用 Firefox。WebKit 尚需独立完成同等级验收。

CommonJS Agent 脚本可用 `omarchy-playwright128 run /absolute/path/script.cjs` 加载固定 SDK；入口设置 NODE_PATH，项目自身已有 node_modules 时应显式加载固定目录。ESM 使用它自己的模块查找规则。`browser-path` 仅输出实际二进制，不会启动 CDP；仅支持 Chromium/CDP 的 Agent 也不能直接换成 Firefox。

每次更改镜像、PRoot、运行模式或浏览器组合后，分别验证 headless、GUI/CDP、真实 Agent、中文输入、实际下载与截图。DOM 填值证明不了人工输入法可用；最终 APK 的安装/升级与冷启动仍是独立回归。
