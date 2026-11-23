# 已安装桌面的组件更新

在 guest 中打开「更新与维护」，选择项目桌面组件。检查会显示已安装和可用版本、变更、兼容要求、包大小和 SHA-256。检查与安装分开，安装需要 guest root。发布来自 [本项目的 GitHub Releases](https://github.com/Audrey-cn/omarchy-android/releases)。

```sh
omarchy-component-update status --json
omarchy-component-update check --json
omarchy-component-update install desktop
omarchy-component-update inventory --json
```

**旧镜像首次接入。** 在已确认的项目 Release 页面选择 `desktop-v版本`，下载同一 Release 的 `omarchy-component-update` 附件。用页面列出的 SHA-256 校验后，在 guest 里显式运行。例如接入 1.0.0：

```sh
cd /tmp
tag=desktop-v1.0.0
curl -fL --output omarchy-component-update \
  "https://github.com/Audrey-cn/omarchy-android/releases/download/$tag/omarchy-component-update"
sha256sum omarchy-component-update
# 与 Release 附件列出的 SHA-256 核对成功后执行
python3 ./omarchy-component-update install desktop --version 1.0.0
```

1.0.0 适配 Ubuntu Noble ARM64、运行时 API 1。PRoot 还要求 APK versionCode 至少为 64。新启动器通过环境提供实际标识。对于旧环境，先核对已安装 APK 的 versionCode，以及项目镜像和运行时版本；确认后可在命令末尾提供 `--runtime-kind proot --runtime-api 1 --apk-version-code 实际数字`。项目的 Root chroot 启动器会提供 `--runtime-kind chroot` 对应的环境标识，该模式只使用 guest 与运行时版本条件。

**文件冲突和恢复。** 组件包将程序和参考默认配置放在 `/usr/lib/omarchy-android/desktop`。Sway、Fcitx5 继续使用用户的活动配置。首次安装仅接管可识别的 `/usr/local/bin` 项目文件；遇到改写的启动器，会显示冲突路径。可保留原文件并退出，或自行将其移到备份路径，再重试安装。安装器只在通过下载长度、哈希、包归属和文件校验后开始写入组件。

若中断或需恢复上一次桌面版本，运行 `omarchy-component-update recover`。若该入口尚未激活，可用最初下载并校验过的 `python3 /tmp/omarchy-component-update recover`。首次安装前的启动器备份和后续升级的前一份桌面包由 guest 内的事务记录管理。恢复作用于桌面组件和这些启动器。

**发布前实机验收。** 从本仓已认证的构建任务取得候选目录及其 `desktop-component.json` 的可信 SHA-256。目录需要同时包含该 JSON 和指定版本的 DEB。将两个文件放入 guest，使用同一更新器执行：

```sh
python3 /tmp/omarchy-component-update install desktop --version 1.0.0 \
  --candidate-dir /tmp/desktop-candidate --catalog-sha256 "$VERIFIED_CATALOG_SHA256"
```

该路径在本机验证目录中清单的预期摘要，再按清单核验包本体，通过同一套兼容检查、安装事务与恢复入口。正式发布时使用 `desktop-v1.0.0` 独立标签并上传 DEB、JSON 和对应的独立更新脚本；不改变 APK 的 latest 指向。
