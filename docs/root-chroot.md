# Root 冷安装与桌面启动

这个入口使用已公开的干净 Ubuntu 24.04 ARM64 初装镜像，默认创建 `/data/omarchy-chroot/rootfs`。Android 应用继续使用自己的 guest。目录已存在时安装器退出，包括上一次掉电留下的未完成目录。先用 `--inspect` 查看位置并核对里面的数据。

在 Android ARM64 的 Termux 中安装 zstd、curl、Termux:X11 命令和配套 Android 应用。Root 需要可以执行 chroot、创建私有挂载命名空间和挂载 proc、tmpfs、devpts。默认使用 Magisk BusyBox，可通过 `OMARCHY_BUSYBOX` 指定另一个兼容的绝对路径。Root 使用系统当前的 SELinux 策略。脚本不写分区，也不变更系统安全设置。

从仓库目录执行。联网安装需要约 4.51 GB 空闲空间，离线安装另需约 3.51 GB 空闲空间，离线镜像本身占用的空间另计。

```sh
bash install.sh --mode chroot --inspect
bash install.sh --mode chroot --dry-run
bash install.sh --mode chroot
```

已有下载的镜像时，可在以上预检或安装命令末尾加 `--image /绝对路径/rootfs.tar.zst`。下载和导入均检查固定发布的字节数及 SHA-256。直接从已获得 Root 的 Android shell 操作，需要保留可信仓库的完整目录结构，从仓库根目录使用 `/system/bin/sh runtime/chroot/install-clean.sh --image /绝对路径/rootfs.tar.zst`，并把 zstd 所在目录加入 PATH。安装器在新建 guest 内引入当前 checkout 的桌面命令和会话隔离助手，以便后续主题切换同样只影响当前 Sway 会话。正常退出的失败安装会清理本次创建的目录。[公开初装镜像](https://github.com/Audrey-cn/omarchy-android/releases/tag/desktop-ubuntu24-e55030353df6)

在普通 Termux 会话启动显示服务器，另开终端启动桌面。前台启动器持续到 Sway 退出，可用 Sway 的退出操作结束。Root 桌面使用自己的 Sway、Waybar、Chromium 和浏览器资料。

```sh
termux-x11 :1
# 另一个终端
su -c /data/omarchy-chroot/start-omarchy.sh
```

运行单条 guest 命令可使用以下入口。每个命令在独立的挂载命名空间执行。正在运行的桌面的 Wayland、D-Bus 和 Sway IPC 属于该会话，查询它们应在桌面终端进行。退出时 Linux subreaper 会回收本次命令及其分离会话的后代。该实现支持只有挂载命名空间的 Android 内核。

```sh
su -c '/data/omarchy-chroot/ubuntu-chroot.sh /usr/local/bin/sway --version'
su -c '/data/omarchy-chroot/ubuntu-chroot.sh /usr/local/bin/omarchy-playwright128 check'
```

同负载对比应记录设备内核、温控状态、镜像和组件版本，分别记录多次执行的耗时与实际失败。图片处理或 CPU 循环的结果只能描述测量的任务。输入、旋转、浏览器 Agent 和桌面恢复需要单独观察。

## 独立分区与启动

Root chroot 和 PRoot 都使用设备当前的 Android 内核。独立启动 Linux 还取决于具体设备变体、引导加载器的解锁状态、启动镜像格式、设备树、内核与显示/触摸/存储/电源驱动以及可恢复的原厂镜像。已知 K20 运行 Android chroot 不能证明其能独立启动相同 Ubuntu 桌面。仓库这条安装路径只分配数据目录。独立分区与引导支持需另建按机型核实的设计和恢复方案。
