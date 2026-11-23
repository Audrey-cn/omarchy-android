# 单仓迁移验证记录（2026-11-23）

## 迁移来源

- 桌面主仓：`68d6dfc8c23ead2edaca94ba9033832d4638fd94`。
- 原 Android App 主分支：`279267804ecbc4bf22dd476c32df3af7ffb423d4`。
- 历史导入提交：`a637d8e`，使用非 squash Git subtree 将 App 放入 `apps/android/`。
- 原辅助 APK 从 `android-app/` 移至 `apps/deck/`。
- 两条原主分支历史均可由合并后的 HEAD 到达；原 App 的 231 个受控文件均保留，允许的修改包括文档、仓库地址、CI 路径和项目显示名。
- 原本的 `omarchy-android-app` 本地目录保持原 HEAD，工作区干净。

## 已执行的本地验证

- `python3 -m unittest discover -s tests -v`：2 个测试通过，覆盖实际 core 文件复制、字节一致、可执行权限、含空格路径、从其他工作目录调用，以及无效目标拒绝。
- 4 个根 workflow 的 YAML 唯一键解析通过，9 个 Bash run 块的语法通过。
- 检查镜像构建在 chroot 前从本次 checkout 部署 core，不再从旧远端拉取核心；App/Core 修改均能触发一体包构建。
- `install.sh`、`scripts/*.sh`、`runtime/*/*.sh`、Deck 构建脚本的 `bash -n` 通过；`bash install.sh --help` 可运行。
- 校验镜像、APK、Gradle 构建目录、本机 SDK 配置和签名密钥的忽略规则。
- `git diff --check` 通过；独立审阅未发现迁移引入的 P1/P2 问题。

## 同时修复的入口问题

`install.sh` 两处 APT hook 字符串的内外单引号冲突，在原提交 `68d6dfc` 已能用 `bash -n` 复现。改用内部双引号后，原有 hook 文本保持一致，安装脚本可以通过语法检查。

Android `gradlew` 补上执行权限，使开发指南中的 `./gradlew` 命令可启动 wrapper。

## 验证边界

本机没有可用 JDK 和完整 Android SDK 构建工具。`apps/android/gradlew --version` 在 wrapper 启动后因找不到 Java Runtime 退出；未完成 Android APK 编译。

未运行远端 GitHub Actions、ARM64 rootfs 构建或 Android 设备测试。因此，这份记录证明本地历史/目录迁移、文件输入与配置校验，不证明完整打包或实机运行通过。

远端仓库、Releases、旧仓库归档均未变更。

## 后续交接补充

读取 Antigravity 历史并核对 GitHub 后，已确认项目原有流程使用云端构建：旧 App `2792678` 的 rootfs 和一体 APK 已在 [45109234902](https://github.com/Audrey-cn/omarchy-android-app/actions/runs/45109234902) 成功。本机缺工具链只解释本地编译未执行，不构成云端构建阻塞。上述迁移验证边界仍适用于尚未送入云端的合并版，详见[交接记录](../HANDOFF.md)。
