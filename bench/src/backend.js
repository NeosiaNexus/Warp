// A Paper backend for the benchmark: downloaded once, configured for whichever forwarding the proxy
// under test uses, booted, and driven through its console. Three of these run behind each proxy so
// that bots can switch between them. The configuration mirrors the e2e harness (offline mode, flat
// pre-generated world, no mobs, native transport) so the backend is never the bottleneck.
import { mkdirSync, rmSync, writeFileSync } from 'node:fs';
import { dirname, join } from 'node:path';

import { download, findJava, ManagedProcess } from './reuse.js';

const READY = /Done \(\d+[.,]\d+s\)!/;
const BOOT_TIMEOUT_MS = 240_000;

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
    if (this.forwarding === 'legacy') {
      writeFileSync(join(this.dir, 'spigot.yml'), 'settings:\n  bungeecord: true\n');
    } else if (this.forwarding === 'modern') {
      const file = join(this.dir, 'config', 'paper-global.yml');
      mkdirSync(dirname(file), { recursive: true });
      writeFileSync(
        file,
        `proxies:\n  velocity:\n    enabled: true\n    online-mode: false\n    secret: '${this.secret}'\n`,
      );
    }
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
    this.process = new ManagedProcess(this.name, this.java, args, {
      cwd: this.dir,
      logFile: join(this.logDir, `backend-${this.name}.log`),
      failures: BACKEND_ERRORS,
    }).start();
    return this.process;
  }

  async boot() {
    this.prepare();
    this.start();
    await this.process.waitFor(READY, BOOT_TIMEOUT_MS);
    return this;
  }

  send(line) {
    this.process?.send(line);
  }

  stop() {
    return this.process ? this.process.stop({ command: 'stop', graceMs: 45_000 }) : Promise.resolve(null);
  }
}

/**
 * Boots the three backends behind a proxy, in parallel, each with the forwarding the proxy uses.
 * @returns {Promise<Backend[]>}
 */
export async function bootBackends({ jar, javaMajor, cacheDir, baseDir, logDir, basePort, backend, forwarding, secret, compressionThreshold, maxHeapMb }) {
  const java = await findJava(javaMajor, cacheDir);
  const names = ['lobby', 'survival', 'mini'];
  const backends = names.map(
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
      }),
  );
  await Promise.all(backends.map((b) => b.boot()));
  return backends;
}
