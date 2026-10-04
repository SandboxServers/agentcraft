# Multiplayer: Session Resume

> Type: how-to. Audience: the next coordinator session and the owner.
> Updated: 2026-10-04 (Wave 0 in review, Wave 1 half written, work paused for a move to another machine). Companions: [README](../README.md), [work packets](../work-packets.md), [audit](../audit.md), [deploy runbook](../deploy.md).

## State: Wave 0 in review, Wave 1 half written

MP-00 (the spike and the plan) and the pull-request CI are on `main`. The four Wave 0 packets are written, reviewed, fixed and verified, and each is an open pull request:

| PR | Packet | What a reviewer should know |
|---|---|---|
| #2 | MP-B baseline | **Merge this first.** It also fixes the one CI job that is red on `main` (`foreman (node 24)`: Node 24 prints its test counters in another format). |
| #1 | MP-T game tests | From here on `gradlew build` boots a headless game-test server (`-x runGameTest` skips it). |
| #4 | MP-H harness | Verified live on macOS only. Its Windows paths have never been run. |
| #5 | MP-F foundation | The frozen contract, the review fixes, and the shared pieces Wave 1 builds on. |

After #2 merges, update the other three from `main` so their CI runs go green.

**Wave 1 is specified.** Every packet section in [work-packets.md](../work-packets.md) ends with a "Decisions" block: the coordinator's answers to the questions two independent advisors raised per packet. Where a Decisions block and older text differ, the Decisions win. The contract gained four shared pieces so that no two packets write the same thing twice: `StudioRange` (who sees which studio), `RateBucket`, the `ForemanStates` test fixtures and `RemoteAgentClicks` (signatures in [the MP-F worknote](../worknotes/MP-F.md), "Seams for Wave 1").

**Seven of the fourteen Wave 1 packets exist as branches on the fork.** Each starts from `mp/integration`, which is also on the fork: `main` plus the four Wave 0 branches. It is the base of every Wave 1 branch and never a branch to merge. No Wave 1 branch has a pull request yet: open them once Wave 0 is on `main`.

| Packet | Branch | State | Still owed |
|---|---|---|---|
| MP-01 Server mode and world rules | `mp/MP-01-server-mode` | Reviewed and verified, a live dedicated server included | Pull request |
| MP-02 Build at an offset | `mp/MP-02-offset-build` | Reviewed and verified | Pull request |
| MP-04 Layout sync | `mp/MP-04-layout-sync` | Reviewed; unit tests verified | The two-client check, which needs MP-03's plots. Pull request |
| MP-03 Plots **(sec)** | `mp/MP-03-plots` | Written, not reviewed | Review, security review, the singleplayer QA compare (it edits `HqFeature`), the live checks in its worknote, and the one-line `HqFeature` change that MP-02's worknote names |
| MP-05 Publisher and redaction **(sec)** | `mp/MP-05-publisher` | Written, not reviewed | Review, security review, the live checks in its worknote |
| MP-09 Studio-aware displays | `mp/MP-09-displays` | Written, not reviewed | Review, the singleplayer QA compare, the `dev.mp.fake` screenshots in its worknote |
| MP-11 Visitor interactions | `mp/MP-11-visitors` | Written, not reviewed | Review, the singleplayer QA compare, the live checks in its worknote |
| MP-06, MP-07, MP-08, MP-10, MP-12, MP-13, MP-14 | none | Not started | Everything |

**"Written, not reviewed"** means that a worker wrote the packet to its brief inside a sandbox, and that the coordinator then ran its JUnit tests and the game tests again outside the sandbox, where they pass. Nobody has read the code against the packet's Decisions yet. The first lines of each such worknote say so.

**What the review found in the three reviewed packets**, so that the next reviewer knows what to look for:

- a singleplayer code path changed by accident (a failed plan save had become an exception; AutoWorld had stopped opening the world from any screen but the title screen);
- telemetry lines without the `studio` and `plot` correlators the contract requires;
- tests that could not fail (one logged the event itself and then asserted the log; one probed six cells far from where a bug would write), and game tests that passed only in the order they happened to run;
- an edit nobody had asked for, inside an unrelated loop.

**The colo runs the AgentCraft server** (deployed 2026-10-03; today's singleplayer-HQ build, so a remote player's agents will not render correctly until the campaign lands):
- The stack is in `/opt/agentcraft` (compose plus `.env`, root, mode 600). The world is in the `agentcraft-data` volume.
- It is published on TCP 25565 with a whitelist.
- Its Watchtower is isolated from Cimmeria's, which was verified by restarting Cimmeria's Watchtower (audit A-48).
- Releases `v2026-10-03.1` and `.2` passed every gate on GitHub. The package is public, so the colo pulls it without a login.
- **Automatic updates are proven end to end** (A-55): `/release` or a dispatch → about 10 minutes of CI → the colo backs up, swaps and keeps the world within about 5 minutes.

## Before dispatching anything

1. **Node ≥ 22.18** on this machine (it has 22.12; A-04/A-05). Until then `foreman/` `npm run check` shows one failing test that is environmental.
2. **Port forwarding:** confirm that the colo network's edge forwards TCP 25565 to the host (audit A-53), then whitelist the first players (deploy.md).

## Picking up Wave 1

Fourteen packets, each in its own worktree and branch (`mp/<packet>-<slug>`) with its own port block ([work-packets.md § Dispatch rules](../work-packets.md#dispatch-rules)).

- **Base.** `mp/integration` on the fork, until the four Wave 0 pull requests are merged. Once they are on `main`, start new packets from `main` and merge `main` into the existing Wave 1 branches.
- **A packet that is written but not reviewed:** read its worknote, then its diff against `mp/integration`, against the packet's Decisions and its row of the file-ownership matrix. Have a second model review it (a security review for the packets marked **(sec)**). Send the findings back as one numbered brief, then verify as below.
- **A packet that is not started:** a worktree from the base, and a writer whose brief points at the contract instead of repeating it.
- **Order of merging.** MP-02 before MP-03. MP-03 before the other networking packets, because their harness checks need an owned plot. Everything else is independent. When MP-02 and MP-03 meet, `HqFeature` needs the one-line change that MP-02's worknote names: MP-03 was written before that method existed.
- **What every writer needs to be told** (it is all in the contract, but these are the ones that cost time):
  - only the files in its matrix row, plus its own new tests and worknote;
  - `MpLog.event(MpEvents.NAME, ...)` written exactly like that, because the catalog test reads the sources as text;
  - a game test that needs multiplayer installs a config, acts, asserts and restores it inside one server-thread call;
  - Minecraft 26.3 signatures come from the decompiled sources (`gradlew mcSources`), never from memory.
- **What the coordinator does after each writer:** `gradlew test --rerun --no-build-cache` and `gradlew build` (JUnit and the game tests) outside the worker's sandbox, the singleplayer QA compare for any packet that touches rendering, the HQ, agents or the client's start-up, the live commands the worknote lists, a review by a second model (and a security review for the packets marked **(sec)**), then the pull request with the owner requested as reviewer.

Things learned that are easy to get wrong:

- **A QA reference is only valid for the display it was shot on.** A window on a display with a 2× backing scale produces frames twice as large, and every screen is drawn half as large in them. Compare runs whose `manifest.json` reports the same `game.window` size, and re-shoot the reference when it differs.
- **Restarting a client on the same DevBridge port within about 30 seconds fails to bind.** The harness waits and retries once by itself; a hand-run client needs the wait.
- **`gradlew build` restores the test task from the build cache.** `gradlew test --rerun --no-build-cache` is what proves the tests ran.
- **Every game-test run starts in a fresh world, and the tests of one run execute in no guaranteed order.** A test must not depend on what another test built, and must put back what it changed.
- **Sandboxed builds need `--no-daemon`.** A Gradle daemon serves the next build that comes along, whoever asked for it. A daemon started inside one worker's sandbox then fails another worktree's build with "projectDirectory … can't be written to"; the other way round, a worker's build runs outside its sandbox.
- **A game client that starts while the display setup changes can hang in its first frame.** Stop it and start it again.
- **An enabled server on the wrong world type prints `Done` first, then refuses and exits with status 0** (MP-01 worknote). A container with a restart policy will loop on it.

## Moving the work to another machine

Everything public is on the fork: the four pull-request branches, `mp/integration`, the Wave 1 branches above and this folder. A new machine needs JDK 25, Node 22.18 or newer, `GRADLE_USER_HOME` set to `<repo>/.gradle-home`, one `gradlew mcSources` run for the decompiled sources, and `npm ci` in `foreman/` and `tools/`. The live checks (QA screenshots, the two-client harness) need a real GPU and display; the builds, JUnit and the game tests do not, which is why CI can run them.

The coordinator's own tooling is **not** in this repository: the build and game-slot wrappers, the sandbox for the worker CLIs, the personal-data scan, the briefs, the advisors' plans and the reviewers' findings. It carries local paths and the list of strings that must never be published, so it travels as a private bundle with its own README. The campaign can continue without it from this folder alone, because the contract, the Decisions and the worknotes are complete; the bundle saves rebuilding the wrappers.

A worker's session does not move with the code. A fix round on a new machine starts a new session from the branch and a review brief.

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
