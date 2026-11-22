#!/usr/bin/env bash
# ============================================================================
# omarchy-android — Unified Installer (Root chroot + Non-Root proot-distro)
# Usage:
#   ./install.sh                  # Auto-detect Root (chroot) or Non-Root (proot)
#   ./install.sh --mode chroot    # Force Root native chroot mode
#   ./install.sh --mode proot     # Force Non-Root Termux proot-distro mode
#   ./install.sh --with-ai        # Also install AI Desktop Suite (CC Switch / Claude / Codex / Hermes)
# ============================================================================
set -euo pipefail

REPO_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
MODE="auto"
WITH_AI=0

while [[ $# -gt 0 ]]; do
  case "$1" in
    --mode) MODE="${2:-auto}"; shift 2 ;;
    --with-ai) WITH_AI=1; shift ;;
    -h|--help)
      echo "Usage: $0 [--mode auto|chroot|proot] [--with-ai]"
      exit 0
      ;;
    *) echo "Unknown arg: $1"; exit 1 ;;
  esac
done

if [[ "$MODE" == "auto" ]]; then
  if [[ "$(id -u)" -eq 0 ]] || command -v su >/dev/null 2>&1 && su -c id >/dev/null 2>&1; then
    MODE="chroot"
  else
    MODE="proot"
  fi
fi

echo "============================================================"
echo "  Omarchy on Android (Wayland Sway + Ubuntu 24.04 ARM64)"
echo "  Selected Runtime Mode : $MODE"
echo "  Install AI Suite      : $([[ $WITH_AI -eq 1 ]] && echo YES || echo NO)"
echo "============================================================"

install_core_inside_ubuntu() {
  local target_root="${1:-}"
  mkdir -p "${target_root}/usr/local/bin" \
           "${target_root}/root/.config/sway" \
           "${target_root}/root/.config/fcitx5" \
           "${target_root}/root/.config/omarchy"

  cp -f "$REPO_DIR"/core/bin/* "${target_root}/usr/local/bin/"
  cp -f "$REPO_DIR"/core/ai-suite/*launch "${target_root}/usr/local/bin/"
  cp -f "$REPO_DIR/core/config/sway/config" "${target_root}/root/.config/sway/config"
  cp -f "$REPO_DIR/core/config/fcitx5/profile" "${target_root}/root/.config/fcitx5/profile"
  chmod 755 "${target_root}"/usr/local/bin/omarchy-* \
            "${target_root}/usr/local/bin/launch-waybar" \
            "${target_root}"/usr/local/bin/*-launch
}

if [[ "$MODE" == "chroot" ]]; then
  echo "[1/3] Installing Root chroot runtime into /data/adb/ ..."
  mkdir -p /data/adb /data/ubuntu
  cp -f "$REPO_DIR/runtime/chroot/ubuntu-chroot.sh" /data/adb/ubuntu-chroot.sh
  cp -f "$REPO_DIR/runtime/chroot/start-omarchy.sh" /data/adb/start-omarchy.sh
  cp -f "$REPO_DIR/runtime/chroot/omarchy-browser-daemon.py" /data/adb/omarchy-browser-daemon.py
  chmod 755 /data/adb/ubuntu-chroot.sh /data/adb/start-omarchy.sh /data/adb/omarchy-browser-daemon.py

  echo "[2/3] Deploying Omarchy core & hardware keygrabber into /data/ubuntu ..."
  install_core_inside_ubuntu "/data/ubuntu"
  cp -f "$REPO_DIR/runtime/chroot/omarchy-keygrab.py" /data/ubuntu/usr/local/bin/omarchy-keygrab.py
  chmod 755 /data/ubuntu/usr/local/bin/omarchy-keygrab.py

  echo "[3/3] Provisioning Ubuntu 24.04 base apps (Git/gh/lazygit, Neovim, Chromium, Thunar, imv, zathura, mpv), official WebApps & zh_CN locale ..."
  /data/adb/ubuntu-chroot.sh '
    export DEBIAN_FRONTEND=noninteractive
    apt-get update -qq
    apt-get install -y waybar wofi swaybg mako-notifier alacritty thunar neovim tmux btop fzf ripgrep fd-find jq \
      imv zathura zathura-pdf-poppler mpv \
      git gh curl wget unzip zstd locales webp imagemagick \
      python3-gi gir1.2-gtk-3.0 gir1.2-gdkpixbuf-2.0 gir1.2-gtklayershell-0.1 papirus-icon-theme \
      python3-evdev xdotool grim slurp wtype \
      fcitx5 fcitx5-chinese-addons fcitx5-frontend-gtk3 fcitx5-frontend-gtk4 fcitx5-frontend-qt5
    sed -i "s/# zh_CN.UTF-8 UTF-8/zh_CN.UTF-8 UTF-8/" /etc/locale.gen
    locale-gen zh_CN.UTF-8 >/dev/null
    if ! command -v lazygit >/dev/null 2>&1; then
      LG_VER=$(curl -fsSLI -o /dev/null -w "%{url_effective}" "https://github.com/jesseduffield/lazygit/releases/latest" | sed "s|.*/tag/v||")
      curl -fsSL "https://github.com/jesseduffield/lazygit/releases/download/v${LG_VER}/lazygit_${LG_VER}_Linux_arm64.tar.gz" -o /tmp/lazygit.tar.gz && \
      tar -xzf /tmp/lazygit.tar.gz -C /usr/local/bin lazygit && chmod 755 /usr/local/bin/lazygit && rm -f /tmp/lazygit.tar.gz || true
    fi
    mkdir -p /root/.local/share/applications /root/.local/share/icons/hicolor/256x256/apps /usr/share/pixmaps
    cat > /root/.local/share/applications/Chromium.desktop <<DESKTOP
[Desktop Entry]
Name=Chromium Browser
Comment=Native Termux Chromium on Wayland
Exec=/usr/local/bin/omarchy-chromium %U
Icon=chromium
Terminal=false
Type=Application
Categories=Network;WebBrowser;
DESKTOP
    cat > /root/.local/share/applications/lazygit.desktop <<DESKTOP
[Desktop Entry]
Name=Lazygit
Comment=Terminal UI for Git
Exec=alacritty -e lazygit
Icon=git
Terminal=false
Type=Application
Categories=Development;
DESKTOP
    if [[ ! -d /root/.local/share/omarchy/.git ]]; then
      git clone --depth 1 https://github.com/basecamp/omarchy.git /root/.local/share/omarchy
    fi
    for ic in /root/.local/share/omarchy/applications/icons/*.png; do
      b=$(basename "$ic")
      low=$(echo "$b" | tr "[:upper:]" "[:lower:]" | tr " " "-")
      cp -f "$ic" "/root/.local/share/icons/hicolor/256x256/apps/$b"
      cp -f "$ic" "/root/.local/share/icons/hicolor/256x256/apps/$low"
      cp -f "$ic" "/usr/share/pixmaps/$low"
    done
    for f in /root/.local/share/omarchy/applications/*.desktop; do
      b=$(basename "$f")
      case "$b" in
        "foot.desktop"|"Activity Monitor.desktop"|"Game Vibes.desktop"|"Herdr.desktop"|omarchy-*.desktop) continue ;;
        *)
          cp -f "$f" "/root/.local/share/applications/$b"
          sed -i "s|^Exec=omarchy-launch-webapp |Exec=/usr/local/bin/omarchy-launch-webapp |" "/root/.local/share/applications/$b"
          sed -i "s|^Exec=omarchy-webapp-handler-hey.*|Exec=/usr/local/bin/omarchy-launch-webapp https://app.hey.com/|" "/root/.local/share/applications/$b"
          sed -i "s|^Exec=omarchy-webapp-handler-zoom.*|Exec=/usr/local/bin/omarchy-launch-webapp https://app.zoom.us/wc|" "/root/.local/share/applications/$b"
          ;;
      esac
    done
    cp -rf /root/.local/share/omarchy/config/fcitx5/* /root/.config/fcitx5/ 2>/dev/null || true
    /usr/local/bin/omarchy-theme-engine.py set tokyo-night
  '

  if [[ $WITH_AI -eq 1 ]]; then
    echo "[AI Suite] Installing Claude Desktop, Codex Desktop & Hermes Desktop ..."
    /data/adb/ubuntu-chroot.sh '/usr/local/bin/omarchy-installer.sh install.ai.claude "Claude Desktop"'
    /data/adb/ubuntu-chroot.sh '/usr/local/bin/omarchy-installer.sh install.ai.chatgpt "ChatGPT Desktop"'
    /data/adb/ubuntu-chroot.sh '/usr/local/bin/omarchy-installer.sh install.ai.hermes "Hermes Desktop"'
  fi

  echo "Done! Run 'su -c /data/adb/start-omarchy.sh' to launch Omarchy."
else
  echo "[1/3] Installing Termux packages (x11-repo, sway, termux-x11, proot-distro) ..."
  pkg update -y
  pkg install -y x11-repo tur-repo
  pkg install -y termux-x11-nightly sway pulseaudio proot-distro chromium git curl
  proot-distro install ubuntu || true

  echo "[2/3] Deploying Omarchy core into proot Ubuntu ..."
  UBUNTU_ROOT="$PREFIX/var/lib/proot-distro/installed-rootfs/ubuntu"
  install_core_inside_ubuntu "$UBUNTU_ROOT"
  cp -f "$REPO_DIR/runtime/proot/start-omarchy-proot.sh" "$HOME/start-omarchy.sh"
  chmod 755 "$HOME/start-omarchy.sh"

  echo "[3/3] Provisioning Ubuntu base apps (Git/gh/lazygit, Neovim, Chromium, Thunar, imv, zathura, mpv) & official WebApps inside proot ..."
  proot-distro login ubuntu -- bash -lc '
    export DEBIAN_FRONTEND=noninteractive
    apt-get update -qq
    apt-get install -y waybar wofi swaybg mako-notifier alacritty thunar neovim tmux btop fzf ripgrep fd-find jq \
      imv zathura zathura-pdf-poppler mpv \
      git gh curl wget unzip zstd locales webp imagemagick \
      python3-gi gir1.2-gtk-3.0 gir1.2-gdkpixbuf-2.0 gir1.2-gtklayershell-0.1 papirus-icon-theme \
      grim slurp \
      fcitx5 fcitx5-chinese-addons fcitx5-frontend-gtk3 fcitx5-frontend-gtk4 fcitx5-frontend-qt5
    sed -i "s/# zh_CN.UTF-8 UTF-8/zh_CN.UTF-8 UTF-8/" /etc/locale.gen
    locale-gen zh_CN.UTF-8 >/dev/null
    if ! command -v lazygit >/dev/null 2>&1; then
      LG_VER=$(curl -fsSLI -o /dev/null -w "%{url_effective}" "https://github.com/jesseduffield/lazygit/releases/latest" | sed "s|.*/tag/v||")
      curl -fsSL "https://github.com/jesseduffield/lazygit/releases/download/v${LG_VER}/lazygit_${LG_VER}_Linux_arm64.tar.gz" -o /tmp/lazygit.tar.gz && \
      tar -xzf /tmp/lazygit.tar.gz -C /usr/local/bin lazygit && chmod 755 /usr/local/bin/lazygit && rm -f /tmp/lazygit.tar.gz || true
    fi
    mkdir -p /root/.local/share/applications /root/.local/share/icons/hicolor/256x256/apps /usr/share/pixmaps
    cat > /root/.local/share/applications/Chromium.desktop <<DESKTOP
[Desktop Entry]
Name=Chromium Browser
Comment=Native Termux Chromium on Wayland
Exec=/usr/local/bin/omarchy-chromium %U
Icon=chromium
Terminal=false
Type=Application
Categories=Network;WebBrowser;
DESKTOP
    cat > /root/.local/share/applications/lazygit.desktop <<DESKTOP
[Desktop Entry]
Name=Lazygit
Comment=Terminal UI for Git
Exec=alacritty -e lazygit
Icon=git
Terminal=false
Type=Application
Categories=Development;
DESKTOP
    [[ -d /root/.local/share/omarchy/.git ]] || git clone --depth 1 https://github.com/basecamp/omarchy.git /root/.local/share/omarchy
    for ic in /root/.local/share/omarchy/applications/icons/*.png; do
      b=$(basename "$ic")
      low=$(echo "$b" | tr "[:upper:]" "[:lower:]" | tr " " "-")
      cp -f "$ic" "/root/.local/share/icons/hicolor/256x256/apps/$b"
      cp -f "$ic" "/root/.local/share/icons/hicolor/256x256/apps/$low"
      cp -f "$ic" "/usr/share/pixmaps/$low"
    done
    for f in /root/.local/share/omarchy/applications/*.desktop; do
      b=$(basename "$f")
      case "$b" in
        "foot.desktop"|"Activity Monitor.desktop"|"Game Vibes.desktop"|"Herdr.desktop"|omarchy-*.desktop) continue ;;
        *)
          cp -f "$f" "/root/.local/share/applications/$b"
          sed -i "s|^Exec=omarchy-launch-webapp |Exec=/usr/local/bin/omarchy-launch-webapp |" "/root/.local/share/applications/$b"
          sed -i "s|^Exec=omarchy-webapp-handler-hey.*|Exec=/usr/local/bin/omarchy-launch-webapp https://app.hey.com/|" "/root/.local/share/applications/$b"
          sed -i "s|^Exec=omarchy-webapp-handler-zoom.*|Exec=/usr/local/bin/omarchy-launch-webapp https://app.zoom.us/wc|" "/root/.local/share/applications/$b"
          ;;
      esac
    done
    /usr/local/bin/omarchy-theme-engine.py set tokyo-night
  '

  echo "Done! Run '~/start-omarchy.sh' inside Termux to launch Omarchy (Non-Root)."
fi
