# Multiplayer: Session Resume

> Type: how-to. Audience: the next coordinator session and the owner.
> Updated: 2026-10-03 (MP-00). Companions: [README](../README.md), [work packets](../work-packets.md), [audit](../audit.md), [deploy runbook](../deploy.md).

## State: planned, Wave 0 ready to dispatch

MP-00 (the spike and this plan) is on `main`. Nothing is built for multiplayer yet.

**The colo runs the AgentCraft server** (deployed 2026-10-03; today's singleplayer-HQ build, so a remote player's agents will not render correctly until the campaign lands):
- The stack is in `/opt/agentcraft` (compose plus `.env`, root, mode 600). The world is in the `agentcraft-data` volume.
- It is published on TCP 25565 with a whitelist.
- Its Watchtower is isolated from Cimmeria's, which was verified by restarting Cimmeria's Watchtower (audit A-48).
- Releases `v2026-10-03.1` and `.2` passed every gate on GitHub. The package is public, so the colo pulls it without a login.
- **Automatic updates are proven end to end** (A-55): `/release` or a dispatch → about 10 minutes of CI → the colo backs up, swaps and keeps the world within about 5 minutes.

## Before dispatching anything

1. **Node ≥ 22.18** on this machine (it has 22.12; A-04/A-05). Until then `foreman/` `npm run check` shows one failing test that is environmental.
2. **Port forwarding:** confirm that the colo network's edge forwards TCP 25565 to the host (audit A-53), then whitelist the first players (deploy.md).

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
