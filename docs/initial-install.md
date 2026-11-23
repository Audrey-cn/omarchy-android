# 首次安装与更新

免 Root 一体 App 将 Android 界面、内嵌 X11 和配套 PRoot 放在精简 APK 内，Ubuntu 桌面与浏览器放在独立的初装镜像内。正式产品保留这一免 Root 路线和 Root chroot 路线；外部 Termux/X11/Deck 的免 Root 组合用于开发、调试和迁移。

## 当前交付状态

当前公开 App 为约 33.8 MB 的 [v1.0.79-test](https://github.com/Audrey-cn/omarchy-android/releases/tag/v1.0.79-test)。已在 K20 测试从 72 同签名覆盖升级，以及隔离 App 的原生 SAF 离线导入和首次桌面显示。真模型与运行时验收分别记录在[本轮交付报告](reports/update-maintenance-k20-20261124.md)。

刷新[镜像目录](https://github.com/Audrey-cn/omarchy-android/releases/tag/desktop-images)可获取推荐的[初装镜像 `desktop-ubuntu24-91a120142609`](https://github.com/Audrey-cn/omarchy-android/releases/tag/desktop-ubuntu24-91a120142609)，压缩包 904,766,052 字节，逻辑解压大小 2,620,243,891 字节。历史镜像 `desktop-ubuntu24-e55030353df6` 仍在目录中，也随 App 内置目录提供离线回退。两份镜像要求 App 版本代码至少为 64。本轮初装快照包含组件 1.0.0；安装后可显式更新到桌面 1.0.3。

旧版 [v1.0.72-test](https://github.com/Audrey-cn/omarchy-android/releases/tag/v1.0.72-test) 的首次安装验收覆盖了目录刷新、下载中断恢复、完整镜像在线下载安装及本地离线导入。完整在线下载使用当轮隔离验收环境，条件见[历史结果](reports/online-installer-k20-20261123.md)。

## 新用户首次安装

1. 从项目 [v1.0.79-test](https://github.com/Audrey-cn/omarchy-android/releases/tag/v1.0.79-test) Release 安装 `arm64-v8a` APK。无需另装 Termux 或 Termux:X11。
2. 打开 App，在“安装 Omarchy 桌面”页面选择初始镜像。列表只提供与当前 App 和架构兼容的版本；“刷新可用镜像”读取项目发布的目录。没有兼容条目时，先等待镜像发布或更新 App。
3. 查看页面显示的下载量与空间要求，点击“下载并安装桌面”。需要为下载缓存、解压后的桌面和至少 512 MiB 余量留出空间。下载显示实际字节进度与通知；校验和解压显示等待状态。
4. 可点击“暂停下载”，之后点击“继续下载并安装”。已下载内容与任务信息保存在磁盘；进程退出或设备重启后，重新打开 App 继续任务。网络中断后也可继续，不需要每次从头下载。“取消下载”清理该任务的下载缓存。
5. 安装完成后点击“启动桌面”。新手引导等待当前桌面和 X11 视口就绪；等待超时可重试或跳过。

镜像必须通过完整字节长度与 SHA256 校验。续传会核对资源身份与服务器的范围响应，不能把不同镜像拼接后安装。安装先解压到独立暂存目录，通过必要文件及配置检查后才登记桌面；已有桌面目录会阻止首次安装覆盖。失败后可继续或重试。

## 离线导入

在联网设备上取得所选目录条目对应的原始 `rootfs.tar.zst`，复制到手机。先在安装页面选择对应的初始镜像版本，再点击“从本地镜像安装”并选择文件。导入会核对该条目的大小和 SHA256，文件必须与所选版本完全匹配；随意改名、其他版本或个人环境备份不能作为该条目的替代品。离线使用仍需要随包或已缓存的匹配目录。

已有用户的同名容器重新导入属于重装操作，不是日常更新入口。个人环境备份与换机迁移应另行处理。

## App、Ubuntu 与镜像各自如何更新

| 内容 | 更新方式 | 对已有桌面的影响 |
| --- | --- | --- |
| App、内嵌 X11、配套 PRoot | 使用同一持久签名密钥签出的新 APK 覆盖安装 | 保留原有 guest、软件、浏览器资料和个人数据；正常启动不向 guest 同步文件或替换 rootfs |
| Ubuntu 内的软件包 | 保留 guest 内配置的 Ubuntu APT 软件源 | 在现有环境中安装和更新软件；不依赖重下初装镜像 |
| 项目桌面组件 | 从桌面「更新」显式安装独立桌面组件；旧镜像用单独下载并校验的更新器首次接入 | 程序和参考默认配置分别受管，活动配置与用户资料继续保存在 guest |
| 图形栈、Chromium/Playwright/Node 配套 | 按组检查、安装和恢复独立运行时包 | 项目维护定版组合，按文件归属与兼容条件检查后替换 |
| 初装镜像 | 低频汇总修正，目录可提供一个或多个兼容版本 | 供新安装选择；发布新镜像不会自动更新或覆盖已有环境 |

覆盖升级应保留已安装 App，使用项目持续签名的兼容新版本。卸载或清除 Android 应用数据会移除该应用私有存储中的桌面与数据，不能代替覆盖升级。

镜像里的定版浏览器配套通过项目运行时组件渠道维护。第三方应用使用各自支持的更新方式。旧镜像首次接入与恢复步骤见[组件更新说明](component-updates.md)，各来源的状态与覆盖见[更新来源](third-party-updates.md)。

## Root chroot 首次安装

在已 Root 的 ARM64 Android 手机上，从完整的可信仓库工作副本运行 `./install.sh --mode chroot --inspect` 查看镜像摘要和目标状态。准备 BusyBox（默认使用 Magisk 的 `/data/adb/magisk/busybox`，也可设置 `OMARCHY_BUSYBOX`）、`zstd` 及支持私有挂载命名空间的内核。桌面显示还需外部 Termux:X11。

- `./install.sh --mode chroot --dry-run` 检查设备能力和空间。
- `./install.sh --mode chroot` 下载并核验安装器固定的公开镜像。已取得匹配镜像时可加 `--image /path/to/rootfs.tar.zst` 离线导入。
- 安装器仅接收空闲的 `/data/omarchy-chroot` 目标。已有 Ubuntu 环境可继续使用原路径。导入完成后以安装目录中的 `start-omarchy.sh` 启动，默认连接 Termux:X11 的 `:0`，可显式选用另一空闲显示编号。启动命令与并行会话示例见 [Root 冷安装与桌面启动](root-chroot.md)。

## 发布规则

初装镜像只从云端干净环境构建，附带精确大小、SHA256、镜像组装提交、组件构建提交和许可信息，不使用手机个人系统快照。版本化镜像使用独立的 `desktop-ubuntu24-<sha12>` Release，设置为非 latest；镜像目录指向已发布的固定资源，随包目录提供离线回退。已发布的目录入口为项目拥有的 `desktop-images` Release 下的 `catalog.json`。

频繁的精简 APK 构建与低频的初装镜像构建独立进行。App 使用正常应用 Release；镜像发布不抢占 App 的 latest 入口。Release 附件和验证记录是判断可用版本的依据，工作流存在或本地构建成功不代表已经公开发布。
