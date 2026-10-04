import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import net from 'node:net';
import os from 'node:os';
import path from 'node:path';
import { readServerEvents } from '../lib/mp/index.mjs';
import { makePlan, parseOptions } from '../lib/mp/config.mjs';
import { prepare, requireFreePorts, shutdown, withLock } from '../lib/mp/harness.mjs';
import { gameSpec, gradleInvocation, seedGameDirs } from '../lib/mp/launch.mjs';
import { rconStop, readJson, recoverProcesses, saveJson, stopProcess } from '../lib/mp/processes.mjs';

function temporary(t) {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'mp-pr-review-'));
  t.after(() => fs.rmSync(dir, { recursive: true, force: true }));
  return dir;
}

test('PR1: down and status ignore launch-only environment defaults; explicit flags stay validated', () => {
  const root = path.resolve('fixture');
  const env = { AGENTCRAFT_MP_CLIENT_HEAP: '128G', AGENTCRAFT_MP_SERVER_HEAP: 'stale', AGENTCRAFT_MP_GRADLE: 'gw-raw' };
  for (const action of ['down', 'status']) {
    const opt = parseOptions([action, '--slot', '92'], root, env);
    assert.equal(opt['gradle-command'], undefined);
    assert.equal(makePlan(root, opt).file, makePlan(root, parseOptions([action, '--slot', '92'], root, {})).file);
    assert.throws(() => parseOptions([action, '--slot', '92', '--client-heap', '128G'], root, {}), /heap must be/);
  }
  assert.throws(() => parseOptions(['up', '--slot', '92'], root, env), /heap must be/);
});

test('PR2: a log replaced by a later up restarts the cursor, even once it has regrown past the old offset', t => {
  const local = { server: { debugLog: path.join(temporary(t), 'debug.log') } };
  const log = local.server.debugLog;
  const lines = (run, count) => Array.from({ length: count }, (_, i) => `[10:0${run}:00] event=presence run=${run} n=${i}\n`).join('');
  const drain = cursor => {
    const events = [];
    for (;;) {
      const chunk = readServerEvents(local, { offset: cursor.offset, generation: cursor.generation, maxBytes: 1024 });
      events.push(...chunk.events);
      if (chunk.offset === cursor.offset && chunk.generation === cursor.generation) return { cursor, events };
      cursor = chunk;
    }
  };
  fs.writeFileSync(log, lines(1, 200));
  let { cursor, events } = drain({ offset: 0 });
  assert.equal(events.length, 200); // chunked reads of one log never restart
  fs.appendFileSync(log, lines(1, 1));
  ({ cursor, events } = drain(cursor));
  assert.equal(events.length, 1); // growth continues from the cursor

  fs.writeFileSync(log, lines(2, 300)); // truncated in place, already longer than the old offset
  assert.ok(fs.statSync(log).size > cursor.offset);
  ({ cursor, events } = drain(cursor));
  assert.deepEqual([events.length, events[0]?.fields.run, events[0]?.fields.n], [300, '2', '0']);

  fs.renameSync(log, `${log}.1`); // rolled over, as log4j does on startup
  fs.writeFileSync(log, lines(3, 400));
  ({ cursor, events } = drain(cursor));
  assert.deepEqual([events.length, events[0]?.fields.run, events[0]?.fields.n], [400, '3', '0']);
  assert.throws(() => readServerEvents(local, { offset: cursor.offset, generation: 'run-3' }), /invalid log generation/);
});

test('PR3: a launcher crash between spawning the build and recording its PID is recoverable from the marker', async t => {
  const dir = temporary(t);
  const opt = parseOptions(['up', '--slot', '92'], dir, {});
  const local = makePlan(dir, opt);
  const state = { ...local, processes: [] };
  fs.mkdirSync(local.dir, { recursive: true });
  let crashed, line;
  await assert.rejects(prepare(local, state, opt, () => {}, () => {}, {
    spawnBuild: (_command, args) => {
      crashed = readJson(local.file).processes[0]; // all a later down knows if the launcher dies here
      line = args.join(' ');
      return { pid: 42 };
    }, stamp: () => 'build-start', wait: async () => { throw new Error('launcher died'); },
  }), /launcher died/);
  assert.equal('pid' in crashed, false);
  // gradlew execs Java with its arguments; the shared daemon never receives them on its command line.
  const inventory = [
    { pid: 50, groupPid: 50, command: `java -classpath gradle-wrapper.jar org.gradle.wrapper.GradleWrapperMain ${line}` },
    { pid: 51, groupPid: 51, command: `grep ${crashed.marker}` },
    { pid: 52, groupPid: 52, command: 'java org.gradle.launcher.daemon.bootstrap.GradleDaemon' },
  ];
  assert.deepEqual(recoverProcesses(crashed, dir, inventory, pid => `start-${pid}`),
    [{ pid: 50, startTime: 'start-50', role: 'primary', groupPid: 50 }]);
  assert.deepEqual(recoverProcesses({ ...crashed, kind: 'client' }, dir, inventory, pid => `start-${pid}`), []);
});

const slot = (t, ...args) => {
  const dir = temporary(t);
  const opt = parseOptions(['up', '--slot', '92', ...args], dir, {});
  return { dir, opt, local: makePlan(dir, opt) };
};

test('PR4: two controllers taking over the same stale lock: exactly one holds it', async t => {
  const { local } = slot(t);
  let boot = 'boot-1', holders = 0, second;
  const release = [];
  const hold = () => { holders++; return new Promise(resolve => release.push(resolve)); };
  void withLock(local, () => new Promise(() => {}), { stamp: () => boot }); // a controller that dies holding the lock
  boot = 'boot-2'; // its PID no longer has the recorded start time
  const first = withLock(local, hold, { stamp: () => {
    // The second controller judges the same lock stale and completes its takeover in between.
    second ??= withLock(local, hold, { stamp: () => boot });
    return boot;
  } });
  await assert.rejects(Promise.race([first, new Promise(resolve => setImmediate(resolve))]), /another up\/down is active/);
  assert.equal(holders, 1);
  release[0]();
  await second;
  // A claimant that stalled before recording its owner is passed over; when its record lands
  // while the next controller is claiming, that controller reads the lock again and stands down.
  const stalled = path.join(local.dir, 'control.lock', '7');
  fs.mkdirSync(stalled);
  fs.utimesSync(stalled, 0, 0);
  await assert.rejects(withLock(local, async () => { holders++; }, { stamp: () => {
    saveJson(path.join(stalled, 'owner.json'), { pid: process.pid, startTime: boot });
    return boot;
  } }), /another up\/down is active/);
  assert.deepEqual([holders, fs.readdirSync(path.dirname(stalled))], [1, ['7']]);
  boot = 'boot-3'; // and once that claimant is gone too, the lock can be taken again
  assert.equal(await withLock(local, async () => 'free', { stamp: () => boot }), 'free');
});

test('PR5: rconStop logs in, then sends stop, one packet per read as the server requires', async t => {
  const seen = [];
  const server = net.createServer(socket => socket.on('error', () => {}).on('data', data => {
    assert.equal(data.readInt32LE(0), data.length - 4); // RconClient drops a client whose read is not one whole packet
    const [id, type, body] = [data.readInt32LE(4), data.readInt32LE(8), data.toString('utf8', 12, data.length - 2)];
    seen.push([type, body]);
    const reply = Buffer.alloc(14);
    [10, type === 3 && body !== 'secret' ? -1 : id, type === 3 ? 2 : 0].forEach((value, i) => reply.writeInt32LE(value, 4 * i));
    socket.write(reply);
  }));
  await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
  t.after(() => server.close());
  await rconStop(server.address().port, 'secret');
  await assert.rejects(rconStop(server.address().port, 'wrong'), /refused the login/);
  assert.deepEqual(seen, [[3, 'secret'], [2, 'stop'], [3, 'wrong']]);
});

test('PR5: a Windows server is asked to stop, waited for, and killed only after that', async t => {
  for (const exits of [true, false]) {
    let live = true, time = 0;
    const calls = [];
    const forced = await stopProcess({ pid: 42, startTime: 'stamp' }, 'root', { kind: 'server', platform: 'win32',
      requested: true, timeoutMs: 300, now: () => time, stamp: () => live ? 'stamp' : null,
      delay: async ms => { time += ms; live &&= !exits; },
      shell: (_root, script) => { calls.push(script.split(' ')[0]); live = false; return ''; } });
    assert.deepEqual([forced, calls, time], exits ? [false, [], 100] : [true, ['Stop-OwnTree'], 300]);
  }
  const { dir, local } = slot(t, '--clients', '1');
  for (const [platform, owned, accepted, requested] of [['win32', true, true, true], ['win32', true, false, false],
    ['win32', false, true, false], ['linux', true, true, false]]) {
    const state = { ...local, rcon: { port: 4242, password: 'secret' }, processes: [{ kind: 'server', pid: 100, startTime: 'server' }] };
    const asked = [], notes = [];
    await shutdown(local, state, note => notes.push(note), { platform, inventory: () => [],
      recover: entry => entry.stopped ? [] : [{ pid: entry.pid, startTime: entry.startTime }],
      portOwned: (_record, port) => owned && port === 4242,
      serverStop: async (...args) => { asked.push(args); if (!accepted) throw new Error('RCON refused the login'); },
      stop: async (_record, _root, options) => {
        assert.equal(options.requested, requested);
        state.processes[0].stopped = true;
        return !requested; // without a request there is nothing to wait for: straight to the kill
      } });
    assert.deepEqual(asked, platform === 'win32' && owned ? [[4242, 'secret']] : []);
    assert.equal(notes.some(note => /killed without a final save/.test(note)), !requested);
  }
  fs.mkdirSync(path.join(dir, 'mod', 'run-template'), { recursive: true });
  fs.writeFileSync(path.join(dir, 'mod', 'run-template', 'options.txt'), 'maxFps:120\n');
  const properties = rcon => { seedGameDirs(local, rcon); return fs.readFileSync(path.join(local.server.gameDir, 'server.properties'), 'utf8'); };
  assert.match(properties({ port: 4242, password: 'secret' }), /\nenable-rcon=true\nrcon\.port=4242\nrcon\.password=secret\nbroadcast-rcon-to-ops=false\n/);
  assert.match(properties(), /\nmax-players=2\nenable-rcon=false\nenable-query=false\n/);
});

test('PR6: a custom .bat/.cmd Gradle command runs through PowerShell on Windows; ports are checked again after Gradle', async t => {
  const { dir, opt, local } = slot(t);
  fs.mkdirSync(path.join(dir, 'mod'));
  fs.writeFileSync(path.join(dir, 'mod', 'gradlew.bat'), '');
  for (const [command, call] of [['gradlew.bat', "& '.\\gradlew.bat' '-I' "], ['C:\\swarm tools\\gw.CMD', "& 'C:\\swarm tools\\gw.CMD' '-I' "]]) {
    const spec = gradleInvocation(dir, 'out.json', command, 'win32', 'ac-mp-run');
    assert.equal(spec.command, 'powershell.exe'); // spawning the batch file itself is EINVAL on current Node
    assert.ok(spec.args.at(-1).startsWith(call) && spec.args.at(-1).endsWith(" '-PmpRun=ac-mp-run' '--no-configuration-cache' '--console=plain'; exit $LASTEXITCODE"));
  }
  assert.equal(gradleInvocation(dir, 'out.json', 'gw-raw', 'win32').command, 'gw-raw');
  assert.equal(gradleInvocation(dir, 'out.json', 'gradlew.bat', 'linux').command, 'gradlew.bat');

  const order = [];
  fs.mkdirSync(local.dir, { recursive: true });
  await assert.rejects(prepare(local, { ...local, processes: [] }, opt, () => {}, () => {}, {
    spawnBuild: () => ({ pid: 42 }), stamp: () => 'build-start',
    wait: async () => { order.push('gradle'); saveJson(path.join(local.dir, 'launch.json'), { client: {}, server: {} }); },
    ports: async plan => { order.push('ports'); await requireFreePorts(plan, async port => port === 8085); },
  }), /port 8085 is already in use/);
  assert.deepEqual(order, ['gradle', 'ports']);
  await requireFreePorts(local, async () => false);
});

test('PR7: only the game clients opt in to the remote DevBridge commands', t => {
  const { local } = slot(t);
  const launch = { main: 'DLI', classpath: ['/one'], jvmArgs: [], args: [] };
  const [server, client] = [undefined, local.clients[0]].map(who => gameSpec(local, { client: launch, server: launch }, 'java', { PATH: 'bin' }, who));
  assert.deepEqual(server.env, { PATH: 'bin' });
  assert.equal(server.cwd, local.server.gameDir);
  assert.deepEqual([client.env.AGENTCRAFT_DEV_REMOTE, client.env.AGENTCRAFT_DEV, client.env.AGENTCRAFT_DEV_PORT, client.env.PATH, client.cwd],
    ['1', '1', '8084', 'bin', local.clients[0].gameDir]);
  assert.ok(client.args.includes('--quickPlayMultiplayer') && !server.args.includes('--quickPlayMultiplayer'));
});

test('PR8: a PID reused within the same whole second is not recovered or signalled unless it still carries this run', async () => {
  const marker = 'ac-mp-12345678-1234-1234-1234-123456789abc';
  const stamp = () => 'Sun Oct  4 12:00:00 2026'; // ps lstart: the reused PIDs report the very same start
  const [leader, child] = [{ pid: 42, groupPid: 42, startTime: stamp() }, { pid: 43, groupPid: 42, startTime: stamp() }];
  const ours = [{ pid: 42, groupPid: 42, command: `java -Dagentcraft.mp.run=${marker} @args` }, { pid: 43, groupPid: 42, command: 'git status' }];
  const reused = [{ pid: 42, groupPid: 42, command: 'vim notes.txt' }, { pid: 43, groupPid: 900, command: 'git status' }];
  const entry = { kind: 'client', marker, executable: 'java', ...leader, members: [{ pid: 43, startTime: stamp() }] };
  assert.deepEqual(recoverProcesses(entry, 'root', ours, stamp, 'linux').map(p => p.pid), [42, 43]);
  assert.deepEqual(recoverProcesses(entry, 'root', reused, stamp, 'linux'), []);
  const stop = async (record, ...tables) => {
    const signals = [];
    let time = 0;
    await stopProcess(record, 'root', { platform: 'linux', marker, timeoutMs: 100, stamp, now: () => time, delay: async ms => { time += ms; },
      inventory: () => tables.length > 1 ? tables.shift() : tables[0], kill: (pid, signal) => signals.push([pid, signal]) }).catch(() => {});
    return signals;
  };
  assert.deepEqual(await stop(leader, ours), [[-42, 'SIGTERM'], [42, 'SIGKILL'], [43, 'SIGKILL']]);
  assert.deepEqual(await stop(child, ours), [[43, 'SIGTERM'], [43, 'SIGKILL']]);
  for (const record of [leader, child]) assert.deepEqual(await stop({ ...record }, reused), []);
  assert.deepEqual(await stop({ ...leader }, ours, ours, reused), [[-42, 'SIGTERM']]); // reused between the two signals
});
