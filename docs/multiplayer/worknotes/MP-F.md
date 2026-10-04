# MP-F Worknote: multiplayer foundation

> Type: reference. Audience: the multiplayer coordinator and packet workers.
> Companions: [work packets](../work-packets.md), [audit](../audit.md).

## Contract

- **Packet:** MP-F, Foundation; packet number 0.
- **Base:** `main` @ `b40768d`; branch `mp/MP-F-foundation`.
- **Status:** Review. The implementation and the review fixes are verified by the coordinator: 32 JUnit tests, the singleplayer QA compare, the fake-studio check and the live dedicated hello (see "Coordinator verification"). The worker's original live hello attempt below failed in its sandbox.
- Review fixes implement coordinator contract amendments A–E. Defaults and legacy caller signatures remain unchanged. No commit, push, PR, colo access, or memory update in the review round.

## What shipped

| Files | Behavior |
|---|---|
| `main/mp/{StudioId,Plot,PlotGrid,PlotDirectory,Plots}` | Immutable studio identity and plot origins; square spiral, inverse footprint lookup, default LOCAL plot 0 directory. Site containment includes boundary block coordinates. |
| `main/mp/state/*` | Exact public record allowlist, lower-case validated wire enums, all-false public policy, immutable collections, strict bounded/sanitized `PublicJson` readers for state, events and intents; `none` goal status, closed lamp bindings and outbound state validation. |
| `main/mp/net/*` | Protocol 1, all ten payload records/codecs and one-time play-phase registration. Dedicated/enabled/can-send-gated hello; connection UUID attribution and compatible-client marker, removed on disconnect/server stop. |
| `main/mp/{MpServerConfig,MpLog,MpEvents,MpReasons}` | Dedicated-only config load, reversible `install` override, exact default values, WARN/default invalid-value behavior; full telemetry catalog and scoped capture hook; Wave 1 reason constants and `studio_event_rejected` (WARN). |
| `main/layout/Anchors.java` | Per-studio immutable snapshots and listeners; self-relative legacy API, unchanged LOCAL disk file and saved revision behavior; builder origin translates all anchor paths and bounds; public `fromJson` shares the existing persistence format. |
| `main/hq/HqBuilder.java` | `Options(force, origin, studio)` and retained `Options(force)` constructor; defaults ZERO/LOCAL. Builders still ignore options until MP-02. |
| `client/mp/{StudioView,Studios,MpMode}` | Own/remote registry, plot-first/layout-fallback lookup, overlay seam, state/event listeners, stable non-reused slots per connection; gated hello, 100-tick no-hello timeout, client-executor disconnect reset, retained server info and registered plots, process-salted address-hash telemetry and REMOTE_VANILLA HUD indicator. |
| `client/mp/dev/MpDevFake.java` | `dev.mp.fake` state/event/clear and `dev.mp.studios`; typed Fields access, shared registry listeners, default overlay, no Foreman or network mutation. |
| `main/mp/RateBucket.java` | Pure caller-clock bucket: starts full at twice the rate, preserves fractional refill credit, caps long idle intervals and ignores backwards time. |
| `main/mp/server/StudioRange.java` | Shared server visibility for layouts/state: own plot always, remote plots within the overworld chunk radius; ordered leave/enter notifications, periodic and hello-triggered refresh, viewer queries and lifecycle cleanup. |
| `mod/src/test/java/dev/agentcraft/client/foreman/ForemanStates.java` | Fresh test-only Foreman states from the two supplied snapshot resources or a caller's JSON; no running client needed. |
| Six common and four client F-stubs | Each initially has an empty `init()`; all wired once in `AgentCraft`/`ClientFeatures`. |
| `mod/build.gradle`, `mod/src/test/java/dev/agentcraft/mp/*` | Isolated JUnit 5 setup, including client contract testing. No game-test source-set edits. Frozen public-record shape, identity-free C2S, exact-cap opt-in acceptance, codec, caps, malformed-input, sanitization, plot, config, anchor, registry, hello, telemetry, dev overlay and wiring tests. |

## APIs and implementation details for later packets

- `MpPayloads.register()` uses the **26.3 Fabric names** `PayloadTypeRegistry.serverboundPlay()` / `clientboundPlay()`. The old `playC2S` / `playS2C` spelling does not exist. Actual signatures were checked in the installed Fabric jar; Minecraft signatures were checked in the provided read-only mcsrc. No source generation ran.
- Payload identifiers are snake case, e.g. `agentcraft:hello_s2c`, `agentcraft:public_state_c2s`, `agentcraft:world_intent_c2s`; each record exposes `TYPE` and `CODEC`. `ServerInfo(int plotStride, int relayRadiusChunks, int publicStatePerSecond, int intentsPerSecond)` is preserved by `MpMode.serverInfo(): Optional<ServerInfo>` after acceptance and cleared on join/reset/disconnect. `Studios.plot(StudioId): Optional<Plot>` exposes registered plots; it is empty for a hello index of −1. Rates decode in 1..1000. `HelloC2S.forCurrentVersion(String)` bounds/sanitizes the local version before replying.
- Public state/event/intent codecs use bounded UTF-8 JSON envelopes, then `PublicJson` validates the allowlist, exact primitive types, original string lengths, enum values and collection caps before returning. Layout/hello/presence codecs use explicit binary fields. The JSON implementation is private; use `PublicJson.toJson`, `fromJson`, `eventFromJson`, and `intentFromJson`.
- Extra defensive limits for otherwise unspecified inputs: layout name/task id 48 chars, event and monitor ids 16; lit-monitor set 64; nonnegative revisions/counts/event lengths, CI slot 0..7, progress 0..1, finite world-bounded layout coordinates and finite yaw/pitch. Envelope character budgets are state 30,000, event 2,048, intent 16,384. Caps reject before sanitization, so control/formatting characters cannot disguise overlength input. Unknown public JSON fields, duplicate JSON members, duplicate agent/CI identities, malformed JSON syntax, and sanitized anchor collisions are refused. JSON nesting is bounded at 16. Sanitization walks code points, skips the full code point after §, and removes ISO controls, FORMAT, LINE_SEPARATOR, PARAGRAPH_SEPARATOR and unpaired surrogates. State decode also checks the sanitized record re-encodes within its envelope.
- `WorldIntent.isBinding(String)` admits only `agent:<sanitized nonempty id, ≤16 chars>`, `ci:#1`..`ci:#8`, `goal`, `goal:atrium`, `decisions`, `merge`, `beacon`. Construction and JSON decode both refuse other keys with the static message `invalid binding`. Monitor ids are nonempty, unchanged by sanitization, and at most 16 characters.
- Opted-out activity/goal/tasks in a state are refused at construction and decode. Outgoing state is validated/sanitized before the C2S buffer is written. Speech event policy belongs to MP-05/MP-06: the event record deliberately has no policy field. Its codec sanitizes/caps text; it cannot independently infer the owner's speech preference.
- `Anchors.publish(StudioId, Layout)` installs the supplied revision without writing disk. MP-03 owns per-plot persistence; MP-04 can preserve server layout revisions. The existing `publish(MinecraftServer, Layout)` increments and persists LOCAL as before; the contract's no-server `publish(Layout)` overload also exists.
- `PlotGrid` spiral starts 0=(0,0), 1=(128,0), 2=(128,128). `indexAt` resolves the inclusive x/z site footprint and returns empty between sites. Strides must be 16-aligned and at least 96; origins beyond ±30,000,000 are refused, including overflowing hello index/stride combinations before client identity changes. `Plot.box()` covers the complete inclusive boundary blocks (its upper AABB coordinates are exclusive).
- Client registry mutation APIs: `setOwn`, `setPlot(id,index,stride)`, `put(StudioView)`, `updateLayout`, `updateState`, `updatePresence`, `remove`, `reset`, `setOverlay`. `put` assigns the actual local slot, ignoring a supplied slot. Slots are not reused until disconnect/reset. `addListener(BiConsumer<StudioId,StudioView>)` receives null on removal; `addEventListener` / `fireEvent` carry `PublicEvent`. These run on the mutation thread; networking packets must mutate on the client thread. Anchor listeners hop to the client thread and consult the latest anchor map: absence removes a view; an explicitly published EMPTY snapshot remains an update. `Studios.at` reuses cached views/optionals and mutation-time plot/view arrays; an external `PlotDirectory` may still allocate in its own lookup.
- `Studios.own().layout()` reads `Anchors.forStudio(self)` so legacy LOCAL builds remain immediately visible. Layout synchronization must publish the layout to `Anchors` as well as set the plot; the anchor listener refreshes the registry.
- Server effects and client hello effects explicitly schedule on their respective game threads. Only enabled dedicated servers send hello, only a matching remote hello attributed to the local player's UUID activates multiplayer, and integrated servers never send/reply. Disconnect resets mode, client slots, own identity, overlay, and remote anchors. Server config and mod-equipped markers reset on server stop. Hello is once per join; public publishers/relays/rates remain their owning packets' empty stubs.
- No connected player owns a plot in the default directory: plot 0 belongs only to `LOCAL`, and this foundation-only dedicated server sends `plotIndex=-1`. Owner-path tests install a fake directory; owned-plot harness checks wait for MP-03.
- `MpServerConfig.install(MpServerConfig)` returns the previous value and rejects null. Keep `server.isDedicatedServer() && MpServerConfig.current().enabled()`, read at call time. Fabric's `GameTestServerMixin` makes the game-test server report **dedicated**; its wiped run directory has no config, so loading uses defaults. Install/act/assert/restore the previous value in one server-thread call; multi-tick tests need a separate environment. Do not seed a global game-test config.
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

## Coordinator verification

Run by the coordinator on 2026-10-03, outside the worker's sandbox, on the same machine.

- `gw build`: green, 18 JUnit tests, no failures.
- Singleplayer QA with the ordinary launcher, run id `MP-F-baseline-coordinator`: 10 ok, 0 skipped, 0 failed. Compared shot by shot with a run on `main`: the same composition in all ten, mean luma within 0.8 on every shot, PNGs read. The first run in a freshly created world can show a blank goal hologram in `qa02_entrance_atrium`; a second run shows it, on `main` as well.
- Live dedicated hello (`game node artifacts/mp-f/check-hello.mjs`, server config `enabled: true`, one dev client): the server logs `hello_sent` and `hello_received`, the client logs `hello_received`, `hello_sent` and `mode_changed from=SINGLEPLAYER to=MULTIPLAYER`, and `dev.mp.studios` reports `MULTIPLAYER` with the own studio in slot 0.
- Seen in the same run: on disconnect the client logs `mode_changed` from the Netty IO thread, so the disconnect reset does not run on the client thread. This was the pre-fix observation for F5/L1.

### After the review fixes

Run by the coordinator on 2026-10-04 against the review fixes, outside the worker's sandbox.

- `gw test --rerun --no-build-cache`: 32 tests, no failures, errors or skips, executed for real (a plain `gw build` restores the test task from the build cache). `git status --short` stays clean afterwards: the test logs are under `build/`.
- Singleplayer QA, run id `MP-F-fix1`: 10 ok, 0 skipped, 0 failed. Compared with a fresh run on `main` (`main-reference-20261004`): no shot moved, mean luma within 1.2 on every shot, both contact sheets read.
  - **A QA reference is only valid for the display it was shot on.** The first compare, against `MP-F-baseline-coordinator`, reported four shots as different. That baseline was shot at 1920 × 1018 and this run at 3840 × 2082: the game window had moved to a display with a 2× backing scale, so every screen (console, diff review, library, task wall) is drawn half as large in the frame at the same GUI scale. Compare runs whose manifests report the same `game.window` size, and re-shoot the reference when it differs.
- `dev.mp.fake` against the same client (`artifacts/mp-f/check-fake.mjs`): the two-agent fixture registers in `SINGLEPLAYER`, the event is injected and `{clear:true}` leaves one studio. A fixture with `goal.status: "none"` is accepted. The client log holds no `agentcraft.mp` line except the four `fake_studio` lines these checks caused.
- Live dedicated hello (`artifacts/mp-f/check-hello.mjs`): the server logs `config_loaded enabled=true`, `hello_sent` and `hello_received`; the client logs `mode_changed from=SINGLEPLAYER to=MULTIPLAYER`, `hello_received` and `hello_sent`, and `dev.mp.studios` reports `MULTIPLAYER`.
- F5/L1 confirmed fixed: on disconnect the client logs `mode_changed from=MULTIPLAYER to=SINGLEPLAYER` from the render thread.
- No game, server or Foreman process was left running.
- One change by the coordinator after the worker's round: `WorldIntent.isBinding` keeps its fixed names in a constant and checks `ci:#1` to `ci:#8` without a regular expression (it runs for every key of every intent). The 32 tests were re-run after it.

## Deviations and remaining verification

- Sandbox `ps` fails with `zsh:1: operation not permitted: ps`. `mac.mjs` therefore recorded `stamp: null`, reported `Foreman exited` even though the showcase Foreman was listening on 27800, and its normal stop command reported no launcher-owned process. Exact PID/start-time/command checks were escalated read-only for cleanup; the verified showcase Foreman was terminated after QA. No launcher source was modified.
- One initial DevBridge probe ran before the client's startup had completed and returned `connect ECONNREFUSED 127.0.0.1:7900`; all subsequent singleplayer checks succeeded.
- The dedicated check ran server and client sequentially inside **one game-wrapper job**. The server logged `event=config_loaded enabled=true` and `Done (0.218s)`. The concurrent client had to start another Gradle daemon and failed with `java.lang.IllegalStateException: Unable to initialize SDL: The video driver did not add any displays` (runClient JVM exit 255). It also logged `Connection Invalid error for service com.apple.hiservices-xpcservice` and `Could not start the FSEvents stream`. No display workaround/retry was attempted. Its DevBridge connection timed out after 90 seconds. The script then sent `stop`, and both Gradle tasks exited. The already-exited server made a later cleanup TERM report `no such process`.
- The local dedicated harness used offline mode only in the ignored run directory; no shipped server config or resource was changed. Server flat-layer warning `No key layers in MapLike[{}]` did not prevent startup and was unrelated to the client SDL failure.
- `/code-review` and `/security-review` were intentionally skipped per SWARM.md; the coordinator runs independent reviewers.

## Coordinator commands for live verification

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

Allow the DevBridge port to settle for 35 seconds between client runs. Success requires live `hello_sent` and `hello_received` on the server/client logs, client `mode_changed ... to=MULTIPLAYER server=<hash>`, and `dev.mp.studios` mode MULTIPLAYER with the connection player's studio in slot 0. Also verify disconnect `mode_changed` runs on the client thread, and no removed remote studios remain after teardown/rejoin. The script writes `hello-result.json`, quits the client and stops the server. Verify both processes exited; stop the exact recorded JVM if the launcher fails to forward stdin. If the memory guard refuses, stop and report without retrying.

For a repeat baseline using the ordinary launcher outside the process-inspection sandbox:

```sh
game node tools/qa.mjs \
--home "$PWD/.agentcraft-home" --profile mp-0 --port 27800 --dev-port 7900 \
--run-id MP-F-review-fixes
game node artifacts/mp-f/check-fake.mjs
game node tools/devcli.mjs --port 7900 --timeout 5 raw '{"type":"dev.mp.studios"}'
game node tools/devcli.mjs --port 7900 --timeout 5 quit
game node tools/mac.mjs stop --profile mp-0
```

## Review fixes

The pre-edit `gw build` was green with the original 18 tests. Current tests: **32 passed, zero failures/errors/skips**. No Minecraft, QA, harness or source-generation command ran during the worker's review round. The coordinator ran the live checks afterwards: see "After the review fixes".

| Finding | Disposition and regression evidence |
|---|---|
| F1 | Fixed: four-field `ServerInfo`, bounded rates, accepted-info lifetime and `Studios.plot`. `WireBoundaryTest.hello_rates_and_combined_plot_origin_are_checked_before_handling`, `StudiosTest.retained_hello_plot_and_disconnect_are_client_executor_owned`, config install test. |
| F2 | Fixed: distinct agent ids and CI slots, explicit collection-cap cause assertions (slot 8 would otherwise fail the slot range), literal all-false policy and all ten allowlist shapes. `CodecTest` cap, policy and shape tests. |
| F3 | Fixed: scans literal and `MpEvents` arguments in real main/client sources, requires nonzero hits, validates all catalog constants. `FoundationTest.catalog_covers_every_mp_event_in_the_sources`; a real temporarily mutated call site failed on `not_in_catalog`, then was restored. |
| F4 | Fixed: code-point sanitizer and state re-encoding envelope backstop. `WireBoundaryTest.accepted_at_cap_states_and_events_are_relay_stable` exercises separators, lone high/low surrogates, quotes, backslashes, supplementary characters, full collections/field caps, encoded lengths and C2S→S2C equality for state and events. |
| F5 | Fixed: DISCONNECT schedules through `disconnectOn(Executor)`; JOIN also schedules on the client. `StudiosTest.retained_hello_plot_and_disconnect_are_client_executor_owned` invokes from a separate thread, checks no mutation before draining the client executor, and checks mode/registry listener threads. |
| F6 | Fixed: `GoalStatusWire.NONE`, wire `none`; fake injection accepts a no-goal fixture with zero progress/null text. `DevFakeTest.none_goal_overlay_callback_and_invalid_numbers_follow_dev_contract`, including codec round trip. |
| F7 | Fixed: anchor absence removes a registry entry, including queued notifications; published EMPTY is still a valid update. Publish/update/EMPTY/remove bridge regression in the retained-hello test. |
| F8 | No code change needed: amendment C preserves the LOCAL-only directory. Existing hello test asserts −1 for a connected player; API notes now explicitly state no ownership before MP-03. |
| F9 | Fixed: closed binding grammar at record construction and decode, static error, no changed-by-sanitization keys, nonempty bounded monitor ids. `WireBoundaryTest.binding_grammar_is_closed_at_construction_and_decode` checks every allowed form and each named refusal. |
| F10 | Fixed: `install` returns the previous config; gate remains dedicated AND enabled and reads current values per call. `FoundationTest.install_restores_previous_config_and_preserves_dedicated_gate`. Game-test guidance above follows Fabric's mixin, not the rejected original reviewer recommendation. |
| F11 | Fixed: AABB exclusive upper corner includes all boundary blocks. `FoundationTest.plot_box_agrees_at_every_boundary_and_extreme_origins_fail_closed` compares block origins and centers on/around all faces. |
| F12 | Fixed: combined hello origin validated at decode, with defensive validation before client identity mutation. `WireBoundaryTest` hello test and `StudiosTest` rejection/timeout test. |
| F13 | Fixed: JUnit working directory/logs under `build/test-work`; scanners receive `agentcraft.src`. Repeated real test runs leave no untracked log directory. |
| F14 | Partly fixed: malformed public-state C2S logs existing `public_state_rejected reason=decode_failed`; capture regression in `WireBoundaryTest.outbound_state_refuses_opted_out_text_and_overlength_fields_before_writing`. Local hello version is bounded before send; maximal-payload test checks long versions and a split surrogate. Other codec refusals remain DecoderExceptions: the frozen catalog has no general decode-rejection event. |
| F15 | Fixed: fake overlay changes before listeners fire. `DevFakeTest` asserts the lookup result inside the listener on both initial set and overlay=false. |
| F16 | Partly fixed: absent config logs `file=none`; malformed/out-of-range defaults have regression tests. Arbitrary unknown key text remains redacted as `unknown`: emitting it would weaken the counts/ids-only telemetry boundary. |
| F17 | Fixed documentation mismatch: snapshot APIs explicitly preserve caller-supplied revisions; builders must advance them, network consumers preserve server revisions. `AnchorsTest.identity_noop_does_not_notify_and_snapshot_publish_preserves_revision`. No revision-stamping change that would break relay equality. |
| F18 | Fixed with F5/F7: remove remote anchors before reset; drain queued anchor work without recreating ghosts. Retained-hello executor/bridge regression. |
| F19 | Fixed for public state: constructor refuses opted-out text, encoder validates/sanitizes before writing. Outbound-state regression. Speech policy remains MP-05/MP-06's contextual responsibility because events carry no policy; no new policy field added. |
| F20 | Not changed: all actual call sites use safe metadata. A generic token grammar cannot distinguish free text from valid ids; broad logging/value/toString restrictions need coordinator agreement for later packet callers. See contract requests. |
| F21 | Fixed: address hashes use a random, unlogged process salt; no new file. `StudiosTest.address_hash_is_salted_and_registry_lookup_reuses_immutable_views` proves it differs from the enumerable unsalted digest and remains stable within the process. |
| F22 | Already fixed by coordinator; no absolute paths reintroduced. |
| F23 | Fixed: STRICT streaming JSON parser, duplicate-member and trailing-value rejection, bounded nesting. `WireBoundaryTest.strict_json_refuses_comments_duplicates_and_trailing_values`. |
| F24 | Fixed within catalog: refused remote hello logs `hello_received` with received protocol/current mode; inactive HUD wording is neutral and distinguishes rejection. `StudiosTest.singleplayer_never_times_out_remote_waits_exactly_100_ticks_and_rejections_are_visible`; integrated hello remains silent. |
| F25 | Fixed registry allocations on known plot/own paths: cached immutable views/optionals, mutation-time arrays, no per-frame `all()` snapshots/streams. Registry identity/invalidation regression plus existing boundary/overlay tests. External directory allocation remains its implementation's responsibility. |
| F26 | Not changed: outside MP-F ownership; MP-T generates game-test entrypoints and coordinator owns lang/mixin coordination. |
| F27 | Fixed: maximal layout (512 48-char anchors, revision 7, pitch 30, plot 3), version, presence, events, 64 legal lamp bindings/monitor ids, asymmetric policy and agent booleans, offline flags, and literal config/policy defaults. `WireBoundaryTest` maximal and relay tests, `FoundationTest` defaults. The amended grammar deliberately forbids the reviewer's suggested arbitrary 48-char lamp keys. |
| F28 | Fixed: 200 integrated ticks stay SINGLEPLAYER with no MP log, remote stays SINGLEPLAYER through tick 99 and changes at tick 100. `StudiosTest` timeout regression. |
| F29 | Fixed with F13: logs under build, source scanners use the supplied source path and Gradle declares source inputs. |
| F30 | Fixed: repeated `setSelf` is a no-op, avoiding duplicate LOCAL stop/reset notifications. `AnchorsTest` notification-count regression; mutating-thread and revision Javadocs corrected. |
| F31 | Fixed: fractional/oversized integers become static `IllegalArgumentException("expected integer")`, translated to DevException. `DevFakeTest` checks 1.5 and 1e99. |
| F32 | Fixed: `WiringTest` pins MP-F's five calls plus ten stubs and payload registration before server stubs. |

API evidence was checked in installed jars/read-only Minecraft sources: Fabric `ConnectionMixin` injects into `channelInactive` and `handleDisconnection`, calling `AbstractNetworkAddon.handleDisconnect` → `ClientPlayNetworkAddon.invokeDisconnectEvent` directly. It does not hop to the client thread. Minecraft's `Minecraft` inherits `Executor` through `BlockableEventLoop`. Fabric `GameTestServerMixin.isDedicated` sets true. Gson's strict streaming reader APIs and AABB's exclusive maximum faces were verified with javap/source reads.

Review-round checks (run `gw` from `mod/`):

- `gw build`: untouched baseline passed, 18 tests; final implementation passed, 32 tests, 0 failures/errors/skips.
- `gw test --tests dev.agentcraft.mp.FoundationTest.catalog_covers_every_mp_event_in_the_sources`: **failed as intended**, one test/one failure, with a temporary real call site changed to `not_in_catalog`. Exact assertion: `not_in_catalog ==> expected: <true> but was: <false>`. Restored the call site; subsequent full build passed.
- An intermediate compile failed because a test-helper edit referenced `reason` outside its scope; corrected before the green builds. Two wrapper calls from the checkout root were refused with `gw: run from a mod/ directory (no ./gradlew here)`; subsequent calls used `mod/`.
- `git diff --check`: pass. `git status --short`: only intended MP-F source/test/build/worknote edits and the new `WireBoundaryTest.java`; no test logs or other stray files.
- The live commands above were left to the coordinator, who ran them afterwards (see "After the review fixes").

## Contract change requests

No additional change is required for amendments A–E. No files outside MP-F's row were edited and no telemetry catalog entry changed.

Optional coordinator follow-ups:

- F14: if every malformed payload needs structured rejection telemetry, define a generic decode event/reason (and ownership). The existing public-state event covers that C2S boundary only; inventing a new catalog entry here is outside this round's authority.
- F20: remove “and by logs” from the PublicJson contract sentence and decide a typed telemetry-value policy before allowing public records/collections as log arguments. Current MP-F call sites never log these values or opt-in text.
- F19: MP-05/MP-06 must gate Say.text with the studio's current sayText policy on send, relay and receipt. No foundation codec can infer that policy from a standalone event.

## Review-fix commits

The worker cannot commit in its sandbox; the coordinator committed its work after verifying it.

1. `f755e34` — the review fixes: contract amendments A to E, the lifecycle and codec bugs, and the tests.
2. The commit that follows it records the fixes and the coordinator's checks in this worknote.

## Commits

1. `2e0d90d` — types, plot grid, config, telemetry, JUnit setup.
2. `ccf0f3b` — payload records/codecs, public JSON and boundary tests.
3. `9ff4300` — per-studio anchors and compatible HQ options.
4. `caace2f` — client registry, mode and hello.
5. `e20b2f2` — dev overlay and inspection commands.
6. `51ca0be` — F-stubs and wiring.
7. Final verification/worknote commit follows these six implementation commits.

## Seams for Wave 1

This round adds five seams; no commits or live processes were started. The existing worknote above is retained as historical verification. `gw` below is the swarm wrapper, run from `<worktree>/mod`.

1. **Telemetry:** new `public static final String` constants `MpReasons.MULTIPLAYER_SCREEN = "multiplayer_screen"`, `BAD_ORIGIN = "bad_origin"`, `BUILD_ERROR = "build_error"`, `UNKNOWN_AGENT = "unknown_agent"`; `MpEvents.STUDIO_EVENT_REJECTED = "studio_event_rejected"`, catalog level `warn`, owned by MP-06. `FoundationTest` pins the names/level and captures the new event.
2. **Layout persistence:** `public static Anchors.Layout Anchors.fromJson(JsonObject root)`. This is the existing reader with public visibility; `Anchors.toJson(Layout)` is unchanged. `AnchorsTest` round-trips bounded and unbounded layouts including anchors and revision.
3. **Rates:** `public RateBucket(int ratePerSecond)` and `public boolean tryTake(long nowNanos)`, in `dev.agentcraft.mp`. Positive rate required; capacity is twice the rate, initially full. The first call establishes the time origin; later calls refill from caller-supplied nanoseconds. Backwards time neither refills nor moves the last-time watermark. Each player/state/event/intent uses its own bucket. `RateBucketTest` covers burst size, one-second refill, fractional credit, backwards time, invalid rates and extreme idle intervals.
4. **Visibility:** `dev.agentcraft.mp.server.StudioRange` exposes the following exact public API:

   ```java
   static int chunkDistance(Plot plot, int chunkX, int chunkZ)
   static List<Plot> visiblePlots(Collection<Plot> plots, StudioId own,
       boolean overworld, int chunkX, int chunkZ, int radius)
   static Change diff(Collection<Plot> previous, Collection<Plot> next)
   record Change(List<Plot> left, List<Plot> entered)
   static void init()
   static void addListener(Listener listener)
   interface Listener {
       void entered(ServerPlayer viewer, Plot plot);
       void left(ServerPlayer viewer, Plot plot);
   }
   static void refresh(MinecraftServer server)
   static List<ServerPlayer> viewersOf(MinecraftServer server, StudioId studio)
   static boolean sees(UUID viewer, StudioId studio)
   ```

   The three core functions have no `ServerPlayer` parameters. Results retain input order and are immutable; changed index/origin counts as left and entered. Chunk distance uses the full inclusive site footprint, floor division for negative coordinates, and horizontal Chebyshev distance. Only own plots remain visible outside the overworld. The shell is server-thread-only, gates on dedicated AND current enabled config, tracks mod-equipped online viewers, refreshes every 20 server ticks and immediately after an accepted hello, calls all left notifications before entered notifications per viewer, and keeps listener registration order. Disconnect silently forgets a viewer; stop clears viewer/server state. One-time feature listener registrations survive server restarts. `init()` is wired after payload registration and before the feature stubs. `StudioRangeTest` covers edge chunks, outside distances/corners, negative origins, radius zero, dimensions, and changed-plot diffs; `WiringTest` pins initialization and order. Server/player/chunk and Fabric callback signatures were verified in read-only sources/jars. The thin shell was compiled, not exercised on a live server in this round.
5. **Foreman fixtures:** test-only `dev.agentcraft.client.foreman.ForemanStates` exposes `public static ForemanState showcase()`, `showcaseLate()`, and `fromSnapshot(JsonObject snapshot)`. Each returns a fresh synced state fed through the existing package-private constructor/receive path. `ForemanStatesTest` loads both without Minecraft: each has 6 agents, 9 tasks and a goal; open decisions are 2 and 0 respectively (the late fixture has 6 answered decisions). Tests also check fresh instances and custom snapshot isolation. The two supplied resource files were not edited; their hashes remain unchanged. No production Foreman class changed.
6. **Remote agent clicks** (added by the coordinator after this round, from an advisor's question): `dev.agentcraft.client.mp.RemoteAgentClicks` exposes `public static void set(BiConsumer<StudioView, String> handler)` and `public static void fire(StudioView studio, String agentId)`. MP-08 calls `fire` for a click on a remote studio's agent instead of opening the own agent card; MP-11 registers its read-only card with `set`. With no handler registered a click does nothing. `RemoteAgentClicksTest` covers the default, the registered handler and its replacement.

Verification: pre-edit `gw test --rerun --no-build-cache` passed all 32 tests. Final `gw test --rerun --no-build-cache` passed all 45 tests (zero failures/errors/skips); `gw build` passed. That was this round's count: item 6 added one test afterwards (46 `@Test` methods), and the pull-request review below added one more, so `gw build` now runs 47 tests with zero failures/errors/skips. `git diff --check` passed. Status contains only this round's intended changes plus the coordinator's modified `work-packets.md` and supplied untracked fixtures. The coordinator's contract file was never written, staged or reverted by this worker. No Minecraft, QA, harness, source generation or Foreman process ran. No blocked seam or additional contract request.

## Pull-request review

`MpLog` now also replaces every Unicode control (`Cc`, which adds U+0085), format control (`Cf`: bidi overrides and isolates, zero-width characters, the BOM) and line or paragraph separator (U+2028, U+2029) in a telemetry value with `_`, because Java's default `\s` and `\p{Cntrl}` match ASCII only; values without those characters log byte for byte as before. `FoundationTest.telemetry_values_neutralise_unicode_separators_and_format_controls` covers nine such code points and an ordinary line, and failed on all nine before the change.

### Second round: four findings on the wire contract

Each claim was checked against the 26.3 sources first (`PacketDecoder.decode` throws on unread bytes, `Connection.exceptionCaught` then disconnects; `Identifier.validPathChar` admits `[a-z0-9/._-]` only). All four were valid. `gw build` now runs **50 tests**, zero failures, errors or skips (47 before; five of the 50 failed before the code changed).

| Finding | What changed |
|---|---|
| Version negotiation | Both hello codecs read `protocol` first (`MpCodecs.hello`). A hello of another protocol is not parsed: its remaining bytes are skipped and it decodes to a hello that carries only that protocol (S2C: `you = LOCAL`, `plotIndex = -1`, `ServerInfo(0,0,0,0)`; C2S: an empty `modVersion`). `MpMode.receiveHello` and `MpPayloads.acceptHello` refuse it by the protocol, as they already did. A hello of this build's protocol decodes as before, trailing bytes included. So `protocol` has to stay the first field, a VarInt, in every later protocol. Test: `WireBoundaryTest.a_hello_of_another_protocol_is_skipped_whole_and_refused_as_a_mismatch`. |
| Caps only at the codec | Every cap is now also refused where the record is built, with an `IllegalArgumentException` that names the field (`agents exceeds 16 entries`, `say text exceeds 120 characters`): `PublicStudioState` (agents, ci, tasks), `PublicAgent`, `PublicTask`, `GoalSummary`, `PublicEvent.Say` and `TaskDone`, `WorldIntent` (lamps, litMonitors), `HelloC2S`, `PresenceS2C`, `LayoutS2C` (layout name, anchors, anchor names). The helpers are `MpText.cap` and `MpText.capOptional`. Before, 17 agents threw in the sender's Netty encoder, and an oversized event, intent, layout, presence or hello was written and then refused by the receiver's decoder; either way a connection dropped. The decoders are unchanged and refuse first, with their old messages. Only sizes moved: ranges (a negative `rev` or count, a CI slot, the progress), duplicate identities and the state envelope are still checked by the state encoder and the decoders alone. Test: `WireBoundaryTest.every_cap_is_accepted_at_the_limit_and_refused_one_over_where_the_record_is_built` (22 caps); the decode-side cap test in `CodecTest` now writes its one-over wire forms by hand, since the records can no longer be built. |
| Out-of-range plot in a hello | `HelloS2C` no longer calls `PlotGrid.originOf` at decode. A plot the grid cannot place decodes, and `MpMode.receiveHello` refuses the hello (logged, mode unchanged, identity untouched) instead of the decoder disconnecting. Rates and the stride are still refused at decode. Test: `WireBoundaryTest.hello_rates_and_combined_plot_origin_are_checked_before_handling`, whose last case used to assert the decoder exception. |
| Agent id and skin charset | `PublicAgent` refuses an `id` or a `skin` that is not a valid identifier path (`MpText.path`, which asks `Identifier.isValidPath`), at construction and therefore at decode (`agent skin is not a resource path`). The check runs on the sanitized value, so a skin that decoded before and is a path decodes to the same record. The `id` got the same rule because remote ids reach identifiers too (`textures/gui/portrait/<id>.png`), the Foreman's cast ids are lowercase names and every agent of both Foreman fixtures passes. Empty stays allowed for both, as before. Not restricted: `PublicTask.assignee`, `Say.agentId`, `Say.to`, `TaskDone.agentId`, and the agent ids inside `WorldIntent` bindings and `litMonitors` (length and sanitizing only, as before), so a renderer must resolve those against the state's agents before it builds an identifier from one. Test: `WireBoundaryTest.agent_ids_and_skins_are_resource_paths_at_decode_and_where_the_record_is_built`; `accepted_at_cap_states_and_events_are_relay_stable` keeps its quote, backslash and emoji fills out of ids and skins. |

For the packets built on this branch: a publisher has to leave out (or map) an agent whose id is not a path and replace a skin that is not one before it builds a `PublicAgent`, and anything that builds a record from unbounded input has to clamp first, because the refusal is now an exception in its own call.
