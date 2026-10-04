# Multiplayer: Session Resume

> Type: how-to. Audience: the next coordinator session and the owner.
> Updated: 2026-10-04 (Wave 0 in review; Wave 1 written, reviewed, on `mp/integration` and open as pull requests #6 to #19). Companions: [README](../README.md), [work packets](../work-packets.md), [audit](../audit.md), [deploy runbook](../deploy.md).

## State: Wave 0 in review, Wave 1 written and reviewed

MP-00 (the spike and the plan) and the pull-request CI are on `main`. The four Wave 0 packets are written, reviewed, fixed and verified, and each is an open pull request:

| PR | Packet | What a reviewer should know |
|---|---|---|
| #2 | MP-B baseline | **Merge this first.** It also fixes the one CI job that is red on `main` (`foreman (node 24)`: Node 24 prints its test counters in another format). |
| #1 | MP-T game tests | From here on `gradlew build` boots a headless game-test server (`-x runGameTest` skips it). |
| #4 | MP-H harness | Verified live on macOS only. Its Windows paths have never been run. |
| #5 | MP-F foundation | The frozen contract, the review fixes, and the shared pieces Wave 1 builds on. |

After #2 merges, update the other three from `main` so their CI runs go green.

**Wave 1 is written.** All fourteen Wave 1 packets are branches on the fork, and `mp/integration` contains all of them. `mp/integration` is `main` plus the four Wave 0 branches plus the Wave 1 branches, each merged with its own merge commit; it is a place to build and test everything together, never a branch to merge. At `268f9ae`, with the fixes from the pull-request review merged again, it builds with 289 JUnit tests and 115 game tests passing, and the tools suite has 70 tests. Each Wave 1 branch is an open pull request against `main` (#6 to #19) with the owner requested as reviewer. Each is stacked on the four Wave 0 pull requests, so its diff also shows their changes until they merge; every description links the comparison from the Wave 0 base, which is the packet's own change.

What "reviewed and verified" means for every packet in the table: a worker model wrote it inside a sandbox; the coordinator read every hunk against the packet's Decisions and its row of the file-ownership matrix; a second model reviewed it (an independent security review for the packets marked **(sec)**); the findings went back to a writer as one numbered brief, were fixed and were reviewed again; the last small findings were corrected by the coordinator, and for the security packets the security reviewer then confirmed the final state; and the coordinator ran `gradlew test --rerun --no-build-cache` and `gradlew build` (JUnit and the game tests) on the result. The first lines of each worknote give its status and name what is still owed.

| Packet | PR | Branch | JUnit / game tests | Still owed |
|---|---|---|---|---|
| MP-01 Server mode and world rules | #6 | `mp/MP-01-server-mode` | 49 / 6 | Nothing new: its live dedicated-server checks were seen when it was reviewed |
| MP-02 Build at an offset | #7 | `mp/MP-02-offset-build` | 46 / 12 | A build on a second plot of a live server (MP-I) |
| MP-03 Plots: registry, lifecycle, commands **(sec)** | #8 | `mp/MP-03-plots` | 69 / 31 | The live dedicated-server checks in its worknote (a join allocates and builds, the commands, a restart keeps the plots) |
| MP-04 Layout sync | #9 | `mp/MP-04-layout-sync` | 57 / 2 | The two-client check |
| MP-05 Public-state publisher and redaction **(sec)** | #10 | `mp/MP-05-publisher` | 99 / 2 | The live checks in its worknote; the leak scan of relayed bytes is MP-I's |
| MP-06 Relay and presence **(sec)** | #11 | `mp/MP-06-relay` | 101 / 4 | The two-client harness checks in its worknote |
| MP-07 World intents **(sec)** | #12 | `mp/MP-07-world-intents` | 71 / 10 | The harness checks in its worknote (a remote studio's lamps follow its owner's Foreman) |
| MP-08 Multi-studio agents | #13 | `mp/MP-08-multi-studio-agents` | 58 / 2 | The two-client look; the plate-layout timing figure and the speech-bubble look were not examined |
| MP-09 Studio-aware displays | #14 | `mp/MP-09-displays` | 67 / 2 | The two-client look (a real remote studio instead of the overlay) |
| MP-10 Studio-aware stations | #15 | `mp/MP-10-stations` | 53 / 2 | The two-client look |
| MP-11 Visitor interactions | #16 | `mp/MP-11-visitors` | 57 / 2 | The two-client checks in its worknote |
| MP-12 DevBridge in multiplayer | #17 | `mp/MP-12-dev-tools` | 60 / 2 | The harness checks in its worknote (remote camera, remote command, `dev.mp.send` on a real server) |
| MP-13 Plot protection **(sec)** | #18 | `mp/MP-13-plot-protection` | 50 / 62 | The live protection checks with two players; the owner's decision on the known limits in its worknote |
| MP-14 Deployment for multiplayer | #19 | `mp/MP-14-deploy` | 46 / 2 | A release-workflow run with the new smoke assertions (the owner starts it) |

**The pull-request review (2026-10-04).** An automated reviewer commented on every pull request and the owner's reviewer wrote one document for all of them. Each finding was checked against the code first, fixed on the packet's own branch with a test that failed before the fix, and verified again (JUnit and game tests); the fixes of the security packets had their own security re-review. Two pull requests were marked blocking and are fixed:

- #13 (MP-08): a remote player's skin name went straight into a resource identifier, which throws on most characters, so it crashed every viewer in range. The skin lookup never throws now and is bounded, and the foundation refuses such a name at decode and where a record is built.
- #17 (MP-12): the DevBridge is on by default in the jar a player installs, and its commands could act on a shared server with the player's rights. The remote paths now need `AGENTCRAFT_DEV_REMOTE=1` in the game's environment, which only the harness sets; the player guide tells players to switch the DevBridge off.

The other fixes, by pull request: #5 the hello survives a protocol change (the protocol is read first, the rest skipped), caps and the agent id and skin character rule are enforced where a record is built, telemetry values lose Unicode separators and format controls; #4 the harness lock takeover, a save before a Windows server stops (RCON on loopback), cleanup that no longer depends on the launch environment, a log cursor that sees a replaced log, a signal only for a process that still carries the run's marker; #8 the server refuses to start on a plot registry it cannot trust (corrupt file, any rejected row, a stride that no longer matches the stored plots), overlapping stored plots, `hq` builds only the default studio in multiplayer; #18 protection covers the whole column above a plot and the non-player entities in it; #11 the rate bucket is consulted first and refusal logs are throttled; #10 a refused state is sent again; #16 in multiplayer a station click outside the own studio opens nothing of the viewer's; #9 an emptied layout is withdrawn; #14 remote displays follow panel and look changes and show a remote wall offline; #7 two tests that could not fail; #1 the game tests run once in CI; #19 the player guide and a warning in the runbook that the first player on plot 0 rebuilds the HQ at the origin.

Not changed, with the reason: the typed-value policy for telemetry (an open owner decision); one automated comment on #15 that does not hold in 26.3 (a block-entity render state is created fresh every frame); the reviewer's note that game tests cannot reach multiplayer server code (Fabric's game-test API makes the game-test server report itself as dedicated, and the multiplayer server tests rely on it). Seen in a game window after the fixes: the QA compare, the overlay checks again, a non-path skin refused, a missing skin drawn with the default, a changed skin applied to the same entity, and the visitor task-wall panel cut to the screen with "+ N more". Still not run by anyone: the harness on Windows or against a real server, and every two-client check.

**The review record.** Findings are counted per pass: the first review, then each re-review after a fix round. "Coordinator" is the coordinating session, which read every hunk of every packet; the security reviewer and the second model are different models from the writer and from each other.

| Packet | Written by | Reviewed by | Findings per pass |
|---|---|---|---|
| MP-01 | deepseek-v4.1-flash | coordinator | reviewed before this wave's main run (two corrections) |
| MP-02 | Cursor Composer | coordinator | reviewed before this wave's main run (one fix round) |
| MP-03 **(sec)** | Cursor Grok; fix rounds deepseek-v4.1-flash | security reviewer (Codex) four times, coordinator | 11, 8, 2, 0 (one declined: the frozen catalog) |
| MP-04 | Cursor Composer | coordinator | reviewed before this wave's main run (one fix round) |
| MP-05 **(sec)** | Cursor Grok; fix round gpt-6-luna | security reviewer (Codex) three times, coordinator | 6, 2, 0 |
| MP-06 **(sec)** | deepseek-v4-pro; fix rounds deepseek-v4.1-flash | security reviewer (Codex) four times, coordinator | 13, 5, 2, 0 |
| MP-07 **(sec)** | deepseek-v4-pro; fix rounds deepseek-v4.1-flash | security reviewer (Codex) four times, coordinator | 13, 10, 2 and one by the coordinator, 0 |
| MP-08 | gpt-6-luna; fix rounds deepseek-v4.1-flash | coordinator, then a second model (Claude) | 4, 5 |
| MP-09 | deepseek-v4.1-flash | coordinator, then a second model (Claude) | 4, 5 |
| MP-10 | deepseek-v4.1-flash | coordinator, then a second model (Claude) | 2, 2 |
| MP-11 | deepseek-v4.1-flash | coordinator, then a second model (Claude) | 3, 3 |
| MP-12 | deepseek-v4.1-flash, finished by mimo-v2.6-flash | a second model (Claude) twice, coordinator | 7, 6 |
| MP-13 **(sec)** | deepseek-v4-pro; fix rounds deepseek-v4.1-flash | security reviewer (Codex) four times, a second model's progress check, coordinator | 12, 5, 2 and one by the coordinator, 3, 0 |
| MP-14 | deepseek-v4.1-flash | coordinator, then a second model (Claude) | 3, 5 |

Two things about that record that a reader should know. Every fix round ran on one inexpensive model, because the worker pool's usage limit is counted in money and the dearer models used it up within minutes; so the plan to spread the writing over several models held only for the first versions. And the reviews of the packets that are not marked **(sec)** were done by the coordinator and a second Claude model instead of by a worker model in the sandbox, for the same reason.

**What has been seen in a game window, and what has not.** Seen, in a singleplayer dev client on `mp/integration`:

- the singleplayer QA compare (`tools/qa.mjs`, ten shots) against a reference shot on the same display at the Wave 1 base: no shot moved, at six points: five while the packets were merged and one after the pull-request review fixes; the singleplayer client logged no `agentcraft.mp` line;
- the remote looks through the `dev.mp.fake` overlay: the monitors and the task wall (MP-09), the podium, merge station, archive, console and hologram (MP-10), the visitor panel and the read-only agent card (MP-11), and the remote agents with their plates, their waiting spot and the dimmed "Foreman offline" state (MP-08);
- `dev.mp.send` refusing in singleplayer, and `dev.state` reporting the mode, the studios and no plot (MP-12).

Not seen by anyone, and still owed before any of this is called done: **every check that needs a dedicated server with a real player, and every two-client harness check**, for every packet (the last column of the table). They belong to MP-I unless a packet's pull request needs them earlier. Nothing in this campaign has run against the hosted server.

**Contract notes for the coordinator's next edit of work-packets.md** (packets may not edit it):

- `studio_event_rejected` needs the reason `bad_version` in the telemetry catalog (MP-06 refuses an event from a sender without an accepted hello with it).
- `plot_command` keeps its catalog fields: a refused command carries no `reason` (decided during MP-03's review).
- The pull-request review changed the contract text in three places, already edited in work-packets.md on the foundation branch: the hello's protocol-first rule, caps refused where a record is built, and the identifier-path rule for an agent's id and skin.
- Decisions taken in the pull-request review that the owner should confirm: a server refuses to start when `plotStride` no longer matches the stored plots (instead of sending each plot's origin on the wire); the remote DevBridge commands need `AGENTCRAFT_DEV_REMOTE=1`; plot protection covers the column above a plot and non-player entities.
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

- **Pull requests.** #6 to #19 are open, one per Wave 1 branch, with the owner requested as reviewer. Each branch started from the Wave 0 integration base: once the four Wave 0 pull requests are on `main`, merge `main` into each Wave 1 branch so that its diff shows only the packet and its CI run is current.
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
