# Hermes 2237 真实 Agent + 已启动 Chromium CDP 验收

这是 guest 内手动执行的验收模板，保留了 K20 第 4 轮实际运行脚本。它固定针对 Hermes `2237be355906fbe6065ce1815711eee52b2d646e`、`qwen3.8-flash`、generic custom provider 与已启动的独立 Chromium 128 GUI/CDP profile。它不是任意模型、浏览器或设备的通用验收，也不会由 CI 自动发出模型请求。

第 4 轮由主任务设备操作者运行，正式回执位于 [k20-agent-20261123.json](../../docs/reports/evidence/k20-agent-20261123.json)：6 次真实 API 调用、5 次实际 CDP tool 完成，中文 DOM/输入/确认、默认目录唯一新文件的完整 55B 与 SHA、2340×1080 PNG 检查全部通过。主任务另行取回截图核 SHA 并目视核对。回执只保留公开模型/方法、布尔检查、计数、产物路径/尺寸/SHA；不包含 endpoint、key、设备标识或模型原始请求/回答。第 3 轮自定义目录的失败保持原判，见下文。

执行此脚本会调用所选模型 API 并控制该独立浏览器。应先明确授权本轮调用、准备独立的临时 Hermes profile 和专用 Chrome profile/CDP 端口，仅使用受控页面。不要指向日常浏览器或包含用户登录状态的 profile。仓库不包含模型配置、密钥、endpoint、用户备份、原始回答或截图。

将本目录 `acceptance.py` 同步至 guest `/tmp/hermes-agent-acceptance.py`。使用实际安装好的 Hermes venv；以下假设源码位于 `/root/.hermes/hermes-agent`，必须有完整依赖并且 Git HEAD 为 `2237be355906fbe6065ce1815711eee52b2d646e`：

```bash
env HERMES_HOME=/tmp/round2-hermes-acceptance \
  NO_PROXY="${NO_PROXY:-127.0.0.1,localhost}" \
  no_proxy="${no_proxy:-127.0.0.1,localhost}" \
  /root/.hermes/hermes-agent/venv/bin/python \
  /tmp/hermes-agent-acceptance.py \
  --agent-root /root/.hermes/hermes-agent \
  --output-dir /tmp/round2-hermes-agent-receipt-1 \
  --download-dir /root/Downloads \
  --budget-seconds 900
```

输出目录必须不存在，防止旧截图或 receipt 被误当作本轮产物。下载使用该独立 Chrome profile 的真实默认目录（本次为 `/root/Downloads`），每轮生成 `hermes-fixture-<UUID>.txt` 唯一文件名。开跑前必须不存在该文件或同名 symlink；结束时仅接受该精确路径的普通文件，并逐字节核对完整 55B 内容和 SHA256。脚本不修改浏览器下载偏好，不调用 `Browser.setDownloadBehavior`，不从其他路径搬运下载来凑过。原有下载不删除、不改名；本轮实际下载路径会写入新 receipt。

脚本成功返回 0，失败返回 1；最后只打印安全摘要和 receipt 路径，不打印请求、连接描述、配置内容或 key。它不会预先调用 CDP，也不会自行执行替代 Agent 的动作序列。页面服务、实际下载文件与截图回调只是独立验收。

`progress.json` 在每个阶段原子更新，并每 5 秒记录 heartbeat；包含经过时间、公开源码位置栈、callback 计数和 CDP 方法/状态/错误类别，不包含栈局部变量、原始响应、URL 或凭据。只有 `Browser.setDownloadBehavior` 的 callback 会额外记录枚举白名单内的 behavior、与预期目录完全一致的 downloadPath 或脱敏分类、路径是否匹配；其他参数和 context/target 标识不落盘，错误只保留固定类别。文本流 callback 写盘最多每秒一次。阶段区分 `agent-import`、`agent-init`、`agent-run-conversation`、产物检查与清理；`toolEvents` 同时记录 started/ok/failed。900 秒是 Hermes 的回合运行预算，不是初始化或阻塞网络请求的硬性进程期限。

模型可能每回合较慢。提示允许它自行将真实 DOM 读取、中文输入、确认和下载合并到一次 CDP evaluate，并直接创建指定 URL 的新页来减少回合；真实 Agent 的工具选择和全部独立通过条件保持不变。没有看到前台页面不能单独用来判定无进度，应先读取 progress 和工具状态。

独立 `HERMES_HOME` 由主任务准备，仅包含所选模型、自定义 provider 和对应 `.env`，browser 项需要：

```yaml
browser:
  backend: "off"
  allow_private_urls: true
```

`"off"` 表示使用原有 `browser_*` 工具，不是关闭全部浏览器；否则新版默认可能因 Browser Use/uvx 可用而切换到 `browser_exec`。CDP override 被上游视为非本地，所以受控本机 HTTP fixture 需要此 profile 内明确的 private URL 许可。脚本仅在当前进程设置 `BROWSER_CDP_URL=http://127.0.0.1:9222`，不改变主 profile。

本轮实际原配置只有 model 级 endpoint，`custom_providers` 是空列表，没有名为 qwen 的 custom entry。因此正确路由是 generic `provider: custom`；`custom:qwen` 只能用于确实存在的命名 entry，不能凭空添加该前缀。

保留原 `model.default=qwen3.8-flash`、`base_url`、`api_mode=chat_completions`、`key_env` 与对应 `.env`。模板先显式调用官方 `load_hermes_dotenv(hermes_home=..., project_env=None, load_external_secrets=False)`，再通过官方 `get_secret(model.key_env)` 在内存取得所选变量，传入 `resolve_runtime_provider(requested="custom", explicit_api_key=..., target_model="qwen3.8-flash")`。它核对 resolver 没有改用其他 endpoint、key 或 API mode。模板不解析、复制或打印密钥文件。

2237 的 generic resolver 本身不读取 `model.key_env`；普通 GUI/CLI 需要在原 key_env 之外添加一个字面量引用，使上游配置展开器将它绑定到标准字段。示意（保留实际 endpoint 和变量名字，不写明文 key）：

```yaml
model:
  default: qwen3.8-flash
  provider: custom
  base_url: <原 endpoint，不变>
  api_mode: chat_completions
  key_env: EXISTING_KEY_ENV
  api_key: "${env:EXISTING_KEY_ENV}"
```

`load_config()` 自身不加载 `.env`。它只合并配置、正规化和展开变量，也不会把已有 custom list 过滤为空；单独调用它看到 `keyLoadedInEnv=false` 不能说明 `run_agent` 的 import loader 没有执行。原模板失败的直接原因是配置没有命名 entry 却请求了 `custom:qwen`。

最小真实入口是 `run_agent.AIAgent(... enabled_toolsets=["browser-cdp"])` 后单次 `agent.run_conversation(user_message=...)`；模型自行选择实际 CDP 调用。只启用 `browser-cdp` 不需要安装 agent-browser/npm CLI。Python 侧需要官方 python-deps 阶段安装的 Agent 依赖，以及 raw CDP 的 `websockets`、HTTP discovery 的 `requests`。请用同一源码的完整 venv，不是仅手动装两个包的系统 Python。

通过条件同时要求真实 API 调用完成、中文 DOM 返回值、页面确认事件、精确默认目录中新文件的全部字节一致、PNG 截图存在且能读取尺寸。下载/截图单独保存 SHA256、字节数和尺寸。Agent 的最终文字不是通过依据。新 receipt 用 `downloadMode=profile-default-unique-filename` 区分本轮验证范围。

## 第 3 轮自定义目录失败的源码结论

第 3 轮真实 Agent 的 8 API / 8 tool 完成了中文 DOM、确认点击、下载服务响应与截图，但指定的 `/tmp/.../downloads/hermes-fixture.txt` 未出现，因此原 receipt 保持失败。实际文件在浏览器默认 `/root/Downloads/hermes-fixture.txt`，为完整 55B，SHA256 `64f3919c426249f095f0b323779de24928383d8265037112770917101fdab1ef`。这不是“下载完全不可用”，也不能把它改判为“自定义目录下载通过”。

精确版本的源码解释了该行为：

- Hermes [2237 browser_cdp_tool.py:173–204](https://github.com/NousResearch/hermes-agent/blob/2237be355906fbe6065ce1815711eee52b2d646e/tools/browser_cdp_tool.py#L173) 每次用 `async with websockets.connect(...)` 建立连接，收到单个响应后退出并关闭。其 [257–262 行](https://github.com/NousResearch/hermes-agent/blob/2237be355906fbe6065ce1815711eee52b2d646e/tools/browser_cdp_tool.py#L257) 明确称该路径为 fresh stateless connection。参数字典直接发送，未找到 downloadPath 改写。
- Chromium [128 DevToolsSession 析构与 Dispose:125–152](https://chromium.googlesource.com/chromium/src/+/128.0.6613.18/content/browser/devtools/devtools_session.cc#125) 在 session 清理时调用每个 domain handler 的 `Disable()`。
- 同版本 [BrowserHandler::Disable:66–84](https://chromium.googlesource.com/chromium/src/+/128.0.6613.18/content/browser/devtools/protocol/browser_handler.cc#66) 将覆盖过下载设置的 context 恢复为 `DEFAULT`。设置操作本身在 [429–486 行](https://chromium.googlesource.com/chromium/src/+/128.0.6613.18/content/browser/devtools/protocol/browser_handler.cc#429) 登记这些 context。

因此不能依赖一次 `Browser.setDownloadBehavior` 调用对后续 stateless tool 保持下载目录。若未来需要该能力，应另做持久连接或受控启动 profile 偏好的验证；本轮不修改 Hermes vendor 连接策略。当前模板只验证默认目录的真实下载。第 4 轮通过的是新真实 Agent 的完整回合及其唯一文件，不是复用或搬运第 3 轮下载；第 3 轮 receipt 和失败证据未改动。

官方固定来源：

- [AIAgent 构造与入口](https://github.com/NousResearch/hermes-agent/blob/2237be355906fbe6065ce1815711eee52b2d646e/run_agent.py#L233)
- [实际 callback 参数](https://github.com/NousResearch/hermes-agent/blob/2237be355906fbe6065ce1815711eee52b2d646e/agent/tool_executor.py#L941)
- [generic custom 的 endpoint、key 与 API mode 解析](https://github.com/NousResearch/hermes-agent/blob/2237be355906fbe6065ce1815711eee52b2d646e/hermes_cli/runtime_provider_backends.py#L112)
- [官方 dotenv loader](https://github.com/NousResearch/hermes-agent/blob/2237be355906fbe6065ce1815711eee52b2d646e/hermes_cli/env_loader.py#L311)
- [CDP env/config 优先级与 HTTP discovery](https://github.com/NousResearch/hermes-agent/blob/2237be355906fbe6065ce1815711eee52b2d646e/tools/browser_tool_cdp.py#L48)
- [CDP 方法参数与结果](https://github.com/NousResearch/hermes-agent/blob/2237be355906fbe6065ce1815711eee52b2d646e/tools/browser_cdp_tool.py#L256)
- [Browser Use 默认选择](https://github.com/NousResearch/hermes-agent/blob/2237be355906fbe6065ce1815711eee52b2d646e/tools/browser_use_cli.py#L201)
- [CDP override 的 SSRF 边界](https://github.com/NousResearch/hermes-agent/blob/2237be355906fbe6065ce1815711eee52b2d646e/tools/browser_tool_cloud.py#L157)

在配置加入上述标准变量引用后，也可用官方 CLI 做非交互单次调用：`hermes chat --provider custom -m qwen3.8-flash -t browser-cdp --oneshot -Q --query-file PROMPT.txt`。CLI 的 API 失败返回非零，但完整 artifact receipt 仍需本模板的独立核对，不能只信最终回答。

## 离线检查与留存

无需模型、网络或设备的本地检查：

```sh
PYTHONDONTWRITEBYTECODE=1 python3 -m unittest discover -s experiments/hermes-cdp-acceptance -p 'test_*.py' -v
```

只检查文件选择、防旧文件误判、参数脱敏和独占写入；不会导入 Hermes 或执行真实 Agent。新设备/版本仍须单独运行上述手工验收，已留存的 K20 回执不能代替新回归。

发布时脚本 SHA256：`d2eb8ef26cebc96b2ecaec8c79efe4765f1220ec3dba8a41375a444469bd0327`。
留存的第 4 轮原回执 SHA256：`38caceb299aeb7cdb8f2d0e5bec187b9634783037b246ef2b056c58af90b28cf`（逐字节复制，未改判）。
