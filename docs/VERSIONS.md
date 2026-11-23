# APK 版本与备份

## 两个应用的关系

| 应用 | Android 包名 | 用途 |
| --- | --- | --- |
| Omarchy Android | `com.omarchy.android` | 当前免 Root 一体包，包含 Ubuntu 桌面；单 APK 路线使用它 |
| Omarchy Deck | `com.omarchy.deck` | 旧外部 Termux:X11 桌面的悬浮控制器，不包含 Linux 系统 |

它们是两个独立组件，不是一个应用的新旧版本。历史 APK 都曾显示 `1.0.0`，也不代表二者内容相同。历史会话里的“v7”是测试包称呼，不是 Android manifest 中已经递增的正式版本号。下述新版本策略只用于 Omarchy Android，Deck 保持自己的版本与签名。

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

## Omarchy Android 测试版本与签名

`apps/android/version.properties` 是版本参数的唯一来源，`scripts/android-build.py` 共用于本地 Gradle、壳 APK 和一体 APK：

- `versionCode = versionCodeBase + git rev-list --count HEAD`，初始 base 为 1。正常追加或合并主线提交时递增，同一提交重复构建和两种 APK 得到相同 code。
- `versionName = 1.0.<versionCode>-test.g<12位源码SHA>`。数字前缀也递增，兼容应用内现有的数字版本比较。工作区有未提交更改加 `.dirty`；临时签名再加 `.temporary`。
- CI 必须完整 checkout（`fetch-depth: 0`）；浅克隆直接报错。发布过 APK 的主线不能改写历史或降低 base。如必须重建历史，先把 base 提升到新 code 超过所有已发布值再构建。
- 本地构建需要 Python 3 与完整 Git 历史。导出的无 Git 源码只有在明确提供 `OMARCHY_ANDROID_VERSION_CODE`、`OMARCHY_ANDROID_VERSION_NAME`、`OMARCHY_ANDROID_SOURCE_SHA` 三项构建元数据时才可构建。

`Audrey-cn/omarchy-android` 主仓 `main` 的 push 和手动构建使用同一持续测试签名，缺失或错误的 secret 立即失败。PR（包括同仓 PR）一律使用临时 debug 签名，不解码持续密钥；附件名带 `-temporary-pr`，版本名带 `.temporary`。其他分支或 fork 手动构建也只得到标明 `temporary-local` 的临时签名。临时包不承诺能覆盖任何其他构建。

Gradle 在 runner 内读取加密 PKCS12 和密码环境变量，完成后删除临时密钥文件。附件只包含 APK 和 `build-metadata.json`，后者记录版本、完整源码 SHA、签名模式与实际 APK 公开证书 SHA-256。上传前用 Android `apksigner` 验证签名、比对持续证书，用 `aapt` 比对实际包名与版本；只有验证成功的持续签名包才记录 `upgradeable: true`。它表示今后同签名及版本递增的更新路径，不代表已验收所有容器数据迁移。项目及新增构建工具仍遵循 GPL-3.0-or-later。

Android 覆盖安装要求签名身份一致，版本号也必须正确管理，参见 [Android 签名说明](https://developer.android.com/studio/publish/app-signing)和[版本说明](https://developer.android.com/studio/publish/versioning)。旧云包使用每次 runner 随机生成的 debug key，**首次切换到本持续密钥仍需一次备份、卸载/安装及恢复**；更换版本号不能绕过签名验证。从稳定签名建立后再验收连续两版覆盖安装，不能声称旧临时包已经支持直接升级。

## 密钥建立与保管（仅维护者操作一次）

本机没有可用 JDK/keytool 时可以使用 OpenSSL 或 LibreSSL。生成器创建 RSA-3072、自签 SHA-256 测试证书（10000 天）、alias `omarchy-android-test`，并用 AES-256-CBC、MAC-SHA256 导出 PKCS12。密码由系统随机源生成，命令参数与标准输出都不含密码/密钥内容；目录为 0700，保留的三个私有文件为 0600，未加密 PEM 随临时目录清理。已有目录会报错，防止误生成新 key 破坏覆盖安装。

在仓库根目录执行，密钥目录必须位于仓库外：

```bash
python3 scripts/android-build.py generate-keystore \
  --output-dir "$HOME/.local/share/omarchy-android-signing/test-20261123"
gh secret set OMARCHY_ANDROID_TEST_KEYSTORE_B64 \
  --repo Audrey-cn/omarchy-android \
  < "$HOME/.local/share/omarchy-android-signing/test-20261123/test-signing.keystore.b64"
gh secret set OMARCHY_ANDROID_TEST_KEYSTORE_PASSWORD \
  --repo Audrey-cn/omarchy-android \
  < "$HOME/.local/share/omarchy-android-signing/test-20261123/password.txt"
```

维护者需要管理仓库 Actions secrets 的权限；workflow 仅需 `contents: read`，不需要 secrets 写权限。GitHub 支持[从文件设置 secret](https://docs.github.com/en/actions/how-tos/write-workflows/choose-what-workflows-do/use-secrets)。将私有目录做加密离线备份；不要上传仓库、Release、构建附件或聊天，也不要重新生成来“恢复”丢失密钥。`.p12`/`.pfx`/`.keystore.b64` 已加入忽略规则，但忽略规则不能替代私有存储。

## 数据恢复验收边界

APK 更新不会把新 rootfs 解压到已有 guest；启动前通过独立 managed assets 更新核心命令，保留容器和个人数据。Gradle 的 `prepareManagedCoreAssets` 任务每次从当前 checkout 生成资产，壳包与一体包都包含同一套核心命令。只对容器代号 `omarchy` 生效，其他导入的 Linux 容器不受影响。

- 默认清单只有 `core/bin` 和 `core/ai-suite/*launch` 中的脚本，安装为 `usr/local/bin/<文件名>`。不复制 Sway/fcitx 配置，也不管理 `/root/.hermes`、浏览器 profile 或应用私有配置。
- `managed-core/manifest.json` 包含 `schemaVersion: 1`、40 位 `sourceSha`，以及 `files` 数组；每项为 `path`（相对 guest 根目录）、`sha256`（64 位小写十六进制）与 `mode`（420/0644 或 493/0755）。每份 regular file 放在 `managed-core/files/<path>`。生成器和运行时都拒绝路径穿越、符号链接、重复条目、坏哈希和清单外路径。
- 预留的管理范围为 `usr/local/bin/<文件名>`、`opt/omarchy-chromium128/**`、`opt/omarchy-node/**`。额外 payload 必须显式设置 `OMARCHY_ANDROID_MANAGED_PAYLOAD_DIR`，提供同 schema、同源码 SHA 的完整资产树，生成器验证每份文件后才合并。当前默认不内置浏览器候选。
- 首次升级旧容器时，内容不同的既有 managed 文件会先精确备份到应用私有 `files/managed-core/omarchy/legacy-backup/<原路径>`；随后只更新已知旧哈希或不存在的目标。之后检测到用户改过的内容，或新管理路径已有其他文件，会保留文件、显示冲突并停止启动，避免悄悄覆盖。
- 先在私有临时目录验证所有待更新 payload，再逐文件原子替换；清单退役文件只在哈希仍匹配时删除。最后才原子保存 `files/managed-core/omarchy/manifest.tsv` 回执。中断后可识别已发布的新哈希并重试，不重新导入 rootfs。每次启动核对 managed 文件内容；大型可选 payload 的首次同步时间和磁盘暂存空间仍需设备实测。

原数据归档已完整校验；历史备份本身不等于跨版本恢复验收。切换持续签名后的恢复，以及连续两版覆盖安装保留数据的设备结果，以验证记录为准。

当前已验证产品代码为 `e876b3b`，集成到 `main`；后续文档提交不改变该 APK 的产品代码。云端和 K20 证据见[设备验证记录](reports/cloud-k20-validation-20261123.md)。
