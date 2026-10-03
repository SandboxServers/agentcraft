# Deploying the AgentCraft server

> Type: how-to. Audience: the operator of a self-updating host (the colo).
> Updated: 2026-10-03. Companions: [campaign README](README.md), [`deploy/`](../../deploy), [release workflow](../../.github/workflows/release-container.yml).

This repository is public. **Never commit a host's address, hostname, SSH alias or any credential.** Host-specific values live only in `.env` on the host.

## How a release reaches the colo

The pipeline is the same shape as Cimmeria's. Nothing in GitHub can reach the colo: the colo pulls.

1. A maintainer comments `/release` on a merged PR (or runs **release-container** from the Actions tab on `main`).
2. `release-container.yml` builds `deploy/Dockerfile` from `main` HEAD. That image is the mod jar from `mod/`, Fabric 26.3 with Fabric API, the vanilla server and libraries pre-downloaded, and `rcon-cli`.
3. The image is scanned with Trivy. A CRITICAL finding blocks the release, except entries in `deploy/.trivyignore`, each with a reason and an expiry.
4. The dated tag `ghcr.io/sandboxservers/agentcraft-server:<YYYY-MM-DD.N>` is pushed with provenance and an SBOM.
5. The smoke test runs what the colo does on every update: boot, studio build, backup hook, graceful stop, restart on the same volume, and a check that a placed block survived.
6. Only then does `latest-prerelease` move, and a GitHub pre-release is created with `compose.yaml` and `.env.example` attached.
7. On the colo, the AgentCraft Watchtower notices the new `latest-prerelease` digest within 5 minutes. It runs `backup.sh` in the running container, stops it gracefully (the world saves), and starts the new image on the same volume.

## One-time setup on the host

Prerequisites: Docker Engine with the `docker compose` v2 plugin, and the chosen TCP port (default 25565) reachable by players.

```bash
mkdir -p /opt/agentcraft && cd /opt/agentcraft
# compose.yaml and .env.example are attached to every GitHub release (or copy them from deploy/)
cp .env.example .env
# edit .env: EULA=TRUE (you accept https://aka.ms/MinecraftEULA), a long random RCON_PASSWORD,
# AGENTCRAFT_PORT if 25565 is taken, MEMORY
docker compose up -d
docker logs -f agentcraft        # wait for "Done (" and "Published layout 'studio'"
```

- **GHCR visibility:** the first release creates the `agentcraft-server` package. If it is private, either make it public (Package settings → Change visibility) or run `docker login ghcr.io` on the host with a read-only token, so Watchtower can pull.
- **Sharing the host with Cimmeria:** this compose project is named `agentcraft` and has its own containers, volume and port. Its Watchtower runs with scope `agentcraft`, and the server container carries only the scope label, never `com.centurylinklabs.watchtower.enable`. So Cimmeria's Watchtower (label-enable mode) never touches it, this one never touches Cimmeria's containers, and the two Watchtower instances do not shut each other down. Check headroom first: the server wants `MEMORY` plus about 1 GB.

## Players and the whitelist

`white-list` and `enforce-whitelist` are on and `online-mode` is on (Minecraft account identity is the studio owner's identity). Add players from the console:

```bash
docker exec agentcraft rcon-cli --password "$RCON_PASSWORD" "whitelist add <name>"
docker exec agentcraft rcon-cli --password "$RCON_PASSWORD" "op <name>"      # only for admins
```

From Git Bash on Windows, prefix `docker exec` commands with `MSYS_NO_PATHCONV=1`, or Git Bash rewrites `/opt/...` paths. For an interactive console: `docker attach agentcraft` (detach with Ctrl-P Ctrl-Q, never Ctrl-C).

What players install is in [player-install.md](player-install.md) (written by MP-14).

## Backups and restore

- **Automatic:** before every Watchtower update, into the volume's `/data/backups`. The newest `BACKUP_KEEP` (default 14) are kept.
- **By hand:** `docker exec agentcraft /opt/agentcraft/bin/backup.sh`. With `RCON_PASSWORD` set it runs `save-off` / `save-all flush` first; without it, it takes a live copy.
- **Off the host:** copy them out with `docker cp agentcraft:/data/backups ./agentcraft-backups`.
- **Restore:**

  ```bash
  docker compose stop agentcraft
  docker run --rm -v agentcraft_agentcraft-data:/data -v "$PWD":/restore alpine \
    sh -c 'cd /data && rm -rf "AgentCraft HQ" && tar -xzf /restore/world-<stamp>.tar.gz'
  docker compose start agentcraft
  ```

## Rolling back a release

Pin the previous release in `.env` and recreate:

```bash
AGENTCRAFT_IMAGE=ghcr.io/sandboxservers/agentcraft-server:<previous YYYY-MM-DD.N>
docker compose up -d
```

Watchtower leaves a pinned tag alone until you remove the pin. Once multiplayer lands (campaign MP-F onward), `"enabled": false` in `/data/config/agentcraft-server.json` turns every multiplayer code path off without a rollback.

## What the image does on start

- It refuses to start unless `EULA=TRUE`.
- It links the image's launcher, libraries and Minecraft into `/data`, and refreshes the Fabric launcher cache when the image changes.
- It mounts mods from the image only. Extra operator mods go in `/data/mods-extra`.
- It seeds `server.properties` once:
  - a superflat world with the grass top at y = 64, which the studio builder requires;
  - level name `AgentCraft HQ`, so the HQ rules apply and the studio builds on first start;
  - creative, peaceful, whitelist on, and the management server off.
- It sets RCON from `RCON_PASSWORD`. RCON is never published outside the container.

## Troubleshooting

| Symptom | Cause and fix |
|---|---|
| Container exits with code 64 at once | `EULA` is not `TRUE` in `.env`. |
| Watchtower logs `client version 1.25 is too old` | Docker Engine 29+ dropped old API versions. Add `DOCKER_API_VERSION: "1.44"` to the watchtower service's environment. |
| Updates never arrive | The GHCR package is private and the host is not logged in, or `AGENTCRAFT_IMAGE` pins a tag. |
| The studio is missing or half-built after changing the world type | The builder needs a superflat world with grass at y = 64. Restore a backup, or start a fresh volume. |
| `docker exec ... /opt/...: no such file` from Git Bash | Use `MSYS_NO_PATHCONV=1`. |
