#!/usr/bin/env bash
# ============================================================================
# Personal Migration Backup Script (LOCAL ONLY — NEVER COMMIT BACKUPS TO GIT!)
# Exports your personal AI configs (CC Switch DB, Claude, Codex, Hermes)
# from an Android device via ADB into a local archive for migrating to a new device.
# ============================================================================
set -euo pipefail

SERIAL="${1:-}"
ADB_CMD="adb"
[[ -n "$SERIAL" ]] && ADB_CMD="adb -s $SERIAL"

OUT_FILE="omarchy-personal-backup-$(date +%Y%m%d-%H%M%S).tar.gz"
echo "Packing personal configs from /data/ubuntu/root/ ..."
$ADB_CMD shell "tar -C /data/ubuntu/root -czf /sdcard/$OUT_FILE \
  .cc-switch/cc-switch.db .cc-switch/settings.json \
  .claude/settings.json \
  .codex/config.toml .codex/auth.json .codex/mimo-models.json .codex/.codex-global-state.json \
  .hermes/config.yaml .hermes/.env .hermes/auth.json .hermes/ui-preferences.json \
  2>/dev/null || true"
$ADB_CMD pull "/sdcard/$OUT_FILE" "./$OUT_FILE"
$ADB_CMD shell "rm -f /sdcard/$OUT_FILE"
chmod 600 "./$OUT_FILE"
echo "Saved encrypted-ready personal archive: ./$OUT_FILE (mode 600 — keep private!)"
