# Omarchy Android

在 Android 手机、折叠屏与平板上运行 Omarchy 风格的 Ubuntu ARM64 Wayland 桌面。桌面核心、Android 一体包和 Termux 部署方案在同一个仓库开发与维护。

## 选择使用方式

| 方式 | 项目位置 | 适用场景 |
| --- | --- | --- |
| 免 Root 一体包 | [`apps/android/`](apps/android/) | APK 内置 PRoot、Termux:X11 和 Ubuntu 桌面镜像，无需额外安装 Termux |
| Termux 部署 | [`install.sh`](install.sh)、[`runtime/`](runtime/) | 使用外部 Termux + Termux:X11，选择 Root chroot 或免 Root proot-distro |
| Omarchy Deck 辅助 APK | [`apps/deck/`](apps/deck/) | 为外部 Termux:X11 桌面提供悬浮球、悬浮键盘和输入桥接 |

一体包与 Deck 是不同应用：一体包包名为 `com.omarchy.android`，Deck 包名为 `com.omarchy.deck`。

## 实机效果

![Omarchy Android Waybar & Panel](docs/screenshots/desktop-waybar-panel.png)

![Omarchy Android App Grid](docs/screenshots/app-grid.png)

![Omarchy Android AI Suite](docs/screenshots/ai-suite.png)

## 桌面功能

- Sway 平铺窗口管理、Waybar 顶栏、GtkLayerShell 状态弹窗与 6 列原生应用网格。
- Omarchy 主题与壁纸，联动 Sway、Waybar、Wofi、App Grid 和 Alacritty 配色。
- Fcitx5 中文拼音、Android 输入法穿透、悬浮键盘与横竖屏控制。
- Alacritty、Thunar、Neovim、Lazygit、imv、Zathura、mpv 和轻量 WebKitGTK 浏览器。
- 按需安装 CC Switch、Codex / ChatGPT Desktop、Claude Desktop、Hermes Desktop 等 AI 工具。
- Root 路线提供 `evdev` 外接键盘与触控板独占，支持 `Alt` / `Super` 桌面快捷键。

共享桌面逻辑放在 `core/`；两种部署路线各自维护运行时，详细区别见[架构说明](docs/ARCHITECTURE.md)。

## 快速开始

### 免 Root 一体包

下载入口统一为本仓库的 [Releases](https://github.com/Audrey-cn/omarchy-android/releases)。选择 `OmarchyAndroid-AllInOne.apk`，仅支持 `arm64-v8a`；APK 包含桌面镜像时，首次打开会导入镜像并初始化桌面。

构建工作流生成的 `OmarchyAndroid-apk` 是普通容器壳包；没有嵌入 `rootfs.tar.zst` 时，需要另行导入容器。`OmarchyAndroid-AllInOne-apk` 才是一体包构建产物。详见[应用说明](apps/android/README.md)。

仓库迁移和本地构建不会自动创建 GitHub Release；发布后的安装包以 Releases 实际附件为准。

### Termux 部署

先在 Android 上安装 Termux 和 Termux:X11，再在设备的 Termux 环境运行：

```bash
git clone https://github.com/Audrey-cn/omarchy-android.git
cd omarchy-android
./install.sh

# 安装时同时加入 AI 桌面工具
./install.sh --with-ai

# 手动选择运行时
./install.sh --mode chroot
./install.sh --mode proot
```

Root `chroot` 与免 Root `proot-distro` 共用桌面核心；Root 路线另有硬件键盘独占等扩展。Deck 可作为这条路线的辅助应用，不能代替 Termux 或 Termux:X11。

### 常用快捷键

| 快捷键 | 功能 |
| --- | --- |
| `Super + Space` | 打开应用网格 |
| `Super + Enter` | 打开终端 |
| `Super + W` | 关闭当前窗口 |
| `Super + 1~5` | 切换工作区 |
| `Super + K` | 打开快捷键速查表 |

Termux 路线还提供 `Alt` 组合键；快捷键以当前 Sway 配置与桌面速查表为准。

## 目录结构

```text
omarchy-android/
├── core/                     # 共享桌面脚本、AI 启动封装、Sway/Fcitx5 配置
├── runtime/
│   ├── chroot/               # 外部 Termux + Root chroot 运行时
│   └── proot/                # 外部 Termux + proot-distro 运行时
├── apps/
│   ├── android/              # 免 Root 一体包：Gradle 工程、容器配置、原生库
│   └── deck/                 # 外部 Termux:X11 使用的辅助 APK
├── install.sh                # Termux 路线安装入口
├── scripts/
│   ├── stage-core.sh         # 将本次 checkout 的 core 安装到指定 rootfs
│   └── backup-personal.sh    # 个人配置迁移脚本，生成的备份不入库
├── .github/workflows/        # Android 壳包、rootfs + 一体包、Deck 构建
└── docs/
    ├── ARCHITECTURE.md        # 两种运行栈与共享核心
    └── DEVELOPMENT.md         # 开发、构建与迁移说明
```

Android 一体包镜像构建直接读取本仓库的 `core/`，与 App 使用同一次 checkout，无需维护第二个核心仓库副本。桌面和 Android 界面的修改可以放在同一个提交中。

## 开发与维护

开发命令、云端工作流、镜像放置位置及 Git 历史迁移方式见[开发指南](docs/DEVELOPMENT.md)，已有进度见[交接记录](docs/HANDOFF.md)。原 App 的源码和历史已导入本仓，后续开发统一在本仓库进行，不依赖旁边的旧目录；旧远端尚未归档。

## 个人配置

个人换机迁移可使用 `scripts/backup-personal.sh`。API Key、Token、账号数据库及个人备份不应提交到仓库；生成的个人备份已加入忽略规则。

## 许可证与致谢

共享核心、Termux 运行时与 Deck 沿用根目录的 [MIT License](LICENSE)。`apps/android/` 基于 [Tiny Container](https://github.com/Cateners/tiny_container)，沿用其 [GPL-3.0 许可及文件声明](apps/android/COPYING)。第三方库、主题、字体和工具保留各自许可，详见[许可证说明](LICENSES.md)。

感谢 [Omarchy](https://github.com/basecamp/omarchy)、[Tiny Container](https://github.com/Cateners/tiny_container)、[Termux:X11](https://github.com/termux/termux-x11)、[PRoot](https://github.com/proot-me/proot)、Sway、wlroots 及相关开源项目。
