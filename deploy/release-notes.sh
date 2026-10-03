#!/usr/bin/env bash
# Emit the GitHub Release body for an AgentCraft server container release.
# Invoked by .github/workflows/release-container.yml.  Usage: deploy/release-notes.sh <YYYY-MM-DD.N>
set -euo pipefail

VERSION="${1:?usage: $0 <YYYY-MM-DD.N>}"
OWNER=$(echo "${GITHUB_REPOSITORY_OWNER:-sandboxservers}" | tr '[:upper:]' '[:lower:]')
IMAGE="ghcr.io/${OWNER}/agentcraft-server"
SHA="${GITHUB_SHA:-}"
TRIGGERING_PR="${TRIGGERING_PR:-}"

cat <<EOF
## AgentCraft server \`${VERSION}\`

Fabric dedicated server (Minecraft 26.3, Fabric API, the AgentCraft mod) on Java 25.
Built from commit \`${SHA:0:7}\`$([ -n "$TRIGGERING_PR" ] && echo ", dispatched via PR #${TRIGGERING_PR}").

### Self-updating host (recommended)

Download \`compose.yaml\` and \`.env.example\` from this release, then:

\`\`\`
cp .env.example .env      # set EULA=TRUE and an RCON_PASSWORD
docker compose up -d
\`\`\`

Watchtower follows \`latest-prerelease\`, backs up the world, and swaps the container within
about 5 minutes of each release. The world lives in the \`agentcraft-data\` volume and persists.

### Pin this exact release

\`\`\`
AGENTCRAFT_IMAGE=${IMAGE}:${VERSION} docker compose up -d
\`\`\`

### Roll back

Set \`AGENTCRAFT_IMAGE\` in \`.env\` to the previous release's tag and \`docker compose up -d\`.
To restore a world backup, see docs/multiplayer/deploy.md.

### Players

Players need Minecraft: Java Edition 26.3 with Fabric Loader, Fabric API and the AgentCraft mod jar,
and run their own Foreman locally (docs/multiplayer/player-install.md).
EOF
