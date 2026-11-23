# Hermes Desktop 原生模块兼容修复

## 问题与范围

Omarchy 的 Arch ARM64 包不能仅通过“解包成功”判定为适合 Ubuntu。已核对的 `hermes-desktop-2026.9.7-7-aarch64` 包实际包含 node-pty 1.1.0 的 `pty.node`，但该 ELF 的 `cfsetispeed`、`cfsetospeed` 导入要求 `GLIBC_2.42`；Ubuntu 24.04 的 libc 基线为 2.39。

node-pty 会依次尝试 `build/Release`、`build/Debug`、`prebuilds/linux-arm64`，捕获前面的错误后只显示最后一个错误。因此界面的“Cannot find module .../prebuilds/.../pty.node”可能掩盖已有 binding 的 libc 加载失败。

修复使用当前 guest 的编译器重新构建 Hermes 自己的 node-pty，不依赖 Claude 的安装或原生模块。已安装的 Electron、Node ABI、node-pty 版本及 guest libc 均在运行时读取；文中已核对的版本不是未来升级时的固定假设。

## 在 Ubuntu guest 中执行

下列两个文件会随 `core/bin/` 一起部署到 `/usr/local/bin/`：

- `omarchy-repair-hermes-pty`：Python 3 诊断、编译、缓存与替换入口。
- `omarchy-hermes-pty-smoke`：由已安装 Hermes 的 Electron 运行的真实原生与 PTY 检查。

先保留原始加载错误，再做完整检查：

```bash
omarchy-repair-hermes-pty --diagnose
omarchy-repair-hermes-pty --check
```

`--diagnose` 直接加载实际 `build/Release/pty.node`，绕过 node-pty 的错误覆盖，原始 GLIBC/动态链接异常会写到 stderr。`--check` 还会使用捆绑的 node-pty JS 实现创建 `/bin/sh`，检查初始终端尺寸、输入写入、resize 后尺寸以及退出码；不联网、不修改 binding。

执行修复：

```bash
omarchy-repair-hermes-pty

# 忽略已成功的 binding 和缓存，重新源码构建
omarchy-repair-hermes-pty --rebuild
```

首次编译调用共享的 `omarchy-node-runtime` 获取独立 `/opt` 下的受校验 Node，将其 `bin` 临时放到构建子进程 PATH 前面；不替换系统 Node。缺少编译工具时，脚本在 guest 内通过 APT 安装 `build-essential`、`python3` 和 `pkg-config`。npm 安装阶段禁用依赖安装脚本，随后仅通过固定 `@electron/rebuild@4.2.0` 对准确版本的 node-pty 做目标 Electron/ARM64 源码编译。

候选 binding 首先在临时复制的**已安装 node-pty JS 包**中完成真实加载与 PTY 验证，成功后才在目标目录原子替换。原位置再次验证失败会恢复替换前的原字节。无需重打包 ASAR，也不改变 node-pty JS 或 Electron 本身。

## 缓存与回滚

默认缓存位于 `/var/cache/omarchy/hermes-pty/`。缓存键包含 Electron/Node/ABI、架构、libc、node-pty 版本和捆绑 JS 指纹；缓存清单记录 binding SHA256。命中缓存仍需在当前 runtime 中做真实检查，损坏或不兼容的缓存不能作为成功结果。安装后的真实检查已成功、但缓存保存失败时，仅报告缓存警告，不把已验证的安装误报为失败。

每个运行时组合最多保留一份 `previous.node` 作为原 binding 备份；临时构建与验证目录在结束后尝试清理。PRoot 虚拟硬链接可能使 `obj.target` 等目录清理时报“Directory not empty”；此时仅记录警告与保留的检查路径，不改变已通过的安装结果，也不覆盖编译或 smoke 的原始失败。恢复该组合的旧 binding：

```bash
omarchy-repair-hermes-pty --rollback
```

回滚恢复原文件，原文件可能仍有最初的 GLIBC 问题；需要再次用 `--diagnose` 判断。更改安装位置或做隔离验证可使用 `--hermes-dir DIR`、`--cache-dir DIR`。

## 验收边界

本地文件测试验证版本键、损坏缓存拒绝、验证前不覆盖文件、替换后失败回滚和原备份保留；它们不能替代目标设备的 native smoke。

```bash
python3 -m unittest discover -s tests -v
```

目标设备还需确认：

1. `--diagnose` 的原始错误被记录，修复后 `--check` 返回 0，并报告输入和尺寸检查成功。
2. 从应用网格真正打开 Hermes 主窗口，可点击交互，关闭后可重新打开，日志没有原生模块加载异常。
3. Hermes 后端初始化及一次真实 Agent/工具调用单独验证。Electron 或 GPU 子进程存活不代表窗口和 Agent 都已可用。

上游依据：[Electron 原生模块说明](https://www.electronjs.org/docs/latest/tutorial/using-native-node-modules)、[electron/rebuild 参数与要求](https://github.com/electron/rebuild)、[node-pty 1.1.0 构建定义](https://github.com/microsoft/node-pty/blob/v1.1.0/binding.gyp)、[Omarchy 包构建配方](https://github.com/omacom/omarchy-pkgs/blob/master/pkgbuilds/hermes-desktop/PKGBUILD)。
