# Omarchy Android 维护总览与发版手册

本手册面向未来重新接手仓库的维护者（包括几个月后的自己），一页梳理**全部 6 条维护通道、发版规范、测试命令与安全红线**。更细的实现原理见文末各专题文档。

---

## 1. 六大维护通道一览（改什么 → 跑哪条流 → 发哪个 Tag）

| 通道 | 负责范围 | 源码/配置位置 | 构建工作流 | Release Tag 规范 | 用户侧更新方式 |
| --- | --- | --- | --- | --- | --- |
| **① Android 一体 App (APK)** | Android 壳、内置 X11、PRoot、在线/离线镜像安装器、悬浮手柄与键盘、统一设置 | `apps/android/` | [`build-android.yml`](../.github/workflows/build-android.yml) | `v1.0.x`（`latest` Release） | Android 同签名覆盖安装（保留桌面与个人数据） |
| **② 项目桌面组件 (`.deb`)** | `core/bin/*`、`core/ai-suite/*`、Sway/Waybar/AppGrid/面板/更新器脚本 | `core/` | [`build-components.yml`](../.github/workflows/build-components.yml) | `desktop-vX.Y.Z`（独立 Tag，不抢 APK `latest`） | 桌面「更新」或 `omarchy-component-update install desktop` |
| **③ 图形栈运行时 (`.deb`)** | `/opt/omarchy-graphics`（Wayland 1.23.1、wlroots 0.18.2、Sway 1.10.1、libinput 1.26.2） | `scripts/graphics-sources.json`、`scripts/build-graphics-runtime.sh` | [`build-components.yml`](../.github/workflows/build-components.yml) | `runtime-vX.Y.Z` | 桌面「更新」或 `omarchy-runtime-update install graphics` |
| **④ 浏览器与 Agent 运行时 (`.deb`)** | `/opt/omarchy-chromium128` + `/opt/omarchy-node`（Chromium 128 + Playwright 1.46 + Node 24） | `scripts/browser-bundle-sources.json`、`scripts/build-browser-bundle.py` | [`build-components.yml`](../.github/workflows/build-components.yml) | `runtime-vX.Y.Z` | 桌面「更新」或 `omarchy-runtime-update install browser` |
| **⑤ 公开初装镜像 (`.tar.zst`)** | 供**新用户首次安装**的 Ubuntu 24.04 ARM64 完整基线（已装 ②③④） | `apps/android/container/`、`scripts/prepare-initial-image.py` | [`build-container.yml`](../.github/workflows/build-container.yml) | `desktop-ubuntu24-<shortsha>` + 目录 `desktop-images` | **仅新装使用**，老用户通过 ①②③④ 增量升级，无需重装镜像 |
| **⑥ Ubuntu APT & 第三方应用** | 系统库（glibc/NSS/GTK 等）、CC Switch、Claude/Codex/Hermes 等 | `core/bin/omarchy-updates`、`core/bin/omarchy-installer.sh` | 上游源 / 厂商 Release | — | 桌面「更新」统一巡检，用户按需显式升级 |

> **一句话记忆**：
> - 改 **Android 界面/悬浮手柄/X11/安装器** → 只发 **① APK**（~34 MB，2 分钟构建）；
> - 改 **Linux 脚本/菜单/主题/启动器** → 只发 **② 桌面组件 `.deb`**（~93 KB，30 秒构建）；
> - 改 **Sway/wlroots 或 Chromium/Node** → 只发 **③④ 运行时 `.deb`**；
> - 只有**积累了较多改动、想给新用户一个新基线**时，才低频重打 **⑤ 初装镜像**。

---

## 2. 维护频率与日常巡检清单

| 节奏 | 要检查的内容 | 参考命令 / 入口 |
| --- | --- | --- |
| **每次提交 PR/Push** | 跑全套单元测试与静态检查（当前 155+ 用例） | `pytest -q` 或 GitHub Actions [`checks.yml`](../.github/workflows/checks.yml) |
| **每次发桌面/运行时包前** | 在 K20 Pro（USB ADB 开发机）上实测安装、回滚与核心路径 | `omarchy-component-update install --candidate-dir ...` / `recover` |
| **每月 / 安全通告时** | 关注 Chromium、Node、Ubuntu 安全更新；评估能否升级 PRoot 兼容的 Chromium 分支 | `docs/runtime-components.md`、`omarchy-updates check --json` |
| **大版本正式发布前** | 在主力机（如 K90 Pro Max / Android 17 免 Root）补跑一次冷装 + 覆盖升级终验 | 验收通过后去掉 `-test` 后缀，发布正式 `vX.Y.Z` |

---

## 3. 绝对不能破坏的 5 条红线

1. **APK 持久签名密钥不可丢、不可换**：所有正式 APK 必须用同一持久 keystore 签名，保证用户覆盖安装不丢失 PRoot 容器与个人文件。
2. **日常启动绝不覆盖 guest 文件**：App 启动只负责拉起容器与 X11，禁止自动覆写用户 `/root`、Sway/Fcitx5 个人配置或桌面脚本；一切 guest 升级必须由用户显式触发。
3. **非 APK Release 绝不抢占 `latest`**：`desktop-v*`、`runtime-v*`、`desktop-ubuntu24-*`、`desktop-images` 发布时务必设置 `--latest=false`（或 Pre-release），避免 App 内更新器误判。
4. **任何组件安装/升级必须保留可回滚事务**：升级前先校验长度、SHA-256、包归属与兼容条件，失败或异常可随时运行：
   ```bash
   omarchy-component-update recover          # 回滚桌面组件
   omarchy-runtime-update recover graphics   # 回滚图形栈
   omarchy-runtime-update recover browser    # 回滚浏览器/Node
   ```
5. **严禁提交个人隐私**：API Key、Token、账号数据库、`scripts/backup-personal.sh` 生成的备份文件与本机绝对路径不得入库。

---

## 4. 常用维护命令速查

```bash
# 1. 本地跑完整回归测试
pytest -q

# 2. 触发云端精简 APK 构建（仅改 apps/android 时）
gh workflow run build-android.yml --repo Audrey-cn/omarchy-android

# 3. 触发云端组件构建（桌面 / 图形 / 浏览器）
gh workflow run build-components.yml --repo Audrey-cn/omarchy-android

# 4. 触发云端初装镜像重打（低频，供新用户）
gh workflow run build-container.yml --repo Audrey-cn/omarchy-android

# 5. Guest 内查看全部 21+ 更新来源状态
omarchy-updates check --json
omarchy-component-update status --json
```

---

## 5. 专题文档索引

- 架构与双运行栈设计：[ARCHITECTURE.md](ARCHITECTURE.md)
- 本地开发、构建与提交规范：[DEVELOPMENT.md](DEVELOPMENT.md)
- 首次在线/离线安装流程：[initial-install.md](initial-install.md)
- 桌面组件更新与回滚：[component-updates.md](component-updates.md)
- 图形栈与 Chromium/Node 维护：[runtime-components.md](runtime-components.md)
- 第三方 AI 应用更新来源：[third-party-updates.md](third-party-updates.md)
- Root chroot 冷安装与启动：[root-chroot.md](root-chroot.md)
- 最新 K20 Pro 综合验收记录：[reports/update-maintenance-k20-20261124.md](reports/update-maintenance-k20-20261124.md)
