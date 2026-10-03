#!/usr/bin/env bash
# Back up the world to /data/backups/<UTC timestamp>.tar.gz and keep the newest $BACKUP_KEEP.
# Runs inside the container: as Watchtower's pre-update hook (deploy/compose.yaml), or by hand:
#   docker exec agentcraft /opt/agentcraft/bin/backup.sh
set -euo pipefail
cd /data

KEEP="${BACKUP_KEEP:-14}"
STAMP="$(date -u +%Y%m%dT%H%M%SZ)"
OUT="/data/backups/world-${STAMP}.tar.gz"
LEVEL="$(sed -n 's/^level-name=//p' server.properties 2>/dev/null || true)"
LEVEL="${LEVEL:-world}"

rcon() { rcon-cli --host 127.0.0.1 --port 25575 --password "$RCON_PASSWORD" "$@"; }

flushed=0
if [ -n "${RCON_PASSWORD:-}" ] && rcon "save-off" >/dev/null 2>&1; then
  rcon "save-all flush" >/dev/null 2>&1 || true
  flushed=1
fi
trap '[ "$flushed" = 1 ] && rcon "save-on" >/dev/null 2>&1 || true' EXIT

mkdir -p /data/backups
tar -czf "$OUT" --exclude="./${LEVEL}/session.lock" -C /data "./${LEVEL}" ./config \
  $( [ -f whitelist.json ] && echo ./whitelist.json ) $( [ -f ops.json ] && echo ./ops.json ) server.properties
echo "[backup] wrote $OUT ($(du -h "$OUT" | cut -f1)); flushed=${flushed}"

# Retention: newest $KEEP backups.
ls -1t /data/backups/world-*.tar.gz 2>/dev/null | tail -n +"$((KEEP + 1))" | xargs -r rm -f
