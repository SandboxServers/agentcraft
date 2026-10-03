# MP-B Worknote: Node baseline and test summaries

> Type: reference. Audience: the multiplayer coordinator and packet workers.
> Companions: [work packets](../work-packets.md), [audit](../audit.md).

## Contract

- **Packet:** MP-B, baseline; packet number 90, branch `mp/MP-B-baseline`.
- **Base:** `main` @ `b40768d`.
- **Machine:** macOS, Apple Silicon, Node v24.18.0, npm 12.0.1.
- **Scope:** matrix-owned engine declarations, launcher Node checks and README prerequisite; coordinator-authorized exception for summary parsing and its existing test.
- **Review requirement:** the parser lives in **`foreman/src/repos.ts`**. This change needs **agent-sandbox-guardian** review before merge. Independent reviews are left to the coordinator per `SWARM.md`.

## What was built

| File | Change |
|---|---|
| `foreman/package.json`, `tools/package.json` | Require Node `>=22.18`. |
| `tools/launch.ps1` | Compare the full Node version against 22.18.0 before option setup, directory creation or npm installation; report the installed version on rejection. |
| `tools/mac.mjs` | Compare major/minor versions before setup; allow later major versions; report the installed version on rejection. |
| `README.md` | Quick-start prerequisite is Node 22.18+. |
| `foreman/src/repos.ts` | One-line summary regex accepts TAP `#` and spec-reporter `ℹ` prefixes. |
| `foreman/test/repos.test.ts` | Existing integration test also asserts exact summary parsing for both prefixes, retaining 482 tests. |

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

## What was run

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

The restricted sandbox explicitly denies `ps` (`operation not permitted`), accounting for the process-table and stopped-turn cleanup failures. The unrestricted checks are green; no process code was changed. The tests use fake SDKs, not the real API. No client/server or live Foreman was started. PowerShell's generated `StartupProfileData-NonInteractive` artifact was removed.

## Deviations and open risks

- No in-game QA: this packet does not touch rendering, HQ or agents. No Minecraft signature or source-generation work was needed.
- Actual Windows launcher operation was not verified on Windows. PowerShell 7 on macOS validates its syntax/guard; its unrelated hidden-file failure is documented above and not changed.
- The seven-version matrix simulates version metadata; only Node v24.18.0 actually ran the full suites.
- No multiplayer contract or telemetry changes. No push, PR, Docker, SSH, colo access or release.

## Contract change requests

- **Authorized exception used:** `foreman/src/repos.ts` and `foreman/test/repos.test.ts`, summary parsing only. Coordinator should schedule agent-sandbox-guardian and upstream-sync-steward review for this upstream file exception, plus the normal test reviewer.
- **Requested follow-up:** authorize/synchronize the root package engine metadata in `foreman/package-lock.json` and `tools/package-lock.json` (`packages[""].engines.node`, currently `>=22`, should become `>=22.18`). These lockfiles are outside MP-B's matrix and outside the summary-only exception, so they remain untouched. `npm ci` and both required suites pass with the existing lockfiles.
