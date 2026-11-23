# 系统内更新与两条部署路线实施计划

**目标：** 完成[更新维护清单](../../discussions/2026-11-23-update-maintenance.md) U1–U7，整合 APK 控制入口、下载恢复、Root 冷安装，并形成合并验收记录。

**约束：** 保留现有 Ubuntu/第三方源；APK 启动不隐式安装 Linux 载荷；用户配置和原 Hermes 模型配置保留；组件、镜像 Release 为非 latest；独立分区只做可行性调查，不刷写分区；所有验证如实记录范围。

## 并行工作与接口

1. 桌面组件：`scripts/build-desktop-component.py` 构建 `omarchy-android-desktop_1.0.0_all.deb` 和 `desktop-component.json`。`omarchy-component-update status --json`、`check --json`、`install desktop [--version VERSION]`、`recover` 负责显式下载、兼容检查、旧文件识别、安装和组件级恢复。未知或个人修改的旧文件保留。
2. 更新来源：`omarchy-updates` 汇总 Ubuntu/APK/项目/第三方状态；修正菜单、检查和 APT hook，保护 Hermes 候选更新与现有资料。与工作 1 通过 CLI JSON 对接。
3. 运行时：校验图形构建输入，独立浏览器/图形包及 `omarchy-runtime-update`，实现新目录 Root 冷安装与启停清理。与工作 1/2 协调状态和发布元数据。
4. Android 与集成：传递 `OMARCHY_APK_VERSION_CODE`、`OMARCHY_APK_VERSION_NAME`、`OMARCHY_RUNTIME_API=1`；在悬浮控制中心连接更新与窗口控制；改进下载来源恢复。新增组件云端构建、发布清单和统一验收。

## 合并验收

- 开发中只在完整模块形成后做一次定向检查；不为每个文案或小改动单独构建/实机验证。
- 集成后统一执行 Python/Android 单测、语法、包布局和独立审查；只有发现新故障才定向复测。
- 同一批云端构建输出 APK、桌面组件与定制运行时，核对签名/版本/来源/大小/哈希。
- K20 同一验收阶段覆盖：原环境数据保留、真实桌面组件升级、来源检查的成功/失败、APK 悬浮入口与方向/输入、Chromium/CDP/原模型 Agent，以及独立目录 Root 冷安装和同负载对比。
- 最后发布已验收附件并同步所有文档。未通过项目保留未完成状态与原因，不以计划替代证据。
