# Omarchy Android 免 Root App

本目录是 [Omarchy Android 单仓项目](../../README.md)中的 Android Gradle 工程，基于 Tiny Container；共享桌面脚本位于仓库根目录的 `core/`。

**集成 X11 与 PRoot 的免 Root 手机/平板端 Omarchy Wayland Linux 桌面入口。**

精简 APK 包含 Android 界面、内嵌 X11、配套原生 PRoot、悬浮手柄与悬浮键盘。首次运行时通过 App 选择、下载或离线导入初装镜像，提供 **Ubuntu 24.04 ARM64 + Sway 1.10.1 (`wlroots 0.18.2` 纯 SHM/Pixman 后端) + Waybar + Omarchy 主题与菜单 + 配套浏览器**。无需 Root 或外部 Termux。

约 33.7 MB 的 [v1.0.72-test](https://github.com/Audrey-cn/omarchy-android/releases/tag/v1.0.72-test) 已通过 K20 同签名升级、既有数据保留、初装与桌面启动验收，详见[实机记录](../../docs/reports/online-installer-k20-20261123.md)。首个[干净初装镜像](https://github.com/Audrey-cn/omarchy-android/releases/tag/desktop-ubuntu24-e55030353df6)与[镜像目录](https://github.com/Audrey-cn/omarchy-android/releases/tag/desktop-images)已公开，支持 App 版本代码 64 及以上，随包目录已包含该镜像。已发布的旧版内置镜像 APK 与新流程的区别见[首次安装与更新](../../docs/initial-install.md)。

---

## 核心特性

- **免 Root · App 内首次安装**：精简 APK 不内置 rootfs 或受管 Linux 文件载荷；支持初始镜像选择、持久断点下载、暂停/继续和匹配镜像的离线导入。日常同签名 APK 更新保留已有桌面与个人数据，不在启动时同步或替换 guest。
- **原汁原味 Omarchy 桌面体验**：
  - `Sway 1.10.1` 平铺窗口管理器 + `Waybar` 顶栏（完整 Nerd Font + `omarchy.ttf` + `NotoColorEmoji`）
  - 6 列原生应用网格（`Super + Space`）、可视化主题与壁纸选择器、完整快捷键速查表（`Super + K`）
  - 默认 `1.5x` 手机/平板清晰缩放 + **圆角屏（R 角）对称安全边距适配**（可点击桌面顶部状态栏的显示器图标，切换方屏 / 标准圆角 / 大圆角）
  - 顶栏实效快捷状态开关：勿扰（`makoctl`）、夜览护眼（硬件暖色滤镜）、防休眠常亮、免密 Sudo、一键全屏截图
- **内置轻量原生 Wayland 浏览器（WebKitGTK 4.1）**：
  - 100% 运行在 Linux Sway 桌面内部，秒开（<0.4s）、低内存（<60 MB）、支持 Cookie 与登录态持久化
  - 精简预装核心原生工具（Alacritty、Thunar、Neovim、Lazygit、imv、Zathura、mpv、Omarchy Browser）与精选 WebApp（DeepSeek、Bilibili），可在「安装」菜单按需安装 CC Switch、AI 工具与开发套件
- **原生免权限贴边悬浮手柄 & 透明悬浮键盘**：
  - 默认吸附屏幕边缘收起为半透明小拉条，自由上下拖动并弹性吸附左右边缘，不遮挡操作区
  - 4 枚自绘极简矢量图标：透明 0.70x 悬浮键盘（底部小白条自由拖动 + 精致细轨透明度滑块）/ 豆包语音与中文穿透 / 横竖屏硬锁定 / 统一控制中心
  - **竖屏软键盘自动上顶缩放**：竖屏调出安卓输入法时，桌面与终端自动收缩至键盘上方，零遮挡
  - **豆包输入法无感穿透**：基于自定义 `InputConnectionWrapper`，候选期零泄露、选词/语音一次性精准上屏
- **统一设置与 4 步交互引导**：内置 X11 显示与触控设置、系统输入法切换、文件存储与电池优化一键授权，首次引导等待当前桌面及 X11 视口就绪后显示，可重试或跳过。

---

## 快速上手

1. 安装已通过 K20 验证的 [v1.0.72-test](https://github.com/Audrey-cn/omarchy-android/releases/tag/v1.0.72-test) APK（仅支持 `arm64-v8a` 设备，以 Release 实际附件为准）。
2. 使用新版本时，首次打开选择初始镜像并点击“下载并安装桌面”，或选择对应版本后“从本地镜像安装”。下载可暂停，重新打开 App 后可继续；校验、解压和初次启动所需时间取决于设备与网络。
3. 安装完成后点击“启动桌面”，桌面就绪后显示可跳过的 4 步交互引导。没有兼容镜像条目时，先等待镜像发布或更新 App；详细步骤见[首次安装与更新](../../docs/initial-install.md)。
4. 可搭配 **豆包输入法**（可在引导页或控制中心检测/下载），点击悬浮手柄话筒即可用中文/语音在 Linux 终端与应用内输入。

### 常用快捷键

| 快捷键 | 功能 |
|---|---|
| `Super + K` | 打开完整快捷键速查表 |
| `Super + Space` | 打开 6 列原生应用网格 |
| `Super + Alt + Space` | 打开 Omarchy 主菜单（主题、安装、系统设置） |
| `Super + Enter` | 打开 Alacritty 终端 |
| `Super + W` | 关闭当前窗口 |
| `Super + 1~5` | 切换工作区 1~5 |

---

## 开发与构建

在仓库根目录运行：

```bash
cd apps/android
./gradlew assembleDebug
```

Android Studio 打开本目录。编译输出 `app/build/outputs/apk/debug/app-debug.apk`；当前交付模式是精简在线安装器，不应放入 `assets/rootfs.tar.zst` 或 `managed-core` 载荷。可用初装镜像由随包 `initial-images.json` 或项目发布的目录提供，离线导入也必须匹配目录中的元数据。

根目录的 `build-android.yml` 独立构建并验证精简 App，artifact 为 `OmarchyAndroid-apk`（非可信签名构建带相应后缀）。`build-container.yml` 手动构建包含本仓 `core/` 与浏览器的干净初装镜像，不再嵌入 APK；可信 main 构建发布独立、非 latest 的镜像 Release。完整操作见[开发指南](../../docs/DEVELOPMENT.md)。

## 项目位置

- 统一仓库：[Audrey-cn/omarchy-android](https://github.com/Audrey-cn/omarchy-android)
- 共享桌面核心：[`../../core/`](../../core/)
- 外部 Termux 部署与 Root 路线：[`../../runtime/`](../../runtime/)
- Deck 辅助 APK：[`../deck/`](../deck/)

原 `omarchy-android-app` 的源码和主分支历史已导入统一仓库；后续开发不依赖旧目录。APK 构建产物先上传 Actions artifacts；独立镜像另有可信 main 发布步骤。下载附件以主仓 Releases 实际内容为准。

---

## 开源协议与致谢 (License & Acknowledgments)

本目录中的应用沿用 **GNU General Public License v3.0 (GPL-3.0)** 及文件内原有声明，详见 [COPYING](COPYING)。共享桌面核心与 Deck 的许可见根目录[许可证说明](../../LICENSES.md)。

衷心感谢以下优秀的开源项目与作者：

- [**tiny_container**](https://github.com/Cateners/tiny_container)（by Caten Hu, GPL-3.0）—— 本项目 Android 容器壳与 JNI 音频/麦克风/PRoot 集成的基础框架
- [**basecamp/omarchy**](https://github.com/basecamp/omarchy) —— Omarchy 桌面美学、主题与工作流设计灵感来源
- [**Termux:X11**](https://github.com/termux/termux-x11) & [**PRoot**](https://github.com/proot-me/proot) —— Android 端高性能 X11 显示与免 Root Linux 容器运行时
- [**Sway**](https://github.com/swaywm/sway) & [**wlroots**](https://gitlab.freedesktop.org/wlroots/wlroots) —— 现代轻量 Wayland 平铺合成器
