# 在线初装与独立 App 更新验收（2026-11-23）

本轮按已确认的发行边界实施：镜像只供首次安装，Ubuntu 软件使用 APT 源；App、X11 与 PRoot 独立发版，正常启动不再向已有 guest 同步文件。

## 已验证的云端交付

- [APK 构建 45165511310](https://github.com/Audrey-cn/omarchy-android/actions/runs/45165511310)：精简 APK 33,705,920 字节，无 rootfs 或受管 Linux 载荷；版本、临时 PR 签名和原生 PRoot 三件套校验通过。这是 PR 测试产物，未覆盖安装到 K20。
- [独立镜像构建 45165450697](https://github.com/Audrey-cn/omarchy-android/actions/runs/45165450697)：源提交 `e55030353df6bae7496720356f2d4b940495824e`；[初装镜像发行版](https://github.com/Audrey-cn/omarchy-android/releases/tag/desktop-ubuntu24-e55030353df6) 与[可用镜像目录](https://github.com/Audrey-cn/omarchy-android/releases/tag/desktop-images)均保持非 latest。
- 镜像压缩大小 999,745,976 字节，普通文件逻辑总量 2,973,377,562 字节；SHA256 为 `88a1558fc93489016ad1c53fccb03ebc0f3986507dccf6b422de444d915f33c6`。完整附件和镜像校验通过，逐项扫描 175,306 个归档条目，无未物化硬链接、设备节点、运行时挂载内容或 Hermes 凭据；machine-id 留空，首次安装单独生成。
- 公开镜像地址实际响应 HTTP 206、正确 Content-Range 与强 ETag；公开 catalog 与随包目录一致。首次安装支持相同版本的本地导入。
- [含隔离设备测试的 APK 构建 45167898852](https://github.com/Audrey-cn/omarchy-android/actions/runs/45167898852) 编译、单元测试和打包校验通过。

## 安装和更新边界

初装先下载并校验，在独立暂存目录解压和配置，再登记新桌面；已有的同名 guest 不会被初装流程覆盖。中断的下载保存校验过的续传信息；解压和登记恢复、离线缓存生命周期单独验证。旧的 `ManagedCoreAssets` 启动同步调用已移除，既有文件和历史同步收据保留。

本轮桌面引导改为等待当前 X11、Sway、Waybar 和视口就绪，取消固定延迟触发；已完成引导的偏好继续有效。

## K20 Pro 实机验收状态

同签名新版 APK 覆盖安装、既有数据对比、实际 HTTPS 进程中断续传、隔离初装、桌面就绪引导和真实 Hermes Agent 回归尚待执行。当前不以云端编译或旧版记录代替这些结果。

Root chroot 的完整冷安装与独立分区启动不属于本轮已完成范围，保留后续集成与讨论。
