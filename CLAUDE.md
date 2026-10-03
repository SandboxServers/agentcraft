# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

AgentCraft: a team of Claude agents works on a real git repo, and a Minecraft mod renders that
work as a studio you walk around in. There are two processes that talk over a localhost WebSocket:

- **Foreman** (`foreman/`, Node ≥ 22.18 + TypeScript, Claude Agent SDK; older Node makes the sim's
  `.ts` tests pass vacuously and `test/ws.test.ts` fail) owns **all** state and runs the
  agents. It keeps working while the game is closed and survives restarts.
- **Mod** (`mod/`, Fabric, Minecraft **26.3**, Java 25) is only a view plus controls. It draws the
  Foreman's state and sends back what the user decides. "The game is only a view" is a core invariant.

Also: `tools/` (Node launchers, DevBridge CLI, screenshot QA), `assets-src/` (Python generators for
every texture/skin/model/GUI sprite), `docs/` (protocol, QA, spec, visual bar), `deploy/` (the
dedicated-server container image and colo compose file).

This checkout is the `SandboxServers/agentcraft` fork (`origin`) of `blendi-remade/agentcraft`
(`upstream`). Work via PRs on the fork; never push to `upstream`. The multiplayer campaign lives in
`docs/multiplayer/` (see "Multiplayer fork" below), and the project's agent cast in `.claude/agents/`.

## Commands

```sh
# Foreman (cd foreman; npm ci first)
npm test                                   # vitest, all tests
npx vitest run test/policy.test.ts         # one file
npx vitest run -t "name of test"           # one test by name
npm run typecheck                          # tsc --noEmit
npm run check                              # typecheck + tests + protocol doc freshness (run before committing)
npm run gen:protocol-doc                   # regenerate docs/protocol.md after changing src/protocol.ts
npm run start -- --backend sim --reset --speed 2          # simulated team, no API usage
npm run start -- --backend claude --repo C:\path\to\repo  # real agents
npm run tui                                # terminal client that connects exactly like the mod

# Mod (cd mod). Always isolate Gradle with a GRADLE_USER_HOME at <repo>/.gradle-home
#   (mod/DEV.md hard-codes C:/Projects/agentcraft/... from the original author's machine; use this checkout's path)
$env:GRADLE_USER_HOME="$PWD\..\.gradle-home"; .\gradlew.bat build      # -> mod/build/libs/agentcraft-0.1.0.jar
.\gradlew.bat runClient                    # dev client, auto-creates/loads the "AgentCraft HQ" world
.\gradlew.bat mcSources                    # decompiled MC sources into mod/build/mcsrc (grep these for real APIs)
.\gradlew.bat --stop

# Tools (npm ci --prefix tools first)
npm test --prefix tools                    # node --test
tools\launch.ps1 -Backend sim              # Foreman + game together (Windows); tools\stop.ps1 to stop
node tools/mac.mjs launch --backend sim    # macOS equivalent; node tools/mac.mjs stop
node tools/devcli.mjs state                # DevBridge CLI: state, camera, shot, cmd, wait, quit ...
node tools/foremancli.mjs status           # Foreman CLI
node tools/qa.mjs --home <repo>/.agentcraft-home   # 10-shot screenshot QA (docs/QA.md)

# Server container (from the repo root; from Git Bash prefix docker exec with MSYS_NO_PATHCONV=1)
docker build -f deploy/Dockerfile -t agentcraft-server:dev .
docker run -d --name ac -e EULA=TRUE -e RCON_PASSWORD=dev -v ac-data:/data agentcraft-server:dev

# Assets (one-time venv: py -3.9 -m venv assets-src\.venv; pip install -r assets-src\requirements.txt)
assets-src\.venv\Scripts\python assets-src\build.py     # regenerate assets-src/out
python assets-src\sync.py                               # copy into mod/src/main/resources/assets (--check, --dry-run)
```

## Architecture

### Foreman (`foreman/src`)

`foreman.ts` is the transport-agnostic core: it owns state, applies user intents, and exposes
primitives to a `Backend` (interface near the top of `foreman.ts`). Collaborators: `taskgraph.ts`
(tasks, deps, statuses todo/doing/review/done/blocked/cancelled), `bus.ts` (messages, feed),
`memory.ts` (markdown notes, shared and per agent), `decisions.ts` (question | permission | merge;
an answer wakes the agent), `repos.ts` (repos, per-task worktrees, diffs, guarded merges),
`store.ts` (atomic `state.json` + JSONL logs under `AGENTCRAFT_HOME/<profile>`), `server.ts`
(WebSocket on 127.0.0.1:7878, refuses any Origin header and non-loopback Host).

Backends: `agents/claude/` (Agent SDK sessions; agent tools are an in-process MCP server
`agentcraft` in `tools.ts`; auth in `auth.ts`) and `agents/sim/` (a deterministic 22-beat
`scenario.ts` doing real git edits/tests/merges; drives demos, QA and most tests without API use).

**Protocol:** `src/protocol.ts` (zod) is the source of truth. `docs/protocol.md` is generated from it
and `npm run check` fails if stale. The mod mirrors it in `mod/src/client/java/dev/agentcraft/client/foreman/Protocol.java`
— change both sides together.

**Safety is the product.** Read the "Permissions" and "Safety guarantees" sections of
`foreman/README.md` before touching `policy.ts`, `gitsafety.ts` or `repos.ts`:
- Agents work only in worktrees on `agentcraft/<agent>/<task>` branches; the user's checkout changes
  only on an approved merge (built off-tree with `merge-tree`/`commit-tree`, applied `--ff-only`).
- Nothing is ever pushed: `policy.ts` (a small shell parser classifying every Bash call) denies push
  in any spelling, and `gitsafety.ts` env makes git itself refuse every transport, signing and
  walking out of the worktree. Anything the policy can't verify must ask.
- Tests set `AGENTCRAFT_USER_NAME=Alex` (`test/setup.ts`) for deterministic output; the SDK is faked
  in `claude-*.test.ts` (see `test/helpers.ts`).

### Mod (`mod/`)

Loom split source sets: `src/main` (common: blocks, block entities, agent entity type, `hq/` HQ
builder, `layout/Anchors`, `world/HqWorld`, `/agentcraft` commands) and `src/client` (everything else).
Client features are packages under `dev.agentcraft.client.*`, each with an `init()` wired by one line
in `ClientFeatures.java`. `mod/FEATURES.md` is the feature map and package ownership table; `mod/DEV.md`
has build/run details, the DevBridge command reference, and hard-won gotchas.

- **Foreman link** (`client/foreman`): `ForemanLink` (java.net.http WS, reconnects forever) feeds
  `ForemanState`, which is applied and read **on the client/render thread only**. `Foreman` is the facade.
- **Agents are client-only entities** ("architecture A", chosen by measurement over server-side mobs):
  `AgentManager` keeps a `ClientAgentEntity` (negative id, never saved) per Foreman agent, routed by
  `GridPathfinder` and `StationAssigner`; `EntityRenderDispatcherMixin` routes them to `AgentRenderer`.
- **Anchors** (`layout/`) are the named positions (desks, stations, `cam_*` camera spots) published by
  the HQ builder (`StudioHqBuilder`, default `studio`) and saved in the world folder. Agents, QA scenes
  and `dev.camera {anchor}` all depend on them.
- World blocks driven by Foreman state (lamps, podium, merge station) go through `client/world/ServerTasks`
  and `client/hq/HqWorldDriver` on the integrated server.
- **DevBridge** (`client/dev`): a localhost WebSocket inside the client (port 7879) used by every tool
  for camera, screenshots, commands and state. Request fields go through `Fields` (strict typing; a NaN
  camera hangs vanilla forever). `id`, `type`, `timeoutMs` are reserved field names.

Minecraft 26.3 is unobfuscated (Mojang names) and very different from 1.21: SDL3 instead of GLFW,
extract/render split with `*RenderState`, `mc.gui.setScreen`, `Identifier` not `ResourceLocation`.
**Do not code from 1.21-era memory** — grep `mod/build/mcsrc` (`gradlew mcSources`) for real signatures.

### Assets

Never hand-edit `mod/src/main/resources/assets` — generate in `assets-src/` and run `sync.py`
(it owns only generated files, tracked in `.art-sync.json`, and merges `lang/en_us.json` keys so
mod-added lang keys are safe to edit in `mod/`). Builds are byte-deterministic (Pillow pinned).

## Working conventions

- Verify visual changes in game: `dev.camera {anchor:"cam_..."}` + `dev.screenshot` (or
  `tools/qa.mjs`), then actually Read the PNG before claiming how something looks.
- QA/tests use the project home `<repo>/.agentcraft-home`, never `~/.agentcraft`. One Foreman per
  profile and one game per checkout; parallel runs need their own `--profile` and port pair.
- Git Bash rewrites arguments starting with `/` into Windows paths (`/status` → `C:/Program Files/Git/status`).
  Use `MSYS_NO_PATHCONV=1`, PowerShell, or omit the slash (`devcli cmd "agentcraft hq"`).
- Generated outputs live in gitignored `artifacts/` (shots, logs, run files).

## Multiplayer fork

The goal of this fork is a shared, hosted multiplayer world where each player drives their own
local Foreman. The campaign is in `docs/multiplayer/`:

- `README.md`: decisions, packet status and the parallelization plan.
- `audit.md`: the spike's evidence rows (`A-nn`).
- `work-packets.md`: the frozen MP-F contract, the file-ownership matrix and the waves.
- `handoffs/session-resume.md`: where to pick up.
- `deploy.md`: the release and colo runbook.

Rules:
- **A packet edits only the files in its row of the ownership matrix.** Contract changes go through the
  coordinator, who is the single writer of the campaign README and work-packets. Packets write
  `worknotes/MP-xx.md`.
- Keep architecture A and "the game is only a view"; the server relays a redacted public subset of
  each owner's state (`PublicStudioState`: the record *is* the allowlist) and never hosts agents,
  code or credentials. Private data (diffs, decisions, console, memory, logs) only ever goes to the
  viewer's own Foreman. Renderers in a remote studio read only the public state.
- Multiplayer stays behind a mode (`MpMode`, server config `enabled`, default off) and mostly in new
  packages, so singleplayer is unchanged and merging upstream stays cheap. Packets that touch
  rendering, the HQ or agents compare `tools/qa.mjs` against the MP-F baseline.
- Use your packet's port block (work-packets.md, Dispatch rules), never the default ports or
  `~/.agentcraft`.
- **This fork is public: never commit the colo server's address, hostnames, SSH alias or credentials.**
  The colo pulls releases (GHCR `latest-prerelease` + a scoped Watchtower); nothing pushes to it.
- Tell the owner before long builds or anything touching the colo; confirm before deploying.
