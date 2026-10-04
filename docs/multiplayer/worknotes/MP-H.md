# MP-H Worknote: two-client harness

> Type: implementation and verification. Audience: the multiplayer coordinator and packet workers.
> Companions: [work packets](../work-packets.md), [audit](../audit.md), [tools](../../../tools/README.md#multiplayer-harness).

## Contract

- **Packet:** MP-H, n = 92; branch `mp/MP-H-harness`.
- **Base:** `main` at `b40768d1f7c77a7fdeaeab7e5bb5a3cea74cbab1`.
- **Machine:** macOS Apple Silicon, Node v24.18.0, Java 25 through swarm wrappers; shared 24 GB RAM.
- Only MP-H files and this worknote were edited. Existing launchers/helpers, mod and Foreman source are unchanged. Coordinator review replaces packet-local `/code-review` and `/security-review` per SWARM.md.

## What was built

| File | Behavior |
|---|---|
| `tools/mp.mjs` | `up`, `down`, `status`, `--json`; required campaign slot, one or two clients, sim default, project home/profile, configurable modest heap caps |
| `tools/lib/mp/config.mjs` | Frozen slot math, validated options, independent client profiles/game directories/usernames |
| `tools/lib/mp/export-launch.gradle` | Init script exports the actual Loom run task classpaths, JVM/program arguments and DLI configuration without executing either run task or editing build.gradle |
| `tools/lib/mp/launch.mjs` | Portable wrapper invocation, Java argfiles, inherited Java/Gradle homes, capped direct JVM launches, y64 superflat server seed and offline ops |
| `tools/lib/mp/processes.mjs` | Atomic PID/start-time records, unique launch-marker recovery, existing Windows bgrun/procs helpers, verified port ownership before dev.quit, bounded graceful/forced cleanup and persistent POSIX group-member identities |
| `tools/lib/mp/harness.mjs` | Startup serialization, durable launch intents, readiness, rollback, idempotent down and JSON summaries with dated startup snapshots |
| `tools/lib/mp/index.mjs` | Wait for every configured client, DevBridge commands on A/B via existing DevClient, bounded incremental server telemetry reads |
| `tools/test/mp-*.test.mjs` | CLI/port/heap/launch, PID reuse/crash recovery, process-group identity, summary, remote readiness, telemetry and shutdown coverage |
| `tools/README.md` | Multiplayer harness usage, resource budget, recovery, library API and swarm-wrapper instructions |

Default heaps are server **2G** and **2G per client**, with `-Xms256M`; clients use 960×540 and 30 FPS, preserving the template graphics preset (see F14/F17 below). Flags or `AGENTCRAFT_MP_SERVER_HEAP` / `AGENTCRAFT_MP_CLIENT_HEAP` override each cap (256M..8G). The explicit user request for launch caps takes precedence over SWARM's prohibition on changing heap settings; no Gradle or existing launcher heap settings were changed. Injected JVM-option environment variables are rejected so they cannot bypass the launch caps. Native/graphics memory remains additional to Java heaps.

The local server is loopback-only and offline, with both distinct offline player UUIDs opped. Its isolated directory has `eula=true`, `level-name=mp-world`, superflat layers 1 bedrock + 124 stone + 3 dirt + 1 grass (top y=64), and enabled AgentCraft multiplayer config. Worlds/options persist across runs. The pre-MP-F base does not implement plots/hello yet; readiness verifies a remote world, not completion of later packets.

## Separate game directories: resolved without a contract change

`gw build configureLaunch` generated `mod/.gradle/loom-cache/launch.cfg`. It supplies absolute asset, mod/classpath-group and game-jar paths, and no game directory. Exporting the real `runClient` / `runServer` task providers supplies the additional platform flags (including macOS `-XstartOnFirstThread`), DLI main classes and classpaths (146 client entries / 123 server entries in this checkout).

The harness runs these launch descriptions directly with different working directories and explicit client `--gameDir`, username and `--quickPlayMultiplayer localhost:25692` arguments. Client env disables AutoWorld and selects the corresponding Foreman/DevBridge. Loom's client `-Xmx4G` is removed before adding the harness cap. Shared compiled classes/assets stay read-only during play. Java argfiles avoid Windows command-length limits. This resolves the configuration question; the coordinator subsequently verified both client counts below. The review fixes still require new live verification.

## What was run

Commands below ran from this worktree unless otherwise noted. Gradle ran only through swarm wrappers: `gw` and `game` (one build and one game at a time on the swarm machine). `<worktree>` below is this packet's checkout. Repeated tools test runs during edits were green; the final count below is authoritative.

| Command | Observed result |
|---|---|
| `node --version` | `v24.18.0` |
| `npm ci --prefix tools --no-audit --no-fund` | exit 0, two dependencies installed |
| `npm ci --prefix foreman --no-audit --no-fund` | exit 0, 147 dependencies installed; npm reported blocked esbuild/fsevents install scripts; no approval or package changes made |
| From `mod/`: `gw build configureLaunch --console=plain` | **BUILD SUCCESSFUL**, 2 seconds; `test NO-SOURCE` on this pre-MP-F/MP-T base; nonfatal `nice: setpriority: Operation not permitted` |
| From `mod/`: `gw -I ../tools/lib/mp/export-launch.gradle mpExportLaunch -PmpLaunchFile=../artifacts/run/mp-launch.json --no-configuration-cache --console=plain` | **BUILD SUCCESSFUL**; metadata inspected: DLI, correct environments, native flags, username/window args, client/server classpaths |
| From `mod/`: same export with `-PmpLaunchFile=../artifacts/run/mp-92/launch.json` | **BUILD SUCCESSFUL**; slot metadata generated for the coordinator |
| `npm test --prefix tools` | **33/33 pass**, zero failures/skips; 23 new harness tests plus 10 existing tests |
| `node --test tools/test/mp-shutdown.test.mjs tools/test/mp-processes.test.mjs` | **9/9 pass** after the final persisted-primary-identity adjustment |
| `node --check tools/lib/mp/harness.mjs` | exit 0 |
| `node tools/mp.mjs --help` | exit 0, documented CLI |
| `node tools/mp.mjs up --slot 92 --clients 3 --json` | expected exit 1 with `{"ok":false,"error":"--clients must be 1 or 2"}` |
| `git diff --check` | exit 0 |
| `npm run check --prefix foreman` | typecheck passed; **479/482 tests passed**, three failures below; protocol freshness command was skipped by the `&&` chain after test failures |
| `npm run check:protocol-doc --prefix foreman` | exit 0 separately; `docs/protocol.md is up to date` |
| `node tools/mp.mjs status --slot 92 --json` | exit 1: `{"ok":false,"error":"ps failed: spawnSync ps EPERM"}` |
| `game node tools/mp.mjs up --slot 92 --clients 1 --backend sim --home <worktree>/.agentcraft-home --profile mp-92 --gradle-command gw-raw --timeout 120 --json` | exit 1: `{"ok":false,"error":"spawnSync ps EPERM"}` at launcher identity preflight, **before any server, Foreman or client launch** |
| `node tools/mp.mjs down --slot 92 --json` | attempted as required; exit 1 with the same `spawnSync ps EPERM`; no game process was started by the failed up |
| `ps -axww -o pid= -o command=` | exit 127: `zsh:1: operation not permitted: ps`; an independent no-processes check is blocked |

Read-only library inspection also used the cached Loom/DLI jars with `javap`, and the existing launcher/mod code. Minecraft source generation was never run. No screenshot/singleplayer QA is applicable: no HQ, renderer, agent or mod behavior was edited. No new mod telemetry events were introduced.

### Foreman failures, outside MP-H ownership

- `test/proc.test.ts:29`: process-table access is denied; expected the actual process table but got undefined.
- `test/claude-handoff.test.ts:190`: warning `could not read the process table to check for processes left by kit's stopped turn`; an orphan-cleanup assertion fails. Its existing `afterAll` explicitly kills the captured CLI and grandchild PIDs even on assertion failure. Their independent `ps` verification is unavailable here.
- `test/repos.test.ts:257`: the successful repo test result has no `summary`. A read-only reproduction created a temporary demo with `createDemo`, ran `npm test --silent` under `CI=1 FORCE_COLOR=0 NO_COLOR=1`, and fed its captured output to `parseTestOutput`. Exit 0, ten tests passed; Node 24 emitted `ℹ tests 10`, `ℹ pass 10`, `ℹ fail 0`, while the parser recognizes only TAP `# tests/pass/fail` lines. Parsed result was `{"failures":[]}` with no summary. Temporary files were removed in `finally`. This explains the third failure independently of the process-table restriction.

## Coordinator live verification commands

Run **outside the restricted sandbox**, from this worktree, with process-table access. The command below holds `game`'s game/Gradle locks through startup, fresh DevBridge state reads, down, status and process inspection. It runs one client first, then two only after a successful one-client cycle. No escalation or launch workaround was attempted by this worker.

```sh
cd <worktree>
game sh <<'SH'
set -eu
cleanup() { node tools/mp.mjs down --slot 92 --json; }
trap cleanup EXIT
trap 'exit 130' INT TERM
for clients in 1 2; do
  node tools/mp.mjs up --slot 92 --clients "$clients" --backend sim \
    --home "$PWD/.agentcraft-home" --profile mp-92 \
    --gradle-command gw-raw --server-heap 2G --client-heap 2G --json
  node tools/devcli.mjs state --port 8084 --timeout 20
  if [ "$clients" -eq 2 ]; then
    node tools/devcli.mjs state --port 8085 --timeout 20
  fi
  node tools/mp.mjs status --slot 92 --json
  node tools/mp.mjs down --slot 92 --json
  node tools/mp.mjs status --slot 92 --json
  # Inspect only this slot's unique markers, leaving other workers' harnesses alone.
  ps -axww -o pid= -o command= > "artifacts/run/mp-92/after-down-$clients.ps"
  node --input-type=module - "$clients" <<'JS'
import fs from 'node:fs';
const state = JSON.parse(fs.readFileSync('artifacts/run/mp-92/state.json', 'utf8'));
const markers = state.processes.map(p => p.marker);
const lines = fs.readFileSync(`artifacts/run/mp-92/after-down-${process.argv[2]}.ps`, 'utf8').split('\n');
const left = lines.filter(line => markers.some(marker => line.includes(marker)));
if (left.length) { console.error(left.join('\n')); process.exit(1); }
console.log('ps: no processes carrying this slot\'s launch markers');
JS
done
trap - EXIT INT TERM
SH
```

For each fresh state, confirm `ok/ready=true`, player `MP92_A` / `MP92_B`, a loaded dimension, `world.name=null` (remote on this base), and `foreman.connected=true` with URLs ending in 27984 / 27985. After MP-12, inspect its explicit mode/server fields too. Each post-down summary must be `stopped`, with zero live recorded process identities. Keep the raw state JSON and process inspection output as live evidence. If `game` refuses for low memory, stop and report it.

On Windows, also verify a two-client cycle with inherited `JAVA_HOME` / `GRADLE_USER_HOME`, paths containing spaces, Ctrl+Break/forced cleanup via the existing helpers, and server world persistence after stopping. Windows dedicated-server cleanup can require the force fallback because the existing background helper has no server stdin; clean-save behavior is not live-verified.

## Coordinator live verification: results

Run by the coordinator on 2026-10-03, outside the worker's sandbox, with the commands of the section above.

- One-client cycle: `up --slot 92 --clients 1` is ready in about 10 s; DevBridge `state` answers; `down`, a second `down` and `status` succeed; no process is left.
- Two-client cycle, run on its own: `up --clients 2` is ready in about 15 s; `state` answers on both clients; `down`, a second `down` and `status` succeed; no process is left.
- Memory with two clients up: the server about 0.7 GB resident, each client about 1.1 GB, each sim Foreman about 0.1 GB, about 3.2 GB in total.
- Failed: a two-client `up` started in the same second as the previous `down`. Client A logged `DevBridge server error (port 8084): java.net.BindException: Address already in use`, both clients still joined, and `up` waited out its 240 s timeout and exited 1. `down` then cleaned up. This is a review finding; the fix is pending.
- Seen: the client rejects the seeded `simulationDistance:4` (`Value 4 outside of range [5:33]`).

## Deviations and open risks

- **Live blocked:** macOS sandbox denies process inspection. One-client up was attempted and failed before launch; two-client up was intentionally not attempted after that blocker. No observed claim of either remote connection or a clean live shutdown is made.
- Windows and Linux launch/stop behavior are unit-covered at argument/identity boundaries, not live-tested.
- Startup metadata is machine/worktree specific and gitignored; re-export after changes. Do not copy it from another checkout or OS.
- Resource caps cover JVM heaps, not native/graphics allocations or the shared Gradle daemon; the game wrapper's low-memory gate still applies.
- The full Foreman check is not green on this machine; failures are listed above. No real Claude API was called.
- Ordinary `git add` was denied when creating this worktree's shared Git `index.lock`; the authorized Git-metadata escalation succeeded. No Minecraft/process-inspection escalation was attempted.

## Contract change requests

- **No `mod/build.gradle` change is needed** for client directory isolation; no MP-T request.
- The coordinator/MP-B should address Node 24 spec-reporter summaries in `foreman/src/repos.ts` (or pin the repo-test reporter to TAP at its owned boundary), then rerun `npm run check` with process-table access. This is outside MP-H's file matrix and was not changed.
- Coordinator must complete the unsandboxed one-/two-client live check and Windows UAT before calling the packet fully verified.

## Review fixes

This round starts at `07b85ec` after the amended implementation `d420e0b`; both commits
were read before editing. Changes are left uncommitted under the updated SWARM rules.
The coordinator's results above describe the previous code. No harness live cycle was
attempted in this review round, and **none of the Windows changes were run on Windows**.
`gw` / `gw-raw` are the swarm wrappers around `./gradlew`; `game` reserves the game slot.
`<worktree>` denotes this checkout, and `<repo>` the main checkout.

Unless noted, named regression tests below are in `tools/test/mp-review.test.mjs` and
are prefixed with the finding IDs. Existing behavioral assertions were retained; log
fixtures now name the actual debug source, and the seed assertion uses the real template.

| Finding | Disposition and regression evidence |
|---|---|
| F1 / L1 | **Fixed.** `waitForClients` incrementally scans stdout from byte zero and fails on any matching-port DevBridge error, naming client/port/cause/log. `up` stops only the failed client, preserves its recovery records, waits 35 s on macOS / 65 s on Linux / 0 on Windows, then relaunches once per client. Stop, delay, relaunch and readiness share the readiness deadline. Second failure and insufficient budget fail fast. Tests cover classifier match/no match/other port, multi-chunk scan, fail-fast even with a ready bridge, platform delays, retry exhaustion, unrelated errors and deadline consumption. The TCP-connect preflight is unchanged. README quick start and restart behavior corrected. |
| F2 | **Fixed.** Plans carry `debugLog` for server and clients; events read `gameDir/logs/debug.log`, with a derived path for old plans. Added `readClientEvents`. DEBUG fixtures prove stdout-only INFO logs are insufficient and that client cursors are independent; existing bounded/incomplete-line tests now use the debug source. |
| F3 | **Fixed.** Preparation has a deadline and cancellation rejection, followed by the existing recorded-identity rollback. POSIX build groups are recorded for cleanup. Fake clock/child tests cover timeout, cancellation without raw `child.kill()`, recorded build identity and environment. |
| F4 | **Fixed.** Export depends on `configureClientLaunch`. Inspected cached Loom 1.18.2 `LoomTasks` with `javap -c -p`: client setup depends on `configureLaunch`, `downloadAssets`, and conditional `extractNatives`. Regression pins the dependency; the actual Gradle dry-run graph contains `downloadAssets` before export. No fresh-machine asset download was attempted. |
| F5 | **Fixed.** POSIX `ps` start-time reads force `LC_ALL=C` and `TZ=UTC`. Test inspects the invocation and checks normal/zombie/missing process responses. |
| F6 | **Not changed**, per coordinator instruction: game-wrapper detection belongs to the coordinator. Foreman launch marker remains intact. |
| F7 | **Not fixed.** The stale-directory takeover race is real. Correct reclamation needs portable fencing or an OS-held lock, including recovery if a reclaimer crashes; another unchecked mkdir/delete sequence is not a safe targeted fix. README now explicitly requires one controller per slot. Concurrent stale-lock takeover remains an open risk. |
| F8 | **Fixed.** Build environment preserves an explicit `GRADLE_USER_HOME`, otherwise uses this checkout's `.gradle-home`; `JAVA_HOME` is inherited. Pure environment and preparation-wiring tests cover both cases. |
| F9 | **Fixed.** The existing crash-recovery fixture builds the bgrun/spec paths with `path.join`, matching the production platform path handling (`mp-processes.test.mjs`). |
| F10 | **Fixed in code, Windows execution unverified.** Windows launch persists only environment overrides and calls existing `Start-Bg` with a unique marker through PowerShell, recording both returned identities. Only Foremen receive Ctrl+Break; failed delivery skips the wait. Clients retain the post-quit wait; server force termination is immediate and may lose changes since autosave. Tests exercise the Windows branch with injected helper/stamps, returned identities, JSON spec, and each stop path including successful graceful Foreman exit. |
| F11 | **Fixed.** PowerShell calls include `-ExecutionPolicy Bypass`, including wrapper invocation. Helper argv test pins the flag and dot-sourced helper. Windows execution unverified. |
| F12 | **Partially addressed.** Added process-stamp, start/stop branch, reused-PID refusal and invalid-state tests. Added a real harmless-child lifecycle test, which skips before spawning if inspection is denied. Here it skipped with `spawnSync ps EPERM`. Real process/lock behavior is not verified in this sandbox; F7 remains deferred. |
| F13 | **Fixed tests.** Literal port cases include slots 0, 90, 91, 92, 98, 99. Mixed readiness keeps waiting when only B is ready and times out when A never connects. Existing config tests now assert custom home/profile and absence of `-XX:MaxHeapSize` overrides. |
| F14 | **Fixed.** Seed preserves real template graphics settings, retaining only the FPS adjustment. Read Minecraft 26.3 sources: `Minecraft` applies the preset after load; Fancy sets render 16 / simulation 12. Dedicated server settings independently remain view 6 / simulation 4. README claim corrected; test compares against the real options template. |
| F15 | **Fixed.** Java 25 is checked through the configured `JAVA_HOME` before preparation, including `--no-build`; old/missing/failed Java rejects clearly. Environment fallback covered with F8. Tests verify Java command/env and rejection cases. |
| F16 | **Fixed.** README documents version-1 success/error JSON shapes and historical snapshot semantics. Nested process identities normalize to `pid`, `startTime`, `role`; regression pins summary/server/client/process keys and excludes raw internal fields. Existing tests cover stopped/ready/partial phases. |
| F17 / L2 | **Fixed for newly seeded options.** No longer writes client `simulationDistance:4`; client minimum is 5 (`Options`), while the server's separate property accepts 4. Real-template test asserts valid seed. Existing client options remain persistent; remove a generated client `options.txt` if reseeding is wanted. |

### Review verification

- Required baseline: `npm test --prefix tools` — **33 passed**, zero failures/skips, before edits.
- Initial `node --test tools/test/mp-review.test.mjs` — **3 expected failures** before implementation (F1, F2, F14/F17); subsequent targeted runs passed those regressions.
- From `mod/`: `gw -I ../tools/lib/mp/export-launch.gradle mpExportLaunch -PmpLaunchFile=../artifacts/run/mp-92/launch.json --no-configuration-cache --console=plain --dry-run` — **BUILD SUCCESSFUL**, exit 0, Loom 1.18.2; graph includes `downloadAssets` and `configureClientLaunch`. Nonfatal `nice: setpriority: Operation not permitted`.
- Final `npm test --prefix tools` — exit 0: **60 passed, 0 failed, 1 skipped (61 total)**.
- `git diff --check` — exit 0, no whitespace errors. `git status --short` — only the 12 intended owned files (including this worknote and the new regression test). No staging or commit attempted.
- Harmless process lifecycle test — **skipped**, exact preflight error `spawnSync ps EPERM`; no child spawned by that test. No Minecraft launch or process-table workaround attempted.

### Coordinator live verification of the review fixes

Run by the coordinator on 2026-10-04, outside the worker's sandbox, with the commands under
"Coordinator live regression commands" below.

- `npm test --prefix tools`: 61 pass, 0 fail, 0 skipped (the test that skips without a process table ran).
- Live cycles of one, two and two clients, each `up` started immediately after the previous `down` on the
  same slot: every `up` succeeded and every client reported ready.
- On both restarts the harness printed `DevBridge port 8084: java.net.BindException: Address already in use`,
  waited 35 s and relaunched that client once (the second time for both clients). `up` returned after about
  58 s and 94 s, instead of waiting out the timeout as before.
- After the last `down` no harness process was left.
- Resident memory of the harness JVMs: about 2.0 GB with one client, about 2.6 GB with two.
- Still not run: anything on Windows.

### Contract change requests (review round)

- Mod owner/coordinator: consider `DevBridge.setReuseAddr(!isWindows)` with the documented Windows exclusive-bind constraint and platform verification. `DevBridge.java` and `mod/DEV.md` are outside MP-H ownership and were not edited. Harness recovery does not require this change.
- Coordinator retains F6 (swarm wrapper detection). F7 needs a follow-up portable lock/fencing design before concurrent controllers can be supported safely.
- Windows graceful dedicated-server stop remains a future contract: existing `Start-Bg` provides no stdin, and HotSpot Ctrl+Break produces a thread dump. A remote command bridge or stdin control would be needed for a final save; no mod change made.

### Coordinator live regression commands

Run outside the sandbox from `<worktree>`. First export with `gw`, then hold `game`
through all cycles. The sequence deliberately performs immediate `down` / `up` on slot 92,
first one client, then two, then a second two-client launch. Each fresh state must pass
`isRemoteReady`; final inspection checks identities and markers from every cycle against `ps`.
The exit-trap cleanup runs on failures too. Preserve stderr to see the single TIME_WAIT
recovery notice if a bind failure occurs; a clean rapid restart may not reproduce it.

```sh
cd <worktree>
(cd mod && gw -I ../tools/lib/mp/export-launch.gradle mpExportLaunch \
  -PmpLaunchFile=../artifacts/run/mp-92/launch.json --no-configuration-cache --console=plain)
game sh <<'SH'
set -eu
cleanup() { node tools/mp.mjs down --slot 92 --json; }
trap cleanup EXIT
trap 'exit 130' INT TERM
: > artifacts/run/mp-92/review-runs.jsonl
for clients in 1 2 2; do
  node tools/mp.mjs up --slot 92 --clients "$clients" --backend sim --no-build \
    --home "$PWD/.agentcraft-home" --profile mp-92 --timeout 240 \
    --server-heap 2G --client-heap 2G --json
  node --input-type=module <<'JS'
import fs from 'node:fs';
import assert from 'node:assert/strict';
import { clientCommand, isRemoteReady } from './tools/lib/mp/index.mjs';
const plan = JSON.parse(fs.readFileSync('artifacts/run/mp-92/state.json'));
fs.appendFileSync('artifacts/run/mp-92/review-runs.jsonl', JSON.stringify(plan) + '\n');
for (const client of plan.clients) {
  const state = await clientCommand(plan, client.id, 'dev.state');
  console.log(JSON.stringify({client: client.id, state}));
  assert.ok(isRemoteReady(state, client));
}
JS
  node tools/mp.mjs down --slot 92 --json
  # No cooldown here: the next loop starts up immediately on the same slot.
done
node tools/mp.mjs down --slot 92 --json
node tools/mp.mjs status --slot 92 --json
ps -axww -o pid= -o command= > artifacts/run/mp-92/review-after-down.ps
node --input-type=module <<'JS'
import fs from 'node:fs';
import assert from 'node:assert/strict';
import { summarize } from './tools/lib/mp/harness.mjs';
import { processInventory } from './tools/lib/mp/processes.mjs';
const runs = fs.readFileSync('artifacts/run/mp-92/review-runs.jsonl', 'utf8').trim().split('\n').map(JSON.parse);
const ps = fs.readFileSync('artifacts/run/mp-92/review-after-down.ps', 'utf8');
for (const state of runs) {
  const summary = summarize(state, state, processInventory(state.root));
  assert.equal(summary.phase, 'stopped');
  assert.ok(summary.processes.every(p => p.processes.length === 0));
  assert.ok(state.processes.every(p => !ps.includes(p.marker)));
}
console.log('ps and recorded identities: no remaining harness processes');
JS
trap - EXIT INT TERM
SH
```

For one-client-only reuse from another launcher, additionally exercise `devcli quit`
then an immediate `down` / `up` under the same game reservation. Linux cooldown and all
Windows launch/control/asset-path behavior still need their own platform UAT.

## Pull-request review

Three reviewer comments were checked against the code, reproduced in `tools/test/mp-pr-review.test.mjs` (`PR1`..`PR3`, each failing before its fix) and fixed. `down` and `status` no longer read `AGENTCRAFT_MP_SERVER_HEAP`, `AGENTCRAFT_MP_CLIENT_HEAP` or `AGENTCRAFT_MP_GRADLE`, so a stale launch environment cannot block cleanup; explicit heap flags are still validated for every action. The log readers now return a `generation` (a digest of the log's first complete lines, at most 4 KiB) next to `offset`, and a caller that passes both back is restarted at byte zero when a later `up` has truncated or replaced the log, even after it has regrown past the old offset; an offset alone keeps the previous shorter-file check, and two logs whose heads are identical cannot be told apart. The Gradle preparation now carries its launch marker on the command line as the unused project property `-PmpRun=<marker>`, and `recoverProcesses` accepts a build process that carries both the marker and this checkout's init-script path, so `down` finds a build whose PID was never recorded; this was checked with unit tests and a harmless stand-in child, not with a real Gradle run or on Windows.

A second round of reviewer comments was checked the same way (`PR4`..`PR7` in the same file; each test fails when its fix is reverted). None of it was run on Windows or against a real server, client or Gradle.

- **Stale-lock race (F7 above): valid, fixed.** The old takeover deleted the stale lock and recreated it, so a second controller that had judged the same lock stale deleted the first one's new lock and both held the slot; ten real processes racing for one stale lock gave two or three holders in 9 of 12 rounds. The lock is now `control.lock/<n>/owner.json`: a stale generation is never deleted first, its successor `n+1` is claimed by an exclusive `mkdir`, and the claimant then reads the lock again and stands down unless its own owner record is intact and no other generation has a live owner. The same race gave exactly one holder in 12 of 12 rounds. A lock left in the old layout by a crashed controller is ignored.
- **Windows server killed without a save: valid, fixed in code.** `stopProcess` forced the Windows server's timeout to 0. The background runner gives the server no stdin and a JVM answers Ctrl+Break with a thread dump, so on Windows `up` now enables RCON in `server.properties` (the server binds it to `server-ip`, so loopback; random password; a free port, since the slot's block has none; both kept in `state.json`), and `down` sends `stop` over it once the recorded server owns that port, waits up to 30 s, and only then kills. Without a sent `stop` (RCON not up yet, login refused, an older `state.json`) the kill is immediate as before. Whenever the server is killed, on any platform, `down` now says it was killed without a final save; the world is still reused. POSIX is unchanged (`SIGTERM`, RCON off). The RCON client is tested against a listener that frames packets as Minecraft 26.3's `RconClient` reads them, not against a server.
- **`.bat`/`.cmd` Gradle override: valid, fixed.** Node 24 documents that batch files need a shell and has refused to spawn them without one (`EINVAL`) since the April 2024 security releases. A custom `.bat`/`.cmd` on Windows now goes through the PowerShell invocation the default wrapper already uses; other commands are spawned as before.
- **Ports checked only before Gradle: valid, fixed.** The check is kept before the Gradle step and repeated as the last step of `prepare`, immediately before the processes start.
- **Seeded `simulationDistance:4`: not valid any more, nothing changed.** `9721a86` (F17 above) already stopped rewriting it; the seed keeps the template's `simulationDistance:10`, which `F14/F17` asserts. The `simulation-distance=4` that remains is the server's own property, which has no such minimum.
- **`AGENTCRAFT_DEV_REMOTE=1`:** the game clients' environment now carries it (`gameSpec`); the server's and the Foremen's do not.

A further comment (`PR8`) was valid: POSIX `ps -o lstart=` has whole-second resolution, and both recovery and `stopProcess` trusted the PID plus that stamp alone, so a PID reused within its predecessor's start second could be recovered and signalled, with its process group. On POSIX a recorded process is now recovered and signalled only while its command line, read from the process table immediately before each signal, still carries the entry's launch marker, or, for a recorded child (which carries none), while it is still in the process group of the leader it was recorded under; Windows start times have 100 ns resolution and are checked as before, and the instant between that read and the signal remains.
