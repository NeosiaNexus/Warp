// The backends of the benchmark. A Paper backend is downloaded once, configured for whichever
// forwarding the proxy under test uses, booted, pinned to the backend cores, and driven through its
// console; three of these run behind each proxy so that bots can switch between them. The
// configuration (offline mode, flat world, no mobs, native transport, no player-data saving, a
// bounded player tracking range) keeps the cost of a player low, and each backend reports its own
// health (ticks per second, tick time, overload and error lines) so the validity gate can reject a
// measurement the backend, not the proxy, limited. The idle scenario uses the stub backend
// (stubbackend.js) instead, started and watched by `StubBackend` here.
import { mkdirSync, rmSync, writeFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

import { download, findJava, ManagedProcess } from './reuse.js';

const READY = /Done \(\d+[.,]\d+s\)!/;
const BOOT_TIMEOUT_MS = 240_000;
const STUB = join(dirname(fileURLToPath(import.meta.url)), 'stubbackend.js');

/**
 * Blocks within which a player is sent the other players. Paper's default (128) makes every player
 * of a crowded world track every other one, a load that grows with the square of the player count
 * and saturates the backend's tick long before the proxy: bounded so that a player sees the crowd
 * around it, not the whole server. The same for every proxy.
 */
export const PLAYER_TRACKING_RANGE = 32;

/** Console lines that mean the backend fell behind or failed at something. */
const OVERLOAD = /Can't keep up!/;
const ERROR_LINE = /\bERROR\]|Exception|Failed to /;

/** Wraps argv in taskset when the process is pinned. */
function pinned(command, args, cpus) {
  return cpus?.length ? ['taskset', ['-c', cpus.join(','), command, ...args]] : [command, args];
}

/**
 * Reads Paper's answers to the `tps` and `mspt` console commands.
 * @param {string[]} lines console lines printed after the commands were sent
 * @returns {{tps: number|null, msptAvg: number|null, msptMax: number|null}} the one-minute ticks per
 *   second, and the average and worst tick time in milliseconds over the last ten seconds
 */
export function parseTickHealth(lines) {
  const clean = lines.map((l) => l.replace(/§./g, ''));
  let tps = null;
  let msptAvg = null;
  let msptMax = null;
  for (const line of clean) {
    const t = /TPS from last 1m, 5m, 15m:\s*\*?([\d.]+)/.exec(line);
    if (t) tps = Number(t[1]);
    // "avg/min/max" for 5 s, 10 s and 1 min: the middle group is the last ten seconds.
    const m = /([\d.]+)\/([\d.]+)\/([\d.]+),\s*([\d.]+)\/([\d.]+)\/([\d.]+),\s*([\d.]+)\/([\d.]+)\/([\d.]+)/.exec(line);
    if (m) {
      msptAvg = Number(m[4]);
      msptMax = Number(m[6]);
    }
  }
  return { tps, msptAvg, msptMax };
}

/** Backend log lines that mean a client connection broke on the server side (a mangled packet). */
const BACKEND_ERRORS = [
  /Internal Exception: .*(DecoderException|EncoderException|CorruptedFrameException|DataFormatException)/,
  /Error (receiving|sending) packet/i,
  /was larger than I expected/,
];

/** Downloads (or reuses) the Paper jar named in versions.json and returns its path. */
export async function fetchBackendJar(backend, cacheDir) {
  const name = `${backend.type}-${backend.version}-${backend.build}`;
  return download(backend.url, join(cacheDir, 'servers', `${name}.jar`), { sha256: backend.sha256 });
}

export class Backend {
  /**
   * @param {object} options
   * @param {string} options.name server name in the proxy's config ("lobby", "survival", "mini")
   * @param {string} options.dir working directory (wiped on prepare)
   * @param {string} options.jar Paper jar
   * @param {string} options.java java executable
   * @param {number} options.port
   * @param {number} options.protocol backend protocol number (for native transport)
   * @param {number} options.compressionThreshold `network-compression-threshold`
   * @param {number} options.gameMode 0 survival, 1 creative, 2 adventure (so bots can tell backends apart)
   * @param {'modern'|'legacy'|'none'} options.forwarding what the proxy forwards
   * @param {string} options.secret the forwarding secret (modern)
   * @param {string} options.logDir
   * @param {string[]|null} [options.cpus] CPU ids the server is pinned to with taskset, or null
   */
  constructor(options) {
    Object.assign(this, options);
    this.process = null;
  }

  prepare() {
    rmSync(this.dir, { recursive: true, force: true });
    mkdirSync(this.dir, { recursive: true });
    writeFileSync(join(this.dir, 'eula.txt'), 'eula=true\n');
    writeFileSync(join(this.dir, 'server.properties'), this.serverProperties());
    // No per-address reconnect throttle: every bot arrives from the proxy's one address.
    writeFileSync(join(this.dir, 'bukkit.yml'), 'settings:\n  connection-throttle: -1\n');
    // No telemetry.
    mkdirSync(join(this.dir, 'plugins', 'bStats'), { recursive: true });
    writeFileSync(join(this.dir, 'plugins', 'bStats', 'config.yml'), 'enabled: false\n');
    writeFileSync(join(this.dir, 'spigot.yml'), this.spigotYml());
    if (this.forwarding === 'modern') {
      const file = join(this.dir, 'config', 'paper-global.yml');
      mkdirSync(dirname(file), { recursive: true });
      writeFileSync(
        file,
        `proxies:\n  velocity:\n    enabled: true\n    online-mode: false\n    secret: '${this.secret}'\n`,
      );
    }
  }

  /**
   * spigot.yml: BungeeCord forwarding when the proxy uses it, no player-data files (thousands of
   * bots would each leave one, written on the server's main thread), and the bounded tracking range.
   */
  spigotYml() {
    return [
      'settings:',
      `  bungeecord: ${this.forwarding === 'legacy'}`,
      'players:',
      '  disable-saving: true',
      'world-settings:',
      '  default:',
      '    entity-tracking-range:',
      `      players: ${PLAYER_TRACKING_RANGE}`,
      '',
    ].join('\n');
  }

  serverProperties() {
    const props = {
      'server-ip': '127.0.0.1',
      'server-port': this.port,
      'online-mode': false,
      'network-compression-threshold': this.compressionThreshold,
      'max-players': 100000,
      'level-type': 'flat',
      'generate-structures': false,
      'allow-nether': false,
      'spawn-monsters': false,
      'spawn-animals': false,
      'spawn-npcs': false,
      difficulty: 0,
      gamemode: this.gameMode,
      'force-gamemode': true,
      'view-distance': 6,
      'simulation-distance': 4,
      'spawn-protection': 0,
      'enforce-secure-profile': false,
      'prevent-proxy-connections': false,
      'player-idle-timeout': 0,
      'pause-when-empty-seconds': -1,
      'max-tick-time': -1,
      'sync-chunk-writes': false,
      'use-native-transport': true,
      'enable-status': true,
      'enable-query': false,
      'enable-rcon': false,
      motd: `bench ${this.name}`,
    };
    return `${Object.entries(props).map(([k, v]) => `${k}=${v}`).join('\n')}\n`;
  }

  start() {
    const args = [
      '-Xms512m',
      `-Xmx${this.maxHeapMb ?? 2048}m`,
      '-XX:+UseG1GC',
      '-Djava.awt.headless=true',
      '-Dfile.encoding=UTF-8',
      '-Dterminal.jline=false',
      '-Djline.terminal=jline.UnsupportedTerminal',
      '-Dlog4j2.formatMsgNoLookups=true',
      '-DPaper.IgnoreJavaVersion=true',
      '-jar',
      this.jar,
      'nogui',
    ];
    const [command, argv] = pinned(this.java, args, this.cpus);
    this.process = new ManagedProcess(this.name, command, argv, {
      cwd: this.dir,
      logFile: join(this.logDir, `backend-${this.name}.log`),
      failures: BACKEND_ERRORS,
    }).start();
    return this.process;
  }

  async boot() {
    this.prepare();
    this.start();
    try {
      await this.process.waitFor(READY, BOOT_TIMEOUT_MS);
    } catch (e) {
      await this.stop().catch(() => {});
      throw e;
    }
    return this;
  }

  send(line) {
    this.process?.send(line);
  }

  /** A point in the console log; pass it to {@link health} to look only at what follows. */
  mark() {
    return this.process?.mark() ?? 0;
  }

  /**
   * The backend's health since `mark`: ticks per second over the last minute and tick times over
   * the last ten seconds (asked on the console now), and how many overload and error lines it
   * printed since the mark.
   */
  async health(mark = 0) {
    const health = { name: this.name, kind: 'paper', tps: null, msptAvg: null, msptMax: null, overloads: 0, errors: 0, errorSample: null };
    if (!this.process?.alive) return { ...health, dead: true };
    const lines = this.process.lines;
    for (let i = mark; i < lines.length; i++) {
      if (OVERLOAD.test(lines[i])) health.overloads++;
      else if (ERROR_LINE.test(lines[i])) {
        health.errors++;
        health.errorSample ??= lines[i].trim().slice(0, 160);
      }
    }
    const asked = this.process.mark();
    this.send('tps');
    this.send('mspt');
    try {
      await this.process.waitFor(/TPS from last 1m/, 5_000, asked);
      await this.process.waitFor(/[\d.]+\/[\d.]+\/[\d.]+,/, 5_000, asked);
    } catch {
      // No answer within five seconds: the main thread is that far behind. tps stays null.
    }
    return { ...health, ...parseTickHealth(lines.slice(asked)) };
  }

  stop() {
    return this.process ? this.process.stop({ command: 'stop', graceMs: 45_000 }) : Promise.resolve(null);
  }
}

/**
 * Boots a set of backends in parallel. If any of them fails to boot (a timeout waiting for "Done",
 * a crash), the ones that did boot, and the one that is still starting, are stopped before the
 * error propagates: a survivor left running would hold its port for every later proxy in the run
 * and keep the process alive at the end. `backends` need only `boot()` and `stop()`.
 * @template {{boot(): Promise<unknown>, stop(): Promise<unknown>}} B
 * @param {B[]} backends
 * @returns {Promise<B[]>} the same backends, all booted
 */
export async function bootAll(backends) {
  const outcomes = await Promise.allSettled(backends.map((b) => b.boot()));
  const failed = outcomes.find((o) => o.status === 'rejected');
  if (!failed) return backends;
  await Promise.allSettled(backends.map((b) => b.stop()));
  throw failed.reason;
}

/**
 * Builds (without starting) the three backends behind a proxy, each with the forwarding the proxy
 * uses.
 * @returns {Backend[]}
 */
export function createBackends({ jar, java, baseDir, logDir, basePort, backend, forwarding, secret, compressionThreshold, maxHeapMb, cpus = null }) {
  const names = ['lobby', 'survival', 'mini'];
  return names.map(
    (name, i) =>
      new Backend({
        name,
        dir: join(baseDir, `backend-${name}`),
        jar,
        java,
        port: basePort + i,
        protocol: backend.protocol,
        compressionThreshold,
        gameMode: i === 1 ? 0 : 1, // survival on "survival", creative elsewhere: bots see the switch
        forwarding,
        secret,
        logDir,
        maxHeapMb,
        cpus,
      }),
  );
}

/**
 * Boots the three backends behind a proxy, in parallel, each with the forwarding the proxy uses.
 * On failure every backend that started is stopped before the error propagates (see bootAll).
 * @returns {Promise<Backend[]>}
 */
export async function bootBackends({ javaMajor, cacheDir, ...rest }) {
  const java = await findJava(javaMajor, cacheDir);
  return bootAll(createBackends({ java, ...rest }));
}

// ---------------------------------------------------------------------------
// Stub backend (idle scenario)
// ---------------------------------------------------------------------------

/** The stub backend's process: started, pinned and watched like a Paper backend. */
export class StubBackend {
  /**
   * @param {object} options
   * @param {string} options.name
   * @param {number} options.port
   * @param {'modern'|'legacy'|'none'} options.forwarding
   * @param {string} options.secret
   * @param {number} options.compressionThreshold
   * @param {string} options.logDir
   * @param {string[]|null} [options.cpus]
   */
  constructor(options) {
    Object.assign(this, options);
    this.process = null;
    this.last = { refused: 0, keepAliveTimeouts: 0 };
  }

  async boot() {
    const args = [STUB, '--port', String(this.port), '--forwarding', this.forwarding, '--compression-threshold', String(this.compressionThreshold)];
    const [command, argv] = pinned(process.execPath, args, this.cpus);
    this.process = new ManagedProcess(this.name, command, argv, {
      cwd: this.logDir,
      env: { STUB_FORWARDING_SECRET: this.secret },
      logFile: join(this.logDir, `backend-${this.name}.log`),
    }).start();
    await this.process.waitFor(/^Done/, 20_000);
    return this;
  }

  async stats() {
    const asked = this.process.mark();
    this.process.send('stats');
    const line = await this.process.waitFor(/^STATS /, 5_000, asked);
    return JSON.parse(line.slice(6));
  }

  /** A point in time; pass it to {@link health} to count only what happened since. */
  async mark() {
    const { refused, keepAliveTimeouts } = await this.stats();
    return { refused, keepAliveTimeouts };
  }

  /** Players held, logins refused and keep-alive timeouts since `mark`, and the event-loop lag. */
  async health(mark = { refused: 0, keepAliveTimeouts: 0 }) {
    if (!this.process?.alive) return { name: this.name, kind: 'stub', dead: true };
    try {
      const s = await this.stats();
      return {
        name: this.name,
        kind: 'stub',
        players: s.players,
        refused: s.refused - mark.refused,
        keepAliveTimeouts: s.keepAliveTimeouts - mark.keepAliveTimeouts,
        lagP99Ms: s.lagP99Ms,
        lagMaxMs: s.lagMaxMs,
      };
    } catch {
      return { name: this.name, kind: 'stub', unresponsive: true };
    }
  }

  stop() {
    return this.process ? this.process.stop({ command: 'stop', graceMs: 5_000 }) : Promise.resolve(null);
  }
}
