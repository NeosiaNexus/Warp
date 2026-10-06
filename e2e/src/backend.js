// Minecraft backend servers (Paper, or vanilla where Paper never shipped a build): download,
// configure, start, drive through the console, stop.
import { cpSync, existsSync, mkdirSync, rmSync, writeFileSync } from 'node:fs';
import { dirname, join } from 'node:path';

import { download } from './download.js';
import { ManagedProcess } from './proc.js';

/** Ready line printed by every vanilla-derived server since 1.7: `Done (3.21s)! For help, …`. */
const READY = /Done \(\d+[.,]\d+s\)!/;

/** How long a backend may take to boot. */
const BOOT_TIMEOUT_MS = 240_000;

/**
 * How many times a backend that hangs while booting is booted again. Paper 1.16 sometimes freezes
 * for good on CI runners before it reads its configuration (#97): the server hangs before Warp
 * starts or any player connects, so booting it again tests nothing less. Scenarios never retry.
 */
const BOOT_RETRIES = 1;

/**
 * First protocol whose Paper builds accept Velocity modern forwarding: 1.13.1. It travels in a login
 * plugin message, which 1.13 introduced; Paper's 1.13 builds do not read it yet.
 */
const PAPER_VELOCITY_SINCE = 401;

/** First protocol whose Paper builds read `config/paper-global.yml` rather than `paper.yml`: 1.19. */
const PAPER_GLOBAL_CONFIG_SINCE = 759;

/**
 * First protocol whose servers read the resource pack's SHA-1 from `resource-pack-sha1`: 1.9. They
 * still read `resource-pack-hash`, as 1.8 does, but warn that it is deprecated.
 */
const RESOURCE_PACK_SHA1_SINCE = 107;

/**
 * The authlib host properties (`minecraft.api.<name>.host`) of every server the bots sign chat on,
 * a set authlib reads in full or not at all: auth, account, session and services in authlib 4
 * (1.20.1); account, session and services in 5 (1.20.2); session and services in 6 (1.20.4 to
 * 1.21.8); session, services and profiles in 7 to 9 (1.21.10 to 26.2). authlib 10 (26.3) reads a
 * discovery host instead, which the mock does not serve: bots reach 26.3 through ViaProxy, offline.
 */
const AUTHLIB_HOSTS = ['auth', 'account', 'session', 'services', 'profiles'];

/**
 * Backend log lines that mean a client connection broke on the server side, usually a packet the
 * proxy mangled. Benign disconnects ("Disconnected", "Timed out") are not listed.
 */
export const BACKEND_PROTOCOL_ERRORS = [
  /Internal Exception: .*(DecoderException|EncoderException|CorruptedFrameException|DataFormatException|IndexOutOfBounds|IllegalArgument|IllegalState)/,
  /Error (receiving|sending) packet/i,
  /Failed to (decode|encode) packet/i,
  /Packet .* was larger than I expected/,
  /Badly compressed packet/i,
  // 1.19.3+: a player's chat acknowledgements and the server's window over them disagree.
  /Failed to validate message acknowledgements/,
  /lost connection: Chat message validation failure/,
];

/**
 * Everything cached for one server build lives in one directory, so that CI can cache each build
 * separately: `server.jar`, `template/` (what its first boot downloaded or patched), `preseed/`.
 */
export function serverCacheDir(server, cacheDir) {
  return join(cacheDir, 'servers', serverId(server));
}

/** Downloads (or reuses) the server jar of a matrix entry and returns its path. */
export async function fetchServerJar(server, cacheDir) {
  return download(server.url, join(serverCacheDir(server, cacheDir), 'server.jar'), { sha256: server.sha256, sha1: server.sha1 });
}

/**
 * Downloads the files some old builds expect to find before their first boot (`preseed` in
 * versions.json): 2017 Paperclip builds fetch vanilla from a Mojang URL that no longer exists.
 * @returns {Promise<Record<string, string>>} relative path in the server directory → cached file
 */
export async function fetchPreseeded(server, cacheDir) {
  const files = {};
  for (const [relative, source] of Object.entries(server.preseed ?? {})) {
    const target = join(serverCacheDir(server, cacheDir), 'preseed', relative);
    files[relative] = await download(source.url, target, { sha256: source.sha256, sha1: source.sha1 });
  }
  return files;
}

/** Stable identifier of a server build: `paper-1.21.4-232`, `vanilla-1.9`. */
export function serverId(server) {
  return `${server.type}-${server.version}${server.build ? `-${server.build}` : ''}`;
}

/**
 * Why the server of a matrix entry cannot accept players forwarded in `mode`, or null when it can
 * (always for `none`). Vanilla servers trust no proxy; Paper accepts Velocity modern forwarding from
 * 1.13.1.
 */
export function forwardingUnsupported(entry, mode) {
  if (mode === 'none') return null;
  if (entry.server.type !== 'paper') return `${entry.server.type} servers accept no forwarded player info, only Paper does`;
  if (entry.protocol < PAPER_VELOCITY_SINCE) return 'Paper accepts Velocity forwarding from 1.13.1';
  return null;
}

/**
 * Groups a version's variants by the backends they need, in order of first use: variants with the
 * same compression threshold and forwarding share one boot of the backends. A variant the server
 * cannot run gets a group of its own, with the reason, and boots nothing.
 * @param {object} entry versions.json entry
 * @param {object[]} variants resolved variants
 * @param {{direct?: boolean}} run `direct`: a control run, where bots join the lobby without Warp
 * @returns {{threshold: number, forwarding: {mode: string, online: boolean}|null, skip: string|null, variants: object[]}[]}
 */
export function backendGroups(entry, variants, { direct = false } = {}) {
  const groups = new Map();
  for (const variant of variants) {
    const skip =
      forwardingUnsupported(entry, variant.forwarding) ??
      (direct && variant.forwarding !== 'none' ? 'a control run has no proxy to forward player info' : null);
    const forwarding = variant.forwarding === 'none' ? null : { mode: variant.forwarding, online: variant.online };
    const key = skip ? `skip ${variant.name}` : JSON.stringify([variant.backendThreshold, forwarding]);
    if (!groups.has(key)) groups.set(key, { threshold: variant.backendThreshold, forwarding, skip, variants: [] });
    groups.get(key).variants.push(variant);
  }
  return [...groups.values()];
}

/**
 * Paper's Velocity forwarding settings: `paper.yml` up to 1.18.2, `config/paper-global.yml` from
 * 1.19. Paper fills in every other setting with its default.
 * @param {number} protocol
 * @param {{secret: string, online: boolean}} forwarding `online`: whether the proxy authenticates
 *   players (Paper then treats them as online-mode players)
 * @returns {{file: string, yaml: string}} the file, relative to the server directory, and its content
 */
export function paperForwardingConfig(protocol, { secret, online }) {
  const velocity = `    enabled: true\n    online-mode: ${online}\n    secret: '${secret}'\n`;
  return protocol < PAPER_GLOBAL_CONFIG_SINCE
    ? { file: 'paper.yml', yaml: `settings:\n  velocity-support:\n${velocity}` }
    : { file: join('config', 'paper-global.yml'), yaml: `proxies:\n  velocity:\n${velocity}` };
}

export class Backend {
  /**
   * @param {object} options
   * @param {string} options.name server name in Warp's config ("lobby", "survival")
   * @param {string} options.dir working directory (wiped)
   * @param {object} options.server matrix entry's `server` object
   * @param {number} options.protocol matrix entry's protocol
   * @param {string} options.jar server jar
   * @param {string} options.java java executable
   * @param {number} options.port
   * @param {number} options.compressionThreshold `network-compression-threshold`
   * @param {number} options.gameMode 1 = creative, 2 = adventure: tells bots which backend they reached
   * @param {{url: string, sha1: string, id: string}} options.resourcePack the pack this server
   *   offers to players
   * @param {{secret: string, online: boolean}|null} options.forwarding Velocity modern forwarding
   *   (Paper only, see {@link forwardingUnsupported}), or null to accept players straight
   * @param {string} options.logDir
   * @param {string} options.logSuffix distinguishes the logs of successive boots (one per backend
   *   threshold and forwarding)
   * @param {string|null} options.mojangHost mock Mojang (see mojang.js) the server takes its
   *   services keys from, or null to keep Mojang's
   */
  constructor(options) {
    Object.assign(this, options);
    this.process = null;
  }

  /**
   * @param {string} templateDir files a previous boot of the same build downloaded or patched
   * @param {Record<string, string>} preseeded relative path → local file to copy in before boot
   */
  prepare(templateDir, preseeded = {}) {
    rmSync(this.dir, { recursive: true, force: true });
    mkdirSync(this.dir, { recursive: true });
    if (templateDir && existsSync(templateDir)) cpSync(templateDir, this.dir, { recursive: true });
    for (const [relative, file] of Object.entries(preseeded)) {
      mkdirSync(dirname(join(this.dir, relative)), { recursive: true });
      cpSync(file, join(this.dir, relative));
    }
    writeFileSync(join(this.dir, 'eula.txt'), 'eula=true\n');
    writeFileSync(join(this.dir, 'server.properties'), serverProperties(this));
    if (this.server.type === 'paper') {
      // Bukkit throttles reconnects from one address to one per 4 s by default; every bot comes
      // from the proxy's address.
      writeFileSync(join(this.dir, 'bukkit.yml'), 'settings:\n  connection-throttle: -1\n');
      // No metrics: bStats, and its predecessor PluginMetrics on 1.8.
      mkdirSync(join(this.dir, 'plugins', 'bStats'), { recursive: true });
      writeFileSync(join(this.dir, 'plugins', 'bStats', 'config.yml'), 'enabled: false\n');
      mkdirSync(join(this.dir, 'plugins', 'PluginMetrics'), { recursive: true });
      writeFileSync(join(this.dir, 'plugins', 'PluginMetrics', 'config.yml'), 'opt-out: true\n');
      // Paper 1.7.10 shows its EULA notice and sleeps 10 s unless this file exists, then creates
      // it. Its -Dcom.mojang.eula.agree=true would skip the notice too, but that path also skips
      // the server's setup and crashes it (Spigot's DedicatedServer#init).
      writeFileSync(join(this.dir, '.eula-lock'), '');
    }
    if (this.forwarding) {
      const { file, yaml } = paperForwardingConfig(this.protocol, this.forwarding);
      mkdirSync(dirname(join(this.dir, file)), { recursive: true });
      writeFileSync(join(this.dir, file), yaml);
    }
  }

  start() {
    const args = [
      '-Xms256m',
      '-Xmx1g',
      '-XX:+UseSerialGC',
      '-Djava.awt.headless=true',
      '-Dfile.encoding=UTF-8',
      // Plain stdin console: lets the harness type commands (`whitelist on`, `stop`).
      '-Dterminal.jline=false',
      '-Djline.terminal=jline.UnsupportedTerminal',
      '-Dlog4j2.formatMsgNoLookups=true',
      '-DPaper.IgnoreJavaVersion=true',
      '-Dpaper.disablePluginRemapping=true',
      // Paper 1.7.10 warns about its UUID conversion and sleeps 10 s unless this is set
      // (CraftBukkit's Main); a new server has nothing to convert. Other builds ignore it.
      '-DIReallyKnowWhatIAmDoingThisUpdate=true',
      ...authlibHosts(this.mojangHost),
      '-jar',
      this.jar,
      'nogui',
    ];
    this.process = new ManagedProcess(this.name, this.java, args, {
      cwd: this.dir,
      logFile: this.logFile,
      failures: BACKEND_PROTOCOL_ERRORS,
    }).start();
    return this.process;
  }

  /** The server's log, which every boot of the same backend threshold and forwarding appends to. */
  get logFile() {
    return join(this.logDir, `${this.name}-${this.logSuffix}.log`);
  }

  ready(timeoutMs) {
    return this.process.waitFor(READY, timeoutMs);
  }

  /**
   * Prepares and starts the server, and waits until it is ready. A boot that hangs (the server still
   * runs, without its ready line after `timeoutMs`) has its threads dumped into the log, and the
   * server boots again, up to BOOT_RETRIES times; `onHang` hears why before each new boot. A server
   * that exits fails at once: a crash is not a hang.
   * @param {string} templateDir see {@link prepare}
   * @param {Record<string, string>} preseeded see {@link prepare}
   * @param {{timeoutMs?: number, onHang: (reason: string) => void}} options
   */
  async boot(templateDir, preseeded, { timeoutMs = BOOT_TIMEOUT_MS, onHang }) {
    for (let attempt = 1; ; attempt++) {
      this.prepare(templateDir, preseeded);
      this.start();
      try {
        return await this.ready(timeoutMs);
      } catch (e) {
        if (!this.process.alive || attempt > BOOT_RETRIES) throw e;
      }
      await this.killHung();
      onHang(`no ready line within ${timeoutMs / 1000} s`);
    }
  }

  /**
   * Writes the threads of a server that hangs into its log (on SIGQUIT the JVM prints them on its
   * standard output), then kills it.
   */
  async killHung() {
    const from = this.process.mark();
    this.process.signal('SIGQUIT');
    // The last section of a HotSpot thread dump: "JNI global references" (8), "JNI global refs" (11+).
    await this.process.waitFor(/^JNI global ref/, 10_000, from).catch(() => {});
    return this.process.stop({ graceMs: 5_000 });
  }

  /** Runs a console command and waits until the server acknowledges it with a matching line. */
  async command(line, acknowledgement, timeoutMs = 10_000) {
    const from = this.process.mark();
    this.process.send(line);
    if (acknowledgement) await this.process.waitFor(acknowledgement, timeoutMs, from);
  }

  stop() {
    return this.process ? this.process.stop({ command: 'stop', graceMs: 45_000 }) : Promise.resolve(null);
  }

  /** Lines from this backend's log (from line `from` on) that indicate a broken client connection. */
  protocolErrors(from = 0) {
    return this.process?.failures(from) ?? [];
  }
}

/**
 * Points authlib at the mock Mojang. From 1.20 (authlib 4) the server fetches the keys that verify
 * player profile keys from the services host (`/publickeys`), so it then accepts the chat sessions of
 * bots whose keys the mock signed. authlib only takes custom hosts when all the ones it reads are
 * set (else it logs "Ignoring hosts properties"), and ignores the others.
 */
function authlibHosts(host) {
  if (!host) return [];
  return AUTHLIB_HOSTS.map((service) => `-Dminecraft.api.${service}.host=${host}`);
}

function serverProperties(b) {
  // Numeric game mode and difficulty: servers before 1.14 do not accept names. Unknown keys are
  // ignored by every version, so one superset works from 1.7 to the latest.
  const props = {
    'server-ip': '127.0.0.1',
    'server-port': b.port,
    'online-mode': false,
    'network-compression-threshold': b.compressionThreshold,
    'max-players': 100,
    'level-type': 'flat',
    'generate-structures': false,
    'allow-nether': false,
    'spawn-monsters': false,
    'spawn-animals': false,
    'spawn-npcs': false,
    difficulty: 0,
    gamemode: b.gameMode,
    'force-gamemode': true,
    'view-distance': 5,
    'simulation-distance': 4,
    'spawn-protection': 0,
    'white-list': false,
    'enforce-whitelist': false,
    'enforce-secure-profile': false,
    'prevent-proxy-connections': false,
    'player-idle-timeout': 0,
    'pause-when-empty-seconds': -1,
    'max-tick-time': -1,
    'rate-limit': 0,
    'sync-chunk-writes': false,
    // Netty's epoll transport in 1.8–1.11 breaks on Java 9+; NIO everywhere keeps it uniform.
    'use-native-transport': false,
    'enable-status': true,
    'enable-query': false,
    'enable-rcon': false,
    'snooper-enabled': false,
    motd: `warp-e2e ${b.name}`,
    // Offered to every player on join, never required (from 1.17). Its id is read from 1.20.3.
    'resource-pack': b.resourcePack.url,
    [b.protocol < RESOURCE_PACK_SHA1_SINCE ? 'resource-pack-hash' : 'resource-pack-sha1']: b.resourcePack.sha1,
    'resource-pack-id': b.resourcePack.id,
    'require-resource-pack': false,
  };
  return `${Object.entries(props).map(([k, v]) => `${k}=${v}`).join('\n')}\n`;
}
