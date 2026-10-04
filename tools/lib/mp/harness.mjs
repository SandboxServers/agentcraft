import { spawn } from 'node:child_process';
import { randomUUID } from 'node:crypto';
import fs from 'node:fs';
import net from 'node:net';
import path from 'node:path';
import { makePlan } from './config.mjs';
import { gradleInvocation, javaArguments, javaArgfile, javaEnvironment, seedGameDirs } from './launch.mjs';
import { clientCommand, readLogTail, waitForClients } from './index.mjs';
import { ownsPort, processInventory, processStamp, readJson, recoverProcesses, sameProcess, saveJson, sleep,
  startProcess, stopProcess } from './processes.mjs';

export function portOpen(port) {
  return new Promise(resolve => {
    const socket = net.connect({ host: '127.0.0.1', port });
    const finish = value => { socket.destroy(); resolve(value); };
    socket.setTimeout(500);
    socket.once('connect', () => finish(true));
    socket.once('timeout', () => finish(false));
    socket.once('error', () => finish(false));
  });
}

export function loadState(plan) {
  const state = readJson(plan.file);
  if (state && (state.version !== 1 || state.slot !== plan.slot || state.root !== plan.root ||
    !Array.isArray(state.processes) || !Array.isArray(state.clients))) throw new Error(`invalid harness record: ${plan.file}`);
  return state;
}

export function summarize(plan, state, inventory, recover = recoverProcesses) {
  const running = (state?.processes ?? []).map(entry => {
    const processes = recover(entry, plan.root, inventory);
    return { kind: entry.kind, client: entry.client ?? null, log: entry.log, processes, pid: entry.pid ?? null,
      primaryRunning: processes.some(p => p.pid === entry.pid || p.role === 'primary') };
  });
  const alive = (kind, id) => running.some(entry => entry.kind === kind && entry.client === (id ?? null) && entry.primaryRunning);
  const source = state ?? plan;
  const anyAlive = running.some(entry => entry.processes.length);
  const allAlive = alive('server') && source.clients.every(client => alive('client', client.id) && alive('foreman', client.id));
  return { version: 1, slot: plan.slot, phase: anyAlive ? (allAlive && state?.phase === 'ready' ? 'ready' : 'partial') : 'stopped',
    home: source.home, profile: source.profile, stateFile: plan.file, error: state?.error ?? null,
    server: { ...source.server, running: !!alive('server') },
    clients: source.clients.map(client => ({ ...client, running: !!alive('client', client.id),
      foremanRunning: !!alive('foreman', client.id), state: state?.states?.[client.id] ?? null })),
    statesCapturedAt: state?.statesCapturedAt ?? null, processes: running };
}

async function withLock(plan, fn) {
  fs.mkdirSync(plan.dir, { recursive: true });
  const lock = path.join(plan.dir, 'control.lock');
  try { fs.mkdirSync(lock); }
  catch (error) {
    if (error.code !== 'EEXIST') throw error;
    const lease = readJson(path.join(lock, 'owner.json'));
    if (sameProcess(lease, plan.root) || (!lease && Date.now() - fs.statSync(lock).mtimeMs < 2000)) {
      throw new Error('another up/down is active for this slot; wait or interrupt that launcher');
    }
    fs.rmSync(lock, { recursive: true });
    // An exclusive mkdir arbitrates two workers trying to recover the same stale lock.
    fs.mkdirSync(lock);
  }
  try {
    saveJson(path.join(lock, 'owner.json'), { pid: process.pid, startTime: processStamp(process.pid, plan.root) });
    return await fn();
  }
  finally { fs.rmSync(lock, { recursive: true, force: true }); }
}

export async function shutdown(plan, state, progress = () => {}, {
  inventory = processInventory, recover = recoverProcesses, portOwned = ownsPort,
  command = clientCommand, stop = stopProcess,
} = {}) {
  if (!state) return;
  state.phase = 'stopping';
  saveJson(plan.file, state);
  const failures = [];
  const table = inventory(plan.root);
  // Clients, Foremen, dedicated server, then any unfinished build. No shared daemon killing.
  const entries = [...state.processes].sort((a, b) =>
    ['client', 'foreman', 'server', 'build'].indexOf(a.kind) - ['client', 'foreman', 'server', 'build'].indexOf(b.kind));
  for (const entry of entries) {
    const records = recover(entry, plan.root, table).map(({ pid, startTime, groupPid, members, role }) => ({ pid, startTime, groupPid, members, role }));
    if (!records.length) continue;
    entry.recoveredProcesses = records;
    saveJson(plan.file, state);
    progress(`Stopping ${entry.kind}${entry.client ? ` ${entry.client.toUpperCase()}` : ''}...`);
    if (entry.kind === 'client') {
      const client = state.clients.find(c => c.id === entry.client);
      if (records.some(record => portOwned(record, client.devPort, plan.root))) {
        try { await command(state, client.id, 'dev.quit', {}, { timeoutMs: 5000 }); }
        catch { /* a stalled/disconnected bridge falls back to the recorded PID */ }
      }
    }
    for (const record of records) {
      try { await stop(record, plan.root, { timeoutMs: entry.kind === 'server' ? 30_000 : 5000,
        consoleRecord: entry.wrapper ?? record, persist: () => saveJson(plan.file, state) }); }
      catch (error) { failures.push(error.message); }
    }
  }
  const left = summarize(plan, state, inventory(plan.root), recover).processes.flatMap(entry => entry.processes);
  state.phase = left.length ? 'partial' : 'stopped';
  saveJson(plan.file, state);
  if (left.length || failures.length) throw new Error(`cleanup incomplete: ${[...failures, ...left.map(p => `PID ${p.pid}`)].join('; ')}`);
}

async function prepare(plan, state, opt, progress, check) {
  const output = path.join(plan.dir, 'launch.json');
  if (!opt['no-build']) {
    progress('Preparing Loom launch metadata (no game tasks)...');
    const spec = gradleInvocation(plan.root, output, opt['gradle-command']);
    const entry = { kind: 'build', marker: `ac-mp-${randomUUID()}`, log: path.join(plan.dir, 'build.log') };
    state.processes.push(entry);
    saveJson(plan.file, state);
    const fd = fs.openSync(entry.log, 'w');
    let child;
    try { child = spawn(spec.command, spec.args, { cwd: spec.cwd, env: process.env,
      windowsHide: true, stdio: ['ignore', fd, fd] }); }
    finally { fs.closeSync(fd); }
    const completion = new Promise((resolve, reject) => {
      child.once('error', reject);
      child.once('exit', code => code === 0 ? resolve() : reject(new Error(`Gradle preparation exited ${code}; see ${entry.log}`)));
    });
    entry.pid = child.pid; entry.startTime = processStamp(child.pid, plan.root);
    saveJson(plan.file, state);
    // Cancellation gets checked while Gradle runs, without changing shared daemon settings.
    const timer = setInterval(() => { try { check(); } catch { child.kill(); } }, 500);
    try { await completion; } finally { clearInterval(timer); }
    check();
  }
  const launch = readJson(output);
  if (!launch?.client || !launch?.server) throw new Error(`missing launch metadata: ${output}; omit --no-build or export with gw`);
  return launch;
}

async function waitUntil(test, entry, plan, timeoutMs, check) {
  const deadline = Date.now() + timeoutMs;
  while (Date.now() < deadline) {
    check();
    if (!sameProcess(entry, plan.root)) throw new Error(`${entry.kind} exited; see ${entry.log}`);
    if (await test()) return;
    await sleep(500);
  }
  throw new Error(`${entry.kind} startup timed out; see ${entry.log}`);
}

export async function runHarness(root, opt, { progress = () => {}, check = () => {} } = {}) {
  const plan = makePlan(root, opt);
  if (opt.action === 'status') return summarize(plan, loadState(plan), processInventory(root));
  return withLock(plan, async () => {
    const previous = loadState(plan);
    if (opt.action === 'down') {
      await shutdown(plan, previous, progress);
      return summarize(plan, loadState(plan), processInventory(root));
    }
    if (summarize(plan, previous, processInventory(root)).processes.some(entry => entry.processes.length)) {
      throw new Error('this slot still has harness processes; run down before up');
    }
    const state = { ...plan, phase: 'starting', processes: [], states: {} };
    saveJson(plan.file, state);
    try {
      check();
      const env = javaEnvironment();
      const ports = [plan.server.port, ...plan.clients.flatMap(c => [c.devPort, c.foremanPort])];
      for (const port of ports) if (await portOpen(port)) throw new Error(`port ${port} is already in use; left its owner alone`);
      for (const [dir, dependency] of [['foreman', 'tsx'], ['tools', 'ws']]) {
        if (!fs.existsSync(path.join(root, dir, 'node_modules', dependency))) throw new Error(`run npm ci --prefix ${dir} first`);
      }
      const launch = await prepare(plan, state, opt, progress, check);
      seedGameDirs(plan);
      const persist = () => saveJson(plan.file, state);
      const start = async (kind, client, spec) => {
        check();
        const entry = { kind, client: client?.id, marker: `ac-mp-${randomUUID()}`, log: spec.log };
        state.processes.push(entry);
        fs.writeFileSync(spec.log, '');
        if (kind === 'foreman') spec.args.unshift(`--title=${entry.marker}`);
        else {
          const argfile = path.join(plan.dir, `${kind}-${client?.id ?? 'server'}.args`);
          fs.writeFileSync(argfile, javaArgfile(spec.args));
          spec.args = [`-Dagentcraft.mp.run=${entry.marker}`, `@${argfile}`];
        }
        return startProcess(spec, entry, state, persist);
      };
      const java = env.JAVA_HOME ? path.join(env.JAVA_HOME, 'bin', process.platform === 'win32' ? 'java.exe' : 'java') : 'java';
      progress(`Starting server :${plan.server.port} (${plan.server.heap} heap)...`);
      const server = await start('server', null, { command: java,
        args: javaArguments(launch.server, plan.server.heap), cwd: plan.server.gameDir, log: plan.server.log, env });
      await waitUntil(() => readLogTail(plan.server.log).includes('Done ('), server, plan, opt.timeout * 1000, check);
      for (const client of plan.clients) {
        progress(`Starting Foreman ${client.id.toUpperCase()} :${client.foremanPort}...`);
        const fm = await start('foreman', client, { command: process.execPath,
          args: ['--import', 'tsx', 'src/main.ts', '--backend', opt.backend, '--home', plan.home,
            '--profile', client.profile, '--port', String(client.foremanPort), '--no-notify'],
          cwd: path.join(root, 'foreman'), log: client.foremanLog, env });
        await waitUntil(() => portOpen(client.foremanPort), fm, plan, opt.timeout * 1000, check);
        progress(`Starting client ${client.id.toUpperCase()} (${client.heap} heap, ${client.gameDir})...`);
        await start('client', client, { command: java,
          args: javaArguments(launch.client, client.heap, client, plan.server.port), cwd: client.gameDir, log: client.log,
          env: { ...env, AGENTCRAFT_PORT: String(client.foremanPort), AGENTCRAFT_DEV_PORT: String(client.devPort),
            AGENTCRAFT_HOME: plan.home, AGENTCRAFT_PROFILE: client.profile, AGENTCRAFT_PLAYER: client.username,
            AGENTCRAFT_DEV: '1', AGENTCRAFT_FOREMAN: '1', AGENTCRAFT_AUTOWORLD: '0',
            AGENTCRAFT_MUTE: '1', AGENTCRAFT_FOCUS: '0', AGENTCRAFT_NOTIFY: '0' } });
      }
      let notice = 0;
      state.states = await waitForClients(plan, { timeoutMs: opt.timeout * 1000,
        check: () => {
          check();
          for (const entry of state.processes.filter(p => p.kind !== 'build')) {
            if (!sameProcess(entry, root)) throw new Error(`${entry.kind} ${entry.client ?? ''} exited; see ${entry.log}`);
          }
        }, onWait: () => { if (Date.now() - notice > 10_000) { notice = Date.now(); progress('Waiting for remote worlds and Foreman links...'); } } });
      state.phase = 'ready';
      state.statesCapturedAt = new Date().toISOString();
      persist();
      return summarize(plan, state, processInventory(root));
    } catch (error) {
      state.error = error.message;
      saveJson(plan.file, state);
      try { await shutdown(plan, state, progress); }
      catch (cleanup) { throw new Error(`${error.message}; ${cleanup.message}`); }
      throw error;
    }
  });
}
