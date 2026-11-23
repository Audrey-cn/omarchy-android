# Omarchy Android 架构

本仓库维护一套共享桌面核心和两条运行路线。Android 一体包与 Termux 部署路线共用 `core/`，并保留各自适合 Android 环境的启动与显示实现。

## 共享桌面核心

`core/bin/` 提供应用网格、菜单、状态面板、主题、浏览器、安装器与 Android 输入桥接；`core/ai-suite/` 提供 AI 桌面工具启动封装；`core/config/` 保存 Sway 与 Fcitx5 配置。

- Termux 路线由根目录 `install.sh` 安装这些文件。
- 一体包路线由 `scripts/stage-core.sh ROOTFS` 把当前 checkout 的文件安装进镜像。

构建一体包时不再从另一个 GitHub 仓库克隆桌面核心。修改 App 与核心的同一提交会一起进入镜像和 APK。

## Termux + Ubuntu 路线

```text
Android
└── 外部 Termux + Termux:X11
    ├── Termux 原生 Sway（X11 后端、Pixman 渲染）
    └── Ubuntu 24.04 ARM64（chroot 或 proot-distro）
        └── core 桌面组件与 Linux 应用 → Wayland socket
```

Sway 在 Termux 用户空间运行，通过共享的临时目录提供 `wayland-1`。Ubuntu 内的 Waybar、Fcitx5、终端和其他应用连接这一 Wayland socket。

`runtime/chroot/` 负责 Root 挂载、启动、浏览器桥接与硬件输入；`runtime/proot/` 负责外部 Termux 中的免 Root 启动。两条路线都依赖单独安装的 Termux 和 Termux:X11。

`apps/deck/` 是这条路线的辅助 Android 应用，提供悬浮球、悬浮键盘和输入桥接，并打开外部 `com.termux.x11`。它不内置 Ubuntu，也不负责管理完整桌面镜像。

## Android 免 Root 一体包路线

```text
apps/android/（基于 Tiny Container）
├── Android 界面、容器导入、悬浮手柄与输入法桥接
├── 内置 Termux:X11、PRoot、音频与麦克风支持
└── 内置 Ubuntu rootfs
    ├── Sway + wlroots（纯 SHM/Pixman X11 后端）
    └── 本仓库 core + Waybar + Fcitx5 + Linux 应用
```

这条路线在应用自身目录中管理容器，无需外部 Termux。`apps/android/container/omarchy.tiny.yaml` 描述容器启动命令与服务；Sway 在容器内连接内置 X11 显示。它使用的 Sway/wlroots 构建与 Termux 路线不同，迁移仓库不改变运行栈版本或应用包名。

Android 应用包名仍为 `com.omarchy.android`，Deck 为 `com.omarchy.deck`。Android 工程保留来自 Tiny Container 的 `com.fct.tc4` 源码 namespace。

## 构建链路

根目录 `.github/workflows/build-container.yml` 在 ARM64 Linux runner 上构建 Ubuntu 24.04 rootfs，安装依赖、复制同次 checkout 的 `core/` 和容器配置，生成 `rootfs.tar.zst`。随后 APK job 将镜像放入 `apps/android/app/src/main/assets/` 并构建 `OmarchyAndroid-AllInOne.apk`。

`.github/workflows/build-android.yml` 只编译 Android 工程；没有预先放入镜像时得到的是普通容器壳包。`.github/workflows/build-apk.yml` 单独构建 Deck。

构建产物上传为 GitHub Actions artifacts；迁移、提交或构建成功均不等于已创建 Release。开发命令和产物路径见[开发指南](DEVELOPMENT.md)。
