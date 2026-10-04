# MP-B Worknote: Node baseline and test summaries

> Type: reference. Audience: the multiplayer coordinator and packet workers.
> Companions: [work packets](../work-packets.md), [audit](../audit.md).

## Contract

- **Packet:** MP-B, baseline; packet number 90, branch `mp/MP-B-baseline`.
- **Base:** `main` @ `b40768d`.
- **Machine:** macOS, Apple Silicon, Node v24.18.0, npm 12.0.1.
- **Scope:** matrix-owned engine declarations, launcher Node checks and README prerequisite; coordinator-authorized exceptions for summary and failing-name parsing in `parseTestOutput`, its tests, and two prerequisite lines in `tools/README.md`.
- **Review requirement:** the parser lives in **`foreman/src/repos.ts`**. This change needs **agent-sandbox-guardian** review before merge. Independent reviews are left to the coordinator per `SWARM.md`.

## What was built

| File | Change |
|---|---|
| `foreman/package.json`, `tools/package.json` | Require Node `>=22.18`. |
| `tools/launch.ps1` | Compare Node major/minor versions before setup, accept prerelease suffixes, and fail closed with the installed version on rejection; resolve summary paths before any `Fail`. |
| `tools/mac.mjs` | Compare major/minor versions before setup; allow later major versions; report the installed version on rejection. |
| `README.md`, `tools/README.md` | Quick-start and both launcher prerequisites are Node 22.18+. |
| `foreman/src/repos.ts` | Accept TAP/spec summaries; strip spec durations and omit the failure-section header from failing names. |
| `foreman/test/repos.test.ts` | Independent `parseTestOutput` describe contains one summary test and two captured-output name fixtures; full suite now contains 485 tests. |

## Root cause of the extra finding

This is distinct from A-04/A-05: native TypeScript tests do run here. The smallest integration repro needs both registration and the test-command test, because registration is established by an earlier test:

```sh
cd foreman
npx vitest run test/repos.test.ts -t 'registers a repo|runs the repo test command'
```

Before the fix: registration passes, the test-command test fails because `res.summary` is undefined. Selecting only the latter test instead fails with `no repo demo-app`; that invocation is not a valid repro of the reported bug.

A temporary probe created the real demo repo with `demoRepo()`, ran its command via `runShell` under the same `CI=1`, `FORCE_COLOR=0`, `NO_COLOR=1` settings, and passed the captured output to `parseTestOutput`. Both `npm test --silent` and direct `node --test` exit 0 and emit:

```text
ℹ tests 10
ℹ suites 0
ℹ pass 10
ℹ fail 0
```

The parser matched only `^# <counter> <number>$`, so it returned `{ failures: [] }` without a summary. `node --test --test-reporter=tap` emitted `#` counters and parsed correctly, confirming a reporter-format mismatch rather than npm suppression or missing tests. The fix accepts both prefixes without changing test execution, exit-code handling, merge, worktree or policy logic. Captured native output: `artifacts/logs/mp-b-node24-output.log` (gitignored).

Regression fixtures were added before the parser fix and failed on the `ℹ` case, then passed after the one-line change. They also retain TAP coverage independent of the installed Node's default reporter.

## Initial verification (3b581ab)

All logs below are gitignored under `artifacts/logs/`. Commands are from the worktree root unless stated.

| Command/check | Result | Log |
|---|---|---|
| `node --version`; `npm --version` | v24.18.0; 12.0.1 | Recorded above |
| `cd foreman && npm ci` | 147 packages installed; 0 vulnerabilities. npm reports blocked esbuild/fsevents install scripts; checks still run successfully. | Tool output |
| `npm ci --prefix tools` | 2 packages installed; 0 vulnerabilities | Tool output |
| `cd foreman && npm run check` (before fix, restricted sandbox) | 479/482; summary failure plus two process-table failures | `mp-b-foreman-before.log` |
| Same before fix, with sandbox escalation for process-table access | 481/482; only the reported summary failure | `mp-b-foreman-unsandboxed-before.log` |
| Integration repro command above | Before: 1 passed / 1 failed / 19 skipped; after: 2 passed / 19 skipped | `mp-b-repro.log`, `mp-b-regression-green.log` |
| Same repro with new fixtures before parser change | Fails: expected `tests 10, pass 10, fail 0`, received undefined | `mp-b-regression-red.log` |
| `cd foreman && npm run check` (after fix, with process-table access) | **482/482**, typecheck green, protocol doc current | `mp-b-foreman-check.log` |
| `npm test --prefix tools` | **10/10**, green before and after changes | `mp-b-tools.log` |
| `cd mod && gw build` | BUILD SUCCESSFUL in 3 s; Java compilation from cache; no test sources on this base | `mp-b-mod-build.log` |
| `node artifacts/logs/mp-b-guard-check.mjs` | Actual mac launcher guard: rejects 21.9.0, 22.12.0, 22.17.9; accepts 22.18.0, 22.19.0, 23.0.0, 24.18.0 | `mp-b-guard-check.log` |
| `pwsh -NoProfile -NonInteractive -File artifacts/logs/mp-b-guard-check.ps1` | Full launch.ps1 syntax parses; actual prerequisite block passes the same 7-version matrix with Node lookup mocked | `mp-b-guard-check.log` |
| `node tools/mac.mjs launch --backend sim --no-game --no-foreman --home "$PWD/.agentcraft-home" --profile mp-90 --port 27980 --dev-port 8080` | Exit 0; expected no-Foreman warning; starts no processes | `mp-b-mac-launch.log` |
| Actual mac launcher import with `process.version`/`process.versions.node` set to v22.17.9, same arguments | Exit 1: `Node 22.18 or newer is required (you have v22.17.9)` | `mp-b-mac-rejection.log` |
| `pwsh -NoProfile -NonInteractive -File tools/launch.ps1 -Backend sim -NoGame -NoForeman -DryRun -Home "$PWD/.agentcraft-home" -Profile mp-90 -Port 27981 -DevPort 8081` | Node guard passes; exit 1 later in existing dependency setup: `Get-Item` cannot read hidden `tools/node_modules/.package-lock.json` on macOS | `mp-b-ps-launch.log` |
| `git diff --check` | Green | Tool output |

The initial restricted sandbox explicitly denied `ps` (`operation not permitted`), accounting for the process-table and stopped-turn cleanup failures. The initial unrestricted checks were green; no process code was changed. The tests use fake SDKs, not the real API. No client/server or live Foreman was started. PowerShell's generated `StartupProfileData-NonInteractive` artifact was removed.

## Deviations and open risks

- No in-game QA: this packet does not touch rendering, HQ or agents. No Minecraft signature or source-generation work was needed.
- Actual Windows launcher operation was not verified on Windows. PowerShell 7 on macOS validates its syntax/guard; its unrelated hidden-file failure is documented above and not changed.
- The seven-version matrix simulates version metadata; only Node v24.18.0 actually ran the full suites.
- No multiplayer contract or telemetry changes. The coordinator opened PR #2; the worker did not push or open a PR. Review fixes are left uncommitted under the strict sandbox, as requested.
- **Accepted Node 23 gap (MP-B-7):** engines and both guards accept 23.0–23.5. These end-of-life releases lack default type stripping until 23.6, per the Node documentation cited in the review findings, and can exhibit the audit’s vacuous sim-test pass. The coordinator accepts this gap; only Node 24.18.0 was actually exercised, and no version-range change was made.

## Contract change requests

- **Authorized exceptions used:** `foreman/src/repos.ts` (only `parseTestOutput`), `foreman/test/repos.test.ts`, and exactly two prerequisite lines in `tools/README.md`. No further scope is requested. The coordinator reports that the initial sandbox-guardian review confirmed pass/fail is derived from the exit code and that only `director.ts` consumes `failures`; these facts were also checked against the code for the review fixes.
- **Lockfile request closed (MP-B-5):** coordinator decision is to leave both package-lock files untouched. Their root engine metadata remains `>=22`; the package manifests require `>=22.18`. Initial `npm ci` passed with this mismatch.

## Review fixes

All seven findings were checked against the current code before edits. None required rejecting the reviewer’s claim. The worker left them uncommitted under the strict sandbox; the coordinator re-ran the full suite outside the sandbox (see the end of this section) and committed them.

| Item | Verification and action |
|---|---|
| MP-B-1 | Reproduced the sim’s failing TAGS_V1 tests with `npm test --silent` and explicit TAP under Node 24.18.0. Spec parsing initially yielded 4 entries for 3 failures (duration suffixes plus `failing tests:`); TAP yielded the 3 clean names. Changed only the fallback in `parseTestOutput` to strip terminal `(N.NNNms)` durations and skip that header. Added exact-name fixtures from captured reporter excerpts; the spec fixture failed before the fix and passed after. Full captures: `artifacts/logs/mp-b-failing-spec.log` and `mp-b-failing-tap.log`. |
| MP-B-2 | Reproduced the `[version]` cast failure on release candidates/nightlies in a temporary launcher copy. Replaced it with major/minor parsing and comparison. Blank, malformed and out-of-range numeric output fail with the required message rather than a conversion exception. |
| MP-B-3 | Reproduced the stray temp file/missing summary when PowerShell’s location differed from process cwd. Moved `SummaryJson` resolution before the first possible `Fail`. All rejection cases now write the expected error JSON in the PowerShell location and leave no misplaced temp file. |
| MP-B-4 | Moved summary assertions out of the order-dependent repo integration test into `describe('parseTestOutput', ...)`. It runs independently by name. One summary test plus two failing-output fixtures increase the full count from 482 to 485. |
| MP-B-5 | Confirmed the root engine metadata mismatch; no edits to either package-lock file, as directed. |
| MP-B-6 | Confirmed both stale launcher prerequisites; changed exactly those two lines in `tools/README.md` to Node 22.18+. |
| MP-B-7 | Confirmed code accepts Node 23.0 and recorded the coordinator-accepted 23.0–23.5 capability gap under open risks; no code change. |

Review-fix verification, on Node v24.18.0:

| Command/check | Result | Log under `artifacts/logs/` |
|---|---|---|
| `cd foreman && npx vitest run test/repos.test.ts -t parseTestOutput` | Before fallback fix: 2 pass / 1 fail. After: **3 pass**, 21 unrelated tests skipped. Runs without the repo-registration test. | `mp-b-review-parser-red.log`, `mp-b-review-parser-green.log` |
| `python3 artifacts/logs/mp-b-review-launcher.py` | **11/11 cases pass** after fixes (3/11 before). Executes only a temporary copy of `launch.ps1` and its helper under this worktree’s `artifacts/`, with fake Node, `-NoGame -NoForeman -DryRun`, packet ports 27981/8081 and an isolated home. Tests stable versions, supported/unsupported prereleases, a nightly, malformed/empty output and numeric overflow, with relative summaries and mismatched PowerShell/process directories throughout. Copies are removed afterward. | `mp-b-review-launcher-before.log`, `mp-b-review-launcher-after.log` |
| `cd foreman && npm run check` | Typecheck passes; **483 passed / 2 failed / 485 total**. Only the expected process-table and orphan-cleanup tests fail under the strict sandbox. The command stops before protocol freshness because Vitest returns nonzero. | `mp-b-review-foreman-check.log` |
| `cd foreman && npm run check:protocol-doc` | Protocol document current; run separately because the full check stopped at the two sandbox failures. | `mp-b-review-protocol.log` |
| `npm test --prefix tools` | **10/10 passed**. | `mp-b-review-tools.log` |
| `git diff --check` | Clean. | Tool output |

The two Foreman failures are `test/proc.test.ts > reads the real process table and kills only a process that is still the same one` (process table undefined) and `test/claude-handoff.test.ts > stop kills what the stopped turn started (orphans of the CLI too)` (explicit log: `could not read the process table`). The sandbox denies `ps`; those tests and their implementation were not changed. A green full-suite result requires the coordinator’s unrestricted rerun. Windows PowerShell 5.1 and actual Node 23 runtimes were not available for verification; launcher probing used installed PowerShell 7 and mocked version output. The real launcher was not run during these fixes.

Proposed commit subject: `Fix test-output parsing and launcher validation after MP-B review`.

**Coordinator verification (outside the sandbox, macOS, Node v24.18.0):** `npm run check` in `foreman/` is **485/485** with typecheck and protocol freshness green; `npm test --prefix tools` is **10/10**; `git diff --check` is clean. The new `launch.ps1` guard logic, run on its own under `pwsh`, accepts v22.18.0, v22.18.0-rc.1, v23.0.0, v24.18.0 and a v25 nightly, and rejects v21.9.0, v22.17.9, non-version output and empty output with the required message. Still not verified: Windows PowerShell 5.1 and a real Windows launch.
