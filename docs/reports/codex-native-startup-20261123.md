# Codex ARM64 原生启动诊断（2026-11-23）

本次已定位并修正容器启动配置中的内核版本格式错误。修复解决 Codex 主进程最早的版本解析崩溃；Codex 的子进程仍在另一个位置崩溃，GUI 尚未验收通过。

## 设备、包与运行边界

- K20，免 Root Android PRoot，Ubuntu 24.04 ARM64；诊断使用实际应用的 `/data/data` 路径。
- Android 报告的实际内核为 `4.14.350-englezos-a8c95a375c`。
- 官方 DEB：`chatgpt 26.1117.41037 arm64`。
- DEB SHA-256：`714146e9c667b26a1bd4e57633c544334a9ed47c399989bfd98cf764140bdc53`。
- 主程序：`/usr/lib/chatgpt/ChatGPT`，ELF build ID `8a92ce83b196272489c872a82820c9ad56f186ff`；包内 Chromium 版本标识为 `158.0.8127.37`，二进制包含 OpenAI Owl 壳路径。
- `dpkg` 配置完成、动态依赖无 `not found`，捆绑 Node 直接加载 `node-pty` 的原生 binding 成功。这些检查不代表 GUI、登录或 Agent 操作通过。
- 同一会话中的 Hermes Electron 40.10.2 可以打开窗口；该版本使用 Chromium 144.0.7559.236，不能据此推断 Owl/Chromium 158 兼容。

官方包来源：[OpenAI ARM64 DEB](https://persistent.oaistatic.com/codex-app-prod/linux/deb/latest/chatgpt_arm64.deb)。官方 Linux 文档列出的平台为桌面 Linux，原生 Wayland 支持仍属实验性：[Linux app](https://learn.chatgpt.com/docs/linux/linux-app)。Electron 版本映射：[40.10.2](https://releases.electronjs.org/release/v40.10.2)。

## 已证主进程根因

原 `apps/android/container/omarchy.tiny.yaml` 使用：

```text
--kernel-release="Linux localhost 6.17.0-PRoot-Distro #1 SMP PREEMPT_DYNAMIC aarch64"
```

这是整行 `uname -a` 式文本，却被 PRoot 放进单独的 `utsname.release` 字段。应用期望该字段以数字版本开头。

临时、未纳入产品的 `LD_PRELOAD` 诊断捕获到：

| 项目 | 值 |
| --- | --- |
| 信号 | `SIGTRAP`, `si_code=1` |
| 主程序加载基址 | `0x3000000000` |
| 原始 PC | `0x300e26f588` |
| ELF 虚拟地址 | `0xe26f588` |
| 原始 LR | `0x300e26f540` |

匹配二进制反汇编：

```text
0xe26f520: bl    uname@plt
0xe26f53c: bl    __isoc99_sscanf@plt
0xe26f540: cmp   w0, #2
0xe26f544: b.ne  0xe26f588
0xe26f588: brk   #0
```

`sscanf` 的格式串在 ELF 地址 `0x1f7aea0`，内容为 `%d.%d`。其输入地址为 `utsname` 起点加 `0x82`，即 `release` 字段。

这与 V8 `KernelSupportsRemapDontUnmap()` 的版本解析检查一致：`CHECK_EQ(sscanf(buffer.release, "%d.%d", &major, &minor), 2)`。Chromium 158.0.8127.37 的 [DEPS](https://chromium.googlesource.com/chromium/src/+/158.0.8127.37/DEPS) 固定 V8 revision 为 `b601a2347f005e8bfadd32f285df97ffba5cf1bd`；该提交的 [platform-linux.cc，第 182–194 行](https://chromium.googlesource.com/v8/v8/+/b601a2347f005e8bfadd32f285df97ffba5cf1bd/src/base/platform/platform-linux.cc#182) 包含对应检查和 Linux 5.13 的功能门槛。

## PRoot 语义与修复

官方 PRoot [选项处理](https://github.com/proot-me/proot/blob/master/src/cli/proot.c#L157) 将 `-k` / `--kernel-release` 交给 `kompat` 扩展。该扩展的 [parse_utsname](https://github.com/proot-me/proot/blob/master/src/extension/kompat/kompat.c#L875) 在普通参数模式下将参数原样复制到 `utsname.release`；[uname 退出处理](https://github.com/proot-me/proot/blob/master/src/extension/kompat/kompat.c#L710) 再将配置的结构写回应用。它不按空格解析 `uname -a` 的列。

`-k` 同时提供有限的旧内核系统调用兼容处理，不会升级宿主内核，也不保证现代内核功能全部可用。PRoot 官方说明，系统资源请求最终仍交给宿主内核：[运行模型](https://proot-me.github.io/)。

本次最小修复是删除该 `--kernel-release` 参数，保留实际宿主内核报告，而非长期固定成 `6.17.0` 或另一台设备的版本。PRoot 的其他启动参数保持原样。

该 YAML 用于新构建镜像的容器启动配置。已经导入的容器有自己的已保存配置；仅更新仓库文件不代表设备上的现有配置已经更新。

## 对照结果与剩余问题

1. 原错误字符串：主进程在上述 `sscanf` 检查处立即崩溃。
2. 单次诊断改成 `6.17.0`：最早解析崩溃消失，推进到窗口/DBus 初始化，随后子进程崩溃，GUI 未通过。
3. 单次诊断使用 `4.14.190`：GPU、Network、Storage 等不同子进程在共同的 ELF 地址 `0xa284528` 触发 `BRK`，LR 为 `0xa284430`；主进程随后报告 GPU 不可用并退出。

## 子进程后续定位：只读共享内存句柄

进一步的寄存器与原栈捕获显示，`0xa284528` 位于 `base::subtle::PlatformSharedMemoryRegion::Take()`，在 `TakeOrFail(...).value()` 取得错误结果时触发陷阱。对应 [Chromium 158 的 Take 实现](https://github.com/chromium/chromium/blob/158.0.8127.37/base/memory/platform_shared_memory_region.cc#L36)。

| 捕获项 | 值及含义 |
| --- | --- |
| 原栈 `sp+0x30` 低 32 位 | `0`，Linux `TakeError::kExpectedReadOnlyButNot` |
| `x22` / mode | `0`，`kReadOnly` |
| `x21` / size | `4` 字节 |
| 原栈保存的 FD | 示例为 `10` 或 `18` |
| `CheckFDAccessMode` 返回值 | `0x100000001`，表示模式不匹配；不是 `fcntl` 调用失败 |

枚举依据：[platform_shared_memory_region.h](https://github.com/chromium/chromium/blob/158.0.8127.37/base/memory/platform_shared_memory_region.h#L95)。权限检查依据：[POSIX 实现](https://github.com/chromium/chromium/blob/158.0.8127.37/base/memory/platform_shared_memory_region_posix.cc#L391)，它比较 `fcntl(fd, F_GETFL) & O_ACCMODE` 与所需访问模式。

新安装的 APK 环境中的独立 Python 探针确认了同一异常：

1. 用 `os.memfd_create(..., MFD_CLOEXEC | MFD_ALLOW_SEALING)` 创建可读写句柄。
2. 用 `O_RDONLY | O_CLOEXEC` 打开 `/proc/self/fd/<fd>`。
3. 返回句柄的实际访问模式仍是 `2`（`O_RDWR`），并没有得到请求的 `O_RDONLY`。
4. 对拥有的普通临时文件按真实文件路径只读打开，访问模式为正常的 `0`；通过 `/dev/fd/<fd>` 打开则返回 `EACCES`。

随后只切换执行文件为应用捆绑的 `proot-latest`、保留同一 loader 和探针条件，结果同样为 `O_RDWR`（`2`）。这是诊断 runner 的对照，没有切换产品默认运行时。

这证明 Chromium 检查发现的访问模式异常真实存在。不能通过伪造 `F_GETFL`、删除只读检查或宣称修改 GPU 驱动已经解决来处理。

提供捆绑 PRoot 基础的 fork 中，已经找到与现象吻合的实现：[fake_id0.c](https://github.com/tiny-computer/proot-termux/blob/master/src/extension/fake_id0/fake_id0.c#L748)。该实现对 fake euid 为 `0` 的进程，将 `/proc/<pid>/fd/N` 的 `openat` 改成 `dup(N)`，只对 `O_PATH` 来源句柄跳过替换，没有检查请求与来源句柄的访问模式是否相同。`dup` 继承已有打开文件描述的访问模式，无法将可读写句柄降为只读。

该替换由 [ee10b279](https://github.com/tiny-computer/proot-termux/commit/ee10b279a38d34b6704345bc448d18f019ca1b49) 引入，[312e880c](https://github.com/tiny-computer/proot-termux/commit/312e880c1aea9509afc456e5935669d8f5ba2708) 后续只修正了 `O_PATH` 情况。捆绑二进制也包含该分支使用的 `/proc/<pid>/fdinfo/<fd>` 与权限仿真实现标识，但其精确构建提交尚未核定。

匹配 Chromium 158 源码的非 executable 共享内存路径直接尝试 `memfd_create`，没有 `MemfdForSharedMemory` 的 `FeatureList` 判断，捆绑 ELF 也没有这一 feature 字符串。因此没有将 `--disable-features=MemfdForSharedMemory` 当作已验证的控制开关。

Termux 上游已有对应修复：[a179d3e8a4e045aaa1fb8cc3284f23509d96d353](https://github.com/termux/proot/commit/a179d3e8a4e045aaa1fb8cc3284f23509d96d353)，于 2026-10-01 合入。它在已知来源访问模式与请求模式不同、且目标为普通文件或 memfd 时，保留真实内核 `openat`，不再替换为 `dup`；tty、pipe、socket 的原兼容路径保留。相关 [上游问题 #404](https://github.com/termux/proot/issues/404) 也记录了 Chromium 新版本子进程崩溃、非 root 身份对照和 PRoot `5.1.107.96` 的后续结果。上游结果不能代替本项目 K20 的验收。

### 本机真实 reopen 的权限边界

K20 随后的非 fake-root 对照仍失败：对同一 memfd 真实只读打开 `/proc/self/fd/<fd>` 返回 `EACCES`（errno `13`）。进一步通过应用自己的 Sway 会话执行 `setpriv --reuid=1000 --regid=1000 --clear-groups python3 probe.py`，探针报告 `uid=1000`、Android SELinux context domain 为 `untrusted_app`、`open_errno=13`。因此该限制也存在于实际应用上下文，不能只归因于 ADB 的 `runas_app` 测试上下文。当前证据尚未区分导致该 `EACCES` 的具体内核权限检查。

上游补丁仅在读取来源 flags 成功、请求与来源的访问模式不同、`stat()` 成功且目标为 `S_ISREG` 时，跳过错误的 `dup` 替换；它没有授予额外权限，也没有在真实 `openat` 返回 `EACCES` 时生成只读句柄的处理。因此它是访问语义的正确性修复，但在本机可能只会把错误的 `O_RDWR` 成功结果变为真实的权限失败。尚不能把它称为 K20 的可用性修复。

上游问题 #404 的 [原始诊断日志](https://github.com/termux/proot/issues/404#issuecomment-5931350454) 包含 `untrusted_app_27` 标签；本次实际应用探针报告的是 `untrusted_app`。该讨论的全部 12 条评论没有给出真实 reopen 返回 `EACCES` 的对应处理，也没有建立对所有 Android 版本、SELinux 域和设备都可用的保证。[非 root 可用的评论](https://github.com/termux/proot/issues/404#issuecomment-5932206031) 与 [新版恢复运行的反馈](https://github.com/termux/proot/issues/404#issuecomment-5945725000) 仅是各自环境的结果。

若后续验证 patched PRoot，应先在私有诊断 runner 中回补确切上游修复并保留本应用扩展，验证 flags/stat 分支和真实 reopen 的结果。只读句柄必须通过实际访问模式、`write` 和可写共享映射检查，不能只看程序没有崩溃。本次产品修改没有绕过共享内存权限检查，也没有加入临时文件替代实现，尚未替换产品中的 PRoot 二进制。

### 可验证的后续路线：让 Chromium 处理真实失败

进一步检查匹配版本的完整创建流程后，需要细化上述 `EACCES` 边界：它证明 memfd 的 procfd 只读重开未成功，尚不能单独证明上游补丁后整个 Chromium 共享内存流程不可用。

Chromium 158 的 [CreateAnonymousRegion，第 130–143 行](https://github.com/chromium/chromium/blob/158.0.8127.37/base/memory/platform_shared_memory_region_posix.cc#L130) 在只读 `open()` 失败时返回空句柄；上层 [Create，第 300–307 行](https://github.com/chromium/chromium/blob/158.0.8127.37/base/memory/platform_shared_memory_region_posix.cc#L300) 随即进入自身既有的文件后端。该后端按真实文件路径打开可写与只读句柄，校验同一 device/inode，并在创建流程结束前 unlink 文件（[第 329–386 行](https://github.com/chromium/chromium/blob/158.0.8127.37/base/memory/platform_shared_memory_region_posix.cc#L329)）。

因此可以先回补确切的 `a179d3e8`，观察错误的 `dup` 成功是否变为真实 `EACCES`，再检查 Chromium 是否安全使用自身文件后端并越过原子进程陷阱。这不需要伪造 `F_GETFL`、删除 `CHECK`、自制临时文件 shim 或放宽 Android 安全策略。此路线尚未实测通过；也不保证其他必须直接使用 memfd 的调用点可用。

配套实验探针位于 `experiments/proot-readonly/procfd-probe.c`，仅操作自身的 4 KiB memfd 和私有临时文件。它记录进程身份、`TracerPid`、dumpable、SELinux 文件标签、`stat()` 结果，并分别测试 self/numeric/thread-self/dev-fd/openat 路径。成功只读句柄还必须满足同 inode、`O_RDONLY`、`FD_CLOEXEC`、实际 `pwrite()` 返回 `EBADF`、可写 `MAP_SHARED` 返回 `EACCES`。

AOSP Android 14 的 [app_domain 宏](https://android.googlesource.com/platform/system/sepolicy/+/refs/tags/android-14.0.0_r1/public/te_macros#209) 对 app 创建的 tmpfs 对象使用 `appdomain_tmpfs` 标签，并列出 `execute/getattr/map/read/write` 权限，未在该宏中授予 `open`。这提供了区分“已有句柄可读写”和“重开被拒”的官方线索，但单个宏不能证明 K20 ROM 的完整有效策略。仍需同时间 AVC 拒绝记录及探针实际文件标签来确认内核权限原因；不修改 SELinux 或 target SDK。

Codex GUI 仍未通过。需要在使用真实内核报告的新启动配置下继续验证，并分别验收首次启动、窗口稳定性、输入、登录和实际 Agent 操作。

原始寄存器捕获、反汇编和设备日志为本次私有诊断材料，未随产品提交。临时 trap handler 不进入镜像或应用。
