# MP-T Worknote: Fabric game tests

> Packet: MP-T (Game tests). Branch: `mp/MP-T-gametests`. Base: `b40768d`.
> Updated 2026-10-03 after review: see [Review fixes](#review-fixes). Packets that write game tests
> need [Adding a game test](#adding-a-game-test) and [Game-test environment](#game-test-environment).

## What shipped

- `mod/build.gradle`, game-test block:
  - Loom 1.18.2's `fabricApi.configureTests` creates the isolated `gametest` source set
    (`mod/src/gametest`) and the headless `runGameTest` server task. Client game tests are disabled.
  - **`gradlew build` and `gradlew check` now run `runGameTest` on every invocation.** Loom makes
    `check` depend on it, and the task has no outputs, so it is never up to date. A warm `build` takes
    about 7 s on this machine (`mod/DEV.md` still says 2-4 s). `gradlew build -x runGameTest` skips
    the game tests, and then does not compile them either.
  - The game-test server's heap is capped at 2 GB, and `runGameTest` fails with
    `Timeout has been exceeded` after 5 minutes.
  - `cleanGameTestRunDir` deletes `mod/build/run/gameTest` before every run, so every run builds the
    HQ in a fresh world.
  - `processGametestResources` writes the `fabric-gametest` entrypoint list from the sources.
- `mod/build.gradle`, `mcSources` ordering: `mcSources` and Loom's two Vineflower tasks run after
  `build`, `test`, `runGameTest` and every `compile*` task when they share a task graph (audit A-03).
  The lines are a block of their own; upstream's `mcSources` comment and task are unchanged.
- `mod/src/gametest/resources/fabric.mod.json`: the test-only mod `agentcraft-gametest`. Its entrypoint
  array holds a placeholder that the build replaces.
- `mod/src/gametest/java/dev/agentcraft/gametest/HqSmokeGameTest.java`: runs `/agentcraft hq` and
  checks the command's result (69), that this command raised the layout revision by one, the 69
  published anchors, the plot-0 spawn anchor at `(0.5, 66.0, 22.5)`, and one placed and bound station
  block: tove's status lamp at `(-15, 69, -10)` with the binding `agent:tove`.

Run the game tests with `gradlew runGameTest` from `mod/` (on the swarm machine:
`game gw-raw runGameTest`).

The API shape was checked against Loom 1.18.2 (`FabricApiTesting`, `GameTestSettings`,
`RunConfigSettings`, `AbstractRunTask`), Fabric `fabric-gametest-api-v1` 4.0.32
(`TestAnnotationLocator`, `GameTestServerMixin`), Fabric Loader 0.19.5 (`DefaultLanguageAdapter`),
brigadier 1.3.11 and the decompiled Minecraft 26.3 sources (`GameTestServer`, `GameTestHelper`,
`GameTestBatchFactory`, `Commands`, `CommandSourceStack`, `WorldPresets`, `FlatLevelSource`).

## Adding a game test

1. Create a class anywhere under `mod/src/gametest/java`, for example
   `dev/agentcraft/gametest/mp/plot/PlotAllocationGameTest.java`: one public top-level class per
   file, named like the file, with a public no-argument constructor (the implicit one is enough).
   Fabric Loader creates one instance of it.
2. Give it test methods of this shape:

   ```java
   @GameTest
   public void allocatesTheNextFreePlot(GameTestHelper helper) {
       // act, assert with helper.assert...
       helper.succeed();
   }
   ```

   `@GameTest` is `net.fabricmc.fabric.api.gametest.v1.GameTest`. A method must be public, not static,
   return `void` and take exactly one `GameTestHelper`. Fabric throws at startup otherwise (by reading
   `TestAnnotationLocator`, not run). The default budget is 20 ticks (`maxTicks`).
3. That is all. **Do not edit `mod/src/gametest/resources/fabric.mod.json`.** Fabric runs only the
   classes listed under its `fabric-gametest` entrypoint, and `processGametestResources` writes that
   list: every `.java` file under `src/gametest/java` whose text contains `@GameTest`, as the class
   name its path gives. The sources are a declared task input, so adding, removing, renaming or editing
   a class regenerates the list, also when the configuration cache is reused.
4. Check that your tests run. `N tests are now running` in the output goes up by the number of methods
   you added (N includes Minecraft's built-in `always_pass`: the smoke test alone gives 2), and
   `mod/build/resources/gametest/fabric.mod.json` lists your class.

Limits of the scan, which is textual:

- The file's top-level class is what gets listed. `@GameTest` methods in a nested class do not run, and
  neither do those of a subclass that only inherits them and does not contain the text itself.
- A file that mentions `@GameTest` only in a comment is listed too. Fabric then logs
  `No methods with the GameTest annotation were found in <class>` and carries on.
  `package-info.java` is never listed.
- A test id is `agentcraft-gametest:<snake_case of ClassName_methodName>`, without the package. Keep
  simple class names unique across packets.
- If no file contains `@GameTest`, `processGametestResources` fails.

## Game-test environment

Every game test of every packet runs in the same place. The facts below were read from the sources
named above and confirmed with a temporary probe test.

- **One JVM, one server, one world.** `runGameTest` starts a single `GameTestServer`. Every listed test
  runs in it, plus `always_pass`. Tests with the same `environment` and dimension share a batch of up
  to 50 and run interleaved on the server thread, in no guaranteed order. JVM statics such as
  `Anchors.current()` are shared by all of them.
- **A fresh run directory for every run.** `mod/build/run/gameTest` is deleted before each run. Nothing
  survives from one run to the next: not the world, the plan and anchor files in it, config files or
  logs. The log of the last run is `mod/build/run/gameTest/logs/latest.log`. Every run therefore
  prints Minecraft's first-boot noise: `Failed to load properties from file: server.properties` (an
  ERROR with a stack trace) and `Failed to load eula.txt`. Both are harmless.
- **It is not the HQ world.** The level is named `Test Level`, so `HqWorld.isHq(server)` is false: no
  HQ game rules, no anchors loaded at start, no automatic HQ build. `server.isDedicatedServer()`
  returns true (a Fabric mixin; vanilla's `GameTestServer` says false).
- **It is not the HQ superflat.** The overworld is Minecraft's `flat_all_dimensions` preset: bedrock at
  y = -64, sandstone from y = -63 to y = 3, air from y = 4, desert biome, no structures, seed 0. The
  surface is the sandstone at y = 3. The HQ code assumes grass at y = 64 (`HqWorld.SURFACE_Y`, audit
  A-32). The studio plan lays its own ground at y = 60 to 64, so plot 0 is built as a slab that floats
  56 blocks above the sandstone, and every build reports `0 foreign replaced`: the terrain
  replacement of audit A-07 is not exercised here.
- **Test structures are far away.** They are placed just above the sandstone around a random position
  up to 15 million blocks from the origin, different on every run
  (`N tests are now running at position x, 4, z`). `GameTestHelper`'s block methods take positions
  relative to the test's own structure. The HQ is at the world origin, so read it through the level
  with absolute coordinates, as the smoke test does:
  `server.overworld().getBlockState(new BlockPos(-15, 69, -10))`.
- **Plot 0 is shared.** The first test that runs `/agentcraft hq` builds plot 0, publishes the layout,
  writes `agentcraft-hq-plan.dat` and `agentcraft-anchors.json` into the world folder and moves the
  world spawn to (0, 66, 22). Every test that starts later in the same run sees all of that.

Rules for a test:

- Plot 0 and `Anchors.current()` may already be built and published when your test starts. Do not
  assert that shared state is absent: no layout yet, a block still air, a file not there.
- Do not assert absolute revision numbers or counters of shared state. Read the value before your
  action and assert the change, as the smoke test does with `revisionBefore + 1`.
- If your test changes blocks in plot 0, put them back, or use another plot. `/agentcraft hq` keeps
  cells that differ from its last plan, so a block you removed stays removed for the tests after you.
- A restart cannot be tested by running the task twice: the second run has a new world. Save, drop the
  in-memory state and load again inside one test (this affects MP-03's persistence test).

## Verification

Commands ran from `mod/`. `gw` and `game` are the swarm machine's wrappers around `./gradlew`.

Original packet (commit `57f1b49`):

| Command | Result |
|---|---|
| `game gw-raw runGameTest` before adding the Loom test configuration | Expected RED: Gradle reported `Task 'runGameTest' not found`. |
| `gw tasks --all` | Green; listed `gametestClasses`, `compileGametestJava`, and `runGameTest`. |
| `gw compileGametestJava processGametestResources` | First run caught the 26.3 `void` command API; after correcting the test, green in 603 ms. |
| `game gw-raw runGameTest` | Green, headless server (`KnotServer`, `Env=SERVER`), `Published layout 'studio' rev 1 with 69 anchors`, all required tests passed, `BUILD SUCCESSFUL in 7s`. The runner reports two tests because Minecraft also registers its built-in `always_pass`; AgentCraft contributes one smoke test. |
| `game gw-raw build mcSources` | The first ordering attempt exposed additional game-test consumers of Loom's generated Minecraft artifact (`compileClientJava`, `compileGametestJava`, and `runGameTest`). After broadening the ordering barrier, green in 9 s: game tests passed, then both Vineflower tasks, `genSources`, and `mcSources` completed. |

Review fixes (code final at commit `d61ef62`; the commit after it changes only this file):

| Check | Result |
|---|---|
| `gw build` | Green in 7 s: `:cleanGameTestRunDir`, `:runGameTest` (`All 2 required tests passed :)`), `:check`, `:build`. `build/libs/agentcraft-0.1.0.jar` contains no gametest entry. |
| `game gw-raw runGameTest`, twice in a row | Both green (7 s and 9 s), the second with the configuration cache reused. Both log `79757 changed, 556 connections, 100 bindings ... previous plan none` and `HQ 'studio' built ...: 80313 blocks updated`. Before the fix every run after the first logged `0 changed ... previous plan known`. |
| Heap cap: `ps`, `runGameTest --info` and `jcmd <pid> VM.flags` during a run | `-Xmx2G` is on the server's command line; the JVM reports `-XX:MaxHeapSize=2147483648`. The default on this 24 GB machine is 6442450944. Peak resident memory in one sampled run: about 1.0 GB. |
| Fuse: timeout temporarily set to 3 s, `game gw-raw runGameTest` | RED as intended: `Requesting stop of task ':runGameTest' as it has exceeded its configured timeout of 3s`, `Timeout has been exceeded`, `BUILD FAILED`; no game-test JVM was left running. Restored to 5 minutes. |
| **Red run**: the test temporarily expected 70 anchors | `game gw-raw runGameTest`: exit 1, `Expected published anchor count to be 70: was 69 on tick 0`, `1 required tests failed :(`, `Task :runGameTest FAILED`, `BUILD FAILED`. `gw build`: exit 1 with the same failure. Reverted. |
| One temporary mutation per new assertion | All RED: `Expected /agentcraft hq result to be 68: was 69`; `Expected layout revision after this command to be 2: was 1`; a lamp check moved in front of the command fails (the lamp is not there before it); expecting the binding `agent:kit` fails. Reverted. |
| Generated entrypoints: a temporary second class `dev.agentcraft.gametest.probe.TempSecondGameTest` | With the configuration cache reused, `processGametestResources` reran, the generated file listed both classes, and the run reported `3 tests are now running` and `All 3 required tests passed :)`. After deleting the class: one class listed, 2 tests, and its `.class` file gone. |
| Generated entrypoints: edge cases | With the only `@GameTest` removed, `processGametestResources` fails with `No class under src/gametest/java contains @GameTest`, and reruns on the next build. A class with the text only in a comment is listed and logged by Fabric (see above). A `package-info.java` with the text is not listed. |
| Configuration cache report of a `runGameTest` run | 0 problems. No file under `src/gametest` is a configuration input: the scan runs at execution time. |
| `gw help --task mcSources` | The task is registered in `build.gradle`, type `Sync`, group `agentcraft`, as on `main`. |
| `gw build mcSources --dry-run` and `gw mcSources build --dry-run` | In both orders every task through `:build` comes first, then `:genCommonSourcesWithVineflower`, `:genCommonSources`, `:genClientOnlySourcesWithVineflower`, `:genClientOnlySources`, `:genSources`, `:mcSources`. Control with the ordering block removed: `gw mcSources build --dry-run` puts the decompiler tasks before `:compileJava`. **The full `build mcSources` run was not repeated** (decompiling is not allowed on the swarm machine). |
| Trial three-way merge of `mod/build.gradle` with MP-F's version (`git merge-file`) | Clean, no conflict. |
| `git diff --check main...HEAD`, `git status --short` | Clean, empty. |

Coordinator re-run of the review fixes, outside the worker's session (2026-10-03, at `d61ef62`): `game gw-raw build`
is green in 6 s (`:cleanGameTestRunDir`, `:runGameTest` with `All 2 required tests passed :)`, `:check`, `:build`);
`game gw-raw runGameTest` twice in a row is green both times, each with `previous plan none` and
`80313 blocks updated`; `runGameTest --info` shows `-Xmx2G` on the game-test server's command line; the jar has no
gametest entry; the generated entrypoint list is `dev.agentcraft.gametest.HqSmokeGameTest`; and
`git diff --check main...HEAD` is clean.

No visual QA was required: this packet changes test and build infrastructure only and does not change
HQ, renderer, or agent production sources.

## Deviations and open risks

No deviation from the packet's matrix row.

- **Every `build` and `check` boots a Minecraft server.** That is what the Definition of done asks for,
  and it has a cost: a failing or hanging game test fails the build, and the build is slower. The 2 GB
  cap bounds the Java heap, not the whole process, and the 5-minute fuse ends the whole run without a
  per-test report.
- **The release image build is unverified.** `deploy/Dockerfile` runs `gradlew build`, so
  `docker build` and the release workflow now run the game tests on Linux, where they have never run
  (no Docker on the swarm machine). A red or hanging game test now blocks a release.
- **On the swarm machine `gw build` starts that server outside the game slot.** The wrapper refuses
  only literal run-task names, so `gw build -x runGameTest` is refused too and there is no opt-out
  through `gw`.
- **The game-test world is not an HQ world.** Its surface is at y = 3, not 64, so tests here do not
  cover the HQ's terrain handling, and MP-01's planned `surface_not_64` startup failure would fire in
  this world as soon as a game test enables multiplayer.
- **All tests share one world and one JVM.** A test that breaks the rules above can make another
  packet's test fail or pass for the wrong reason, depending on the order they start in.
- **The entrypoint scan is textual**, with the limits listed under "Adding a game test".
- **A real server restart cannot be tested**, because every run starts from an empty directory.
- **`build mcSources` in one invocation** was run in full only by the original packet, before the
  ordering lines moved. After the move the ordering was checked by dry run only.

## Contract change requests

For the coordinator. Nothing outside MP-T's row was edited.

- **MP-14** (`deploy/Dockerfile`, `.github/workflows/release-container.yml`): the image build now runs
  the game tests. Either keep them as a release gate and prove it on the first release run, or build
  with `gradlew build -x runGameTest` (or `assemble`: only the jar is copied out).
- **MP-Z** (`mod/DEV.md`, `README.md`, `CLAUDE.md`): the timing row "Incremental `gradlew build` (warm
  daemon), 2-4 s" and the build commands no longer say what `build` does. Document `runGameTest`, the
  `-x runGameTest` opt-out and the new timing.
- **Swarm tooling** (outside the repository): decide whether `gw build` and `gw check` should take the
  game slot, and let `-x runGameTest` through the wrapper's guard.
- **MP-01**: decide how the `surface_not_64` check treats the game-test world (surface at y = 3).
- **Game-test packets** (MP-02, MP-03, MP-07, MP-13 and any other): no shared file to edit for
  registration. Their briefs should point to "Adding a game test" and "Game-test environment" above.
- **Dispatch rules** ("run `gradlew build` and `gradlew mcSources` as separate invocations"): can be
  relaxed once someone repeats the full `build mcSources` run on a machine where decompiling is allowed.

## Review fixes

The coordinator's decisions on the 15 review findings, and what was done.

- **A. Keep game tests inside `build`, safely** (findings 1, 6, 7, 12). The `gameTest` run configuration
  gets `-Xmx2G`; verified on the running JVM. `runGameTest` has a 5-minute timeout; verified with a
  temporary 3 s timeout. "What shipped" now says that `build` and `check` run the game tests. The
  image build and the documented timings are contract change requests.
- **B. Reset the run directory** (findings 2, 3). `cleanGameTestRunDir` runs before every
  `runGameTest`. Two consecutive runs both built 80313 blocks with `previous plan none`.
- **C. Stronger smoke test** (findings 9, 10). It takes the command's result from the source callback,
  asserts the revision change and asserts tove's status lamp and its binding. The position and
  binding were checked in `StudioHqBuilder.desks` first, and each new assertion was shown to fail.
- **D. No shared entrypoint file** (findings 4, 8, 11). The list is generated; a second test class was
  picked up and dropped again without touching any other file. The fallback (a verification task) was
  not needed.
- **E. Upstream footprint** (finding 5). The ordering lines moved out of upstream's `mcSources` block,
  which is byte-identical to `main` again. `mcSources` is covered by the name matcher.
- **F. `eula = true` deleted** (findings 14, 15). Loom reads it only for client game tests.
- **G. Worknote** (finding 13). This revision: the two new sections, the real risks, the red run and
  this list.
