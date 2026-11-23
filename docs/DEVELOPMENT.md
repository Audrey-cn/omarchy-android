# 开发与构建

## 单仓开发

后续统一在 `Audrey-cn/omarchy-android` 开发。共享桌面改 `core/`；Root 与外部 Termux 启动适配改 `runtime/`；Android 一体 App 界面和首次安装改 `apps/android/`；外部桌面的辅助悬浮应用改 `apps/deck/`。

Android 项目通过 Git subtree 导入，保留原仓提交历史；这不是需要另行初始化的 Git submodule。`git log --all` 可查看合并后的历史。

原 App 历史已导入本仓，后续不依赖独立的 `omarchy-android-app` 目录，也不与旧仓自动同步。迁移不会自动归档旧远端或发布新 APK；本仓文档与应用入口统一指向主仓库的 Releases。接手历史见[交接记录](HANDOFF.md)，持续更新的设备实证与云端运行见[K20 Pro 验证报告](reports/cloud-k20-validation-20261123.md)。

## 默认使用云端构建

默认流程是提交到 GitHub，由 Actions 独立构建精简 APK 和干净初装镜像，再下载产物到 Android 设备验证。本地 JDK/Android SDK 并非采用该流程的前置条件；下文的 Gradle 命令适用于需要本地编译时。

`build-android.yml` 支持匹配路径的 main push、面向 main 的 PR 和手动 dispatch。`build-container.yml` 仅手动触发，避免频繁 App 变更重建整个系统。feature 分支单独 push 不会触发 main 的 push 过滤；手动 dispatch 需要工作流先在默认分支注册，再选择目标分支。

首个[干净初装镜像](https://github.com/Audrey-cn/omarchy-android/releases/tag/desktop-ubuntu24-e55030353df6)与[固定镜像目录](https://github.com/Audrey-cn/omarchy-android/releases/tag/desktop-images)已公开，随包目录已纳入同一份真实元数据。约 33.7 MB 的精简 APK 云端构建及布局校验已通过；新的同签名 App Release 和 K20 实机验收仍待完成。当前状态和用户流程见[首次安装与更新](initial-install.md)。

## 编译精简 Android App

在仓库根目录运行：

```bash
cd apps/android
./gradlew assembleDebug
```

Android Studio 应打开 `apps/android/`，而不是仓库根目录。当前 CI 使用 JDK 21、Android 37 平台、NDK 27.2.12479018 与 CMake 3.22.1；版本以工程配置及 `build-android.yml` 为准；本机 SDK 路径写到 `apps/android/local.properties`，不要提交。

输出为 `apps/android/app/build/outputs/apk/debug/app-debug.apk`。APK 包含 Android 界面、内嵌 X11、配套原生 PRoot 与 `initial-images.json`，不包含 rootfs 或受管 Linux 文件载荷。Gradle 在构建前拒绝旧构建遗留的这些资产；CI 以 `scripts/android-build.py verify --delivery online-installer` 检查实际 APK 布局、配套原生库、签名与版本。

可信 main 构建使用持续保存的同一测试签名密钥，PR 和其他非可信上下文不取得该密钥，artifact 带相应签名模式后缀。只有证书兼容且版本符合 Android 升级规则的新 APK 才能覆盖既有安装。不要将签名密钥或口令提交入库。

App 普通启动不再调用旧的 guest 受管文件同步。已有桌面脚本、浏览器、用户修改与旧同步记录留在原处；App/X11/PRoot 更新不重导入或替换 rootfs。Ubuntu 软件包在现有 guest 内使用其 APT 源维护。

## 构建独立初装镜像

手动运行 **Build Omarchy Initial Desktop Image**（`.github/workflows/build-container.yml`）：

1. ARM64 job 从干净 Ubuntu 24.04 基础构建 rootfs，通过 `scripts/stage-core.sh` 装入本次 checkout 的 `core/`，安装桌面依赖与配套浏览器。
2. 容器配置来自 `apps/android/container/omarchy.tiny.yaml`。镜像记录源码提交、软件清单和许可，不使用手机系统或个人备份作构建输入。
3. `scripts/prepare-initial-image.py` 生成 `rootfs.tar.zst`、`image.json`、`catalog.json`、`SHA256SUMS` 与来源/许可附件，artifact 为 `initial-desktop-image-<完整提交 SHA>`。
4. 仅主仓可信 main 的手动构建执行发布：版本标签为 `desktop-ubuntu24-<sha12>`，设置 prerelease 和 `latest=false`，不抢占 App 更新入口。

镜像与 APK 分开发行，不再生成 `OmarchyAndroid-AllInOne-apk`。修正 `core/` 或镜像组件后重建初装镜像；已有用户不会因安装新 APK 自动取得这些 guest 文件修正，也不会因镜像重发而重装系统。此轮不建设整套 rootfs 更新或迁移管理器。

首次安装目录 schema 为 1，条目包含镜像 ID、ABI、最低 App 版本、精确字节数、解压大小、SHA256、源码 SHA 与固定 HTTPS 下载地址。首批镜像的 `minAppVersionCode` 设置为 64；随包 `apps/android/app/src/main/assets/initial-images.json` 只应纳入已发布且核对过的真实元数据。App 显式刷新读取主仓 `desktop-images` Release 的 `catalog.json`；该固定目录入口需单独维护，不能假定版本化镜像发布后它已自动更新。保留随包目录作为离线回退，允许目录提供一个或多个兼容初始版本。

镜像、APK、构建目录与本机签名密钥均不入库。首次安装下载支持持久断点；离线导入仍需匹配所选目录条目的大小与校验值。验证时使用隔离测试目录，不清空已有个人桌面。

若在 Linux 上自行准备 rootfs，可从仓库根目录将当前核心装入一个已存在的镜像目录：

```bash
sudo bash scripts/stage-core.sh /path/to/rootfs
```

这一步仅部署核心文件，不代替工作流中的依赖安装、Sway 编译和完整镜像打包。

## 编译 Deck 辅助 APK

在仓库根目录运行：

```bash
bash apps/deck/build.sh
```

构建需要 Android SDK Build Tools 34.0.0、Android 34 平台及 JDK；脚本支持 `ANDROID_HOME`、`JAVA_HOME` 与 `ANDROID_BUILD_TOOLS_ROOT`。输出为 `apps/deck/OmarchyDeck.apk`。

Deck 用于外部 Termux:X11 桌面，包名与一体包不同。它不包含 rootfs，不能作为一体包安装。

## 构建入口与验证

| 工作流 | 内容 | 产物 |
| --- | --- | --- |
| `build-android.yml` | 精简 APK 编译、单测、布局/原生库/签名/版本校验 | `OmarchyAndroid-apk`，非可信签名模式有后缀 |
| `build-container.yml` | 手动构建干净 rootfs、同仓 core 和浏览器；可信 main 发布非 latest 镜像 | `initial-desktop-image-<完整提交 SHA>` 与版本化镜像 Release |
| `build-apk.yml` | Deck 编译 | `OmarchyDeck-apk` |
| `checks.yml` | 脚本语法与核心部署测试 | 检查结果 |

提交前在仓库根目录运行基础检查，再执行改动涉及的构建：

```bash
git diff --check
python3 -m unittest discover -s tests -v
```

完整镜像打包使用云端 ARM64 Linux runner。历史内置镜像交付的提交 `e876b3b` 和运行 [45120464556](https://github.com/Audrey-cn/omarchy-android/actions/runs/45120464556) 的构建、37 项测试及 K20 结果保留在[验证报告](reports/cloud-k20-validation-20261123.md)，不代表新在线安装流程已经验收。新的交付还需分别核对下载恢复、失败安装不覆盖、既有 guest 保留与实际桌面就绪引导。

GitHub Actions artifacts 与 Releases 附件是不同入口。App 工作流上传 artifact，App Release 需单独发布已验证的同签名 APK；镜像工作流另有可信 main 发布步骤。对外发布保留对应源码与许可证，并确认目录指向的镜像附件真实可取、大小和校验值一致。

App 更新检查读取主仓的 latest Release，版本标签使用 `vX.Y.Z`，附件应为对应的精简 App。镜像与目录 Release 必须保持非 latest。Deck 单独测试产物放在 Actions artifacts，避免把仅含 Deck 的发布设为 App 的 latest Release。

## 许可证

迁移不改变各组件原有许可。共享核心、Termux 运行时和 Deck 沿用根 MIT；Android 一体包保留 Tiny Container 的 GPL 声明及第三方依赖声明，详见[许可证说明](../LICENSES.md)。
