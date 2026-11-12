// A minimal Minecraft backend for the idle-connection scenario. Measuring what an idle connection
// costs a proxy needs thousands of connections that are really idle, and a real server cannot give
// that on one machine: every player it holds costs it ticks, chunks and entity tracking, it starts
// refusing logins long before the proxy is under any pressure, and players standing in one world see
// each other, so the proxy forwards a stream of entity packets and the connections are not idle.
//
// The stub speaks just enough of protocol 769 (1.21.4) for a proxy to carry a player into the PLAY
// state and hold it there:
//   - handshake and login, with the player-information forwarding the proxy under test uses: modern
//     (Velocity's login plugin message, whose HMAC-SHA256 signature is verified against the shared
//     secret), legacy (BungeeCord's extended handshake) or none;
//   - Set Compression at the same threshold as the Paper backends, so the proxy's backend link runs
//     the same pipeline as it does against Paper;
//   - a configuration phase reduced to Finish Configuration (no registries: only the raw client of
//     this harness connects, and it ignores them);
//   - one Join Game, then a keep-alive every 15 seconds, the vanilla cadence, and nothing else;
//   - an immediate Pong Response to a PLAY ping request, like a vanilla server.
// A connection that leaves a keep-alive unanswered for 30 seconds is closed, as vanilla does.
//
// It runs as its own process (so it can be pinned away from the proxy) and prints `Done` when it
// listens. The line `stats` on its standard input prints one `STATS {...}` JSON line, from which the
// harness reads how many players it holds, how many logins it refused, and how late its event loop
// ran (its own saturation signal: the validity gate rejects a level the stub could not keep up with).
import { createHmac, timingSafeEqual } from 'node:crypto';
import { createServer } from 'node:net';
import { monitorEventLoopDelay } from 'node:perf_hooks';
import { createInterface } from 'node:readline';
import { fileURLToPath } from 'node:url';
import { deflateSync } from 'node:zlib';

import { IDS, mcString, readVarInt, varInt } from './rawclient.js';

/** Milliseconds between keep-alives to one player: the vanilla server's 15 seconds. */
export const KEEP_ALIVE_INTERVAL_MS = 15_000;
/** A player that leaves a keep-alive unanswered this long is disconnected, as on vanilla. */
export const KEEP_ALIVE_TIMEOUT_MS = 30_000;
/** The channel of Velocity's modern forwarding. */
const VELOCITY_CHANNEL = 'velocity:player_info';
/** Forwarding version requested: 1, the plain profile (address, UUID, name, properties). */
const VELOCITY_FORWARDING_VERSION = 1;

const STATE = { HANDSHAKE: 0, STATUS: 1, LOGIN: 2, CONFIG: 3, PLAY: 4 };

// ---------------------------------------------------------------------------
// Packets
// ---------------------------------------------------------------------------

function readString(buf, offset) {
  const len = readVarInt(buf, offset);
  if (!len) throw new Error('truncated string');
  const end = len.offset + len.value;
  if (end > buf.length) throw new Error('truncated string');
  return { value: buf.toString('utf8', len.offset, end), offset: end };
}

/**
 * The Join Game packet body (after the packet id) for protocol 769: one flat overworld, creative
 * mode, the smallest view distance. A proxy reads this packet to learn the player's entity id and
 * dimension, so it must be well formed.
 */
export function joinGameBody(entityId) {
  const int = Buffer.alloc(4);
  int.writeInt32BE(entityId);
  const world = mcString('minecraft:overworld');
  return Buffer.concat([
    int,
    Buffer.from([0]), // not hardcore
    varInt(1),
    world, // the one world the server has
    varInt(100000), // max players
    varInt(2), // view distance
    varInt(2), // simulation distance
    Buffer.from([0, 1, 0]), // reduced debug info off, respawn screen on, limited crafting off
    varInt(0), // dimension type id
    world, // dimension name
    Buffer.alloc(8), // hashed seed
    Buffer.from([1, 0xff]), // creative, no previous game mode
    Buffer.from([0, 1]), // not a debug world, flat
    Buffer.from([0]), // no death location
    varInt(0), // portal cooldown
    varInt(63), // sea level
    Buffer.from([0]), // secure chat not enforced
  ]);
}

/**
 * Checks a modern-forwarding response (signature, then the signed payload) and extracts the player.
 * @param {Buffer} data the login plugin response's data
 * @param {string} secret the shared forwarding secret
 * @returns {{uuid: Buffer, name: string}|null} null when the signature does not match
 */
export function readModernForwarding(data, secret) {
  if (data.length < 33) return null;
  const signature = data.subarray(0, 32);
  const payload = data.subarray(32);
  const expected = createHmac('sha256', secret).update(payload).digest();
  if (!timingSafeEqual(signature, expected)) return null;
  const version = readVarInt(payload, 0);
  const address = readString(payload, version.offset);
  const uuid = payload.subarray(address.offset, address.offset + 16);
  const name = readString(payload, address.offset + 16);
  return { uuid: Buffer.from(uuid), name: name.value };
}

/**
 * Extracts the player's UUID from a BungeeCord (legacy) forwarding handshake host:
 * `host\0address\0uuid[\0properties]`.
 * @returns {Buffer|null} the 16-byte UUID, or null when the handshake carries no forwarding
 */
export function readLegacyForwarding(host) {
  const parts = host.split('\0');
  if (parts.length < 3 || !/^[0-9a-fA-F]{32}$/.test(parts[2])) return null;
  return Buffer.from(parts[2], 'hex');
}

// ---------------------------------------------------------------------------
// Server
// ---------------------------------------------------------------------------

class Connection {
  constructor(server, socket) {
    this.server = server;
    this.socket = socket;
    this.state = STATE.HANDSHAKE;
    this.compressed = false;
    this.inbox = Buffer.alloc(0);
    this.host = '';
    this.name = '';
    this.uuid = null;
    this.playing = false;
    this.nextKeepAlive = 0;
    this.awaitingKeepAliveSince = 0;
    socket.setNoDelay(true);
    socket.on('data', (chunk) => this.onData(chunk));
    socket.on('error', () => {});
    socket.on('close', () => this.server.forget(this));
  }

  onData(chunk) {
    this.inbox = this.inbox.length ? Buffer.concat([this.inbox, chunk]) : chunk;
    try {
      for (;;) {
        const header = readVarInt(this.inbox, 0);
        if (!header) break;
        const end = header.offset + header.value;
        if (this.inbox.length < end) break;
        let body = this.inbox.subarray(header.offset, end);
        this.inbox = this.inbox.subarray(end);
        if (this.compressed) {
          const dataLen = readVarInt(body, 0);
          if (dataLen.value !== 0) continue; // nothing the stub reads is large enough to be compressed
          body = body.subarray(dataLen.offset);
        }
        const id = readVarInt(body, 0);
        this.handle(id.value, body.subarray(id.offset));
      }
    } catch {
      this.refuse();
    }
  }

  handle(id, data) {
    switch (this.state) {
      case STATE.HANDSHAKE: return this.onHandshake(data);
      case STATE.STATUS: return this.onStatus(id, data);
      case STATE.LOGIN: return this.onLogin(id, data);
      case STATE.CONFIG: return this.onConfig(id);
      default: return this.onPlay(id, data);
    }
  }

  onHandshake(data) {
    const protocol = readVarInt(data, 0);
    const host = readString(data, protocol.offset);
    const next = readVarInt(data, host.offset + 2);
    this.host = host.value;
    this.state = next.value === 1 ? STATE.STATUS : STATE.LOGIN;
  }

  onStatus(id, data) {
    if (id === 0x00) {
      const json = JSON.stringify({ version: { name: '1.21.4', protocol: 769 }, players: { max: 100000, online: this.server.players.size }, description: { text: 'bench stub' } });
      this.send(0x00, mcString(json));
    } else if (id === 0x01) {
      this.send(0x01, data.subarray(0, 8));
    }
  }

  onLogin(id, data) {
    const { forwarding, secret } = this.server.options;
    if (id === IDS.login.toServer.loginStart) {
      const name = readString(data, 0);
      this.name = name.value;
      this.uuid = Buffer.from(data.subarray(name.offset, name.offset + 16));
      if (forwarding === 'modern') {
        this.send(IDS.login.toClient.pluginRequest, varInt(1), mcString(VELOCITY_CHANNEL), Buffer.from([VELOCITY_FORWARDING_VERSION]));
        return;
      }
      if (forwarding === 'legacy') {
        const uuid = readLegacyForwarding(this.host);
        if (!uuid) return this.refuse();
        this.uuid = uuid;
      }
      this.acceptLogin();
    } else if (id === IDS.login.toServer.pluginResponse) {
      const messageId = readVarInt(data, 0);
      const successful = data[messageId.offset] === 1;
      const player = successful ? readModernForwarding(data.subarray(messageId.offset + 1), secret) : null;
      if (!player) return this.refuse();
      this.uuid = player.uuid;
      this.name = player.name;
      this.acceptLogin();
    } else if (id === IDS.login.toServer.acknowledged) {
      this.state = STATE.CONFIG;
      this.send(IDS.configuration.toClient.finish);
    }
  }

  acceptLogin() {
    const { compressionThreshold } = this.server.options;
    if (compressionThreshold >= 0) {
      this.send(IDS.login.toClient.compress, varInt(compressionThreshold));
      this.compressed = true;
    }
    this.send(IDS.login.toClient.success, this.uuid, mcString(this.name), varInt(0));
  }

  onConfig(id) {
    if (id !== IDS.configuration.toServer.finish) return; // client information, brand: ignored
    this.state = STATE.PLAY;
    this.playing = true;
    this.server.logins++;
    this.server.players.add(this);
    this.send(IDS.play.toClient.login, joinGameBody(this.server.nextEntityId++));
    this.nextKeepAlive = Date.now() + KEEP_ALIVE_INTERVAL_MS;
  }

  onPlay(id, data) {
    if (id === IDS.play.toServer.keepAlive) {
      this.awaitingKeepAliveSince = 0;
    } else if (id === IDS.play.toServer.pingRequest) {
      this.send(IDS.play.toClient.pongResponse, data.subarray(0, 8));
    }
  }

  /** Keep-alive bookkeeping, called once a second for every player. */
  tick(now) {
    if (this.awaitingKeepAliveSince && now - this.awaitingKeepAliveSince > KEEP_ALIVE_TIMEOUT_MS) {
      this.server.keepAliveTimeouts++;
      this.socket.destroy();
      return;
    }
    if (now >= this.nextKeepAlive) {
      const payload = Buffer.alloc(8);
      payload.writeBigInt64BE(BigInt(now));
      this.send(IDS.play.toClient.keepAlive, payload);
      this.nextKeepAlive = now + KEEP_ALIVE_INTERVAL_MS;
      this.awaitingKeepAliveSince ||= now;
    }
  }

  send(id, ...parts) {
    if (this.socket.destroyed) return;
    const inner = Buffer.concat([varInt(id), ...parts]);
    if (!this.compressed) {
      this.socket.write(Buffer.concat([varInt(inner.length), inner]));
      return;
    }
    const threshold = this.server.options.compressionThreshold;
    const body = inner.length >= threshold ? Buffer.concat([varInt(inner.length), deflateSync(inner)]) : Buffer.concat([varInt(0), inner]);
    this.socket.write(Buffer.concat([varInt(body.length), body]));
  }

  refuse() {
    if (!this.playing) this.server.refused++;
    this.socket.destroy();
  }
}

export class StubServer {
  /**
   * @param {object} options
   * @param {number} options.port
   * @param {'modern'|'legacy'|'none'} options.forwarding
   * @param {string} options.secret the forwarding secret (modern)
   * @param {number} options.compressionThreshold negative for no compression
   */
  constructor(options) {
    this.options = options;
    this.players = new Set();
    this.logins = 0;
    this.refused = 0;
    this.keepAliveTimeouts = 0;
    this.nextEntityId = 1;
    this.server = createServer((socket) => new Connection(this, socket));
    this.ticker = null;
    this.lag = monitorEventLoopDelay({ resolution: 10 });
  }

  listen() {
    return new Promise((resolve, reject) => {
      this.server.once('error', reject);
      this.server.listen({ port: this.options.port, host: '127.0.0.1', backlog: 4096 }, () => {
        this.lag.enable();
        this.ticker = setInterval(() => {
          const now = Date.now();
          for (const player of this.players) player.tick(now);
        }, 1_000);
        resolve(this);
      });
    });
  }

  forget(connection) {
    this.players.delete(connection);
  }

  /** Counters since start, and the event-loop delay since the last call (then reset). */
  stats() {
    const lagMaxMs = Math.round(this.lag.max / 1e4) / 100;
    const lagP99Ms = Math.round(this.lag.percentile(99) / 1e4) / 100;
    this.lag.reset();
    return { players: this.players.size, logins: this.logins, refused: this.refused, keepAliveTimeouts: this.keepAliveTimeouts, lagMaxMs, lagP99Ms };
  }

  close() {
    clearInterval(this.ticker);
    this.lag.disable();
    for (const player of this.players) player.socket.destroy();
    return new Promise((resolve) => this.server.close(() => resolve()));
  }
}

// ---------------------------------------------------------------------------
// Process entry point
// ---------------------------------------------------------------------------

async function main(argv) {
  const arg = (name, fallback) => {
    const i = argv.indexOf(`--${name}`);
    return i >= 0 ? argv[i + 1] : fallback;
  };
  const server = await new StubServer({
    port: Number(arg('port')),
    forwarding: arg('forwarding', 'none'),
    secret: process.env.STUB_FORWARDING_SECRET ?? '',
    compressionThreshold: Number(arg('compression-threshold', '-1')),
  }).listen();
  console.log(`Done: stub backend listening on 127.0.0.1:${server.options.port} (forwarding ${server.options.forwarding})`);
  createInterface({ input: process.stdin }).on('line', (line) => {
    if (line.trim() === 'stats') console.log(`STATS ${JSON.stringify(server.stats())}`);
    else if (line.trim() === 'stop') server.close().then(() => process.exit(0));
  });
  process.on('SIGTERM', () => process.exit(0));
}

if (process.argv[1] === fileURLToPath(import.meta.url)) {
  main(process.argv.slice(2)).catch((e) => {
    console.error(e.stack ?? e.message);
    process.exit(1);
  });
}
