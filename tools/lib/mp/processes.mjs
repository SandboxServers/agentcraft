import { spawn, spawnSync } from 'node:child_process';
import fs from 'node:fs';
import path from 'node:path';
import { psQuote } from './launch.mjs';

export const sleep = ms => new Promise(resolve => setTimeout(resolve, ms));
export function readJson(file) {
  try { return JSON.parse(fs.readFileSync(file, 'utf8')); }
  catch (error) { if (error.code === 'ENOENT') return null; throw new Error(`cannot read ${file}: ${error.message}`); }
}
export function saveJson(file, value) {
  fs.mkdirSync(path.dirname(file), { recursive: true });
  const tmp = `${file}.${process.pid}.tmp`;
  fs.writeFileSync(tmp, JSON.stringify(value, null, 2) + '\n');
  fs.renameSync(tmp, file);
}

export function powershell(root, script, run = spawnSync, options = {}) {
  const result = run('powershell.exe', ['-NoProfile', '-NonInteractive', '-ExecutionPolicy', 'Bypass', '-Command',
    `. ${psQuote(path.join(root, 'tools', 'lib', 'procs.ps1'))}; ${script}`], { encoding: 'utf8', windowsHide: true, ...options });
  if (result.error || result.status !== 0) throw new Error(`process inspection failed: ${result.error?.message ?? result.stderr}`);
  return result.stdout.trim();
}

export function processStamp(pid, root, { platform = process.platform, run = spawnSync } = {}) {
  if (!Number.isInteger(pid) || pid < 1) return null;
  if (platform === 'win32') return powershell(root, `Get-ProcStart ${pid}`, run) || null;
  const result = run('ps', ['-p', String(pid), '-o', 'lstart=', '-o', 'stat='],
    { encoding: 'utf8', env: { ...process.env, LC_ALL: 'C', TZ: 'UTC' } });
  if (result.error) throw result.error;
  if (result.status === 1) return null;
  if (result.status !== 0) throw new Error(`ps failed: ${result.stderr}`);
  const match = /^(.*?)\s+(\S+)\s*$/.exec(result.stdout.trim());
  return match && !match[2].startsWith('Z') ? match[1].trim() : null;
}

export function sameProcess(record, root, stamp = processStamp) {
  return !!(record?.pid && record?.startTime && stamp(record.pid, root) === record.startTime);
}

export function processInventory(root) {
  if (process.platform === 'win32') {
    const raw = powershell(root, 'ConvertTo-Json -Compress -InputObject @(Get-CimInstance Win32_Process | Select-Object ProcessId,CommandLine)');
    return JSON.parse(raw || '[]').map(p => ({ pid: p.ProcessId, command: p.CommandLine ?? '' }));
  }
  const result = spawnSync('ps', ['-axww', '-o', 'pid=', '-o', 'pgid=', '-o', 'command='], { encoding: 'utf8' });
  if (result.error || result.status !== 0) throw new Error(`ps failed: ${result.error?.message ?? result.stderr}`);
  return result.stdout.split('\n').flatMap(line => {
    const m = /^\s*(\d+)\s+(\d+)\s+(.*)$/.exec(line);
    return m ? [{ pid: Number(m[1]), groupPid: Number(m[2]), command: m[3] }] : [];
  });
}

// Durable intent precedes spawn. A unique marker outside the Java argfile permits recovery
// if the launcher dies in the tiny gap between spawn and recording the PID/start time.
export function recoverProcesses(entry, root, inventory = processInventory(root), stamp = processStamp) {
  const found = [];
  const pending = [entry.wrapper, entry];
  while (pending.length) {
    const record = pending.pop();
    if (!record) continue;
    pending.push(...(record.members ?? []), ...(record.recoveredProcesses ?? []));
    if (sameProcess(record, root, stamp) && !found.some(p => p.pid === record.pid)) found.push(record);
  }
  if (!/^ac-mp-[0-9a-f-]{36}$/.test(entry.marker ?? '')) return found;
  for (const p of inventory) {
    if (!p.command.includes(entry.marker) || found.some(record => record.pid === p.pid)) continue;
    const startsWithExe = executable => executable && (p.command.startsWith(`${executable} `) || p.command.startsWith(`"${executable}" `));
    const direct = startsWithExe(entry.executable) || (entry.kind === 'foreman' && p.command.trim() === entry.marker);
    const wrapper = startsWithExe(process.execPath) && p.command.includes(path.join(root, 'tools', 'lib', 'bgrun.mjs'));
    // A coordinator's grep/search containing our marker is never a harness process.
    if (!direct && !wrapper) continue;
    const startTime = stamp(p.pid, root);
    if (startTime) found.push({ pid: p.pid, startTime, role: wrapper ? 'wrapper' : 'primary',
      ...(p.groupPid === p.pid ? { groupPid: p.pid } : {}) });
  }
  return found;
}

export async function startProcess(spec, entry, state, persist, { platform = process.platform, shell = powershell, stamp = processStamp, timeoutMs = 20_000 } = {}) {
  entry.executable = spec.command;
  persist();
  fs.mkdirSync(path.dirname(spec.log), { recursive: true });
  let child;
  let error;
  if (platform === 'win32') {
    // Start-Bg uses ShellExecute and gives bgrun a hidden console, unlike detached spawn.
    const specFile = path.join(state.dir, `${entry.marker}.json`);
    const env = Object.fromEntries(Object.entries(spec.env ?? {}).filter(([key, value]) => process.env[key] !== value));
    saveJson(specFile, { ...spec, env });
    const result = JSON.parse(shell(state.root, `$s = Read-JsonFile ${psQuote(specFile)}; ` +
      `$e = @{}; if ($s.env) { $s.env.PSObject.Properties | ForEach-Object { $e[$_.Name] = [string]$_.Value } }; ` +
      `$L = @{Run=${psQuote(state.dir)}; Tools=${psQuote(path.join(state.root, 'tools'))}}; ` +
      `Start-Bg -L $L -NodeExe ${psQuote(process.execPath)} -Name ${psQuote(entry.marker)} ` +
      `-Command $s.command -Arguments @($s.args) -Cwd $s.cwd -Log $s.log -ErrLog '' -Env $e | ConvertTo-Json -Compress`,
      undefined, { timeout: Math.max(1, Math.ceil(timeoutMs)) }));
    entry.wrapper = { pid: result.wrapperPid, startTime: result.wrapperStart };
    entry.pid = result.pid; entry.startTime = result.start;
    persist();
    if (!sameProcess(entry, state.root, stamp)) throw new Error(`${entry.kind} exited during launch; see ${spec.log}`);
    return entry;
  }
  const fd = fs.openSync(spec.log, 'a');
  try { child = spawn(spec.command, spec.args, { cwd: spec.cwd, env: spec.env ?? process.env,
    detached: true, stdio: ['ignore', fd, fd] }); }
  finally { fs.closeSync(fd); }

  child.once('error', e => { error = e; });
  entry.pid = child.pid; entry.groupPid = child.pid; entry.startTime = stamp(child.pid, state.root);
  persist();
  child.unref();
  await sleep(25); // let asynchronous spawn errors arrive

  persist();
  if (error) throw error;
  if (!sameProcess(entry, state.root, stamp)) throw new Error(`${entry.kind} exited during launch; see ${spec.log}`);
  return entry;
}

export async function stopProcess(record, root, { timeoutMs = 10_000, consoleRecord = record, persist = () => {}, kind,
  platform = process.platform, shell = powershell, stamp = processStamp, now = Date.now, delay = sleep,
  inventory = processInventory, kill = process.kill.bind(process), budgetMs = Infinity } = {}) {
  const deadline = now() + budgetMs;
  if (!sameProcess(record, root, stamp)) return;
  const members = platform !== 'win32' && record.groupPid === record.pid
    ? groupSnapshot(record, inventory(root), root, stamp) : [record];
  record.members = members.filter(member => member.pid !== record.pid);
  persist(); // retain child identities even if the group leader exits or down crashes
  if (platform === 'win32') {
    // Java treats Ctrl+Break as a thread dump, not a graceful shutdown.
    if (kind === 'foreman' && sameProcess(consoleRecord, root, stamp)) {
      if (shell(root, `Send-CtrlBreak ${consoleRecord.pid}`).toLowerCase() !== 'true') timeoutMs = 0;
    } else if (kind !== 'client') timeoutMs = 0;
  } else {
    // Direct POSIX launches own a detached group. Signal its tools too, while the leader's
    // PID/start identity still matches, then retain member identities across leader exit.
    try { kill(record.groupPid === record.pid ? -record.pid : record.pid, 'SIGTERM'); }
    catch (error) { if (error.code !== 'ESRCH') throw error; }
  }
  const until = Math.min(deadline, now() + timeoutMs);
  while (now() < until && members.some(member => sameProcess(member, root, stamp))) await delay(Math.min(100, Math.max(0, deadline - now())));
  for (const member of members.filter(member => sameProcess(member, root, stamp))) {
    if (platform === 'win32') shell(root, `Stop-OwnTree ${member.pid} ${psQuote(member.startTime)} | Out-Null`);
    else {
      try { kill(member.pid, 'SIGKILL'); }
      catch (error) { if (error.code !== 'ESRCH') throw error; }
    }
  }
  for (let i = 0; i < 50 && now() < deadline && members.some(member => sameProcess(member, root, stamp)); i++) await delay(Math.min(100, Math.max(0, deadline - now())));
  const left = members.filter(member => sameProcess(member, root, stamp));
  if (left.length) throw new Error(`processes ${left.map(member => member.pid).join(', ')} are still running`);
}

export function groupSnapshot(record, inventory, root, stamp = processStamp) {
  if (!sameProcess(record, root, stamp) || record.groupPid !== record.pid) return [];
  return [{ pid: record.pid, startTime: record.startTime }, ...inventory.filter(p => p.groupPid === record.pid && p.pid !== record.pid).flatMap(p => {
    const startTime = stamp(p.pid, root);
    return startTime ? [{ pid: p.pid, startTime }] : [];
  })];
}

export function ownsPort(entry, port, root) {
  if (!sameProcess(entry, root)) return false;
  if (process.platform === 'win32') return Number(powershell(root, `Get-PortOwner ${Number(port)}`)) === entry.pid;
  const result = spawnSync('lsof', ['-nP', `-iTCP:${port}`, '-sTCP:LISTEN', '-t'], { encoding: 'utf8' });
  return result.status === 0 && result.stdout.trim().split(/\s+/).includes(String(entry.pid));
}
