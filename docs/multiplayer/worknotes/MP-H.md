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

Default heaps are server **2G** and **2G per client**, with `-Xms256M`; clients use 960×540, 6-chunk render distance and 30 FPS. Flags or `AGENTCRAFT_MP_SERVER_HEAP` / `AGENTCRAFT_MP_CLIENT_HEAP` override each cap (256M..8G). The explicit user request for launch caps takes precedence over SWARM's prohibition on changing heap settings; no Gradle or existing launcher heap settings were changed. Injected JVM-option environment variables are rejected so they cannot bypass the launch caps. Native/graphics memory remains additional to Java heaps.

The local server is loopback-only and offline, with both distinct offline player UUIDs opped. Its isolated directory has `eula=true`, `level-name=mp-world`, superflat layers 1 bedrock + 124 stone + 3 dirt + 1 grass (top y=64), and enabled AgentCraft multiplayer config. Worlds/options persist across runs. The pre-MP-F base does not implement plots/hello yet; readiness verifies a remote world, not completion of later packets.

## Separate game directories: resolved without a contract change

`gw build configureLaunch` generated `mod/.gradle/loom-cache/launch.cfg`. It supplies absolute asset, mod/classpath-group and game-jar paths, and no game directory. Exporting the real `runClient` / `runServer` task providers supplies the additional platform flags (including macOS `-XstartOnFirstThread`), DLI main classes and classpaths (146 client entries / 123 server entries in this checkout).

The harness runs these launch descriptions directly with different working directories and explicit client `--gameDir`, username and `--quickPlayMultiplayer localhost:25692` arguments. Client env disables AutoWorld and selects the corresponding Foreman/DevBridge. Loom's client `-Xmx4G` is removed before adding the harness cap. Shared compiled classes/assets stay read-only during play. Java argfiles avoid Windows command-length limits. This resolves the configuration question; actual multi-client runtime remains unverified because of the sandbox blocker below.

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
