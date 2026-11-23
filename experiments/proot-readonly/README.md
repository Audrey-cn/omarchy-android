# PRoot 只读共享内存实验

此目录用于诊断 Codex/Owl Chromium 158 的原生子进程崩溃，不进入 APK、rootfs 或默认启动流程。当前没有 Android 验收成功结论。设备操作由主代理统一执行。

## 实验假设

旧 Tiny fork 将 fake-root 进程的 `/proc/<pid>/fd/N` 打开替换成 `dup(N)`。当来源为 `O_RDWR`、请求为 `O_RDONLY` 时，这会返回仍可写的句柄。Chromium 的只读共享内存检查因此正确地拒绝该句柄。

本实验只回补 Termux [a179d3e8](https://github.com/termux/proot/commit/a179d3e8a4e045aaa1fb8cc3284f23509d96d353)，保持其原始补丁字节。修复在目标是普通文件/memfd 且访问模式不同时保留内核真实 open；不会授予权限。

K20 实际应用上下文中的真实 memfd reopen 曾返回 `EACCES`。但 Chromium 158 [CreateAnonymousRegion 的失败](https://github.com/chromium/chromium/blob/158.0.8127.37/base/memory/platform_shared_memory_region_posix.cc#L130) 可以触发 [Create 自带的文件后端](https://github.com/chromium/chromium/blob/158.0.8127.37/base/memory/platform_shared_memory_region_posix.cc#L300)：它自行创建可写和只读句柄、验证同 inode 并 unlink。因此真实失败可能让程序安全回退，而旧错误的 dup 成功会阻止该回退。此假设需要真实 Codex 测试，探针通过不能代替 GUI 验收。

## 固定输入与构建

所有 URL、版本和校验值位于 [sources.env](sources.env)。Tiny 源码固定为 `2919ebdcb865b192490f8c4ca1957a621f13d95b`，保留 `--tiny-storage` 等 fork 代码；a179 补丁仅修改 `fake_id0.c`。两组源码都先应用相同的 `build-compat.patch`，仅为 `ashmem_memfd.c` 补充其 `strcmp`/`memset` 所需的 `<string.h>` 声明，保证实验差异仍只有 a179。

- talloc `2.5.0` 的 SHA-256 与交叉编译答案参照 Termux [固定版本构建脚本](https://github.com/termux/termux-packages/blob/e770a66ab0cd21f698295a482725040cc5feef82/packages/libtalloc/build.sh)。静态链接 talloc，避免给实验 runner 增加动态库查找变量。
- NDK r27d `27.3.13750724`、Android API 28。下载后核对 Google [官方 SHA-1](https://github.com/android/ndk/wiki/Unsupported-Downloads#r27d)，并记录实际 SHA-256。没有未经校验的系统 NDK 复用路径。
- AArch64 与 ARM32 loader 使用各自 NDK driver。两个产物组使用同一套 loader 源码和配置，并检查最终 loader 字节相同。
- 补丁先执行 `patch --dry-run --fuzz=0`，失败即停止；没有自动修改或宽松跳过路径。

在 Ubuntu 24.04 x86_64、安装构建工具后运行：

```bash
bash experiments/proot-readonly/build-android.sh \
  /tmp/omarchy-proot-build-review /tmp/omarchy-proot-artifacts
```

工作目录必须不存在，产物目录必须为绝对路径。脚本下载固定源码和 NDK，编译两组 Android 可执行文件，运行宿主 Linux 原生探针，生成以下实验产物：

```text
control/proot                  # 未回补的固定 Tiny 源码
patched/proot                  # 同源 + 原样 a179d3e8
{control,patched}/loader-*      # 对应 loader；构建末尾检查两组相同
procfd-probe-android-arm64      # 独立 Bionic 探针，无 PRoot/talloc 依赖
probe-linux-control.txt        # 普通 Linux 上的真实探针结果
applied-source.diff
receipt.json                   # cross_compiled_not_device_validated
download-sha256.txt
SHA256SUMS
```

`.github/workflows/experimental-proot-readonly.yml` 仅通过 PR 的本目录或 workflow 路径变动触发。权限仅为 `contents: read`，只上传实验 artifact，绝不写 `jniLibs`、发布 Release 或安装到设备。新 workflow 尚未进入默认分支时，应由主代理审查后推送并创建/更新 PR，不能假定可对分支直接 dispatch。

## 最小设备验证顺序

先记录 APK、内核、PRoot executable、两种 loader 的 SHA-256 和实际 SELinux domain。所有路径使用实际应用 `/data/data` 的 rootfs，避免绝对 `.proot.l2s` 链因 `/data/user/0` 别名失效。

1. 从实际 Sway 会话运行当前 guest 探针，分别为 fake-root 与 `setpriv --reuid=1000 --regid=1000 --clear-groups`。也可在 guest 中用 `gcc -std=gnu11 -O2 -Wall -Wextra -Werror procfd-probe.c -o procfd-probe` 编译。
2. 在 Android host 直接运行 artifact 中的 Bionic 探针，并显式将 `TMPDIR` 设为该 app 自己可写的私有 cache 目录（Android host 通常没有 `/tmp`）。应优先使用实际 app 自己创建的 host 进程；若使用 ADB `run-as`，必须标明它可能是不同的 `runas_app` 域，不能冒称同 app 上下文对照。此步骤不经 PRoot，才可隔离路径转换。
3. 私有 runner 中用同一 rootfs、配置、身份、环境和 loader，只切换 `control/proot` 与 `patched/proot`。不要更新应用设置或现用 `jniLibs`。可先记录原 loader，再单独验证 artifact 的配套 loader；任何 loader 变化都单列，不与 a179 的执行文件对照混合。
4. 查看 `path_stat ... regular=1`、来源 flags、reopen errno、实际 mode 与安全检查。同时间只读捕获 AVC 拒绝记录，结合 `selinux kind=memfd label=...` 判断内核权限原因。
5. 若 patched 让原错误 `O_RDWR` 返回变为真实 `EACCES`，这不是只读 FD 通过，应继续验证 Chromium 自有文件后端是否启用。保持原 Codex launcher 及 flags，运行全新临时 profile；观察各子进程、窗口和日志，确认原 `0xa284528` 陷阱消失。

## 如何读探针

- `metadata /proc/self/status` 给出真实内核 UID、TracerPid 等；`uid/euid` 在 PRoot 内可能被模拟，应同时看两者。
- `selinux` 是已有 FD 的实际标签，读取失败时只报告 errno。
- `path_stat` 对应上游补丁的 `stat/S_ISREG` 条件；guest 结果不保证 tracer 自己的 stat 一定相同。
- `readonly_enforced` 要求同 device/inode、`O_RDONLY`、`FD_CLOEXEC`、能读、`pwrite` 为 `EBADF`、可写 `MAP_SHARED` 为 `EACCES`，没有只相信 `F_GETFL`。
- `file_fallback ... nlink_zero=1` 和 `route=unlinked_pair ... readonly_enforced` 验证真实私有文件在 unlink 后仍具有正确只读句柄。这只是受控探针，不是给 Chromium 增加自制替代后端。
- 退出 `0` 表示至少一种 memfd 路径达到上述探针条件；`10` 表示没有，`3` 表示 memfd 创建失败，`2` 为探针准备失败，`77` 为非 Linux。退出 `0` 不代表 Codex、全部路径或免 Root Android 已通过。

所有写入只作用于探针自身 4 KiB 分配。探针不会读取用户应用数据，不伪造 `F_GETFL`、不绕过 Chromium `CHECK`、不修改 SELinux/target SDK，也不注册默认 runtime。宿主 Linux 正常结果不能外推 Android；最终至少需要 Codex 窗口稳定、子进程、输入和实际 Agent 操作验收。

## 首轮云编译记录

[45129222955](https://github.com/Audrey-cn/omarchy-android/actions/runs/45129222955) 于 2026-11-23 15:11:48（上海）停止在 control PRoot 的 `ashmem_memfd.c` 编译：固定 Tiny 源码遗漏 `<string.h>`，NDK r27 Clang 拒绝 `strcmp` 和 `memset` 的隐式声明。归档校验、Linux 原生探针、Android ARM64 探针和 talloc 交叉编译已先行通过；这次失败不是 Android/Codex 运行测试。上述共用 header 修复保持编译器检查开启，尚需后续云编译确认其余阶段。
