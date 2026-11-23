# Omarchy Android 工作交接（2026-11-23）

## 来源与当前仓库

已读取用户指定的 Antigravity 会话 `66d045c2-c241-4c0f-b656-a5fc13d42d92`，最后一条回复为 step 2746（2026-11-23 12:00，北京时间），并只读核对了 GitHub Actions 的实际结果。历史对话中的建议和发布询问只作为进度证据，不视为新的执行授权。

当前统一开发目录为 `omarchy-android`，迁移 [PR #1](https://github.com/Audrey-cn/omarchy-android/pull/1) 已合入 `main`，后续统一从 `main` 开发。合并实现提交 `0bf736f`，通过 `a637d8e` 导入 App 完整主分支历史；桌面核心基线 `68d6dfc`、App 基线 `2792678` 均已包含。

原独立 App 目录已不在原路径；源码和历史保存在统一仓库中，后续构建不依赖旁边存在另一个目录。

## 当前接手进展

- 最终产品提交 `e876b3b` 的[一体包云构建](https://github.com/Audrey-cn/omarchy-android/actions/runs/45120464556)、壳包、Deck 和 37 项检查均成功；迁移 PR 已合入 `main`，保留两个原仓主分支历史。
- 最终 APK 已在 K20 Pro 清洁安装，自动初始化、桌面、引导、冷启动、中文输入、1.5 倍缩放、DNS、横竖屏及键盘避让通过。原始数据与原 APK 的验证备份保留在本机持久目录。
- Hermes 原生 binding 重建和真实 PTY 通过；Firefox 的 headless、Wayland 完整 fixture 与 GUI 通过；CC Switch 首次数据库初始化和主窗口通过。
- Codex/Chromium 的共享内存兼容仍未通过；Hermes 需完成后端接入，首次设置等待与连接超时冲突也未修复。模型请求、实际 Agent 工具链、K90 和正式 Release 不列为已验收。

产物校验、运行 ID 与具体边界见 [K20 Pro 云构建和验证记录](reports/cloud-k20-validation-20261123.md)，原生阻塞见[诊断报告](reports/codex-native-startup-20261123.md)。

## 已确认的产品方向

- 免 Root 一体 APK 为主要产品路线，首次使用自动初始化并进入桌面；保留外部 Termux/Root 运行时与 Deck 辅助应用。
- 设备验证优先 K20 Pro；K90 Pro Max 的安装确认过程较长，留作后续兼容性验证。
- 使用 Linux 桌面内部的 WebKitGTK 4.1 轻量浏览器，不让桌面 WebApp 跳到 Android 外部浏览器。
- 默认 WebApp 只预装 Bilibili、DeepSeek；保留核心原生工具，隐藏无关系统入口。更多 AI 工具按需安装。
- 默认 1.5 倍缩放；四步可跳过的箭头引导覆盖显示缩放、悬浮手柄、Super+K 与豆包/权限设置。
- 悬浮手柄可拖动、贴边折叠，键盘展开时仍保留边缘入口；键盘采用底部拖动条与透明度调节。
- 设置入口统一包含 X11 与输入法设置；应用内使用 Omarchy Android 品牌，同时保留开源许可与致谢。
- 顶栏左右对称内缩，提供方屏 6px、标准圆角 18px、大圆角 28px 切换。

## Antigravity 停止时的位置

v7 的旧构建 `45106931412` 已下载并安装到 K20 Pro，记录中有安装成功、应用网格与 DeepSeek 桌面内窗口的截图证据。随后发现容器沿用了构建机的 `127.0.0.53` DNS，于设备中临时修复，并将镜像内公共 DNS 修复提交为 `2792678`。

会话最后仍在等待 `2792678` 的云端构建；助手提出发布 v1.0 与同步安装 K90，但后面没有用户确认或执行结果。不能把这些建议记成已完成。

## 接手时发现的功能缺口（Antigravity 基线）

下表保留首次接手时的历史证据与缺口，不描述当前安装器状态。接手后已新增独立 Hermes 原生修复、Agent 浏览器安装与测试，并继续定位 Codex 等问题；当前结论见[本轮验证记录](reports/cloud-k20-validation-20261123.md)。

| 项目 | 当时已有证据 | 当时待处理或待验 |
| --- | --- | --- |
| CC Switch | steps 2612/2614：安装 4.0.39 返回 0，日志记录主窗口显示 | 同场有 DNS/更新请求失败，模型服务未完成端到端验收 |
| Hermes Desktop | step 2634：安装返回 0 | steps 2636/2644/2646 均报缺少 ARM64 `pty.node`，启动未通过；接手时安装器仅在已有 Claude 的原生模块时尝试借用 |
| Codex Desktop | 早期外部 Termux/Root 路线有测试记录 | 后期独立免 Root APK 中没有找到成功安装、窗口或交互验收，不沿用另一条路线的结果 |
| Playwright / Agent 浏览器 | 用户 step 2680 提出需求，step 2683 讨论兼容方案 | 未找到安装或自动化执行验收；接手时安装器没有 Playwright 分支，不能把 WebKitGTK 窗口能打开等同于 Agent 浏览器已可用 |
| 浏览器安装菜单 | 接手时 `core/bin/omarchy-installer.sh` 仍有旧入口 | Chromium 项仍宣称 158 已预装；Firefox 项实际安装 Epiphany，与最终轻量浏览器方案需要统一 |

历史助手对浏览器兼容性、速度与内存的推断不作为当前验收证据；需在目标免 Root APK 中分别验证。

## 接手时独立核实的旧仓云构建

APK 一直使用 GitHub Actions 构建；本机没有完整 Android 工具链不影响采用这条路线。rootfs 使用 `ubuntu-24.04-arm`，APK 使用 `ubuntu-latest`。

| 产物 | 运行与提交 | 结果（北京时间） |
| --- | --- | --- |
| 旧 App 仓 rootfs + 一体 APK | [45109234902](https://github.com/Audrey-cn/omarchy-android-app/actions/runs/45109234902)，`2792678` | 两个 job 均成功，11/23 12:06:46 完成 |
| 旧 App 仓普通 APK 壳 | [45109234923](https://github.com/Audrey-cn/omarchy-android-app/actions/runs/45109234923)，`2792678` | 成功，11/23 12:02:44 完成 |
| Deck 辅助 APK | [45042723565](https://github.com/Audrey-cn/omarchy-android/actions/runs/45042723565)，`85715ca` | 成功，11/23 00:15:01 完成 |

首次接手查询时，一体包运行有未过期的 `omarchy-ubuntu24-arm64-container` 和 `OmarchyAndroid-AllInOne-apk` artifacts。当时两个远端仓库均无正式 GitHub Release，解释了历史会话中的空发布页；该查询不代替后续发布状态。

旧 App 仓库先前有 API 36/37 不匹配、缺失 Ubuntu 包、Meson c23/参数和 libinput 版本等构建失败，后续成功运行已越过这些问题。合并没有降级现有工具链或运行栈。

## 后续验证顺序

1. 保留最终产品提交 `e876b3b` 和云运行 `45120464556` 的对应关系；该版本四项云工作流已成功，完整校验值见验证报告。
2. 最终一体包已在 K20 Pro 清洁安装并自动进入桌面，原首启 NPE 未重现；后续发布继续区分一体包与普通壳包。
3. 对最终 APK 回归桌面进入、DNS/WebApp、应用网格、真实中文输入、横竖屏和键盘避让、引导/设置、圆角边距，以及已修复的原生模块和浏览器入口。基线 guest 中部署开发脚本的验证与最终 APK 验证分别记录。
4. 补齐 Agent 浏览器与 AI 客户端尚未通过或未配置的项目，再做 K90 兼容性验证。具体通过项和未通过项持续记录在[本轮验证报告](reports/cloud-k20-validation-20261123.md)。
5. Actions 构建产物与 GitHub Release 分开管理；本交接不把构建成功或发布建议记成正式发布完成。

首次接手时仅完成历史与远端状态读取、文档整理，合并分支当时尚未推送。此后已推进提交、推送、合并版云构建和 K20 实机验证；`e876b3b` 四项云工作流已成功，最终 APK 在 K20 Pro 的清洁初始化、中文输入和横竖屏/键盘避让已通过；各工具的实际边界见本轮验证报告。
