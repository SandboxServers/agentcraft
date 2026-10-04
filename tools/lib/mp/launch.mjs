import fs from 'node:fs';
import { spawnSync } from 'node:child_process';
import path from 'node:path';
import { createHash } from 'node:crypto';

export const psQuote = value => `'${String(value).replaceAll("'", "''")}'`;

// JAVA_HOME and GRADLE_USER_HOME remain inherited, including under swarm wrappers.
export function gradleInvocation(root, output, command, platform = process.platform) {
  const args = ['-I', path.join(root, 'tools', 'lib', 'mp', 'export-launch.gradle'),
    'mpExportLaunch', `-PmpLaunchFile=${output}`, '--no-configuration-cache', '--console=plain'];
  if (command) return { command, args, cwd: path.join(root, 'mod') };
  if (platform === 'win32') return { command: 'powershell.exe', args: ['-NoProfile', '-NonInteractive', '-ExecutionPolicy', 'Bypass', '-Command',
    `& .\\gradlew.bat ${args.map(psQuote).join(' ')}; exit $LASTEXITCODE`], cwd: path.join(root, 'mod') };
  return { command: 'sh', args: ['./gradlew', ...args], cwd: path.join(root, 'mod') };
}

// Java argfiles avoid Windows command-length limits; always quote paths and literal backslashes.
export function javaArgfile(args) {
  return args.map(value => {
    if (/[\r\n\0]/.test(value)) throw new Error('newline/NUL in Java argument');
    return `"${value.replaceAll('\\', '\\\\').replaceAll('"', '\\"')}"`;
  }).join('\n') + '\n';
}

export function javaArguments(launch, heap, client, serverPort, platform = process.platform) {
  if (!launch?.main || !launch.classpath?.length || !Array.isArray(launch.jvmArgs) || !Array.isArray(launch.args)) {
    throw new Error('invalid Loom launch metadata; run the build/export step');
  }
  // An environment or Loom heap default must never override the explicit harness cap.
  const jvm = launch.jvmArgs.filter(arg => !/^-Xm[sx]|^-XX:(?:Initial|Max)HeapSize=/.test(arg));
  const args = [...launch.args];
  for (const key of ['--username', '--gameDir', '--quickPlayMultiplayer', '--width', '--height']) {
    for (let i = args.indexOf(key); i >= 0; i = args.indexOf(key)) args.splice(i, 2);
  }
  if (client) args.push('--username', client.username, '--gameDir', client.gameDir,
    '--quickPlayMultiplayer', `localhost:${serverPort}`, '--width', '960', '--height', '540');
  return [...jvm, '-Xms256M', `-Xmx${heap}`, '-cp', launch.classpath.join(platform === 'win32' ? ';' : ':'), launch.main, ...args];
}

export function javaEnvironment(env = process.env) {
  // These inject arbitrary JVM flags after our command-line cap. Reject rather than silently
  // changing the operator's environment. Gradle still receives the environment unchanged.
  for (const key of ['JAVA_TOOL_OPTIONS', '_JAVA_OPTIONS', 'JDK_JAVA_OPTIONS']) {
    if (env[key]?.trim()) throw new Error(`${key} must be unset for capped harness JVMs`);
  }
  return { ...env };
}

export function seedGameDirs(plan) {
  const dir = plan.server.gameDir;
  fs.mkdirSync(path.join(dir, 'config'), { recursive: true });
  // A local, offline test server only; bind to loopback to keep it off the LAN.
  const generator = { biome: 'minecraft:plains', layers: [
    { block: 'minecraft:bedrock', height: 1 }, { block: 'minecraft:stone', height: 124 },
    { block: 'minecraft:dirt', height: 3 }, { block: 'minecraft:grass_block', height: 1 }], structure_overrides: [] };
  fs.writeFileSync(path.join(dir, 'eula.txt'), 'eula=true\n');
  fs.writeFileSync(path.join(dir, 'server.properties'), [
    'server-ip=127.0.0.1', `server-port=${plan.server.port}`, 'online-mode=false', 'enforce-secure-profile=false',
    'level-name=mp-world', 'level-type=minecraft:flat', `generator-settings=${JSON.stringify(generator)}`,
    'gamemode=creative', 'difficulty=peaceful', 'spawn-protection=0', 'view-distance=6',
    'simulation-distance=4', 'max-players=2', 'enable-rcon=false', 'enable-query=false', 'sync-chunk-writes=true', ''
  ].join('\n'));
  fs.writeFileSync(path.join(dir, 'config', 'agentcraft-server.json'), JSON.stringify({ enabled: true,
    plotStride: 128, autoAllocate: true, autoBuild: true, forceCreative: true, worldRules: true,
    protectPlots: true, relayRadiusChunks: 12, publicStatePerSecond: 4, intentsPerSecond: 10 }, null, 2));
  fs.writeFileSync(path.join(dir, 'ops.json'), JSON.stringify(plan.clients.map(client => ({
    uuid: offlineUuid(client.username), name: client.username, level: 4, bypassesPlayerLimit: true })), null, 2));
  for (const client of plan.clients) {
    fs.mkdirSync(client.gameDir, { recursive: true });
    const options = path.join(client.gameDir, 'options.txt');
    if (!fs.existsSync(options)) {
      const template = fs.readFileSync(path.join(plan.root, 'mod', 'run-template', 'options.txt'), 'utf8');
      fs.writeFileSync(options, template.replace('maxFps:120', 'maxFps:30'));
    }
  }
}

export function offlineUuid(username) {
  const bytes = createHash('md5').update(`OfflinePlayer:${username}`, 'utf8').digest();
  bytes[6] = (bytes[6] & 0x0f) | 0x30;
  bytes[8] = (bytes[8] & 0x3f) | 0x80;
  const h = bytes.toString('hex');
  return `${h.slice(0, 8)}-${h.slice(8, 12)}-${h.slice(12, 16)}-${h.slice(16, 20)}-${h.slice(20)}`;
}

export function gradleEnvironment(root, env = process.env) {
  return { ...env, GRADLE_USER_HOME: env.GRADLE_USER_HOME || path.join(root, '.gradle-home') };
}

export function requireJava25(env = process.env, platform = process.platform, run = spawnSync) {
  const java = env.JAVA_HOME ? path.join(env.JAVA_HOME, 'bin', platform === 'win32' ? 'java.exe' : 'java') : 'java';
  const result = run(java, ['-version'], { env, encoding: 'utf8', windowsHide: true });
  if (result.error || result.status !== 0 || !/version "25(?:[.\"+-])/.test(`${result.stderr}\n${result.stdout}`)) {
    throw new Error(`Java 25 is required; set JAVA_HOME to a JDK 25 installation (${result.error?.message ?? result.stderr?.trim() ?? 'java -version failed'})`);
  }
  return java;
}
