# Omarchy Android

**开箱即用的免 Root 单 APK 手机/平板端 Omarchy Wayland Linux 桌面环境。**

将完整的 **Ubuntu 24.04 ARM64 + Sway 1.10.1 (`wlroots 0.18.2` 纯 SHM/Pixman 后端) + Waybar + Omarchy 主题与菜单体系 + 内置 Termux:X11 + 原生悬浮手柄与悬浮键盘** 全部打包进一个独立 APK——无需 Root、无需外部 Termux、无需手动导入镜像，安装打开即用。

---

## 核心特性

- **真正免 Root · 单 APK 开箱即用**：内置 `assets/rootfs.tar.zst` 镜像，首次启动自动初始化并直达 Omarchy Wayland 桌面。
- **原汁原味 Omarchy 桌面体验**：
  - `Sway 1.10.1` 平铺窗口管理器 + `Waybar` 顶栏（完整 Nerd Font + `omarchy.ttf` + `NotoColorEmoji`）
  - 6 列原生应用网格（`Super + Space`）、可视化主题与壁纸选择器、完整快捷键速查表（`Super + K`）
  - 默认 `1.5x` 手机/平板清晰缩放 + **圆角屏（R 角）对称安全边距适配**（可在右上角「显示与缩放」一键切换方屏 / 标准圆角 / 大圆角）
  - 顶栏实效快捷状态开关：勿扰（`makoctl`）、夜览护眼（硬件暖色滤镜）、防休眠常亮、免密 Sudo、一键全屏截图
- **内置轻量原生 Wayland 浏览器（WebKitGTK 4.1）**：
  - 100% 运行在 Linux Sway 桌面内部，秒开（<0.4s）、低内存（<60 MB）、支持 Cookie 与登录态持久化
  - 精简预装核心原生工具（Alacritty、Thunar、Neovim、Lazygit、imv、Zathura、mpv、Omarchy Browser）与精选 WebApp（DeepSeek、Bilibili），可在「安装」菜单按需安装 CC Switch、AI 工具与开发套件
- **原生免权限贴边悬浮手柄 & 透明悬浮键盘**：
  - 默认吸附屏幕边缘收起为半透明小拉条，自由上下拖动并弹性吸附左右边缘，不遮挡操作区
  - 4 枚自绘极简矢量图标：透明 0.70x 悬浮键盘（底部小白条自由拖动 + 精致细轨透明度滑块）/ 豆包语音与中文穿透 / 横竖屏硬锁定 / 统一控制中心
  - **竖屏软键盘自动上顶缩放**：竖屏调出安卓输入法时，桌面与终端自动收缩至键盘上方，零遮挡
  - **豆包输入法无感穿透**：基于自定义 `InputConnectionWrapper`，候选期零泄露、选词/语音一次性精准上屏
- **统一设置与 4 步箭头引导**：内置 X11 显示与触控设置、系统输入法切换、文件存储与电池优化一键授权，首次启动提供可跳过的交互式箭头指引。

---

## 快速上手

1. 前往 [Releases](https://github.com/Audrey-cn/omarchy-android-app/releases) 下载最新版 `OmarchyAndroid-AllInOne.apk` 并安装（仅支持 `arm64-v8a` 设备）。
2. 首次打开保持亮屏等待约 30~60 秒完成桌面初始化，自动进入 Omarchy 桌面并弹出 4 步箭头引导。
3. 推荐搭配 **豆包输入法**（可在引导页或控制中心一键检测/下载），点击悬浮手柄话筒即可直接用中文/离线语音在 Linux 终端与应用内输入。

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

## 相关仓库

- 核心脚本与 Root Chroot 伴侣仓库：[Audrey-cn/omarchy-android](https://github.com/Audrey-cn/omarchy-android)
- 单 APK 免 Root 一体包仓库（本仓库）：[Audrey-cn/omarchy-android-app](https://github.com/Audrey-cn/omarchy-android-app)

---

## 开源协议与致谢 (License & Acknowledgments)

本项目基于 **GNU General Public License v3.0 (GPL-3.0)** 开源，详见 [COPYING](COPYING)。

衷心感谢以下优秀的开源项目与作者：
- [**tiny_container**](https://github.com/Cateners/tiny_container)（by Caten Hu, GPL-3.0）—— 本项目 Android 容器壳与 JNI 音频/麦克风/PRoot 集成的基础框架
- [**basecamp/omarchy**](https://github.com/basecamp/omarchy) —— Omarchy 桌面美学、主题与工作流设计灵感来源
- [**Termux:X11**](https://github.com/termux/termux-x11) & [**PRoot**](https://github.com/proot-me/proot) —— Android 端高性能 X11 显示与免 Root Linux 容器运行时
- [**Sway**](https://github.com/swaywm/sway) & [**wlroots**](https://gitlab.freedesktop.org/wlroots/wlroots) —— 现代轻量 Wayland 平铺合成器
