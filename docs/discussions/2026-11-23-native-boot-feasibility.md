# 独立分区与原生启动的可行性

本轮完成方案调查，不修改手机分区或启动链。Root 冷安装、组件更新与原生启动分别验收。

K20 Pro 只读核对得到设备代号 `raphael`、Android 14、当前内核 `4.14.350-englezos-a8c95a375c`。这些结果不证明该机型具备通用 GKI 或桌面 Linux 的完整驱动支持；后续仍需单独核对启动镜像和分区布局。

| 路线 | 当前 rootfs 能否复用 | 额外工作与适用范围 |
| --- | --- | --- |
| Android 内的 Root chroot | 可作为用户空间基础，仍使用 Android 内核 | 本轮提供新目录冷安装与运行时；无需重新分区 |
| 单独存储分区或文件系统上的 chroot | 用户空间可复用，但仍运行在 Android 内 | 需要挂载、权限、存储生命周期与恢复设计；仅改变存储位置不等于独立启动，当前已有独立目录可先满足隔离需求 |
| 脱离 Android 原生启动 Linux | Ubuntu 用户空间可部分复用，当前 rootfs 不能直接当作手机启动镜像 | 需要具体机型的内核、设备树、启动格式、显示/触摸/网络/电源驱动及恢复路径；按机型建立独立移植项目 |

## 依据与结论

Linux `chroot` 改变进程的文件系统根目录，内核继续由宿主提供，见 [Linux chroot 手册](https://www.man7.org/linux/man-pages/man2/chroot.2.html)。因此本项目现有 Ubuntu 包更新及 Root 运行时都不承担 Android 内核、GPU 驱动或启动链维护。

Android 启动镜像存在不同的内核、ramdisk 和 vendor boot 组合，见 [AOSP 启动分区说明](https://source.android.com/docs/core/architecture/partitions/generic-boot)及 [vendor boot 说明](https://source.android.com/docs/core/architecture/partitions/vendor-boot-partitions)。这不能被理解为所有旧手机都支持同一个 GKI 启动镜像。

采用动态分区的机型还需要考虑 `super`、逻辑分区与 fastbootd，见 [AOSP 动态分区说明](https://source.android.com/docs/core/ota/dynamic_partitions/implement)。是否使用这些布局必须逐台核对，不能从 Android 版本推断，也不能直接把容器压缩包写入分区。

ARM64 原生启动需要固件与设备树配合，见 [Linux EFI boot stub 文档](https://www.kernel.org/doc/html/latest/admin-guide/efi-stub.html)。即使 CPU 架构相同，也不能据此承诺跨机型通用的完整手机驱动支持。

建议正式产品继续维护免 Root App 和 Root chroot 两条路线；独立分区/原生启动保留为按机型移植的独立方向。开始移植前先核对该机型维护中的内核和驱动、可恢复启动方式、分区布局、触摸显示与休眠能力，再定义首台设备的实验范围。该调查不构成原生启动镜像已经可用的声明。
