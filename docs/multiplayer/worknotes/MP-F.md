# MP-F Worknote: multiplayer foundation

> Type: reference. Audience: the multiplayer coordinator and packet workers.
> Companions: [work packets](../work-packets.md), [audit](../audit.md).

## Contract

- **Packet:** MP-F, Foundation; packet number 0.
- **Base:** `main` @ `b40768d`; branch `mp/MP-F-foundation`.
- **Machine:** macOS Apple Silicon, Java 25, Node 24.18.0.
- **Status:** UATPending: implementation and singleplayer/dev checks pass; the live dedicated hello remains unverified because its client could not initialize SDL.
- No contract fields, defaults, ownership boundaries, or existing caller signatures were changed. No push, PR, colo access, or memory update.

## What shipped

| Files | Behavior |
|---|---|
| `main/mp/{StudioId,Plot,PlotGrid,PlotDirectory,Plots}` | Immutable studio identity and plot origins; square spiral, inverse footprint lookup, default LOCAL plot 0 directory. Site containment includes boundary block coordinates. |
| `main/mp/state/*` | Exact public record allowlist, lower-case validated wire enums, all-false public policy, immutable collections, strict bounded/sanitized `PublicJson` readers for state, events and intents. |
| `main/mp/net/*` | Protocol 1, all ten payload records/codecs and one-time play-phase registration. Dedicated/enabled/can-send-gated hello; connection UUID attribution and compatible-client marker, removed on disconnect/server stop. |
| `main/mp/{MpServerConfig,MpLog,MpEvents,MpReasons}` | Dedicated-only config load, exact default values, WARN/default invalid-value behavior; full telemetry catalog and scoped capture hook. |
| `main/layout/Anchors.java` | Per-studio immutable snapshots and listeners; self-relative legacy API, unchanged LOCAL disk file and saved revision behavior; builder origin translates all anchor paths and bounds. |
| `main/hq/HqBuilder.java` | `Options(force, origin, studio)` and retained `Options(force)` constructor; defaults ZERO/LOCAL. Builders still ignore options until MP-02. |
| `client/mp/{StudioView,Studios,MpMode}` | Own/remote registry, plot-first/layout-fallback lookup, overlay seam, state/event listeners, stable non-reused slots per connection; gated hello, 100-tick no-hello timeout, disconnect reset, address-hash telemetry and REMOTE_VANILLA HUD indicator. |
| `client/mp/dev/MpDevFake.java` | `dev.mp.fake` state/event/clear and `dev.mp.studios`; typed Fields access, shared registry listeners, default overlay, no Foreman or network mutation. |
| Six common and four client F-stubs | Each initially has an empty `init()`; all wired once in `AgentCraft`/`ClientFeatures`. |
| `mod/build.gradle`, `mod/src/test/java/dev/agentcraft/mp/*` | Isolated JUnit 5 setup, including client contract testing. No game-test source-set edits. Frozen public-record shape, identity-free C2S, exact-cap opt-in acceptance, codec, caps, malformed-input, sanitization, plot, config, anchor, registry, hello, telemetry, dev overlay and wiring tests. |

## APIs and implementation details for later packets

- `MpPayloads.register()` uses the **26.3 Fabric names** `PayloadTypeRegistry.serverboundPlay()` / `clientboundPlay()`. The old `playC2S` / `playS2C` spelling does not exist. Actual signatures were checked in the installed Fabric jar; Minecraft signatures were checked in the provided read-only mcsrc. No source generation ran.
- Payload identifiers are snake case, e.g. `agentcraft:hello_s2c`, `agentcraft:public_state_c2s`, `agentcraft:world_intent_c2s`; each record exposes `TYPE` and `CODEC`. `HelloS2C.serverInfo()` contains `ServerInfo`.
- Public state/event/intent codecs use bounded UTF-8 JSON envelopes, then `PublicJson` validates the allowlist, exact primitive types, original string lengths, enum values and collection caps before returning. Layout/hello/presence codecs use explicit binary fields. The JSON implementation is private; use `PublicJson.toJson`, `fromJson`, `eventFromJson`, and `intentFromJson`.
- Extra defensive limits for otherwise unspecified inputs: layout name/binding/task id 48 chars, event and monitor ids 16; lit-monitor set 64; nonnegative revisions/counts/event lengths, CI slot 0..7, progress 0..1, finite world-bounded layout coordinates and finite yaw/pitch. Envelope character budgets are state 30,000, event 2,048, intent 16,384. Caps reject before sanitization, so control/formatting characters cannot disguise overlength input. Unknown public JSON fields, duplicate agent/CI identities, and sanitized anchor/binding collisions are refused.
- Opted-out activity/goal/tasks in a state are refused. Speech event policy belongs to MP-05/MP-06: the event record deliberately has no policy field. Its codec sanitizes/caps text; it cannot independently infer the owner's speech preference.
- `Anchors.publish(StudioId, Layout)` installs the supplied revision without writing disk. MP-03 owns per-plot persistence; MP-04 can preserve server layout revisions. The existing `publish(MinecraftServer, Layout)` increments and persists LOCAL as before; the contract's no-server `publish(Layout)` overload also exists.
- `PlotGrid` spiral starts 0=(0,0), 1=(128,0), 2=(128,128). `indexAt` resolves the inclusive x/z site footprint and returns empty between sites. Strides must be 16-aligned and at least 96.
- Client registry mutation APIs: `setOwn`, `setPlot(id,index,stride)`, `put(StudioView)`, `updateLayout`, `updateState`, `updatePresence`, `remove`, `reset`, `setOverlay`. `put` assigns the actual local slot, ignoring a supplied slot. Slots are not reused until disconnect/reset. `addListener(BiConsumer<StudioId,StudioView>)` receives null on removal; `addEventListener` / `fireEvent` carry `PublicEvent`. These run on the mutation thread; networking packets must mutate on the client thread. Anchor listeners hop to the client thread.
- `Studios.own().layout()` reads `Anchors.forStudio(self)` so legacy LOCAL builds remain immediately visible. Layout synchronization must publish the layout to `Anchors` as well as set the plot; the anchor listener refreshes the registry.
- Server effects and client hello effects explicitly schedule on their respective game threads. Only enabled dedicated servers send hello, only a matching remote hello attributed to the local player's UUID activates multiplayer, and integrated servers never send/reply. Disconnect resets mode, client slots, own identity, overlay, and remote anchors. Server config and mod-equipped markers reset on server stop. Hello is once per join; public publishers/relays/rates remain their owning packets' empty stubs.
- A plot may be absent at hello time (`plotIndex=-1`), including this foundation-only dedicated server. Allocation and layout delivery belong to later packets.
- The wiring test checks that seams remain wired once and expose `init()`, rather than requiring them to remain empty after Wave 1 implements them.

## Verification

All Gradle builds used `gw build` from this worktree's `mod/`. `gw` and `game` are the swarm machine's wrappers around `./gradlew` (one build and one game at a time), and `<worktree>` below is this packet's checkout. The wrapper printed `nice: setpriority: Operation not permitted`; builds still succeeded. Each implementation step was committed after a green build. Final suite: 18 tests, zero failures/errors/skips.

| Command/check | Result | Evidence (gitignored) |
|---|---|---|
| `gw build` after types/config/telemetry | Pass | JUnit `FoundationTest` |
| `gw build` after payloads/codecs | Pass | JUnit `CodecTest` |
| `gw build` after anchors/HQ options | Pass | JUnit `AnchorsTest` |
| `gw build` after registry/mode/hello | Pass | JUnit `StudiosTest` |
| `gw build` after DevBridge overlay | Pass | JUnit `DevFakeTest` |
| `gw build` after F-stubs/wiring | Pass | JUnit `WiringTest` |
| `npm ci --prefix tools --no-audit --no-fund` | Pass, 2 packages | Launcher also installed Foreman dependencies, 147 packages |
| `game node tools/qa.mjs --home "$PWD/.agentcraft-home" --profile mp-0 --port 27800 --dev-port 7900 --run-id MP-F-baseline-20261003` | Initial launcher failed before game launch: process stamps could not be read in sandbox | `artifacts/shots/qa/MP-F-baseline-20261003/{launch.log,manifest.json}` |
| `game node tools/mac.mjs launch --backend sim --no-foreman --home "$PWD/.agentcraft-home" --profile mp-0 --port 27800 --dev-port 7900 --dev --no-wait --summary-json artifacts/run/mp-f-client-attempt.json` | Client booted; fresh HQ built, 69 anchors | `artifacts/logs/mac-game.log` |
| `game node tools/qa.mjs --no-launch --home "$PWD/.agentcraft-home" --profile mp-0 --port 27800 --dev-port 7900 --run-id MP-F-baseline-20261003-direct` | **Pass: 10 ok, 0 skipped, 0 failed** | Contact sheet and manifest in `artifacts/shots/qa/MP-F-baseline-20261003-direct/` |
| Contact sheet image read | Inspected all ten frames: exterior/day/night, atrium, desks, task wall, podium, console, diff and library populated | `contact_sheet.png`; no earlier visual baseline was available for a before/after comparison |
| `game node tools/devcli.mjs --port 7900 --timeout 5 raw '<two-agent dev.mp.fake request>'` | Pass; fake owner Bob, slot 1, two public agents, 69 anchors, SINGLEPLAYER | Full request is in the temporary `artifacts/mp-f/check-fake.mjs` |
| `game node artifacts/mp-f/check-fake.mjs` | Pass: repeated set, `dev.mp.studios`, say event injection, clear; own studio remains | `artifacts/mp-f/fake-result.json`; `fake_studio` set/clear telemetry in game log |
| `game node tools/devcli.mjs --port 7900 --timeout 5 quit` | Pass, save/quit and bridge closed; Gradle client task exited successfully | Game log |
| `game node artifacts/mp-f/check-hello.mjs` | Dedicated server boot pass on 25600; **client failure**, hello unverified | `artifacts/mp-f/{hello-server,hello-client}.log` |
| `git diff b40768d --check` | Pass | No whitespace errors |
| Process cleanup | All exact owned client/Foreman/server JVMs exited. Server accepted `stop` on stdin; no game/server remains. | Targeted `ps` checks; shutdown log; the packet did not stop shared Gradle daemons |

**Usable singleplayer QA baseline run id: `MP-F-baseline-20261003-direct`.** The initial run has no screenshots and must not be used as the baseline. No `hello_sent`, `hello_received` or `mode_changed` was observed in the singleplayer log.

## Deviations and remaining verification

- Sandbox `ps` fails with `zsh:1: operation not permitted: ps`. `mac.mjs` therefore recorded `stamp: null`, reported `Foreman exited` even though the showcase Foreman was listening on 27800, and its normal stop command reported no launcher-owned process. Exact PID/start-time/command checks were escalated read-only for cleanup; the verified showcase Foreman was terminated after QA. No launcher source was modified.
- One initial DevBridge probe ran before the client's startup had completed and returned `connect ECONNREFUSED 127.0.0.1:7900`; all subsequent singleplayer checks succeeded.
- The dedicated check ran server and client sequentially inside **one game-wrapper job**. The server logged `event=config_loaded enabled=true` and `Done (0.218s)`. The concurrent client had to start another Gradle daemon and failed with `java.lang.IllegalStateException: Unable to initialize SDL: The video driver did not add any displays` (runClient JVM exit 255). It also logged `Connection Invalid error for service com.apple.hiservices-xpcservice` and `Could not start the FSEvents stream`. No display workaround/retry was attempted. Its DevBridge connection timed out after 90 seconds. The script then sent `stop`, and both Gradle tasks exited. The already-exited server made a later cleanup TERM report `no such process`.
- The local dedicated harness used offline mode only in the ignored run directory; no shipped server config or resource was changed. Server flat-layer warning `No key layers in MapLike[{}]` did not prevent startup and was unrelated to the client SDL failure.
- `/code-review` and `/security-review` were intentionally skipped per SWARM.md; the coordinator runs independent reviewers.

## Coordinator commands for the remaining live hello

Run outside the restricted display sandbox. Use this worktree, one game slot, and n=0 ports. The temporary check script and ignored server config already exist on this machine:

```sh
cd <worktree>
game node artifacts/mp-f/check-hello.mjs
```

The script launches these exact children from `mod/` under the enclosing `game` job (do not add nested game locks):

```sh
gw-raw runServer --args='nogui' --console=plain
AGENTCRAFT_AUTOWORLD=0 AGENTCRAFT_FOREMAN=0 AGENTCRAFT_PORT=27800 AGENTCRAFT_DEV_PORT=7900 \
AGENTCRAFT_HOME=<worktree>/.agentcraft-home AGENTCRAFT_PROFILE=mp-0 \
AGENTCRAFT_FOCUS=0 gw-raw runClient \
--args='--username MPFoundation --width 1920 --height 1080 --quickPlayMultiplayer 127.0.0.1:25600' --console=plain
```

Success requires live `hello_sent` and `hello_received` on the server/client logs, client `mode_changed ... to=MULTIPLAYER server=<hash>`, and `dev.mp.studios` mode MULTIPLAYER with the connection player's studio in slot 0. The script writes `hello-result.json`, quits the client and stops the server. Verify both processes exited; stop the exact recorded JVM if the launcher fails to forward stdin. If the memory guard refuses, stop and report without retrying.

For a repeat baseline using the ordinary launcher outside the process-inspection sandbox:

```sh
game node tools/qa.mjs \
--home "$PWD/.agentcraft-home" --profile mp-0 --port 27800 --dev-port 7900 \
--run-id MP-F-baseline-coordinator
node tools/mac.mjs stop --profile mp-0
```

## Contract change requests

None. No existing files outside the MP-F matrix row were edited. All artifacts, npm dependencies, run configs and test logs are ignored/local only.

## Commits

1. `2e0d90d` — types, plot grid, config, telemetry, JUnit setup.
2. `ccf0f3b` — payload records/codecs, public JSON and boundary tests.
3. `9ff4300` — per-studio anchors and compatible HQ options.
4. `caace2f` — client registry, mode and hello.
5. `e20b2f2` — dev overlay and inspection commands.
6. `51ca0be` — F-stubs and wiring.
7. Final verification/worknote commit follows these six implementation commits.
