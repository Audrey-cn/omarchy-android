# 下载线路

默认使用 GitHub 直连。桌面「更新中心」的下载线路卡片和 Android 首次桌面安装页的「下载线路 / Download Route」可立即切换直连、国内加速或自定义 HTTPS 镜像。

国内加速按顺序尝试 `https://gh-proxy.com/`、`https://ghfast.top/`，然后回退官方 GitHub。自定义前缀使用相同的拼接方式，例如 `https://mirror.example/https://github.com/owner/repo/releases/download/tag/asset`。网络错误、字节长度错误或 SHA256 错误均不能使镜像内容通过校验。

桌面配置位于 `/root/.config/omarchy/download-mirror.json`：

```json
{"mode": "custom", "custom_prefix": "https://mirror.example/"}
```

`mode` 可选 `direct`、`accel`、`custom`。环境变量 `OMARCHY_GITHUB_MIRROR` 优先于配置文件，可设为 `direct`、`accel` 或自定义 `https://` 前缀。自定义前缀不接受账号、密码、查询参数或片段。

桌面镜像仅用于有 SHA256 和长度约束的二进制包，发行目录及描述文件仍从官方 GitHub 获取。Android 配置通过 SharedPreferences 保存，仅展开初装镜像的 GitHub Release 下载链接；目录、镜像身份、SHA256 和长度检查保持生效。桌面和 Android 的线路设置独立保存。

桌面二进制下载在终端显示进度、速度和剩余时间，约每 0.2 秒刷新；重定向输出时静默。Runtime 下载可从已有部分文件请求 Range，206 校验范围后续传，200 则从零重下；完成后校验完整文件。
