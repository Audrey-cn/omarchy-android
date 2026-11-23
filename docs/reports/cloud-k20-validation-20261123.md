# 合并仓库云构建与 K20 Pro 验证记录（2026-11-23）

## 范围与证据归属

本轮以 K20 Pro 的一体包 Ubuntu 24.04 guest 为主要验证环境。以下分别记录云端 APK 构建、已安装基线的设备行为，以及随后将开发中的核心脚本部署到该 guest 后的功能测试。

**最终产品代码为 `e876b3b`。其一体 APK 已在 K20 Pro 清洁安装，自动初始化、进入桌面与首启无崩溃回归通过。** 可选软件分开记录基线测试与最终包复测，不将仅安装成功计作可用。本记录不将其他设备、其他运行时或历史会话中的结论当作本轮验收。

## 云构建与首次初始化

| 提交 / 运行 | 已确认结果 | 尚未完成 |
| --- | --- | --- |
| `4e20a8b` / [45112385652](https://github.com/Audrey-cn/omarchy-android/actions/runs/45112385652) | 合并仓库基线云构建成功；一体包已安装到 K20 Pro | 首次初始化触发 `ContainerManageFragment` 的 `AsyncListDiffer` 提交回调 NPE，不能记为首次启动全流程通过 |
| `9fd8d1c` / [45114601221](https://github.com/Audrey-cn/omarchy-android/actions/runs/45114601221) | 生命周期回调修复已云构建成功 | 后续 `831e69e`、`e876b3b` 的清洁安装及首次初始化均已通过，原 NPE 未重现 |
| `831e69e` / [45119331926](https://github.com/Audrey-cn/omarchy-android/actions/runs/45119331926) | 一体包构建成功；K20 清洁初始化、引导、默认缩放、DNS 与冷启动通过 | 作为中间回归版本，已由最终版本替换 |
| `e876b3b` / [45120464556](https://github.com/Audrey-cn/omarchy-android/actions/runs/45120464556) | 一体包、壳包、Deck、37 项集成检查均成功；14:03:18 开始 K20 清洁初始化，自动进入桌面及引导，随后冷启动通过，全轮未出现目标 App 崩溃 | 可选工具与模型服务分别记录下方验收边界 |

首启 NPE 与 Linux 桌面组件启动是两个问题。重新打开已安装基线后，已确认能进入桌面，并验证四步引导、默认 1.5 倍缩放和 DNS 域名解析正常；这一结果不能替代修复版的首次初始化测试。

## 中文输入

基线与最终 `e876b3b` 均使用真实豆包输入法 T9 输入“你好”，并在 Linux 侧文件中逐字核对，内容完全相同，文字穿透通过。

基线与最终版本均实测横竖屏切换：1080×2340 竖屏中，豆包键盘展开后 guest 输出缩为 1080×1417；收起后恢复 1080×2340，随后切回 2340×1080 横屏。

该证据对应此次真实中文按键输入；不扩大为所有输入法、全部候选操作或语音链路均已验收。

## Hermes Desktop

| 项目 | 设备实测 |
| --- | --- |
| 安装包 | 官方来源的 `hermes-desktop-2026.9.7-7` ARM64 包，下载校验通过 |
| 原始故障 | 在 Ubuntu glibc 2.39 中直接加载现有 binding，明确复现 `GLIBC_2.42 not found`；不是简单的文件缺失 |
| 版本 | 已安装 Electron `40.10.2`、Node `24.15.0`、模块 ABI `143`；捆绑 node-pty `1.1.0` |
| 修复后原生模块 | 在目标 guest 中源码重编 node-pty，匹配已安装 Electron；候选与安装后两轮均输出 `PTY_NATIVE_LOAD_OK` |
| 真实 PTY | shell 创建、输入、resize 和退出均通过：`input=true`、`resize=true`、退出码 `0` |
| GUI | 基线与最终包均能显示首次设置页；未选择安装或连接后端时，最终包随后显示连接超时恢复页，不能记为完整可用 |
| Agent 级验收 | 后端与模型未配置，尚未验证模型回复或真实工具调用 |

首次真实修复过程中，原生模块和两轮 PTY 检查均已通过，但构建目录清理遇到 PRoot 的 `Directory not empty: obj.target`，导致旧脚本最终误报失败。清理边界已修正为警告并保留检查路径，且不覆盖编译或 smoke 的原始错误；最终包已重新从官方包重建 binding，两轮 PTY 检查再次通过；清理失败仅警告，安装命令正确返回 0。

修复实现与检查命令见 [Hermes 原生模块说明](../hermes-native.md)。原生加载和 Electron 子进程存在均不足以单独证明 Agent 已可用。

## Agent 浏览器

Playwright 使用独立安装与浏览器缓存，版本为 `1.65.1`。日常 WebKitGTK 浏览器、Playwright Firefox 和 Chromium 分别验证，不相互替代结果。

| 浏览器 / 模式 | 已确认结果 | 验收边界 |
| --- | --- | --- |
| Firefox `159`、revision `1572`，headless | 基线与最终 `e876b3b` 均完整通过本地 fixture：中文 DOM 输入、点击、截图、cookie、下载内容校验 | 证明此设备与当前 guest 中该模式可运行；不等于所有 Agent 已接入 |
| 同版本 Firefox，Wayland | 基线与最终 `e876b3b` 均通过同一完整 fixture | 与 headless 分开验收；不是仅观察进程存在 |
| 独立 Firefox GUI 入口 | 已实际打开 | 安装器两模式门槛及桌面入口生成通过；初始 URL 连接失败时保留窗口的修复也已实测，最终 APK 的双模式、门槛、独立持久 profile GUI 均已复测通过 |
| Chromium `159`、revision `1255` | 进程出现 `SIGTRAP`；普通路径与实际应用数据路径复测仍失败 | headless / CDP 未通过，不记为可用，也不外推为所有 Chromium 版本均不兼容 |

最终包两份报告分别为 `2026-11-23T06-22-56-751Z-firefox-headless-7794` 与 `2026-11-23T06-23-04-337Z-firefox-wayland-8269`，均 `status=passed`。手机直连官方下载曾超出本次安装时限；随后由电脑从相同官方备用源下载并校验 ZIP CRC，经 ADB 传入，交由原 Playwright 安装器从临时 loopback 缓存完成解包、双探针和桌面入口发布。临时缓存服务已关闭。

Firefox 的通过不能替代仅接受 Chromium/CDP 的 Agent 工具链验收。浏览器安装入口、版本和 probe 说明见 [Agent 浏览器说明](../browser-agent.md)。

## Codex Desktop

官方 ARM64 DEB `26.1117.41037` 已完成正常包配置；动态库检查和原生 PTY 加载通过。

图形启动仍未通过。已修复错误 `uname.release` 导致的首个版本解析崩溃；后续 GPU、网络等子进程因只读共享内存描述符访问模式不符而退出。实际复现请求 O_RDONLY 却获得 O_RDWR；真实 reopen 对照返回 EACCES，bundled classic/latest 均未通过。详见 [原生启动诊断](codex-native-startup-20261123.md)。安装成功、依赖完整和原生模块可加载不等于 Codex Desktop 已可用；本轮也未建立其模型会话或工具调用通过的证据。

## 最终产物与保留

- 一体 APK artifact：`14792819694`，运行 `45120464556`，产品代码 `e876b3bbbf21187b2a61cb63c22f988c1c38017a`。
- artifact ZIP SHA-256：`32d31b7fc2a3f061528fb34de28990fd2b0bde19ed78d063946221f49383d533`。
- APK：880,460,687 字节，SHA-256 `1f48e8d9b7a33ba84fef593c96740213f869b1eccbe91b17c722e40106f1ffb4`。下载后校验 ZIP 与 APK，再进行设备安装。
- 最终容器已移除错误内核伪装；设备 guest 报告真实宿主内核，默认缩放 1.5，DNS 域名解析正常。
- 原应用数据与原签名 APK 的完整验证备份保留于本机持久目录，不披露私人路径、设备序列号或个人截图。

现有 Actions 使用临时 debug 签名；本轮已实际遇到 `INSTALL_FAILED_UPDATE_INCOMPATIBLE`。因此先备份旧数据和 APK，再清洁安装测试版本；没有声称覆盖升级保留了原数据。后续稳定发布需另行配置持续签名密钥，本轮未创建正式 Release。

## 剩余边界

1. Chromium/CDP 与 Codex Desktop 的只读共享内存兼容仍未通过；不能通过伪造访问位或跳过只读检查宣称解决。
2. Hermes 原生模块与真实 PTY 已通过，GUI 能显示；本地后端尚未安装/连接，首次设置等待与 renderer 45 秒超时的冲突已记录，模型回复和 Agent 工具调用未验收。
3. CC Switch `4.0.39` 在基线及最终包中安装、欢迎/主窗口均通过；最终包首次启动没有 Python 数据库异常，schema 由应用建立，显式直连时不残留代理配置。失败传播与上述行为另经 8 项测试覆盖；尚未配置供应商或验证真实模型请求。
4. K90 兼容性与正式发布不属于本轮已完成验收。
