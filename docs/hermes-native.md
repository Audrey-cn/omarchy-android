# Hermes Desktop 原生模块与首次启动修复

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

## 首次选择页面的 45 秒误超时

`hermes-desktop-2026.9.7-7` 的主进程在缺少本地后端时等待用户选择安装或远程连接；这一等待本身没有失败期限。renderer 却从 `getConnection()` 调用起计时 45 秒，用户尚未选择也会进入 `Hermes couldn't start / Timed out connecting to Hermes backend`。同一时间预算也不足以覆盖正常的依赖安装。

`omarchy-repair-hermes-onboarding` 只修改初次 `getConnection()` 的一个调用点：只有 bootstrap IPC 明确报告正在等待选择或正在安装、且没有错误时暂停连接计时；结束后保留正常的 45 秒连接期限。后端 Promise 的真实拒绝仍立即向上传递；状态 IPC 缺失、失败或连续 2 秒无响应都会恢复连接计时。它不自动安装后端、不改模型配置，也不改之后 WebSocket、API 或 Agent 的期限。

修补范围按实际文件校验，不按包名猜测。当前仅接受 Electron `40.10.2`、安装来源 commit `2237be355906fbe6065ce1815711eee52b2d646e` 及以下 SHA256；不同版本必须先重新核对上游实现：

| 文件 | SHA256 |
| --- | --- |
| 官方 ARM64 包 | `3f75d5abaeb25e81bf110a76ac0414fa8516fce05d32fc4807b2c54e368a3a84` |
| `dist/electron-main.mjs` | `6b05b0377cb80789550aea1c85d318dddb4653392ffd09e1b9a1f3440566e64c` |
| `dist/assets/index-oSoqM7KJ.js` | `a06a9032b16ba6d89ffdfa5693f168cbe25eb585ae78179280481e905aeeb504` |
| `dist/assets/hermes-CVux_Zfz.js` | `32459cafb4cb1a02b5a7b6f9c02faf680f7411d8e7f739b05a8d902ce3c4716d` |

安装器在 native 修复后调用此 helper。已安装 guest 可在关闭 Hermes 后执行：

```bash
omarchy-repair-hermes-onboarding
omarchy-repair-hermes-onboarding --check
hermes-desktop-launch

# 关闭 Hermes 后恢复经过指纹核对的原 renderer
omarchy-repair-hermes-onboarding --rollback
```

原 renderer 和来源/补丁指纹保存在 `/opt/hermes-desktop/.omarchy-onboarding/`，写入和替换均为原子操作。重复修补不累加；回滚拒绝覆盖后来出现的未知修改。用户配置与历史会话不在补丁范围内。

## 本地后端的正常初始化路径

在首选页面点击 **Install locally**。桌面按安装 stamp 固定的上游 commit 取得 `scripts/install.sh`，缓存于 `$HERMES_HOME/bootstrap-cache/install-<commit>.sh`，然后按 manifest 分阶段安装：系统依赖、源码、Python venv、Python/Node 依赖、命令入口、配置模板及完成标记。GUI 使用非交互阶段模式，跳过需要用户输入的 API 设置和 gateway 服务配置；完成后由桌面自身启动本地 `hermes ... serve`，无需另建常驻 gateway 服务。

默认 `HERMES_HOME` 为 `$HOME/.hermes`。源码和 venv 位于其 `hermes-agent/` 下，初次安装后可做以下不显示模型密钥的检查：

```bash
test -x "$HOME/.hermes/hermes-agent/venv/bin/python"
PYTHONPATH="$HOME/.hermes/hermes-agent" \
  "$HOME/.hermes/hermes-agent/venv/bin/python" \
  -c 'import hermes_cli.main; print("HERMES_CLI_IMPORT_OK")'
```

需要共享 Node 时，先运行 `NODE_BIN="$(omarchy-node-runtime)"`，再以 `PATH="$(dirname "$NODE_BIN"):$PATH" hermes-desktop-launch` 启动。已有命名 custom provider 的模型配置应保留该名称、对应 provider 定义及所需环境变量；安装前后不要将完整 `.env`、连接描述对象或 Electron 的连接 JSON 打印到日志。配置迁入和一次真实模型/工具请求属于后续验收。

安装和运行日志分别位于 `$HERMES_HOME/logs/bootstrap-*.log` 与 `$HERMES_HOME/logs/desktop.log`。只有实际进入安装阶段才会生成 bootstrap 日志；停留在选择页面时没有该日志不代表安装失败。安装错误应以原阶段错误和进程退出为准，不能靠扩大 renderer 的等待时间掩盖。

官方包本身还带有 `/usr/share/hermes-desktop/install.sh`，但 desktop 的初始下载逻辑不会自动使用它。安装器会调用以下命令，将经过 stamp 和 SHA256 核对的该文件原子发布到固定 commit 的 bootstrap cache：

```bash
omarchy-repair-hermes-onboarding --seed-bootstrap-cache
```

当前已知脚本 SHA256 为 `5854b15670b51a8daae8f59ddfa917062de9f74be261eb73b4b8d719710f8968`。相同缓存可重复使用；已有不同内容或符号链接时拒绝覆盖，需先检查其来源。`--hermes-home DIR` 可指定其他 profile 的数据根。缓存只免去初始 Node `https.get` 拉取 `install.sh` 的网络请求，源码、Python/Node 依赖仍需缓存或网络，不代表完整离线安装。Chromium 的 `--proxy-server` 也不能保证代理 Node 的这一请求；需要代理时限定到本次进程和安装子进程，并保持本地连接绕过代理。

## 验收边界

本地文件测试验证版本键、损坏缓存拒绝、验证前不覆盖文件、替换后失败回滚和原备份保留；它们不能替代目标设备的 native smoke。

首启回归使用虚拟时钟复现官方 wrapper 在等待选择时 45 秒失败，并检查修补后长时间等待/安装、安装结束后的正常期限、真实后端拒绝、IPC 丢失、定时器清理、来源校验及精确回滚。缓存官方包的实际 renderer 也已完成指纹校验、修补后 ES module 语法检查与原字节回滚。上述验证不等于目标设备后端安装或模型调用已经通过。

```bash
python3 -m unittest discover -s tests -v
```

目标设备还需确认：

1. `--diagnose` 的原始错误被记录，修复后 `--check` 返回 0，并报告输入和尺寸检查成功。
2. 从应用网格真正打开 Hermes 主窗口，可点击交互，关闭后可重新打开，日志没有原生模块加载异常。
3. Hermes 后端初始化及一次真实 Agent/工具调用单独验证。Electron 或 GPU 子进程存活不代表窗口和 Agent 都已可用。

上游依据：[Electron 原生模块说明](https://www.electronjs.org/docs/latest/tutorial/using-native-node-modules)、[electron/rebuild 参数与要求](https://github.com/electron/rebuild)、[node-pty 1.1.0 构建定义](https://github.com/microsoft/node-pty/blob/v1.1.0/binding.gyp)、[Omarchy 包构建配方](https://github.com/omacom/omarchy-pkgs/blob/master/pkgbuilds/hermes-desktop/PKGBUILD)。
