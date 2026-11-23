# APK 版本与备份

## 两个应用的关系

| 应用 | Android 包名 | 用途 |
| --- | --- | --- |
| Omarchy Android | `com.omarchy.android` | 当前免 Root 一体包，包含 Ubuntu 桌面；单 APK 路线使用它 |
| Omarchy Deck | `com.omarchy.deck` | 旧外部 Termux:X11 桌面的悬浮控制器，不包含 Linux 系统 |

它们是两个独立组件，不是一个应用的新旧版本。当前两份 manifest 都显示 `1.0.0`，也不代表二者内容相同。历史会话里的“v7”是测试包称呼，不是 Android manifest 中已经递增的正式版本号。

单 APK 路线不依赖 Deck；继续使用外部 Termux 路线时，Deck 才有用途。不通过并排安装旧应用来管理同一应用的版本。

## 保留方式

- 手机使用当前版本；不为版本备份额外安装同包旧版本。
- 原应用数据在本机持久目录保留一份，原签名 APK 本机至多保留一份用于恢复。
- 旧公开 APK 上传统一仓库的 GitHub Release，附来源、大小与 SHA-256。
- 数据归档包含私人配置，不上传公开仓库或 Release。
- 上传后核对 GitHub 返回的大小与 SHA-256，再删除重复的临时 APK 和分片/ZIP；保留验证回执。当前已验证测试 APK 在本机保留一个交付文件。

## 已发布的历史归档（2026-11-23）

| 归档 | 来源 | 用途 |
| --- | --- | --- |
| [K20 v7 一体 APK](https://github.com/Audrey-cn/omarchy-android/releases/tag/archive/android-v7-20261123) | 旧 App 提交 `a8982ae`、运行 `45106931412`；原安装与本地保留 APK 已核对 | 合仓前版本恢复 |
| [Omarchy Deck](https://github.com/Audrey-cn/omarchy-android/releases/tag/archive/deck-20261123) | 提交 `85715ca`、运行 `45042723565`；手机安装 APK 与官方云构建附件的大小和 SHA-256 一致 | 旧 Termux 控制器恢复 |

两个归档均标为 prerelease，并显式设置为非 latest。它们不代表新的推荐版本，也不应触发一体包的最新版更新检查。附件只有公开 APK、来源清单和校验文件，没有私人应用数据。

## 后续版本管理仍需完成

1. **持续签名密钥。** 本轮已遇到不同云构建 debug 签名无法覆盖安装；保留旧 APK 不能代替解决持续签名。后续需让同一应用的更新保持签名一致，避免重复清洁安装。
2. **递增版本号。** 一体包需要明确的 `versionCode`、`versionName` 与发布标签对应关系；两个组件各自管理版本，不能依靠文件名中的 v7/v8 判断更新。
3. **恢复验证。** 数据归档已完整校验，但没有把旧容器配置覆盖回修复版，也未声称已经完成跨版本数据迁移验收。

当前已验证产品代码为 `e876b3b`，集成到 `main`；后续文档提交不改变该 APK 的产品代码。云端和 K20 证据见[设备验证记录](reports/cloud-k20-validation-20261123.md)。
