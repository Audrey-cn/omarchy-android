# 更新维护交付与 K20 Pro 验收

日期为 2026-11-24。本轮将 APK、guest 桌面组件、两个独立运行时和初装镜像分别构建。以下产物表记录独立构建与补丁版本。安装状态、用户数据保留和发布状态以各自证据为准。

## 构建与产物身份

| 内容 | 来源提交 | Actions 运行 | 产物 |
| --- | --- | --- | --- |
| Android 一体 App | `26e9c02ff3d6133be919806dfc80c0bfc4920f8e` | [45203584927](https://github.com/Audrey-cn/omarchy-android/actions/runs/45203584927) | versionCode 79，`1.0.79-test.g26e9c02ff3d6`，可信 main 的持久签名 |
| 桌面、浏览器和图形配套 1.0.0 | `fae53dc4f5338ba114aca85ed1b5ac778ecb34e0` | [45204787282](https://github.com/Audrey-cn/omarchy-android/actions/runs/45204787282) | 完整组件构建，ARM64 运行时链接检查与三组载荷汇总校验通过 |
| 初装镜像 `desktop-ubuntu24-91a120142609` | `91a120142609bce64c5ceba5dd7ed8ee94fe2fcd` | [45207010017](https://github.com/Audrey-cn/omarchy-android/actions/runs/45207010017) | 从已验证的 1.0.0 组件构建组装，镜像已公开 |
| 桌面组件 1.0.1（验收中间版） | `85c2cfe3c9a5aa851825115340355d5c0e987de9` | [45212792268](https://github.com/Audrey-cn/omarchy-android/actions/runs/45212792268) | 单独构建和校验桌面包，运行时继续使用 1.0.0 |
| 桌面组件 1.0.2（验收中间版） | `92c2ba472e3994d71920df194c82e8f259db7445` | [45219071023](https://github.com/Audrey-cn/omarchy-android/actions/runs/45219071023) | Root 勿扰与 Mako 重载的组件修正，独立桌面构建通过 |
| 桌面组件 1.0.3 | `4937e8e53a3afc1fec7e5702ccefe402c30703ce` | [45222829166](https://github.com/Audrey-cn/omarchy-android/actions/runs/45222829166) | Root Chromium 启动器选择 GTK4，独立桌面构建与包校验通过 |

| 产物 | 字节 | SHA-256 |
| --- | ---: | --- |
| APK 79 | 33,756,080 | `eafbb5b2a7cd6fd7f469e183d0bc28ba0d5c44e3c71d7c20d7bdd1dde83732a6` |
| `omarchy-android-desktop_1.0.0_all.deb` | 91,476 | `41eefcb208166080cf0ba375d8ee76a072f7d3a8ea2258b09ed35a2e363c6087` |
| `omarchy-browser-runtime_1.0.0_arm64.deb` | 164,888,928 | `4b111f3e94d2d48cd7d4cd8296d25c16c5cafafbc6726cdba6e3354bc2f63596` |
| `omarchy-graphics-runtime_1.0.0_arm64.deb` | 6,172,186 | `febc309fdf283a88cc53f3e12e43327bf526bf702e5b8185d18d9167a9d0d876` |
| `rootfs.tar.zst`（`desktop-ubuntu24-91a120142609`） | 904,766,052 | `7e055fc088210b25476c78e3567aebd8358126fa1fcd5dc94a2442b45512afaf` |
| `omarchy-android-desktop_1.0.1_all.deb` | 92,084 | `7fdb7c18bf933bebfd890140dcb83aeafa9fea222ae2f0b8bbc2ede5259ecb49` |
| `omarchy-android-desktop_1.0.2_all.deb` | 92,784 | `132274fdcc10e4910b5238e83f7a45e4da64a7641439446507c2918229d31b0c` |
| `omarchy-android-desktop_1.0.3_all.deb` | 92,926 | `09501bbdf737c4348201dc510381efba0975087b61366d2fe1c1fe0acb7f46c0` |

APK 的签名证书 SHA-256 为 `bcb2f64501107f9425a0efe9193f3703acddb6e34daa54196978de7720c86c17`。桌面 1.0.1 的 `desktop-component.json` SHA-256 为 `516bfc9e4a1a0536280fcb33a0bb42a9cee7cd802df0371a1b6114a73702e38a`。桌面 1.0.2 的清单 SHA-256 为 `40c5455d1cb94be7302d3cf1a86caa99d886b1ddabf2eaaaddfca9c4a9e9084c`。桌面 1.0.3 的清单 SHA-256 为 `67688de07847e9dd8e803090512a3cd21aaef93f62cdb61e2739be016bdeb958`。各组件工作流提供 `SHA256SUMS` 与 `release-verification.json`。

镜像的逻辑解压大小为 2,620,243,891 字节。`source.json`、`image.json` 和目录条目分别将组装提交记录为 `sourceSha`、组件构建提交记录为 `componentSourceSha`。镜像仍含桌面 1.0.0。为保持 APK 64 起的初装检查兼容，镜像中的 `omarchy-chromium` 和 `omarchy-playwright128` 两个入口保留为普通文件，内容来自同一桌面包。新用户安装后可在桌面内显式安装 1.0.3。初装镜像保持为 1.0.0 固定快照；桌面启动器修正使用独立更新，沿用原 APK、镜像与原生运行时构建。当前仓库的 Root 首装脚本会将本次 checkout 的启动器写入它新建的受管 guest。

## 源码与 CI 覆盖

- APK 保持原应用签名；正常启动路径保留 guest。Ubuntu APT、项目桌面、浏览器、图形和第三方应用由各自渠道进行状态检查。
- 桌面包使用 `/usr/lib/omarchy-android/desktop`，浏览器/Node 和图形运行时使用专属 `/opt` 路径。组件校验读取实际 DEB 控制及载荷，核对组件身份、依赖、文件归属和来源。独立桌面构建还检查范围中是否混入其他包。
- 可信 main 的 Linux 综合回归通过 153/153 项，覆盖真实 `dpkg` 和 Linux 子进程回收。运行记录为 [45210551268](https://github.com/Audrey-cn/omarchy-android/actions/runs/45210551268) 和 [45212792268](https://github.com/Audrey-cn/omarchy-android/actions/runs/45212792268)。桌面 1.0.2 和最终 1.0.3 的综合回归各通过 155/155 项。原生包兼容性检查和镜像封装由各自产物工作流覆盖。K20 的桌面行为和同负载比较由实机验证记录。

## K20 实机证据

| 范围 | 设备证据与状态 |
| --- | --- |
| 正式 App 覆盖升级 | 使用持久签名 APK 79 覆盖安装 72。正式 PRoot guest 先安装桌面 1.0.1，再安装桌面 1.0.2。最终安装桌面 1.0.3 和浏览器、图形运行时 1.0.0。5 个个人文件在维护前后的 SHA-256 相同。真实浏览器 Agent 验收通过。 |
| 新镜像首次安装 | 使用 APK 79 的隔离验收 App 通过原生 SAF 完整导入新镜像，实际检测桌面、浏览器及图形包均为 1.0.0，Chromium 128 可用。Sway 输出为横屏 1560×720，现场截图显示壁纸和 Waybar 出现后才显示引导。验收 App 已卸载，保留正式 App。 |
| Root chroot 冷安装 | 在独立目录导入旧公开初装镜像，随后完成桌面 1.0.0 → 1.0.1 以及浏览器、图形运行时 1.0.0 安装。Root 图形桌面已就绪并安装桌面 1.0.2。Mako 勿扰开启、关闭、重载和恢复原模式均通过。测试复用活动的 Termux:X11 `:0`，原桌面使用的 Xwayland `:1` 继续保留。当前 Termux:X11 已在运行，因此拒绝另开 `:2`。Root 运行时与 Chromium 通过独立 GUI、CDP、触屏输入和截图验收。最终也安装了桌面 1.0.3。退出该会话后，其确切挂载命名空间内的剩余进程数为 0；原 native Sway 会话仍运行，X11 焦点回到测试前的 PointerRoot。 |

Root 启动器的 `OMARCHY_X11_DISPLAY` 接收 Termux:X11 对应的 `:数字`，默认 `:0`。本轮复用既有 `:0`；选择其他编号时，需要 Termux:X11 已成功在相同编号启动。环境与启动示例见 [Root 冷安装与桌面启动](../root-chroot.md)。

官方 Ubuntu APT 在新 Root guest 中实际更新了 `libgcrypt20` 至 `1.10.3-2ubuntu0.2`、`libgnutls30t64` 至 `3.8.3-1.1ubuntu3.6`、`liblz4-1` 至 `1.9.4-1build1.1`、`libseccomp2` 至 `2.5.5-1ubuntu3.1`。软件源保持原配置；更新后 Sway 版本命令与浏览器配套文件检查通过。

三个组件均完成了真实的安装、恢复与重装：桌面恢复到 `legacy`，浏览器和图形恢复返回 `recovered`，随后再装回本轮版本。恢复测试保留旧浏览器来源收据；五项个人文件校验值一致。

### APK 控件、在线安装与个人配置保留

从 Android 控制中心点击「更新」，实际打开了 Alacritty 中的更新菜单。同一测试窗口依次进入浮动和全屏，Sway IPC 确认两种状态。测试结束后恢复窗口布局并关闭测试窗口。输出从横屏 1560×720 切到竖屏 720×1560，随后恢复横屏和 App 原设置。全局输入法和网络代理设置保持原状。隔离验收 App 已卸载，设备筛选中仅保留正式 `com.omarchy.android`。

发布后在正式 PRoot guest 从公开 Release 实际下载并安装桌面 1.0.3，没有使用本地候选目录。下载的 92,926 字节与清单 SHA-256 一致；更新器报告 `current`，已装和可用版本均为 1.0.3，且有可用组件恢复记录。

正式 guest 实网检查了 21 个来源。APK、桌面、浏览器与图形配套均匹配各自渠道的已发布版本；APT 与 CC Switch 报告有更新。Hermes Desktop 报告固定适配，Hermes CLI 和 ChatGPT Desktop 报告没有已验证的自动更新方式，其余未安装。浏览器状态只描述本项目发布渠道。Chromium 128 不是上游安全最新版本，本轮交付维护通道与兼容性修复。完整分类、个人文件核对数量和控制中心状态见[脱敏记录](evidence/k20-update-ui-and-sources-20261124.json)。

### 正式 PRoot guest 的真实 Hermes Agent 回归

沿用 K20 原模型 `qwen3.8-flash` 和已有凭据，在隔离 profile 中执行真实浏览器任务，原 Hermes 配置保持原有内容。首次尝试在验收 Chromium 尚未就绪时启动 Agent，记录为失败。第二次在浏览器就绪后完成 7 次真实模型请求和 6 次 CDP 工具调用，8 项检查全部通过，涵盖 DOM 读取、点击、新下载与截图。浏览器使用默认下载目录，新增的文件为 55 字节；PNG 为 2289×851。回执的 `sourceCommit` 记录 Hermes Agent 本身的源码提交。下载和截图校验见[脱敏收据](evidence/k20-update-agent-20261124.json)。

### Root Chromium 与 Android 输入

Root Chromium 的原生 Wayland 输入路径使用 text-input v1，当前 Sway 使用 v3。实机在桌面 1.0.2、浏览器和图形 1.0.0 上验证以 `--gtk-version=4` 启动 Chromium 128，同一启动参数进入桌面组件 1.0.3。安装正式包后，默认启动器的 GTK4 参数生效，Chromium 进程实际加载 GTK4 与 Fcitx GTK 模块。测试从 Android 输入 `nihao` 和空格，页面收到 `你好`，切换 `keyboard-us` 后输入 `K20Input42`。同一次验收还确认了 Sway、Waybar、Mako、Fcitx5、Chromium CDP，以及 41 字节的受控下载和 751×545 PNG。回执中的 PNG SHA-256 与取回文件相符。该定向回归和前述 Hermes 真模型回归使用相同浏览器及图形原生包版本。[脱敏回执](evidence/k20-update-root-20261124.json)

### Root 与 PRoot 同机同负载微基准

在同一台 K20 上交替运行 3 对采样。两个 guest 使用字节一致的 Ubuntu Python 3.12.3。CPU 负载为 150,000 次迭代的 PBKDF2，系统调用负载为对同一 `/usr/bin/python3` 执行 10,000 次 `stat`。两组派生摘要和文件大小一致。

| 负载 | PRoot 三次耗时（ms） | Root chroot 三次耗时（ms） |
| --- | --- | --- |
| PBKDF2 | 67.540、103.890、106.879 | 73.795、58.328、61.393 |
| 10,000 次 `stat` | 2043.539、1245.515、1192.495 | 15.054、14.788、14.978 |

本次执行期间有后台会话，温度未锁定。上述采样反映这两项特定负载，浏览器、桌面响应与其他应用的性能不能由这些耗时外推。

### 验收环境清理

验收结束后清理了本轮创建的模型和浏览器 profile、Agent 测试素材、Root 验收目录、设备候选包及暂存文件与测试用离线镜像。正式 App、新 Root 安装和原 native 桌面继续保留；旧 `/data/ubuntu` 保持原状。清理结果见[脱敏记录](evidence/k20-update-ui-and-sources-20261124.json)。

## Release 状态

- [v1.0.79-test](https://github.com/Audrey-cn/omarchy-android/releases/tag/v1.0.79-test) 为 APK 的 latest Release。
- [desktop-v1.0.3](https://github.com/Audrey-cn/omarchy-android/releases/tag/desktop-v1.0.3) 和 [runtime-v1.0.0](https://github.com/Audrey-cn/omarchy-android/releases/tag/runtime-v1.0.0) 为独立组件 Release。1.0.1 与 1.0.2 用于中间验收。
- [desktop-ubuntu24-91a120142609](https://github.com/Audrey-cn/omarchy-android/releases/tag/desktop-ubuntu24-91a120142609) 为本轮公开初装镜像。[desktop-images](https://github.com/Audrey-cn/omarchy-android/releases/tag/desktop-images) 列新镜像在前，并保留历史 `desktop-ubuntu24-e55030353df6`。公开目录为 1,337 字节，SHA-256 为 `ffae136c969e09b8062da1d05ba46fbb0ccf5740e80b4bf2842db6b56acd445d`。

发布后通过 GitHub Release API 核对了上述 APK、DEB、镜像及目录附件的精确大小和 SHA-256。

Chromium 128 继续作为已验证的兼容配套，后续上游安全版本及补丁适配仍属于持续维护项，不能将项目渠道的 `current` 理解为上游安全最新。独立分区路线本轮完成可行性整理，后续设备适配另行推进。
