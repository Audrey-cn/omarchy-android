# 开发与构建

## 单仓开发

后续统一在 `Audrey-cn/omarchy-android` 开发。共享桌面改 `core/`；Termux 启动与硬件支持改 `runtime/`；Android 一体包界面和容器管理改 `apps/android/`；外部桌面的辅助悬浮应用改 `apps/deck/`。

Android 项目通过 Git subtree 导入，保留原仓提交历史；这不是需要另行初始化的 Git submodule。`git log --all` 可查看合并后的历史。

原 App 历史已导入本仓，后续不依赖独立的 `omarchy-android-app` 目录，也不与旧仓自动同步。迁移不会自动归档旧远端或发布新 APK；本仓文档与应用入口统一指向主仓库的 Releases。已有工作进度与云端运行见[交接记录](HANDOFF.md)。

## 默认使用云端构建

本项目已有的开发流程是提交到 GitHub，由 Actions 构建 rootfs 和 APK，再下载产物到 Android 设备验证。本地 JDK/Android SDK 并非采用该流程的前置条件；下文的 Gradle 命令适用于需要本地编译时。

合并后的构建工作流支持 main 的 push、面向 main 的 PR 和手动 dispatch。feature 分支单独 push 不会触发 main 的 push 过滤，可用 PR 验证合并版；手动 dispatch 需要工作流先在默认分支注册，再选择目标分支。

## 编译 Android 容器壳

在仓库根目录运行：

```bash
cd apps/android
./gradlew assembleDebug
```

Android Studio 应打开 `apps/android/`，而不是仓库根目录。当前 CI 使用 JDK 21、Android 37 平台、NDK 27.2.12479018 与 CMake 3.22.1；版本以工程配置及 `build-android.yml` 为准；本机 SDK 路径写到 `apps/android/local.properties`，不要提交。

输出为 `apps/android/app/build/outputs/apk/debug/app-debug.apk`。工程随仓库保存所需原生库；没有 `assets/rootfs.tar.zst` 时，该产物是容器壳，需另行导入容器。

## 编译包含镜像的一体包

使用根目录的 **Build Omarchy Ubuntu 24.04 ARM64 Container** 工作流（`.github/workflows/build-container.yml`）：

1. ARM64 job 构建 Ubuntu rootfs，并通过 `scripts/stage-core.sh` 装入本次 checkout 的 `core/`。
2. 容器配置来自 `apps/android/container/omarchy.tiny.yaml`。
3. 后续 APK job 将 `rootfs.tar.zst` 下载到 `apps/android/app/src/main/assets/`，再编译 Android 工程。
4. 下载 `OmarchyAndroid-AllInOne-apk` artifact，内含 `OmarchyAndroid-AllInOne.apk`。

本地已取得对应镜像时，也可将它放到以下位置后运行上述 Gradle 命令：

```text
apps/android/app/src/main/assets/rootfs.tar.zst
```

该镜像、APK、构建目录与本机签名密钥均不入库。只修改 `core/` 后重新编译 Android 壳，不会更新已有镜像；需要先重建 rootfs，再构建一体包。

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
| `build-android.yml` | Android 工程编译 | `OmarchyAndroid-apk`（普通壳包） |
| `build-container.yml` | 同仓 core + Ubuntu rootfs + Android 一体包 | 容器镜像及 `OmarchyAndroid-AllInOne-apk` |
| `build-apk.yml` | Deck 编译 | `OmarchyDeck-apk` |
| `checks.yml` | 脚本语法与核心部署测试 | 检查结果 |

提交前在仓库根目录运行基础检查，再执行改动涉及的构建：

```bash
git diff --check
python3 -m unittest discover -s tests -v
```

目录、脚本与 YAML 校验只能证明迁移配置一致；完整镜像打包使用云端 ARM64 Linux runner，一体包启动与输入行为还需 Android 设备验证。旧 App 仓最新 `2792678` 已有成功的云端一体包构建，合并版仍需单独验证。

GitHub Actions artifacts 与 Releases 附件是两个发布入口。现有工作流上传 artifacts，不会自动创建 Release；对外发布时应确认上传的是包含镜像的一体包，并保留对应版本的源码与许可证声明。

App 更新检查读取主仓的 latest Release，版本标签使用 `vX.Y.Z`，并将对应的一体 APK 一起发布。Deck 单独测试产物放在 Actions artifacts，避免把仅含 Deck 的发布设为 App 的 latest Release。

## 许可证

迁移不改变各组件原有许可。共享核心、Termux 运行时和 Deck 沿用根 MIT；Android 一体包保留 Tiny Container 的 GPL 声明及第三方依赖声明，详见[许可证说明](../LICENSES.md)。
