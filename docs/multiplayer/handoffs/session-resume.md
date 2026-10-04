# Multiplayer: Session Resume

> Type: how-to. Audience: the next coordinator session and the owner.
> Updated: 2026-10-04 (Wave 0 in review, Wave 1 ready to write). Companions: [README](../README.md), [work packets](../work-packets.md), [audit](../audit.md), [deploy runbook](../deploy.md).

## State: Wave 0 in review, Wave 1 ready to write

MP-00 (the spike and the plan) and the pull-request CI are on `main`. The four Wave 0 packets are written, reviewed, fixed and verified, and each is an open pull request:

| PR | Packet | What a reviewer should know |
|---|---|---|
| #2 | MP-B baseline | **Merge this first.** It also fixes the one CI job that is red on `main` (`foreman (node 24)`: Node 24 prints its test counters in another format). |
| #1 | MP-T game tests | From here on `gradlew build` boots a headless game-test server (`-x runGameTest` skips it). |
| #4 | MP-H harness | Verified live on macOS only. Its Windows paths have never been run. |
| #5 | MP-F foundation | The frozen contract, the review fixes, and the shared pieces Wave 1 builds on. |

After #2 merges, update the other three from `main` so their CI runs go green.

**Wave 1 is specified and not yet written.** Every packet section in [work-packets.md](../work-packets.md) now ends with a "Decisions" block: the coordinator's answers to the questions two independent advisors raised per packet. Where a Decisions block and older text differ, the Decisions win. The contract gained four shared pieces so that no two packets write the same thing twice: `StudioRange` (who sees which studio), `RateBucket`, the `ForemanStates` test fixtures and `RemoteAgentClicks` (signatures in [the MP-F worknote](../worknotes/MP-F.md), "Seams for Wave 1").

**The colo runs the AgentCraft server** (deployed 2026-10-03; today's singleplayer-HQ build, so a remote player's agents will not render correctly until the campaign lands):
- The stack is in `/opt/agentcraft` (compose plus `.env`, root, mode 600). The world is in the `agentcraft-data` volume.
- It is published on TCP 25565 with a whitelist.
- Its Watchtower is isolated from Cimmeria's, which was verified by restarting Cimmeria's Watchtower (audit A-48).
- Releases `v2026-10-03.1` and `.2` passed every gate on GitHub. The package is public, so the colo pulls it without a login.
- **Automatic updates are proven end to end** (A-55): `/release` or a dispatch → about 10 minutes of CI → the colo backs up, swaps and keeps the world within about 5 minutes.

## Before dispatching anything

1. **Node ≥ 22.18** on this machine (it has 22.12; A-04/A-05). Until then `foreman/` `npm run check` shows one failing test that is environmental.
2. **Port forwarding:** confirm that the colo network's edge forwards TCP 25565 to the host (audit A-53), then whitelist the first players (deploy.md).

## Writing Wave 1

Fourteen packets, each in its own worktree and branch (`mp/<packet>-<slug>`) with its own port block ([work-packets.md § Dispatch rules](../work-packets.md#dispatch-rules)).

- **Base.** Until the four Wave 0 pull requests are merged, Wave 1 starts from a local branch that merges all four onto `main` (they merge cleanly). Once they are on `main`, start from `main`. Do not push that integration branch as if it were a product branch.
- **Order of merging.** MP-02 before MP-03. MP-03 before the other networking packets, because their harness checks need an owned plot. Everything else is independent.
- **What every writer needs to be told** (it is all in the contract, but these are the ones that cost time):
  - only the files in its matrix row, plus its own new tests and worknote;
  - `MpLog.event(MpEvents.NAME, ...)` written exactly like that, because the catalog test reads the sources as text;
  - a game test that needs multiplayer installs a config, acts, asserts and restores it inside one server-thread call;
  - Minecraft 26.3 signatures come from the decompiled sources (`gradlew mcSources`), never from memory.
- **What the coordinator does after each writer:** `gradlew build` (JUnit and the game tests), the singleplayer QA compare for any packet that touches rendering, the HQ or agents, a review by a second model (and a security review for the packets marked **(sec)**), then the pull request with the owner requested as reviewer.

Three things learned in Wave 0 that are easy to get wrong:

- **A QA reference is only valid for the display it was shot on.** A window on a display with a 2× backing scale produces frames twice as large, and every screen is drawn half as large in them. Compare runs whose `manifest.json` reports the same `game.window` size, and re-shoot the reference when it differs.
- **Restarting a client on the same DevBridge port within about 30 seconds fails to bind.** The harness waits and retries once by itself; a hand-run client needs the wait.
- **`gradlew build` restores the test task from the build cache.** `gradlew test --rerun --no-build-cache` is what proves the tests ran.

## Moving the work to another machine

Everything that matters is on GitHub: the four pull-request branches and this folder. A new machine needs JDK 25, Node 22.18 or newer, `GRADLE_USER_HOME` set to `<repo>/.gradle-home`, one `gradlew mcSources` run for the decompiled sources, and `npm ci` in `foreman/` and `tools/`. The live checks (QA screenshots, the two-client harness) need a real GPU and display; the builds, JUnit and the game tests do not, which is why CI can run them.

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
