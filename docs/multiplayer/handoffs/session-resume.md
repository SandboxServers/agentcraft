# Multiplayer: Session Resume

> Type: how-to. Audience: the next coordinator session and the owner.
> Updated: 2026-10-03 (MP-00). Companions: [README](../README.md), [work packets](../work-packets.md), [audit](../audit.md), [deploy runbook](../deploy.md).

## State: planned, Wave 0 ready to dispatch

MP-00 (the spike and this plan) is committed on `main`. Nothing is built for multiplayer yet. **Nothing has been deployed to the colo.** The release pipeline exists (`.github/workflows/release-container.yml`, `deploy/`) but has never been run on GitHub; it was verified locally only.

## Before dispatching anything

1. **Node ≥ 22.18** on this machine (it has 22.12; A-04/A-05). Until then `foreman/` `npm run check` shows one failing test that is environmental.
2. **Push `main`** to `origin` (the plan commits are local until the owner says to push). `workflow_dispatch` and `/release` only work once the workflow files are on the default branch.
3. Optional, owner-confirmed: one `/release` (or a manual dispatch) to prove the pipeline on GitHub's runners. That publishes `ghcr.io/sandboxservers/agentcraft-server`. Set the package visibility, or log the colo into GHCR, before Watchtower can pull it.

## Dispatching Wave 0

Four packets, in parallel, each in its own worktree (`git worktree add -b mp/<packet>-<slug> ../agentcraft-wt/<packet> main`) with its own port block ([work-packets.md § Dispatch rules](../work-packets.md#dispatch-rules)):

| Packet | Writer | Notes |
|---|---|---|
| MP-F | `fabric-mod-dev`, with `minecraft-netcode-advisor` on payloads | Review: `server-authority-enforcer` (codecs), `privacy-redaction-auditor` (public record). It records the singleplayer QA baseline run id. |
| MP-T | `fabric-mod-dev` | Touches `build.gradle` (a different block from MP-F). Second to merge rebases. |
| MP-B | `foreman-node-dev` | Tiny. |
| MP-H | `foreman-node-dev` | Uses today's mod. Resolve the two-game-dirs question early. |

When MP-F merges, flip Wave 1 to Ready and dispatch as resources allow (the resource note in Dispatch rules).

## Colo deploy (when the owner says so)

Follow [deploy.md](../deploy.md). On the colo:

```bash
mkdir -p /opt/agentcraft && cd /opt/agentcraft
# put compose.yaml and .env.example from the latest release here
cp .env.example .env && $EDITOR .env      # EULA=TRUE, RCON_PASSWORD, AGENTCRAFT_PORT
docker compose up -d
```

Check RAM headroom next to the Cimmeria stack first. The two Watchtowers are isolated by scope (deploy.md, "Sharing the host with Cimmeria").

## UAT checklist

To be written by MP-Z, one step per behaviour, each with its `agentcraft.mp` log query. The outline:

- join and plot allocation;
- your own studio renders fully;
- another player's agents move, then dim and go offline;
- lamps change for both players;
- visitor clicks are read-only;
- nothing private is visible on a remote display;
- a restart keeps plots;
- a Watchtower update keeps the world and leaves a backup.
