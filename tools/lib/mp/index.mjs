import fs from 'node:fs';
import { DevClient } from '../devclient.mjs';
import { sleep } from './processes.mjs';
export { portsForSlot, makePlan, parseOptions } from './config.mjs';

export async function clientCommand(plan, id, type, payload = {}, { timeoutMs = 10_000, connect = DevClient.connect } = {}) {
  const client = plan.clients.find(c => c.id === id.toLowerCase());
  if (!client) throw new Error(`client ${id} is not in this harness`);
  const dev = await connect({ host: '127.0.0.1', port: client.devPort, timeoutMs });
  try { return await dev.call(type, payload, { timeoutMs }); }
  finally { dev.close(); }
}

export function isRemoteReady(state, client) {
  // Before MP-12 adds mode/server fields, dev.state uses null world.name for a remote
  // connection. Require a loaded dimension, expected player and own synced Foreman too.
  return state?.ok === true && state.ready === true && state.world?.name === null &&
    typeof state.world.dimension === 'string' && state.player?.name === client.username &&
    state.foreman?.connected === true && state.foreman.url === `ws://127.0.0.1:${client.foremanPort}`;
}

export async function waitForClients(plan, { timeoutMs = 600_000, command = clientCommand, check = () => {}, onWait = () => {} } = {}) {
  const deadline = Date.now() + timeoutMs;
  const states = {};
  while (Date.now() < deadline) {
    check();
    const replies = await Promise.allSettled(plan.clients.map(async client => {
      const state = await command(plan, client.id, 'dev.state', {}, { timeoutMs: Math.min(2000, Math.max(1, deadline - Date.now())) });
      states[client.id] = state;
      return isRemoteReady(state, client);
    }));
    if (replies.every(reply => reply.status === 'fulfilled' && reply.value)) return states;
    onWait(states);
    await sleep(500);
  }
  throw new Error(`clients did not become ready on the remote server within ${timeoutMs}ms; see client logs`);
}

/** Incremental server telemetry: retain offset, pass it on the next read. Incomplete lines wait. */
export function readServerEvents(plan, { offset = 0, event, maxBytes = 1024 * 1024 } = {}) {
  if (!Number.isInteger(offset) || offset < 0) throw new Error('invalid log offset');
  if (!Number.isInteger(maxBytes) || maxBytes < 1024 || maxBytes > 8 * 1024 * 1024) throw new Error('maxBytes must be 1KB..8MB');
  let data;
  let fd;
  try {
    fd = fs.openSync(plan.server.log, 'r');
    const size = fs.fstatSync(fd).size;
    if (offset > size) offset = 0; // a new up truncates logs
    data = Buffer.alloc(Math.min(maxBytes, size - offset));
    data = data.subarray(0, fs.readSync(fd, data, 0, data.length, offset));
  }
  catch (error) { if (error.code === 'ENOENT') return { offset: 0, events: [] }; throw error; }
  finally { if (fd !== undefined) fs.closeSync(fd); }
  const last = data.lastIndexOf(10);
  if (last < 0) {
    if (data.length === maxBytes) throw new Error(`server log line exceeds ${maxBytes} bytes`);
    return { offset, events: [] };
  }
  const events = data.subarray(0, last + 1).toString('utf8').split(/\r?\n/).flatMap(line => {
    const name = /\bevent=([a-z_0-9]+)/.exec(line)?.[1];
    if (!name || (event && name !== event)) return [];
    return [{ event: name, fields: Object.fromEntries([...line.matchAll(/\b([A-Za-z_][A-Za-z_0-9]*)=([^\s]+)/g)]
      .map(match => [match[1], match[2]])), line }];
  });
  return { offset: offset + last + 1, events };
}

export function readLogTail(file, maxBytes = 64 * 1024) {
  const fd = fs.openSync(file, 'r');
  try {
    const size = fs.fstatSync(fd).size;
    const data = Buffer.alloc(Math.min(maxBytes, size));
    return data.subarray(0, fs.readSync(fd, data, 0, data.length, Math.max(0, size - maxBytes))).toString('utf8');
  } finally { fs.closeSync(fd); }
}
