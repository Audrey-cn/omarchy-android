# 图形栈与浏览器配套维护

图形与浏览器各发布一个 Ubuntu Noble ARM64 Debian 包。版本、来源提交、源码校验值、包长度、包 SHA-256 和依赖列在每个包同名的 JSON 附件中。K20 候选经实机验收后可发布为 `runtime-vX.Y.Z`。用户执行组件更新时才会下载安装。命令先匹配 Release 附件，再核对独立元数据与实际字节，检查 Ubuntu、架构和运行时兼容性。

```sh
omarchy-runtime-update check graphics
omarchy-runtime-update install graphics
omarchy-runtime-update recover graphics
omarchy-runtime-update check browser
omarchy-runtime-update install browser
omarchy-runtime-update recover browser
```

离线验收可向 check 或 install 传入 `--candidate-dir DIR --metadata-sha256 SHA256`。从已经认证的 CI 产物单独取得 JSON 摘要。每个组件在目录里放一个版本的 JSON 及其对应的 DEB。受管文件已被修改、文件与其他 Debian 包冲突、哈希错误或运行环境不兼容时，输出具体原因。安装中断后，recover 使用上一次替换前的本地快照。恢复前还会识别安装后修改的文件。该快照仅覆盖这个组件。Chromium 用户资料和 Hermes 模型配置均在载荷范围之外。

图形包在 `/opt/omarchy-graphics` 安装 Wayland 1.23.1、wayland-protocols 1.38、libinput 1.26.2、wlroots 0.18.2、Sway 1.10.1，构建保留了 Android X11 的 SHM/Pixman 路径。四个公共入口 sway、swaymsg、swaybar、swaynag 经已知校验值匹配后切换。既有的系统共享库保留给原有程序。浏览器包在已有专用的 `/opt/omarchy-chromium128` 和 `/opt/omarchy-node` 发布完整的 Chromium 128.0.6613.18、Playwright 1.46.1 和 Node 24.15.0 组合。[图形源码锁](../scripts/graphics-sources.json) · [浏览器源码锁](../scripts/browser-bundle-sources.json)

CI 在 Ubuntu ARM64 上实际安装包并采集运行时 ELF 链接到的系统库、Ubuntu 所属包及版本，随产物生成 graphics-linkage.json 与 browser-linkage.json。glibc、NSS、X11、Pixman、Pango/Cairo 等共享依赖继续通过 Ubuntu 更新。包的 Depends 记录运行需要的库；到期升级应重跑加载和版本测试，并在 K20 复测桌面输入、横竖屏、恢复、网页、下载、截图、CDP 和真实 Agent。出现 ABI 回归时才针对已证实的包添加局部版本约束，记录回归和解除条件。

Chromium 128.0.6613.18 早于同分支多个已发布安全修复。例如 Chrome Linux 128.0.6613.119 公告包含 WebAudio 修复 CVE-2024-8362。本次重新打包沿用已测三件套，仍有浏览器安全更新待做。维护者应跟踪 Chromium 安全公告、Playwright 的浏览器版本注册表和 Node 公告，选择匹配的新组合，更新源码锁、配套启动检查与变更说明后，走独立 CI 和实机验收。当前固定版本仅表示兼容记录。[Chrome 安全修复公告](https://chromereleases.googleblog.com/2024/09/stable-channel-update-for-desktop.html) · [Playwright 浏览器注册表](https://github.com/microsoft/playwright/blob/main/packages/playwright-core/browsers.json) · [Node 安全公告](https://nodejs.org/en/blog/vulnerability)

从根目录构建时，先按 browser-bundle-sources.json 运行 scripts/build-browser-bundle.py，再将输出的 managed-core/files 交给 scripts/runtime-component.py browser。图形包由 scripts/build-graphics-runtime.sh 在一次性 Ubuntu 24.04 ARM64 环境构建，执行前需要 root。两个构建脚本的参数及校验可参见独立 CI。[组件 CI](../.github/workflows/build-components.yml)
