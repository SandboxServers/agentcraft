import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { readServerEvents } from '../lib/mp/index.mjs';
import { makePlan, parseOptions } from '../lib/mp/config.mjs';
import { prepare } from '../lib/mp/harness.mjs';
import { readJson, recoverProcesses } from '../lib/mp/processes.mjs';

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
