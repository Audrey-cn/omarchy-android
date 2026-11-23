# Omarchy Android 工作交接（2026-11-23）

## 来源与当前仓库

已读取用户指定的 Antigravity 会话 `66d045c2-c241-4c0f-b656-a5fc13d42d92`，最后一条回复为 step 2746（2026-11-23 12:00，北京时间），并只读核对了 GitHub Actions 的实际结果。历史对话中的建议和发布询问只作为进度证据，不视为新的执行授权。

当前统一开发目录为 `omarchy-android`，本地分支 `codex/unify-android-monorepo`。合并实现提交 `0bf736f`，通过 `a637d8e` 导入 App 完整主分支历史；桌面核心基线 `68d6dfc`、App 基线 `2792678` 均已包含。

原独立 App 目录已不在原路径；源码和历史保存在统一仓库中，后续构建不依赖旁边存在另一个目录。

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

## 仍需处理的功能缺口

| 项目 | 已有证据 | 待处理或待验 |
| --- | --- | --- |
| CC Switch | steps 2612/2614：安装 4.0.39 返回 0，日志记录主窗口显示 | 同场有 DNS/更新请求失败，模型服务未完成端到端验收 |
| Hermes Desktop | step 2634：安装返回 0 | steps 2636/2644/2646 均报缺少 ARM64 `pty.node`，启动未通过；当前安装器仅在已有 Claude 的原生模块时尝试借用 |
| Codex Desktop | 早期外部 Termux/Root 路线有测试记录 | 后期独立免 Root APK 中没有找到成功安装、窗口或交互验收，不沿用另一条路线的结果 |
| Playwright / Agent 浏览器 | 用户 step 2680 提出需求，step 2683 讨论兼容方案 | 未找到安装或自动化执行验收；当前安装器没有 Playwright 分支，不能把 WebKitGTK 窗口能打开等同于 Agent 浏览器已可用 |
| 浏览器安装菜单 | 当前 `core/bin/omarchy-installer.sh` 仍有旧入口 | Chromium 项仍宣称 158 已预装；Firefox 项实际安装 Epiphany，与最终轻量浏览器方案需要统一 |

历史助手对浏览器兼容性、速度与内存的推断不作为当前验收证据；需在目标免 Root APK 中分别验证。

## 云端构建：已独立核实

APK 一直使用 GitHub Actions 构建；本机没有完整 Android 工具链不影响采用这条路线。rootfs 使用 `ubuntu-24.04-arm`，APK 使用 `ubuntu-latest`。

| 产物 | 运行与提交 | 结果（北京时间） |
| --- | --- | --- |
| 最新 rootfs + 一体 APK | [45109234902](https://github.com/Audrey-cn/omarchy-android-app/actions/runs/45109234902)，`2792678` | 两个 job 均成功，11/23 12:06:46 完成 |
| 最新普通 APK 壳 | [45109234923](https://github.com/Audrey-cn/omarchy-android-app/actions/runs/45109234923)，`2792678` | 成功，11/23 12:02:44 完成 |
| Deck 辅助 APK | [45042723565](https://github.com/Audrey-cn/omarchy-android/actions/runs/45042723565)，`85715ca` | 成功，11/23 00:15:01 完成 |

一体包运行当前有未过期的 `omarchy-ubuntu24-arm64-container` 和 `OmarchyAndroid-AllInOne-apk` artifacts。两个远端仓库均尚无正式 GitHub Release；发布页空白有这个实际原因。

旧 App 仓库先前有 API 36/37 不匹配、缺失 Ubuntu 包、Meson c23/参数和 libinput 版本等构建失败，后续成功运行已越过这些问题。合并没有降级现有工具链或运行栈。

## 后续验证顺序

1. 将统一仓库的迁移分支送入 GitHub Actions，验证新的目录和同仓核心输入；旧仓 `2792678` 成功不能替代合并版云端验证。
2. 统一仓库的 feature 分支单独 push 不会触发当前仅面向 main 的 push 过滤；可通过面向 main 的 PR 触发新工作流。手动 dispatch 需要该 workflow 已在默认分支注册，再选择目标 ref。
3. 从新的 `build-container.yml` 运行下载 `OmarchyAndroid-AllInOne-apk`；普通壳包不能代替包含 rootfs 的一体包。
4. 优先 K20 Pro 验证首次初始化、DNS/WebApp、应用网格、中文/语音穿透、横竖屏和键盘避让、引导/设置与圆角边距；保留安装版本和运行 ID。
5. 补齐 Agent 浏览器与 AI 客户端的待验项，再做 K90 兼容性验证。发布 v1.0 属于后续发布步骤，当前尚未执行。

本次交接只读取历史和远端状态、整理文档；合并分支尚未推送，也未触发新云端运行。
