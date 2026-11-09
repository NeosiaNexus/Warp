// Load generators, all driven at a fixed offered rate so the proxy, not the generator, sets the
// pace. Idle connections use the dependency-free raw client (thousands per process); traffic, probe
// and switch bots reuse the e2e mineflayer client so they move, load chunks and run the /server
// command exactly as a player would. The storm generator opens raw hostile connections while a real
// bot tries to join, to check that genuine players still get in.
import { connect as netConnect } from 'node:net';

import { connect as connectBot } from '../../e2e/src/clients/mineflayer.js';
import { probeRounds, settle } from './probe.js';
import { sleep } from './reuse.js';
import { connectIdle, frameHandshake, frameStatusRequest } from './rawclient.js';

const HOST = '127.0.0.1';

/**
 * Packets a client may still send while it is back in the configuration phase. Everything else a
 * bot writes in that window (a teleport confirmation or a position from a physics tick that was
 * already queued when the server asked it to reconfigure) is a PLAY packet in CONFIG framing: a
 * vanilla client never sends one, Warp drops it, and Velocity decodes the id as a configuration
 * packet, fails, and kicks the player. The guard makes the bots behave like a vanilla client.
 */
const CONFIG_PHASE_PACKETS = new Set([
  'configuration_acknowledged', 'settings', 'finish_configuration', 'keep_alive', 'pong', 'select_known_packs',
  'resource_pack_receive', 'custom_payload', 'cookie_response', 'client_information',
]);

/** Drops PLAY-state writes between the start and the end of a configuration re-entry. */
function guardConfigPhase(handle) {
  const client = handle.bot._client;
  const write = client.write.bind(client);
  let reconfiguring = false;
  client.on('start_configuration', () => { reconfiguring = true; });
  client.on('state', (state) => { if (state === 'play') reconfiguring = false; });
  client.write = (name, params) => {
    if (reconfiguring && !CONFIG_PHASE_PACKETS.has(name)) return;
    write(name, params);
  };
  return handle;
}

/** Connects a mineflayer bot with the configuration-phase guard installed. */
function connectGuarded(options) {
  return connectBot(options).then(guardConfigPhase);
}

/** Spreads `count` actions over `count/ratePerSec` seconds, calling `fn(i)` for each. */
async function ramp(count, ratePerSec, fn) {
  const gap = 1000 / Math.max(1, ratePerSec);
  const started = [];
  for (let i = 0; i < count; i++) {
    started.push(fn(i));
    if (i < count - 1) await sleep(gap);
  }
  return started;
}

// ---------------------------------------------------------------------------
// Idle connections (raw client)
// ---------------------------------------------------------------------------

export class IdlePool {
  constructor({ port, prefix = 'idle' }) {
    this.port = port;
    this.prefix = prefix;
    this.clients = [];
    this.failures = [];
  }

  /** Ramps `count` idle connections in at `ratePerSec`, resolving once all have spawned or failed. */
  async fill(count, ratePerSec) {
    const pending = await ramp(count, ratePerSec, (i) =>
      connectIdle({ host: HOST, port: this.port, username: `${this.prefix}_${i}` }, 30_000)
        .then((c) => this.clients.push(c))
        .catch((e) => this.failures.push(e.message)),
    );
    await Promise.all(pending);
    return this;
  }

  get online() {
    return this.clients.filter((c) => !c.closed && !c.error).length;
  }

  async drain() {
    for (const c of this.clients) c.destroy();
    this.clients = [];
    await sleep(200);
  }
}

// ---------------------------------------------------------------------------
// Traffic bots (mineflayer, moving)
// ---------------------------------------------------------------------------

export class TrafficPool {
  constructor({ port, version, prefix = 'move' }) {
    this.port = port;
    this.version = version;
    this.prefix = prefix;
    this.bots = [];
    this.failures = [];
    this.movers = [];
    this.moving = false;
  }

  async fill(count, ratePerSec) {
    const pending = await ramp(count, ratePerSec, (i) =>
      connectGuarded({ host: HOST, port: this.port, version: this.version, username: `${this.prefix}_${i}` })
        .then((b) => this.bots.push(b))
        .catch((e) => this.failures.push(e.message)),
    );
    await Promise.all(pending);
    return this;
  }

  /** Starts every bot walking and turning, which sends position packets and loads chunks. */
  startMoving() {
    this.moving = true;
    for (const handle of this.bots) {
      const bot = handle.bot;
      const mover = (async () => {
        let yaw = 0;
        while (this.moving && !handle.stats.ended) {
          try {
            bot.setControlState('forward', true);
            yaw += Math.PI / 4;
            await bot.look(yaw, 0, true);
            bot.setControlState('jump', Math.random() < 0.2);
          } catch {
            break;
          }
          await sleep(500);
        }
      })();
      this.movers.push(mover);
    }
  }

  /** Chunks loaded across all bots so far: a proxy that forwards more does more work. */
  get chunks() {
    return this.bots.reduce((sum, b) => sum + b.stats.chunks, 0);
  }

  get online() {
    return this.bots.filter((b) => !b.stats.ended).length;
  }

  async drain() {
    this.moving = false;
    await Promise.allSettled(this.movers);
    await Promise.allSettled(this.bots.map((b) => b.quit()));
    this.bots = [];
    this.movers = [];
    await sleep(200);
  }
}

// ---------------------------------------------------------------------------
// Latency probe (tab-complete round-trip through the proxy vs direct to the backend)
// ---------------------------------------------------------------------------

/** The join burst has ended once no chunk has arrived for this long. */
const BURST_QUIET_MS = 1_500;
/** Never wait longer than this for the join burst to end. */
const BURST_MAX_MS = 20_000;

/**
 * One probe bot asks the server to complete a command prefix at a fixed cadence and times the reply.
 * A tab completion is a request-response that travels client to proxy to backend and back, like a
 * keep-alive but on demand and at a controlled rate, and crucially it is not chat or a command: a
 * modern server answers it without the signing that makes it disconnect an offline bot's chat or
 * command packet. The prefix must match at least one command, because a server sends no response for
 * a prefix with no suggestions; `/he` matches `help` on every server. The probe waits for the bot's
 * join burst (its chunks) to end, runs `warmup` rounds it discards, then `samples` rounds it keeps;
 * latency is measured from the intended send time, not the actual one, so a slow round does not
 * hide behind the next (the coordinated-omission guard), and a round slower than `timeoutMs` counts
 * as `timeoutMs` (see probe.js).
 * @returns {Promise<{latencies: number[], timeouts: number, warmup: number, settled: boolean}>}
 */
export async function probeLatency({ port, version, samples, intervalMs, warmup = 0, timeoutMs = 8_000, username = 'probe' }) {
  const bot = await connectGuarded({ host: HOST, port, version, username });
  try {
    const settled = await settle(() => bot.stats.chunks, { quietMs: BURST_QUIET_MS, maxMs: BURST_MAX_MS, sleep });
    const result = await probeRounds({
      round: () => bot.bot.tabComplete('/he', false),
      samples,
      warmup,
      intervalMs,
      timeoutMs,
      sleep,
    });
    return { ...result, settled };
  } finally {
    await bot.quit();
  }
}

// ---------------------------------------------------------------------------
// Switch storm (bots repeatedly change backend)
// ---------------------------------------------------------------------------

export class SwitchPool {
  constructor({ port, version, servers, prefix = 'sw' }) {
    this.port = port;
    this.version = version;
    this.servers = servers; // ordered backend names
    this.prefix = prefix;
    this.bots = [];
    this.failures = [];
    this.switches = 0;
    this.switchFailures = 0;
    this.latencies = [];
    this.firstChunkLatencies = [];
    this.running = false;
  }

  async fill(count, ratePerSec) {
    const pending = await ramp(count, ratePerSec, (i) =>
      connectGuarded({ host: HOST, port: this.port, version: this.version, username: `${this.prefix}_${i}` })
        .then((b) => this.bots.push(b))
        .catch((e) => this.failures.push(e.message)),
    );
    await Promise.all(pending);
    return this;
  }

  get online() {
    return this.bots.filter((b) => !b.stats.ended).length;
  }

  /** Each bot cycles through the backends with /server, measuring how long the switch takes. */
  async storm(durationMs) {
    this.running = true;
    const deadline = Date.now() + durationMs;
    const loops = this.bots.map(async (handle, idx) => {
      let next = idx % this.servers.length;
      while (this.running && Date.now() < deadline && !handle.stats.ended) {
        next = (next + 1) % this.servers.length;
        const target = this.servers[next];
        const t0 = Date.now();
        try {
          const chunks = handle.stats.chunks;
          await handle.command(`server ${target}`);
          // First chunk: the proxy has connected the bot to the new backend and the backend has
          // logged it in and started sending its world. Five chunks: the world is arriving.
          await handle.waitFor(() => handle.stats.chunks > chunks, `first chunk after switch to ${target}`, 15_000);
          this.firstChunkLatencies.push(Date.now() - t0);
          await handle.waitFor(() => handle.stats.chunks > chunks + 5, `chunks after switch to ${target}`, 15_000);
          this.switches++;
          this.latencies.push(Date.now() - t0);
        } catch {
          this.switchFailures++;
          await sleep(500);
        }
        await sleep(250);
      }
    });
    await Promise.allSettled(loops);
    this.running = false;
  }

  async drain() {
    this.running = false;
    await Promise.allSettled(this.bots.map((b) => b.quit()));
    this.bots = [];
    await sleep(200);
  }
}

// ---------------------------------------------------------------------------
// Connection / status storm
// ---------------------------------------------------------------------------

/**
 * Opens raw connections at `ratePerSec` for `durationMs`, mixing full status pings and half-open
 * handshakes, while a real bot joins partway through. Reports whether the real bot got in and how
 * many connections the proxy accepted.
 */
export async function connectionStorm({ port, version, ratePerSec, durationMs }) {
  let opened = 0;
  let joinedDuringStorm = false;
  let joinError = null;
  const deadline = Date.now() + durationMs;
  const sockets = new Set();

  const open = () => {
    const socket = netConnect(port, HOST);
    sockets.add(socket);
    socket.on('error', () => {});
    socket.on('close', () => sockets.delete(socket));
    // Handshake to status, then a status request: a cheap, well-formed hostile connection.
    socket.on('connect', () => {
      opened++;
      socket.write(frameHandshake(port, 1));
      socket.write(frameStatusRequest());
    });
    setTimeout(() => socket.destroy(), 3_000);
  };

  const flood = (async () => {
    const gap = 1000 / Math.max(1, ratePerSec);
    while (Date.now() < deadline) {
      open();
      await sleep(gap);
    }
  })();

  // Halfway through, a genuine player tries to join and play.
  const join = (async () => {
    await sleep(durationMs / 2);
    try {
      const bot = await connectGuarded({ host: HOST, port, version, username: 'storm_survivor' });
      joinedDuringStorm = true;
      await sleep(1_000);
      await bot.quit();
    } catch (e) {
      joinError = e.message;
    }
  })();

  await Promise.all([flood, join]);
  for (const s of sockets) s.destroy();
  return { opened, joinedDuringStorm, joinError };
}

export { ramp };
