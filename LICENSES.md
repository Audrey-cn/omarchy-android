# 许可证说明

本仓库合并了桌面核心与 Android 一体包源码，保留各组件原有许可证和版权声明。

| 组件 | 许可入口 |
| --- | --- |
| `core/`、`runtime/`、`install.sh`、`scripts/`、`apps/deck/` 及本仓原创文档 | 根目录 [MIT License](LICENSE) |
| `apps/android/` 中基于 Tiny Container 的 Android 应用源码与相关文件 | [COPYING](apps/android/COPYING)，沿用 GPL-3.0 及各文件中的原始声明 |
| 第三方原生库、Android 依赖、容器内的软件、主题和字体 | 各自的许可证与版权声明 |

根目录的 MIT 许可证不替换 `apps/android/` 的 GPL 声明，也不替换第三方组件的许可证。文件内有单独许可或版权声明时，应一并保留。

Android 应用保留了 Tiny Container 的源码版权头、[内置 GPL 文本](apps/android/app/src/main/assets/gpl_v3.txt)及[开源依赖清单](apps/android/app/src/main/assets/open_source_libraries.json)。上游项目与文档保留在 [Tiny Container](https://github.com/Cateners/tiny_container) 和 `apps/android/doc/`；仓库迁移不会将这些内容重新声明为本仓原创。
