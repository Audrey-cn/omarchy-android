#!/bin/bash
# ============================================================================
# Omarchy ARM64 Installer Engine (Ubuntu 24.04 + tools + WebKitGTK WebApps)
# Handles ALL 74+ official `install.*` entries from basecamp/omarchy cleanly!
# ============================================================================
set -e

export OMARCHY_PATH="/root/.local/share/omarchy"
export PATH="/usr/local/bin:$OMARCHY_PATH/bin:/usr/local/sbin:/usr/sbin:/usr/bin:/sbin:/bin"
export LANG=C.UTF-8
export LC_ALL=C.UTF-8

# Use USB proxy if port 18888 is listening (auto-detect!)
if bash -c "exec 3<>/dev/tcp/127.0.0.1/18888" 2>/dev/null; then
    export http_proxy="http://127.0.0.1:18888"
    export https_proxy="http://127.0.0.1:18888"
fi

KEY="$1"
LABEL="${2:-$1}"
APP_DIR="/root/.local/share/applications"
ICON_DIR="/root/.local/share/icons/hicolor/256x256/apps"
mkdir -p "$APP_DIR" "$ICON_DIR"

ensure_apt_updated() {
    if [ -z "$(ls -A /var/lib/apt/lists 2>/dev/null | grep -v '^partial$\|^auxfiles$\|^lock$')" ]; then
        echo "正在更新 APT 软件源索引..."
        DEBIAN_FRONTEND=noninteractive apt-get update -qq
    fi
}

install_webapp_desktop() {
    local name="$1"
    local url="$2"
    local icon_name="$3"
    local icon_src="$OMARCHY_PATH/applications/icons/${icon_name}.png"
    local icon_dest="$ICON_DIR/${icon_name}.png"

    echo "正在为 $name 创建独立桌面应用 (WebApp)..."
    if [[ -f "$icon_src" ]]; then
        cp -f "$icon_src" "$icon_dest"
    else
        icon_dest="web-browser"
    fi

    cat << EOF > "$APP_DIR/${name}.desktop"
[Desktop Entry]
Version=1.0
Name=$name
Comment=$name (Omarchy App)
Exec=/usr/local/bin/omarchy-chromium --app=$url
Icon=$icon_dest
Terminal=false
Type=Application
Categories=Network;Utility;
StartupNotify=true
EOF
    chmod +x "$APP_DIR/${name}.desktop"
    echo ""
    echo "✓ $name 已安装完成！"
    echo "  -> 已添加到应用网格 (Super + Space)，正在为你启动窗口..."
    /usr/local/bin/omarchy-chromium --app="$url" &
}

install_nerd_font() {
    local display="$1"
    local zipname="$2"
    local family="$3"
    echo "正在从 Nerd Fonts 官方仓库下载并安装 $display..."
    mkdir -p "/usr/local/share/fonts/$zipname"
    curl -fSL -o "/tmp/${zipname}.zip" "https://github.com/ryanoasis/nerd-fonts/releases/latest/download/${zipname}.zip"
    unzip -oq "/tmp/${zipname}.zip" -d "/usr/local/share/fonts/$zipname"
    rm -f "/tmp/${zipname}.zip"
    fc-cache -f
    echo "✓ 字体 $display ($family) 已安装并刷新字体缓存！"
}

ensure_apt_updated

case "$KEY" in
    # --- 1. Package & AUR Fuzzy Search ---
    install.package|install.aur)
        pkg=$(apt-cache pkgnames | sort | fzf --prompt="搜索并安装 ARM64 软件包 > " --preview="apt-cache show {1}" --preview-window="down:60%:wrap")
        if [[ -n "$pkg" ]]; then
            DEBIAN_FRONTEND=noninteractive apt-get install -y "$pkg"
            echo "✓ 软件包 $pkg 安装完成！"
        fi
        ;;

    # --- 2. AI Desktop & CLI Tools ---
    install.ai.doubao-say)
        echo "正在安装 Doubao Say (豆包说 · Wayland / Omarchy 语音输入)..."
        DEBIAN_FRONTEND=noninteractive apt-get update -qq
        DEBIAN_FRONTEND=noninteractive apt-get install -y gir1.2-gtk-4.0 gir1.2-adw-1 gir1.2-webkit-6.0 python3-cairo python3-gi-cairo libportaudio2 libasound2-plugins python3-venv python3-pip
        rm -rf /opt/doubao-say
        git clone --depth 1 https://github.com/quanru/doubao-say.git /opt/doubao-say
        python3 -m venv --system-site-packages /opt/doubao-say/.venv
        /opt/doubao-say/.venv/bin/pip install --no-cache-dir "PyJWT[crypto]>=2.10,<3" "websockets>=12" "sounddevice>=0.4.6" "evdev>=1.4"
        cat > /usr/local/bin/doubao-say-launch <<'LAUNCH'
#!/usr/bin/env bash
export XDG_RUNTIME_DIR=/tmp WAYLAND_DISPLAY=wayland-1 GDK_BACKEND=wayland GSK_RENDERER=cairo WEBKIT_DISABLE_DMABUF_RENDERER=1 WEBKIT_DISABLE_COMPOSITING_MODE=1 WEBKIT_DISABLE_SANDBOX_THIS_IS_DANGEROUS=1 PULSE_SERVER=127.0.0.1
export DBUS_SESSION_BUS_ADDRESS="unix:path=/tmp/omarchy-dbus.sock"
export PYTHONPATH="/opt/doubao-say/src${PYTHONPATH:+:$PYTHONPATH}" PYTHONDONTWRITEBYTECODE=1
exec /opt/doubao-say/.venv/bin/python -m doubao_input "$@"
LAUNCH
        chmod 755 /usr/local/bin/doubao-say-launch
        cat > "$APP_DIR/Doubao-Say.desktop" <<'DESKTOP'
[Desktop Entry]
Name=Doubao Say (豆包说)
Comment=豆包语音输入 (Wayland / Omarchy)
Exec=/usr/local/bin/doubao-say-launch
Icon=audio-input-microphone
Terminal=false
Type=Application
Categories=Utility;AudioVideo;
DESKTOP
        echo "✓ Doubao Say (豆包说) 已安装完成！可在应用网格中启动并登录豆包网页端授权。"
        ;;
    install.ai.cc-switch)
        echo "正在从 GitHub Releases 下载最新版 CC Switch (ARM64 .deb)..."
        if ! CC_RELEASE_URL=$(curl -fsSLI -o /dev/null -w '%{url_effective}' "https://github.com/farion1231/cc-switch/releases/latest"); then
            echo "无法解析 CC Switch 最新版本；停止安装，请检查本次请求的网络或代理。" >&2
            exit 1
        fi
        case "$CC_RELEASE_URL" in
            https://github.com/farion1231/cc-switch/releases/tag/v*) ;;
            *) echo "CC Switch 版本跳转地址异常；停止安装。" >&2; exit 1 ;;
        esac
        CC_TAG="${CC_RELEASE_URL##*/}"
        if [[ ! "$CC_TAG" =~ ^v[0-9]+\.[0-9]+\.[0-9]+(-[0-9A-Za-z]+([.-][0-9A-Za-z]+)*)?$ ]]; then
            echo "CC Switch 版本号格式异常；停止安装。" >&2
            exit 1
        fi
        CC_VER="${CC_TAG#v}"
        echo "检测到最新版本: v${CC_VER}"
        curl -fSL -o /tmp/cc-switch_arm64.deb "https://github.com/farion1231/cc-switch/releases/download/v${CC_VER}/CC-Switch-v${CC_VER}-Linux-arm64.deb"
        ensure_apt_updated
        DEBIAN_FRONTEND=noninteractive apt-get install -y /tmp/cc-switch_arm64.deb
        rm -f /tmp/cc-switch_arm64.deb
        for f in /usr/share/applications/*CC*Switch*.desktop /usr/share/applications/*cc-switch*.desktop; do
            [ -f "$f" ] && sed -i "s|^Exec=.*|Exec=/usr/local/bin/cc-switch-launch|" "$f"
        done
        update-desktop-database /usr/share/applications "$APP_DIR" 2>/dev/null || true
        echo "✓ CC Switch v${CC_VER} (ARM64) 已安装完成！已添加到应用网格 (Super + Space)。"
        ;;
    install.ai.chatgpt)
        echo "正在安装 OpenAI 官方原生 ARM64 ChatGPT / Codex Desktop (.deb)..."
        curl -fSL -o /tmp/chatgpt_arm64.deb "https://persistent.oaistatic.com/codex-app-prod/linux/deb/latest/chatgpt_arm64.deb"
        DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends /tmp/chatgpt_arm64.deb
        rm -f /tmp/chatgpt_arm64.deb "$APP_DIR/ChatGPT.desktop"
        for f in /usr/share/applications/*chatgpt*.desktop /usr/share/applications/*codex*.desktop; do
            [ -f "$f" ] && sed -i "s|^Exec=.*|Exec=/usr/local/bin/chatgpt-launch %u|" "$f"
        done
        update-desktop-database /usr/share/applications "$APP_DIR" 2>/dev/null || true
        echo "✓ 官方原生 ARM64 Codex / ChatGPT Desktop 已安装完成！"
        ;;
    install.ai.claude)
        echo "正在安装官方原生 ARM64 Claude Desktop (.deb)..."
        mkdir -p /usr/share/keyrings
        curl -fsSL https://downloads.claude.ai/claude-desktop/apt/stable/keyring.gpg -o /usr/share/keyrings/claude-desktop-archive-keyring.asc
        echo "deb [arch=amd64,arm64 signed-by=/usr/share/keyrings/claude-desktop-archive-keyring.asc] https://downloads.claude.ai/claude-desktop/apt/stable stable main" > /etc/apt/sources.list.d/claude-desktop.list
        DEBIAN_FRONTEND=noninteractive apt-get update -qq
        DEBIAN_FRONTEND=noninteractive apt-get install -y claude-desktop
        rm -f "$APP_DIR/Claude.desktop"
        for f in /usr/share/applications/*claude*.desktop; do
            if [ -f "$f" ]; then
                sed -i "s|^Exec=.*|Exec=/usr/local/bin/claude-desktop-launch %u|" "$f"
                cp -f "$f" "$APP_DIR/$(basename "$f")"
            fi
        done
        update-desktop-database /usr/share/applications "$APP_DIR" 2>/dev/null || true
        echo "✓ 官方原生 ARM64 Claude Desktop 已安装完成！"
        ;;
    install.ai.hermes)
        echo "正在从 Omarchy 官方 ARM64 仓库拉取正版 Hermes Desktop 预编译包..."
        curl -fsSL "https://pkgs.omarchy.org/stable/aarch64/omarchy.db" -o /tmp/omarchy.db
        HFILE=$(tar --zstd -xOf /tmp/omarchy.db "$(tar --zstd -tf /tmp/omarchy.db | grep -E '^hermes-desktop.*/desc$' | head -n 1)" | awk '/%FILENAME%/{getline; print; exit}')
        curl -fSL -o "/tmp/$HFILE" "https://pkgs.omarchy.org/stable/aarch64/$HFILE"
        tar --zstd -xf "/tmp/$HFILE" -C / --exclude='.PKGINFO' --exclude='.MTREE' --exclude='.BUILDINFO' --exclude='.INSTALL'
        rm -f "/tmp/$HFILE" /tmp/omarchy.db "$APP_DIR/Hermes.desktop"
        /usr/local/bin/omarchy-repair-hermes-pty
        /usr/local/bin/omarchy-repair-hermes-onboarding
        /usr/local/bin/omarchy-repair-hermes-onboarding --seed-bootstrap-cache
        for f in /usr/share/applications/*hermes*.desktop; do
            [ -f "$f" ] && sed -i "s|^Exec=.*|Exec=/usr/local/bin/hermes-desktop-launch %u|" "$f"
        done
        update-desktop-database /usr/share/applications "$APP_DIR" 2>/dev/null || true
        echo "✓ 正版 NousResearch Hermes Desktop (ARM64) 已安装完成！已添加到应用网格 (Super + Space)。"
        ;;
    install.ai.grok-bot)
        install_webapp_desktop "Grok" "https://grok.com" "X"
        ;;
    install.ai.perplexity)
        install_webapp_desktop "Perplexity" "https://www.perplexity.ai" "ChatGPT"
        ;;
    install.ai.antigravity)
        install_webapp_desktop "Antigravity" "https://antigravity.google" "ChatGPT"
        ;;
    install.ai.amp)
        install_webapp_desktop "Amp" "https://ampcode.com" "ChatGPT"
        ;;
    install.ai.ollama)
        echo "正在安装 Ollama ARM64 本地大模型引擎..."
        curl -fsSL https://ollama.com/install.sh | sh
        echo "✓ Ollama 已安装完成！运行 ollama serve & ollama run qwen2.5:1.5b 即可体验。"
        ;;
    install.ai.openclaw)
        echo "正在安装 OpenClaw CLI..."
        command -v node >/dev/null || mise use --global node@latest
        npm install -g openclaw || true
        ;;
    install.ai.lm-studio|install.ai.herdr|install.ai.t3-code)
        echo "ℹ️  $LABEL 目前官方仅提供 x86_64 桌面包，在 ARM64 手机上推荐使用 Ollama 本地模型或终端 AI CLI (Claude/Codex/OpenCode)。"
        ;;

    # --- 3. Development Environments (Powered by official `mise`!) ---
    install.development.python)
        echo "正在通过 mise 安装最新 ARM64 Python 环境..."
        mise use --global python@latest
        ;;
    install.development.javascript.node)
        echo "正在通过 mise 安装最新 ARM64 Node.js 环境..."
        mise use --global node@latest
        ;;
    install.development.javascript.bun)
        echo "正在通过 mise 安装最新 ARM64 Bun 运行时..."
        mise use --global bun@latest
        ;;
    install.development.javascript.deno)
        echo "正在通过 mise 安装最新 ARM64 Deno 运行时..."
        mise use --global deno@latest
        ;;
    install.development.go)
        echo "正在通过 mise 安装最新 ARM64 Go 工具链..."
        mise use --global go@latest
        ;;
    install.development.rust)
        echo "正在安装 ARM64 Rust (rustc + cargo)..."
        DEBIAN_FRONTEND=noninteractive apt-get install -y rustc cargo
        ;;
    install.development.rails)
        echo "正在通过 mise 安装 Ruby + Rails..."
        DEBIAN_FRONTEND=noninteractive apt-get install -y libyaml-dev libssl-dev zlib1g-dev
        mise use --global ruby@latest
        ;;
    install.development.zig)
        mise use --global zig@latest
        ;;
    install.development.java)
        mise use --global java@latest
        ;;
    install.development.dotnet)
        mise use --global dotnet@latest
        ;;
    install.development.php.php)
        DEBIAN_FRONTEND=noninteractive apt-get install -y php-cli php-curl php-mbstring php-xml composer
        ;;
    install.development.php.laravel)
        DEBIAN_FRONTEND=noninteractive apt-get install -y php-cli composer
        composer global require laravel/installer
        ;;
    install.development.elixir.elixir)
        DEBIAN_FRONTEND=noninteractive apt-get install -y elixir erlang
        ;;
    install.development.docker-dbs)
        echo "正在安装本地数据库套件 (PostgreSQL / Redis / SQLite3)..."
        DEBIAN_FRONTEND=noninteractive apt-get install -y postgresql redis-server sqlite3
        echo "✓ PostgreSQL, Redis, SQLite3 已安装完成！"
        ;;
    install.development.*)
        lang="${KEY##*.}"
        mise use --global "${lang}@latest"
        ;;

    # --- 4. Browsers ---
    install.browser.omarchy)
        DEBIAN_FRONTEND=noninteractive apt-get install -y python3-gi gir1.2-gtk-3.0 gir1.2-webkit2-4.1
        cat << 'EOF' > "$APP_DIR/omarchy-browser.desktop"
[Desktop Entry]
Name=Omarchy Browser
Comment=WebKitGTK browser (not a Playwright automation engine)
Exec=/usr/local/bin/omarchy-browser %U
Icon=web-browser
Terminal=false
Type=Application
Categories=Network;WebBrowser;
EOF
        echo "✓ Omarchy Browser (WebKitGTK) 已安装。"
        ;;
    install.browser.chromium)
        /usr/local/bin/omarchy-install-playwright-browser chromium
        ;;
    install.browser.firefox)
        /usr/local/bin/omarchy-install-firefox
        cat << 'EOF' > "$APP_DIR/Firefox.desktop"
[Desktop Entry]
Name=Firefox (Mozilla)
Comment=Mozilla Firefox DEB, separate from Playwright's patched Firefox
Exec=/usr/local/bin/omarchy-firefox %U
Icon=firefox
Terminal=false
Type=Application
Categories=Network;WebBrowser;
EOF
        chmod +x "$APP_DIR/Firefox.desktop"
        echo "✓ Mozilla Firefox DEB 已安装；这不是 Playwright 的 Firefox 引擎。首次启动仍需验证本机兼容性。"
        ;;
    install.browser.playwright)
        /usr/local/bin/omarchy-install-playwright-browser chromium
        ;;
    install.browser.firefox-playwright)
        /usr/local/bin/omarchy-install-playwright-browser firefox
        ;;
    install.browser.*)
        echo "尚未为 $LABEL 实现并验证 ARM64 / Android PRoot 安装方案。没有替换安装其他品牌浏览器。" >&2
        echo "可选择内置 Chromium 128、Firefox (Playwright)、Omarchy Browser 或 Firefox (Mozilla DEB)。" >&2
        exit 1
        ;;

    # --- 5. Editors ---
    install.editor.vscode)
        echo "正在下载微软官方 ARM64 VSCode (.deb)..."
        curl -fSL -o /tmp/vscode-arm64.deb "https://code.visualstudio.com/sha/download?build=stable&os=linux-deb-arm64"
        DEBIAN_FRONTEND=noninteractive apt-get install -y /tmp/vscode-arm64.deb
        rm -f /tmp/vscode-arm64.deb
        for f in /usr/share/applications/code*.desktop; do
            [ -f "$f" ] && sed -i 's|/usr/share/code/code|/usr/share/code/code --no-sandbox --disable-dev-shm-usage --in-process-gpu --disable-gpu-compositing --ozone-platform=wayland --enable-wayland-ime --password-store=basic|g' "$f"
        done
        update-desktop-database /usr/share/applications 2>/dev/null || true
        echo "✓ VSCode (ARM64) 安装完成！已添加到应用网格 (Super + Space)。"
        ;;
    install.editor.vim)
        DEBIAN_FRONTEND=noninteractive apt-get install -y vim
        ;;
    install.editor.emacs)
        DEBIAN_FRONTEND=noninteractive apt-get install -y emacs-nox
        ;;
    install.editor.helix)
        echo "正在下载 Helix ARM64 官方版本..."
        HVER=$(curl -fsSL https://api.github.com/repos/helix-editor/helix/releases/latest | jq -r .tag_name)
        curl -fSL -o /tmp/helix.tar.xz "https://github.com/helix-editor/helix/releases/download/${HVER}/helix-${HVER}-aarch64-linux.tar.xz"
        tar -xf /tmp/helix.tar.xz -C /opt/
        ln -sf /opt/helix-${HVER}-aarch64-linux/hx /usr/local/bin/hx
        ln -sf /opt/helix-${HVER}-aarch64-linux/hx /usr/local/bin/helix
        rm -f /tmp/helix.tar.xz
        echo "✓ Helix ($HVER) 安装完成！"
        ;;
    install.editor.cursor|install.editor.zed|install.editor.sublime)
        echo "ℹ️  $LABEL 暂未提供 Linux ARM64 桌面包，已为你推荐安装原生 ARM64 VSCode、Neovim 或 Helix。"
        ;;

    # --- 6. Terminals ---
    install.terminal.alacritty)
        DEBIAN_FRONTEND=noninteractive apt-get install -y alacritty
        ;;
    install.terminal.foot)
        DEBIAN_FRONTEND=noninteractive apt-get install -y foot
        ;;
    install.terminal.kitty)
        DEBIAN_FRONTEND=noninteractive apt-get install -y kitty
        ;;
    install.terminal.ghostty)
        echo "ℹ️  Ghostty 需从源码编译，当前系统已内置 Alacritty、Kitty 和 Foot 原生 Wayland 终端。"
        ;;

    # --- 7. Services & Web Apps ---
    install.service.tailscale)
        echo "正在安装 Tailscale 组网客户端..."
        curl -fsSL https://tailscale.com/install.sh | sh
        ;;
    install.service.notion)
        install_webapp_desktop "Notion" "https://www.notion.so" "Basecamp"
        ;;
    install.service.slack)
        install_webapp_desktop "Slack" "https://app.slack.com" "Discord"
        ;;
    install.service.bitwarden)
        install_webapp_desktop "Bitwarden" "https://vault.bitwarden.com" "Basecamp"
        ;;
    install.service.1password)
        install_webapp_desktop "1Password" "https://my.1password.com" "Basecamp"
        ;;
    install.service.microsoft)
        install_webapp_desktop "Microsoft 365" "https://www.microsoft365.com" "windows"
        ;;
    install.service.wechat)
        install_webapp_desktop "WeChat" "https://wx.qq.com" "WhatsApp"
        ;;
    install.service.dingtalk)
        install_webapp_desktop "DingTalk" "https://im.dingtalk.com" "WhatsApp"
        ;;
    install.service.dropbox)
        install_webapp_desktop "Dropbox" "https://www.dropbox.com" "Basecamp"
        ;;
    install.service.signal)
        install_webapp_desktop "Signal" "https://signal.org" "WhatsApp"
        ;;
    install.service.once)
        install_webapp_desktop "ONCE" "https://once.com" "Basecamp"
        ;;
    install.webapp)
        app_name=$(gum input --prompt="应用名称 > " --placeholder="例如: GitHub")
        app_url=$(gum input --prompt="网页地址 > " --placeholder="例如: https://github.com")
        if [[ -n "$app_name" && -n "$app_url" ]]; then
            install_webapp_desktop "$app_name" "$app_url" "GitHub"
        fi
        ;;

    # --- 8. Fonts & Styles ---
    install.style.font.cascadia) install_nerd_font "Cascadia Mono" "CascadiaMono" "CaskaydiaMono Nerd Font" ;;
    install.style.font.meslo) install_nerd_font "Meslo LG Mono" "Meslo" "MesloLGL Nerd Font" ;;
    install.style.font.fira) install_nerd_font "Fira Code" "FiraCode" "FiraCode Nerd Font" ;;
    install.style.font.victor) install_nerd_font "Victor Code" "VictorMono" "VictorMono Nerd Font" ;;
    install.style.font.bitstream) install_nerd_font "Bitstream Vera Mono" "BitstreamVeraSansMono" "BitstromWera Nerd Font" ;;
    install.style.font.iosevka) install_nerd_font "Iosevka" "Iosevka" "Iosevka Nerd Font Mono" ;;
    install.style.theme|install.style.background|install.style.icons)
        echo "当前已内置全部 23 套 Omarchy 官方主题与高清壁纸！请通过 主菜单 -> 外观 (Appearance) -> 主题 / 背景 切换。"
        ;;

    # --- 9. Gaming ---
    install.gaming.retroarch|install.gaming.retro-launcher)
        DEBIAN_FRONTEND=noninteractive apt-get install -y retroarch
        ;;
    install.gaming.geforce-now)
        install_webapp_desktop "GeForce NOW" "https://play.geforcenow.com" "YouTube"
        ;;
    install.gaming.xbox-cloud)
        install_webapp_desktop "Xbox Cloud Gaming" "https://www.xbox.com/play" "YouTube"
        ;;
    install.gaming.*)
        echo "ℹ️  $LABEL 为 x86_64 PC 游戏组件，在 ARM64 手机上推荐使用 RetroArch 或云游戏 (GeForce NOW / Xbox Cloud)。"
        ;;

    # --- 10. Preinstalls ---
    install.preinstalls)
        echo "正在恢复/安装 Omarchy 常用桌面 WebApps (GitHub, YouTube, ChatGPT, Chromium)..."
        install_webapp_desktop "GitHub" "https://github.com" "GitHub"
        install_webapp_desktop "YouTube" "https://youtube.com" "YouTube"
        install_webapp_desktop "ChatGPT" "https://chatgpt.com" "ChatGPT"
        echo "✓ 常用预装应用已全部添加到应用网格 (Super + Space)！"
        ;;

    *)
        echo "正在尝试查找并安装: $LABEL ($KEY)..."
        echo "提示: 可使用 安装 -> 软件包 (Package) 模糊搜索 Ubuntu ARM64 全量软件源。"
        ;;
esac
