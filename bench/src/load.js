// Load generators. They run inside the worker processes of the fleet (fleet.js, worker.js), never in
// the orchestrating process: one Node.js thread cannot drive hundreds of connections and time them
// honestly, so the load is split over several processes, each pinned to its own core, and each
// reports how late its event loop ran so the validity gate can tell when the generator, not the
// proxy, was the limit.
//
// Idle connections and movers use the dependency-free raw client (thousands per process). A mover
// walks back and forth along its own line at sprint speed, which makes the backend stream chunks
// through the proxy continuously. Switch bots reuse the e2e mineflayer client, which runs the
// /server command and parses every packet exactly as a player's client would. The latency probe is a
// raw client timing the PLAY ping round trip. The storm generator opens raw hostile connections
// while a real bot tries to join, to check that genuine players still get in.
import { connect as netConnect } from 'node:net';

import { probeRounds, settle } from './probe.js';
import { connectIdle, frameHandshake, frameStatusRequest, MOVER_TICKS_PER_SEC } from './rawclient.js';

const HOST = '127.0.0.1';
const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));

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
  client.on('state', (state) => {
    if (state !== 'play') return;
    reconfiguring = false;
    // The client library arms a one-shot listener for the code of conduct packet at every
    // configuration phase. A 1.21.4 server never sends that packet, so the listener of each past
    // phase would stay armed for good and a bot that switches servers would pile them up (Node
    // warns from the eleventh on). The phase is over: drop it.
    client.removeAllListeners('code_of_conduct');
  });
  client.write = (name, params) => {
    if (reconfiguring && !CONFIG_PHASE_PACKETS.has(name)) return;
    write(name, params);
  };
  return handle;
}

/** Connects a mineflayer bot with the configuration-phase guard installed. */
async function connectGuarded(options) {
  // Imported on first use: a worker that only drives raw clients never loads the bot library.
  const { connect } = await import('../../e2e/src/clients/mineflayer.js');
  return guardConfigPhase(await connect(options));
}

/** Spreads `count` actions over `count/ratePerSec` seconds, calling `fn(i)` for each. */
export async function ramp(count, ratePerSec, fn) {
  const gap = 1000 / Math.max(0.001, ratePerSec);
  const started = [];
  const begin = Date.now();
  for (let i = 0; i < count; i++) {
    const wait = begin + i * gap - Date.now();
    if (wait > 0) await sleep(wait);
    started.push(fn(i));
  }
  return started;
}

// ---------------------------------------------------------------------------
// Raw clients: idle connections and movers
// ---------------------------------------------------------------------------

/** The line a mover walks: out to `max` blocks from its spawn point, back to `min`, and again. */
export function moverPath(index) {
  // The golden angle spreads consecutive movers around the circle; leg lengths differ so that the
  // movers do not all turn, or all come back near the spawn point, at the same moment.
  return { heading: (index * 2.399963229728653) % (2 * Math.PI), min: 64, max: 160 + ((index * 37) % 96) };
}

export class RawPool {
  /**
   * @param {object} options
   * @param {number} options.port
   * @param {boolean} options.movers whether the clients walk (traffic) or stand still (idle)
   */
  constructor({ port, movers = false }) {
    this.port = port;
    this.movers = movers;
    this.clients = [];
    this.failures = [];
    this.ticker = null;
  }

  /**
   * Ramps the clients named `names` in at `ratePerSec`, resolving once each has reached PLAY or
   * failed. `indexes` are the clients' positions in the whole fleet (they pick each mover's path).
   */
  async fill(names, indexes, ratePerSec, timeoutMs = 30_000) {
    if (this.movers && !this.ticker) {
      this.ticker = setInterval(() => {
        for (const c of this.clients) c.tick();
      }, 1000 / MOVER_TICKS_PER_SEC);
    }
    const pending = await ramp(names.length, ratePerSec, (i) =>
      connectIdle({ host: HOST, port: this.port, username: names[i], path: this.movers ? moverPath(indexes[i]) : null }, timeoutMs)
        .then((c) => this.clients.push(c))
        .catch((e) => this.failures.push(e.message)),
    );
    await Promise.all(pending);
  }

  status() {
    const sum = (key) => this.clients.reduce((s, c) => s + c[key], 0);
    return {
      online: this.clients.filter((c) => !c.closed && !c.error).length,
      failed: this.failures.length,
      failureSamples: this.failures.slice(0, 3),
      bytesIn: sum('bytesIn'),
      frames: sum('frames'),
      compressedFrames: sum('compressedFrames'),
      teleports: sum('teleports'),
      kicked: this.clients.filter((c) => c.kicked).length,
    };
  }

  async drain() {
    clearInterval(this.ticker);
    this.ticker = null;
    for (const c of this.clients) c.destroy();
    this.clients = [];
    this.failures = [];
    await sleep(200);
  }
}

// ---------------------------------------------------------------------------
// Latency probe (PLAY ping round trip through the proxy vs direct to the backend)
// ---------------------------------------------------------------------------

/** The join burst has ended once no compressed frame (a chunk) has arrived for this long. */
const BURST_QUIET_MS = 1_500;
/** Never wait longer than this for the join burst to end. */
const BURST_MAX_MS = 20_000;

/**
 * One raw client sends the PLAY ping request at a fixed cadence and times the pong. The ping is a
 * request-response that travels client to proxy to backend and back, and the server answers it from
 * its network thread without waiting for a game tick, so the round trip is the path and not the
 * backend's tick phase. The probe stands still (it loads no chunk after its join burst), waits for
 * that burst to end, runs `warmup` rounds it discards, then `samples` rounds it keeps. Times come
 * from the monotonic clock in fractional milliseconds. A round slower than `timeoutMs`, or cut by a
 * disconnect, counts as `timeoutMs` (see probe.js).
 * @returns {Promise<{latencies: number[], timeouts: number, warmup: number, settled: boolean}>}
 */
export async function probeLatency({ port, samples, intervalMs, warmup = 0, timeoutMs = 8_000, username = 'probe' }) {
  const client = await connectIdle({ host: HOST, port, username }, 30_000);
  try {
    const settled = await settle(() => client.compressedFrames, { quietMs: BURST_QUIET_MS, maxMs: BURST_MAX_MS, sleep });
    const result = await probeRounds({
      round: async () => {
        if ((await client.ping()) === null) throw new Error('connection closed');
      },
      samples,
      warmup,
      intervalMs,
      timeoutMs,
      sleep,
      now: () => performance.now(),
    });
    return { ...result, settled };
  } finally {
    client.destroy();
  }
}

// ---------------------------------------------------------------------------
// Switch storm (bots repeatedly change backend)
// ---------------------------------------------------------------------------

export class SwitchPool {
  constructor({ port, version, servers }) {
    this.port = port;
    this.version = version;
    this.servers = servers; // ordered backend names
    this.bots = [];
    this.failures = [];
    this.switches = 0;
    this.switchFailures = 0;
    this.latencies = [];
    this.firstChunkLatencies = [];
    this.running = false;
  }

  async fill(names, indexes, ratePerSec) {
    this.indexes = indexes;
    const pending = await ramp(names.length, ratePerSec, (i) =>
      connectGuarded({ host: HOST, port: this.port, version: this.version, username: names[i] })
        .then((b) => this.bots.push(Object.assign(b, { fleetIndex: indexes[i] })))
        .catch((e) => this.failures.push(e.message)),
    );
    await Promise.all(pending);
  }

  status() {
    return {
      online: this.bots.filter((b) => !b.stats.ended).length,
      failed: this.failures.length,
      failureSamples: this.failures.slice(0, 3),
      bytesIn: 0,
      frames: 0,
      compressedFrames: 0,
      teleports: 0,
      kicked: this.bots.filter((b) => b.stats.kicked).length,
    };
  }

  /**
   * Each bot cycles through the backends with /server, measuring how long the switch takes, and
   * rests `pauseMs` between two switches: the pause sets the offered switch rate, so the backends
   * (each switch is a full login and a view distance of chunks for one of them) are not the limit.
   */
  async storm(durationMs, pauseMs) {
    this.running = true;
    const deadline = Date.now() + durationMs;
    const loops = this.bots.map(async (handle) => {
      // Bots start their first switch spread over one pause, not all in the same instant.
      await sleep((handle.fleetIndex * 137) % Math.max(1, pauseMs));
      // Every bot joined the first (default) server, so its first hop must go anywhere but there:
      // the proxy answers a /server to the current server with "already connected", not a switch.
      let next = handle.fleetIndex % (this.servers.length - 1);
      while (this.running && Date.now() < deadline && !handle.stats.ended) {
        next = (next + 1) % this.servers.length;
        const target = this.servers[next];
        const t0 = performance.now();
        // Only chunks that follow the new backend's Join Game count. The old backend is usually
        // still streaming its world when the command is sent, and those chunks keep reaching the
        // bot until the proxy cuts over: counting them would time the old backend's tail, not the
        // switch. Arrival times are taken in the packet listener, so they are not rounded to a poll.
        const seen = { joined: false, chunks: 0, firstAt: 0, sixthAt: 0 };
        const onPacket = (data, meta) => {
          if (meta.name === 'login') {
            seen.joined = true;
            seen.chunks = 0;
          } else if (seen.joined && meta.name === 'map_chunk') {
            seen.chunks++;
            if (seen.chunks === 1) seen.firstAt = performance.now();
            else if (seen.chunks === 6) seen.sixthAt = performance.now();
          }
        };
        const client = handle.bot._client;
        client.on('packet', onPacket);
        try {
          await handle.command(`server ${target}`);
          // First chunk: the proxy has connected the bot to the new backend and the backend has
          // logged it in and started sending its world. Five more chunks: the world is arriving.
          await handle.waitFor(() => seen.firstAt > 0, `first chunk after switch to ${target}`, 15_000);
          await handle.waitFor(() => seen.sixthAt > 0, `chunks after switch to ${target}`, 15_000);
          this.firstChunkLatencies.push(seen.firstAt - t0);
          this.switches++;
          this.latencies.push(seen.sixthAt - t0);
        } catch {
          this.switchFailures++;
        } finally {
          client.removeListener('packet', onPacket);
        }
        await sleep(pauseMs);
      }
    });
    await Promise.allSettled(loops);
    this.running = false;
    return {
      switches: this.switches,
      failures: this.switchFailures,
      latencies: this.latencies,
      firstChunkLatencies: this.firstChunkLatencies,
    };
  }

  async drain() {
    this.running = false;
    await Promise.allSettled(this.bots.map((b) => b.quit()));
    this.bots = [];
    this.failures = [];
    await sleep(200);
  }
}

// ---------------------------------------------------------------------------
// Connection / status storm
// ---------------------------------------------------------------------------

/**
 * Opens raw connections at `ratePerSec` for `durationMs`, each a handshake to the status state and
 * a status request: a cheap, well-formed hostile connection. Returns how many connected.
 */
export async function flood({ port, ratePerSec, durationMs }) {
  let opened = 0;
  let attempted = 0;
  const sockets = new Set();
  const open = () => {
    attempted++;
    const socket = netConnect(port, HOST);
    sockets.add(socket);
    socket.on('error', () => {});
    socket.on('close', () => sockets.delete(socket));
    socket.on('connect', () => {
      opened++;
      socket.write(frameHandshake(port, 1));
      socket.write(frameStatusRequest());
    });
    setTimeout(() => socket.destroy(), 3_000);
  };
  // Scheduled against the clock, several per timer tick when the rate is above the timer's
  // resolution, so the offered rate does not sag to what one timer per connection can deliver.
  const begin = Date.now();
  while (Date.now() - begin < durationMs) {
    const due = Math.floor(((Date.now() - begin) / 1000) * ratePerSec);
    while (attempted < due) open();
    await sleep(2);
  }
  for (const s of sockets) s.destroy();
  return { opened, attempted };
}

/** A genuine player joins and plays for a second. Resolves with whether it got in. */
export async function joinOnce({ port, version, username }) {
  try {
    const bot = await connectGuarded({ host: HOST, port, version, username });
    await sleep(1_000);
    await bot.quit();
    return { joined: true, error: null };
  } catch (e) {
    return { joined: false, error: e.message };
  }
}
