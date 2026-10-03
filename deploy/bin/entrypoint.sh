#!/usr/bin/env bash
# AgentCraft server entrypoint. /data is the persistent volume (world, configs, logs, backups);
# /opt/agentcraft is the image (launcher, Minecraft, libraries, mods) and is replaced on update.
set -euo pipefail

HOME_DIR="${AGENTCRAFT_HOME:-/opt/agentcraft}"
cd /data

log() { printf '[entrypoint] %s\n' "$*"; }

# Minecraft's EULA must be accepted by whoever runs the server, never by the image.
case "${EULA:-}" in
  [Tt][Rr][Uu][Ee]) echo "eula=true" > eula.txt ;;
  *)
    log "Set EULA=TRUE to accept the Minecraft EULA (https://aka.ms/MinecraftEULA)."
    exit 64
    ;;
esac

# Image-owned files: re-linked on every start so an update always takes effect.
for item in fabric-server-launch.jar libraries versions; do
  if [ -e "$HOME_DIR/$item" ]; then ln -sfn "$HOME_DIR/$item" "/data/$item"; fi
done
# The launcher's cache must be writable: copy the image's once per image version.
IMAGE_VERSION="$(cat "$HOME_DIR/VERSION" 2>/dev/null || echo unknown)"
if [ -d "$HOME_DIR/.fabric" ] && [ "$(cat /data/.fabric/.image-version 2>/dev/null || true)" != "$IMAGE_VERSION" ]; then
  rm -rf /data/.fabric
  cp -a "$HOME_DIR/.fabric" /data/.fabric
  echo "$IMAGE_VERSION" > /data/.fabric/.image-version
  log "Refreshed the Fabric launcher cache for image ${IMAGE_VERSION}."
fi
# Mods come from the image only; an operator's extra mods go in /data/mods-extra.
rm -rf /data/mods && mkdir -p /data/mods
for jar in "$HOME_DIR"/mods/*.jar /data/mods-extra/*.jar; do
  [ -e "$jar" ] && ln -sfn "$jar" "/data/mods/$(basename "$jar")"
done

# Operator-owned files: seeded once, then never overwritten.
mkdir -p /data/config /data/backups
if [ ! -f server.properties ]; then
  cp "$HOME_DIR/defaults/server.properties" server.properties
  log "Seeded server.properties (superflat studio world, whitelist on)."
fi
for f in "$HOME_DIR"/defaults/config/*; do
  [ -e "$f" ] || continue
  target="/data/config/$(basename "$f")"
  [ -f "$target" ] || { cp "$f" "$target"; log "Seeded config/$(basename "$f")."; }
done

# RCON for backups and scripted admin, reachable only inside the container (not published).
set_prop() {
  local key="$1" value="$2"
  if grep -q "^${key}=" server.properties; then
    sed -i "s|^${key}=.*|${key}=${value}|" server.properties
  else
    echo "${key}=${value}" >> server.properties
  fi
}
if [ -n "${RCON_PASSWORD:-}" ]; then
  set_prop enable-rcon true
  set_prop rcon.port 25575
  set_prop rcon.password "$RCON_PASSWORD"
  set_prop broadcast-rcon-to-ops false
else
  set_prop enable-rcon false
  log "RCON_PASSWORD is not set: RCON is off, so pre-update backups fall back to a cold copy."
fi

log "AgentCraft $(cat "$HOME_DIR/VERSION" 2>/dev/null || echo unknown), Minecraft ${MC_VERSION:-?}, memory ${MEMORY:-3G}"
# shellcheck disable=SC2086
exec java -Xms"${MEMORY:-3G}" -Xmx"${MEMORY:-3G}" ${JAVA_OPTS:-} -jar fabric-server-launch.jar nogui
