# Multiplayer: Session Resume

> Type: how-to. Audience: the next coordinator session and the owner.
> Updated: 2026-10-04 (Wave 0 in review; Wave 1 written, reviewed and on `mp/integration`). Companions: [README](../README.md), [work packets](../work-packets.md), [audit](../audit.md), [deploy runbook](../deploy.md).

## State: Wave 0 in review, Wave 1 written and reviewed

MP-00 (the spike and the plan) and the pull-request CI are on `main`. The four Wave 0 packets are written, reviewed, fixed and verified, and each is an open pull request:

| PR | Packet | What a reviewer should know |
|---|---|---|
| #2 | MP-B baseline | **Merge this first.** It also fixes the one CI job that is red on `main` (`foreman (node 24)`: Node 24 prints its test counters in another format). |
| #1 | MP-T game tests | From here on `gradlew build` boots a headless game-test server (`-x runGameTest` skips it). |
| #4 | MP-H harness | Verified live on macOS only. Its Windows paths have never been run. |
| #5 | MP-F foundation | The frozen contract, the review fixes, and the shared pieces Wave 1 builds on. |

After #2 merges, update the other three from `main` so their CI runs go green.

**Wave 1 is written.** All fourteen Wave 1 packets are branches on the fork, and `mp/integration` contains all of them. `mp/integration` is `main` plus the four Wave 0 branches plus the Wave 1 branches, each merged with its own merge commit; it is a place to build and test everything together, never a branch to merge. At `fc06bbc` it builds with 238 JUnit tests and 102 game tests passing. No Wave 1 branch has a pull request yet: open them once Wave 0 is on `main`.

What "reviewed and verified" means for every packet in the table: a worker model wrote it inside a sandbox; the coordinator read every hunk against the packet's Decisions and its row of the file-ownership matrix; a second model reviewed it (an independent security review for the packets marked **(sec)**); the findings went back to a writer as one numbered brief, were fixed and were reviewed again; the last small findings were corrected by the coordinator, and for the security packets the security reviewer then confirmed the final state; and the coordinator ran `gradlew test --rerun --no-build-cache` and `gradlew build` (JUnit and the game tests) on the result. The first lines of each worknote give its status and name what is still owed.

| Packet | Branch | JUnit / game tests | Still owed |
|---|---|---|---|
| MP-01 Server mode and world rules | `mp/MP-01-server-mode` | 49 / 6 | Nothing new: its live dedicated-server checks were seen when it was reviewed |
| MP-02 Build at an offset | `mp/MP-02-offset-build` | 46 / 11 | A build on a second plot of a live server (MP-I) |
| MP-03 Plots: registry, lifecycle, commands **(sec)** | `mp/MP-03-plots` | 63 / 28 | The live dedicated-server checks in its worknote (a join allocates and builds, the commands, a restart keeps the plots) |
| MP-04 Layout sync | `mp/MP-04-layout-sync` | 54 / 2 | The two-client check |
| MP-05 Public-state publisher and redaction **(sec)** | `mp/MP-05-publisher` | 90 / 2 | The live checks in its worknote; the leak scan of relayed bytes is MP-I's |
| MP-06 Relay and presence **(sec)** | `mp/MP-06-relay` | 94 / 4 | The two-client harness checks in its worknote |
| MP-07 World intents **(sec)** | `mp/MP-07-world-intents` | 70 / 10 | The harness checks in its worknote (a remote studio's lamps follow its owner's Foreman) |
| MP-08 Multi-studio agents | `mp/MP-08-multi-studio-agents` | 54 / 2 | The two-client look; the plate-layout timing figure and the speech-bubble look were not examined |
| MP-09 Studio-aware displays | `mp/MP-09-displays` | 60 / 2 | The two-client look (a real remote studio instead of the overlay) |
| MP-10 Studio-aware stations | `mp/MP-10-stations` | 53 / 2 | The two-client look |
| MP-11 Visitor interactions | `mp/MP-11-visitors` | 52 / 2 | The two-client checks in its worknote |
| MP-12 DevBridge in multiplayer | `mp/MP-12-dev-tools` | 55 / 2 | The harness checks in its worknote (remote camera, remote command, `dev.mp.send` on a real server) |
| MP-13 Plot protection **(sec)** | `mp/MP-13-plot-protection` | 50 / 53 | The live protection checks with two players; the owner's decision on the known limits in its worknote |
| MP-14 Deployment for multiplayer | `mp/MP-14-deploy` | 46 / 2 | A release-workflow run with the new smoke assertions (the owner starts it) |

**What has been seen in a game window, and what has not.** Seen, in a singleplayer dev client on `mp/integration`:

- the singleplayer QA compare (`tools/qa.mjs`, ten shots) against a reference shot on the same display at the Wave 1 base: no shot moved, at five points while the packets were merged, the last one with all fourteen in; the singleplayer client logged no `agentcraft.mp` line;
- the remote looks through the `dev.mp.fake` overlay: the monitors and the task wall (MP-09), the podium, merge station, archive, console and hologram (MP-10), the visitor panel and the read-only agent card (MP-11), and the remote agents with their plates, their waiting spot and the dimmed "Foreman offline" state (MP-08);
- `dev.mp.send` refusing in singleplayer, and `dev.state` reporting the mode, the studios and no plot (MP-12).

Not seen by anyone, and still owed before any of this is called done: **every check that needs a dedicated server with a real player, and every two-client harness check**, for every packet (the last column of the table). They belong to MP-I unless a packet's pull request needs them earlier. Nothing in this campaign has run against the hosted server.

**Contract notes for the coordinator's next edit of work-packets.md** (packets may not edit it):

- `studio_event_rejected` needs the reason `bad_version` in the telemetry catalog (MP-06 refuses an event from a sender without an accepted hello with it).
- `plot_command` keeps its catalog fields: a refused command carries no `reason` (decided during MP-03's review).
- MP-13 took three decisions under D-MP05 that the owner should confirm: with an empty hand a visitor may use only doors, trapdoors, fence gates, buttons and levers inside a plot; protection covers a margin of one block around each plot, because beds, doors and attached blocks reach across the edge (a visitor can neither place nor break a block that touches someone else's plot); and structures completed from outside the margin that need blocks the owner placed across the plot's edge (a nether portal, a golem or wither pattern) are left open, while the eye of ender is closed. Its worknote lists what protection does not cover (fluids and fire from outside, projectiles, trampling); closing the projectile case needs a second mixin, which the packet was not allowed.

**What the reviews found**, so that the next reviewer knows what to look for:

- a singleplayer code path changed by accident: a failed plan save had become an exception; AutoWorld had stopped opening the world from any screen but the title screen; the station driver wrote to the integrated server's world from the client thread, where the chunk lookup returns nothing, so no lamp changed while every test passed;
- a trust boundary decided on the wrong position: plot protection tested the clicked block, so a block could be placed into a plot from just outside it; breaking the outside half of a bed removed the half inside; a lily pad, which vanilla places from the item's use-in-air path, was never tested at all; and an eye of ender put into a portal frame outside replaced blocks inside;
- presence and privacy taken from a cache instead of the source: "online" read from the last stored state; speech text kept because the policy was read when the text was queued, not when it was sent;
- telemetry lines without the `studio` and `plot` correlators the contract requires, and a recipient count that counted attempts;
- tests that could not fail (one logged the event itself and then asserted the log; one put its "outside" block where the code under test never looks; one used the owner, who is always allowed), and game tests that left blocks, players or bookkeeping behind in the shared world;
- an edit nobody had asked for, inside an unrelated loop.

**The colo runs the AgentCraft server** (deployed 2026-10-03; today's singleplayer-HQ build, so a remote player's agents will not render correctly until the campaign lands):
- The stack is in `/opt/agentcraft` (compose plus `.env`, root, mode 600). The world is in the `agentcraft-data` volume.
- It is published on TCP 25565 with a whitelist.
- Its Watchtower is isolated from Cimmeria's, which was verified by restarting Cimmeria's Watchtower (audit A-48).
- Releases `v2026-10-03.1` and `.2` passed every gate on GitHub. The package is public, so the colo pulls it without a login.
- **Automatic updates are proven end to end** (A-55): `/release` or a dispatch → about 10 minutes of CI → the colo backs up, swaps and keeps the world within about 5 minutes.

## Before dispatching anything

1. **Node ≥ 22.18** on the machine that runs the `foreman/` checks (A-04/A-05). With an older Node `npm run check` shows one failing test that is environmental.
2. **Port forwarding:** confirm that the colo network's edge forwards TCP 25565 to the host (audit A-53), then whitelist the first players (deploy.md).

## Picking up after Wave 1

- **Pull requests.** Once the four Wave 0 pull requests are on `main`, open one pull request per Wave 1 branch with the owner requested as reviewer. Each branch started from the Wave 0 integration base, so merge `main` into it first.
- **Order of merging into `main`.** MP-02 before MP-03. MP-03 before the other networking packets, because their harness checks need an owned plot. MP-14 after MP-01 and MP-03. Everything else is independent.
- **Where MP-02 and MP-03 meet**, `HqFeature.buildAndPublish` must call `PlanStore.invalidateUnless(server, options.studio(), builder.id())`, so that a player's own plan file is the one invalidated. MP-03 was written before that method existed. The merge commit "Integrate mp/MP-03-plots" on `mp/integration` carries that line; the same change is needed when the two reach `main`.
- **MP-I is next** (it has not started): the harness end to end, the leak scan of relayed bytes and remote screenshots, the full singleplayer QA compare, and every check the table above lists as owed.
- **A packet that needs another round:** read its worknote, then its diff against `mp/integration`, against the packet's Decisions and its row of the file-ownership matrix. Have a second model review it (a security review for the packets marked **(sec)**). Send the findings back as one numbered brief, then verify as below.
- **What every writer needs to be told** (it is all in the contract, but these are the ones that cost time):
  - only the files in its matrix row, plus its own new tests and worknote;
  - `MpLog.event(MpEvents.NAME, ...)` written exactly like that, because the catalog test reads the sources as text;
  - a game test that needs multiplayer installs a config, acts, asserts and restores it inside one server-thread call, and puts back every block, player and file it touched;
  - Minecraft 26.3 signatures come from the decompiled sources (`gradlew mcSources`), never from memory.
- **What the coordinator does after each writer:** read every hunk; `gradlew test --rerun --no-build-cache` and `gradlew build` (JUnit and the game tests); the singleplayer QA compare for any packet that touches rendering, the HQ, agents or the client's start-up; the live commands the worknote lists; a review by a second model (and a security review for the packets marked **(sec)**), and a re-review after every fix round; then the pull request with the owner requested as reviewer.

Things learned that are easy to get wrong:

- **A QA reference is only valid for the display it was shot on.** A window on a display with a 2× backing scale produces frames twice as large, and every screen is drawn half as large in them. Compare runs whose `manifest.json` reports the same `game.window` size, and re-shoot the reference when it differs.
- **Restarting a client on the same DevBridge port within about 30 seconds fails to bind.** The harness waits and retries once by itself; a hand-run client needs the wait.
- **`gradlew build` restores the test task from the build cache.** `gradlew test --rerun --no-build-cache` is what proves the tests ran.
- **Every game-test run starts in a fresh world, and the tests of one run execute in no guaranteed order.** A test must not depend on what another test built, and must put back what it changed.
- **Sandboxed builds need `--no-daemon`.** A Gradle daemon serves the next build that comes along, whoever asked for it. A daemon started inside one worker's sandbox then fails another worktree's build with "projectDirectory … can't be written to"; the other way round, a worker's build runs outside its sandbox.
- **A game client that starts while the display setup changes can hang in its first frame.** Stop it and start it again.
- **An enabled server on the wrong world type prints `Done` first, then refuses and exits with status 0** (MP-01 worknote). A container with a restart policy will loop on it.
- **Green tests do not show that singleplayer still works.** The station driver's regression (above) passed 67 JUnit tests and 10 game tests. Read the hunk, and run the QA compare.
- **A fix round can introduce the next finding.** Every fix round here was reviewed again by the second model, and the four security packets written in this wave each needed three or four passes.
- **Vanilla's `GameTestServer.isDedicatedServer()` returns false; Fabric's game-test API overrides it to true.** That is why the `dedicated && enabled` gate is open in game tests.
- **A worker session that grows too long fails with a provider error and no report.** Start a fresh session from the working tree with a brief that lists what remains.

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
