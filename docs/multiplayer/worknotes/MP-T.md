# MP-T Worknote: Fabric game tests

> Packet: MP-T (Game tests). Branch: `mp/MP-T-gametests`. Base: `b40768d`.

## What shipped

- `mod/build.gradle`: Loom 1.18's `fabricApi.configureTests` creates the isolated
  `gametest` source set and headless `runGameTest` server task. Client game tests are disabled.
- `mod/build.gradle`: Minecraft source generation is ordered after compilation, tests, and game
  tests when `build` and `mcSources` share a task graph, fixing audit A-03.
- `mod/src/gametest/resources/fabric.mod.json`: test-only mod metadata and the
  `fabric-gametest` entrypoint.
- `mod/src/gametest/java/dev/agentcraft/gametest/HqSmokeGameTest.java`: runs
  `/agentcraft hq`, checks that 69 anchors are published, and checks the plot-0 spawn anchor at
  `(0.5, 66.0, 22.5)`.

The API shape was checked against Loom 1.18.2's `GameTestSettings`, Fabric
`fabric-gametest-api-v1` 4.0.32, and the decompiled Minecraft 26.3
`GameTestHelper` and `Commands` sources. In 26.3, `performPrefixedCommand` returns `void`, so the
smoke test asserts the command's published layout rather than a command return value.

## Verification

Commands ran from `mod/`.

| Command | Result |
|---|---|
| `game gw-raw runGameTest` before adding the Loom test configuration | Expected RED: Gradle reported `Task 'runGameTest' not found`. |
| `gw tasks --all` | Green; listed `gametestClasses`, `compileGametestJava`, and `runGameTest`. |
| `gw compileGametestJava processGametestResources` | First run caught the 26.3 `void` command API; after correcting the test, green in 603 ms. |
| `game gw-raw runGameTest` | Green, headless server (`KnotServer`, `Env=SERVER`), `Published layout 'studio' rev 1 with 69 anchors`, all required tests passed, `BUILD SUCCESSFUL in 7s`. The runner reports two tests because Minecraft also registers its built-in `always_pass`; AgentCraft contributes one smoke test. |
| `game gw-raw build mcSources` | The first ordering attempt exposed additional game-test consumers of Loom's generated Minecraft artifact (`compileClientJava`, `compileGametestJava`, and `runGameTest`). After broadening the ordering barrier, green in 9 s: game tests passed, then both Vineflower tasks, `genSources`, and `mcSources` completed. |
| `git diff --check` | Green. |

No visual QA was required: this packet changes test/build infrastructure only and does not change
HQ, renderer, or agent production sources.

## Deviations and open risks

- No deviation from the packet contract.
- No unverified items or open risks.

## Contract change requests

None. All edits stay within MP-T's ownership row.
