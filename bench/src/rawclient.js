// A minimal, dependency-free Minecraft client that logs in offline and then sits in the PLAY state
// answering keep-alives. It exists for the idle-connection scenario, where thousands of these must
// run in one Node process: a full mineflayer bot runs physics and a world model per connection, far
// too heavy at that scale, while this client parses only the few packets it must answer and blindly
// drops the rest. It speaks one modern protocol (1.21.4, number 769): the idle scenario measures
// what a connection costs the proxy, which does not depend on the client's version.
//
// It handles compression (the proxy sets a threshold, so inbound frames may be zlib-compressed) and
// the 1.20.5+ configuration phase (known packs, finish configuration). It never deflates: every
// packet it sends is far below any threshold, so it sends them uncompressed (data length 0). It never
// inflates either: every packet it must answer (keep-alive, ping, position synchronisation, chunk
// batch end, configuration control) is far below the compression threshold, so it arrives with a
// data length of 0, and a compressed frame (a chunk, registry data) is counted and dropped unread.
// That keeps a client cheap enough for one process to hold thousands of them without the load
// generator becoming the bottleneck.
//
// Two optional behaviours build the other load shapes on the same client. A "mover" walks in a
// straight line at sprint speed by sending its position twenty times a second, which makes the
// backend stream new chunks through the proxy for as long as it walks (the heavy-traffic scenario).
// `ping()` sends the PLAY-state ping request, which a vanilla or Paper server answers from its
// network thread without waiting for a game tick, and times the reply with the monotonic
// nanosecond clock (the latency scenario).
import { randomUUID } from 'node:crypto';
import { connect } from 'node:net';

/** The protocol these clients speak, whatever the backend's version: 1.21.4. */
export const RAW_PROTOCOL = 769;

// Packet ids for protocol 769 (from minecraft-data). Serverbound unless noted.
const LOGIN_START = 0x00;
const LOGIN_ACKNOWLEDGED = 0x03;
const S_CLIENT_INFORMATION = 0x00; // configuration
const S_KNOWN_PACKS = 0x07; // configuration
const S_FINISH_CONFIGURATION = 0x03; // configuration
const S_CONFIG_KEEP_ALIVE = 0x04; // configuration
const S_CONFIG_PONG = 0x05; // configuration
const S_PLAY_TELEPORT_CONFIRM = 0x00;
const S_PLAY_CHUNK_BATCH_RECEIVED = 0x09;
const S_PLAY_CONFIGURATION_ACKNOWLEDGED = 0x0e;
const S_PLAY_KEEP_ALIVE = 0x1a;
const S_PLAY_POSITION = 0x1c;
const S_PLAY_PING_REQUEST = 0x24;
const S_PLAY_PONG = 0x2b;

const C_LOGIN_ENCRYPTION = 0x01;
const C_LOGIN_SUCCESS = 0x02;
const C_LOGIN_SET_COMPRESSION = 0x03;
const C_CONFIG_KNOWN_PACKS = 0x0e;
const C_CONFIG_FINISH = 0x03;
const C_CONFIG_KEEP_ALIVE = 0x04;
const C_CONFIG_PING = 0x05;
const C_LOGIN_DISCONNECT = 0x00;
const C_CONFIG_DISCONNECT = 0x02;
const C_PLAY_CHUNK_BATCH_FINISHED = 0x0c;
const C_PLAY_DISCONNECT = 0x1d;
const C_PLAY_KEEP_ALIVE = 0x27;
const C_PLAY_PING = 0x37;
const C_PLAY_PONG_RESPONSE = 0x38;
const C_PLAY_POSITION = 0x42;
const C_PLAY_START_CONFIGURATION = 0x70;

/** Packet ids of protocol 769 used by the raw client and the stub backend, by state and direction. */
export const IDS = Object.freeze({
  login: {
    toServer: { loginStart: LOGIN_START, pluginResponse: 0x02, acknowledged: LOGIN_ACKNOWLEDGED },
    toClient: { disconnect: C_LOGIN_DISCONNECT, success: 0x02, compress: 0x03, pluginRequest: 0x04 },
  },
  configuration: {
    toServer: { finish: 0x03, keepAlive: 0x04 },
    toClient: { disconnect: C_CONFIG_DISCONNECT, finish: 0x03, keepAlive: 0x04 },
  },
  play: {
    toServer: { keepAlive: S_PLAY_KEEP_ALIVE, pingRequest: S_PLAY_PING_REQUEST, position: S_PLAY_POSITION },
    toClient: { login: 0x2c, keepAlive: C_PLAY_KEEP_ALIVE, pongResponse: C_PLAY_PONG_RESPONSE, position: C_PLAY_POSITION },
  },
});

/** Blocks per second a mover walks: the vanilla sprinting speed. */
export const MOVER_SPEED = 5.6;
/** Position packets per second a mover sends: one per client tick, as a vanilla client does. */
export const MOVER_TICKS_PER_SEC = 20;
/** Chunks per tick a mover tells the server it can take (the vanilla client reports what it measured). */
const CHUNKS_PER_TICK = 25;

const STATE = { LOGIN: 0, CONFIG: 1, PLAY: 2 };

// ---------------------------------------------------------------------------
// Wire helpers
// ---------------------------------------------------------------------------

/** Encodes a non-negative integer as a VarInt. */
export function varInt(value) {
  const bytes = [];
  let v = value >>> 0;
  do {
    let b = v & 0x7f;
    v >>>= 7;
    if (v !== 0) b |= 0x80;
    bytes.push(b);
  } while (v !== 0);
  return Buffer.from(bytes);
}

/** A length-prefixed UTF-8 string. */
export function mcString(text) {
  const bytes = Buffer.from(text, 'utf8');
  return Buffer.concat([varInt(bytes.length), bytes]);
}

/** Reads a VarInt at `offset`; returns the value and the offset after it, or null if incomplete. */
export function readVarInt(buf, offset) {
  let result = 0;
  let shift = 0;
  let pos = offset;
  while (pos < buf.length) {
    const byte = buf[pos++];
    result |= (byte & 0x7f) << shift;
    if ((byte & 0x80) === 0) return { value: result >>> 0, offset: pos };
    shift += 7;
    if (shift >= 35) throw new Error('VarInt too long');
  }
  return null;
}

/** An offline-mode player UUID (version 3, namespace OfflinePlayer). Not cryptographic; good enough. */
export function offlineUuid() {
  return randomUUID();
}

/** An uncompressed frame: VarInt length of (packet id + parts), then them. */
export function framePacket(id, ...parts) {
  const inner = Buffer.concat([varInt(id), ...parts]);
  return Buffer.concat([varInt(inner.length), inner]);
}

/** A handshake frame for `nextState` (1 status, 2 login), announcing protocol 769. */
export function frameHandshake(port, nextState) {
  const portBytes = Buffer.alloc(2);
  portBytes.writeUInt16BE(port);
  return framePacket(0x00, varInt(RAW_PROTOCOL), mcString(HOST_NAME), portBytes, varInt(nextState));
}

/** A status request frame (empty packet 0x00 in the status state). */
export function frameStatusRequest() {
  return framePacket(0x00);
}

const HOST_NAME = '127.0.0.1';

// ---------------------------------------------------------------------------
// Client
// ---------------------------------------------------------------------------

export class RawClient {
  /**
   * @param {object} options
   * @param {string} options.host
   * @param {number} options.port
   * @param {string} options.username
   * @param {{heading: number, min: number, max: number}|null} [options.path] when set the client is
   *   a mover: it walks along `heading` (radians) out to `max` blocks from where the server placed
   *   it, back to `min`, and again
   */
  constructor({ host, port, username, path = null }) {
    this.host = host;
    this.port = port;
    this.username = username;
    this.path = path;
    this.travelled = 0; // blocks from the spawn point along the path
    this.outbound = true;
    this.position = null; // {x, y, z} once the server has placed the player
    this.teleports = 0;
    this.frames = 0;
    this.compressedFrames = 0;
    this.kicked = false;
    this.pings = new Map(); // ping id -> {sent: bigint, resolve}
    this.nextPing = 1n;
    this.state = STATE.LOGIN;
    this.threshold = -1; // compression off until the server sets it
    this.inbox = Buffer.alloc(0);
    this.socket = null;
    this.spawned = false;
    this.closed = false;
    this.error = null;
    this.bytesIn = 0;
    this.keepAlives = 0;
    this.onSpawn = null;
    this.onClose = null;
  }

  /** Connects and resolves once the client reaches the PLAY state (has "spawned"). */
  connect(timeoutMs = 30_000) {
    return new Promise((resolve, reject) => {
      const timer = setTimeout(() => this.fail(new Error(`${this.username}: no spawn within ${timeoutMs / 1000}s`)), timeoutMs);
      this.onSpawn = () => {
        clearTimeout(timer);
        resolve(this);
      };
      this.fail = (e) => {
        clearTimeout(timer);
        this.error ??= e;
        this.destroy();
        if (!this.spawned) reject(e);
      };
      const socket = connect(this.port, this.host);
      this.socket = socket;
      socket.setNoDelay(true);
      socket.on('connect', () => this.onConnected());
      socket.on('data', (chunk) => this.onData(chunk));
      socket.on('error', (e) => this.fail(new Error(`${this.username}: ${e.message}`)));
      socket.on('close', () => {
        this.closed = true;
        for (const waiter of this.pings.values()) waiter.resolve(null);
        this.pings.clear();
        if (!this.spawned) this.fail(new Error(`${this.username}: connection closed before spawn`));
        this.onClose?.();
      });
    });
  }

  onConnected() {
    // Handshake (next state 2 = login) then Login Start (name + UUID).
    const portBytes = Buffer.alloc(2);
    portBytes.writeUInt16BE(this.port);
    this.sendRaw(this.frame(0x00, varInt(RAW_PROTOCOL), mcString(this.host), portBytes, varInt(2)));
    const uuid = Buffer.from(offlineUuid().replace(/-/g, ''), 'hex');
    this.sendRaw(this.frame(LOGIN_START, mcString(this.username), uuid));
  }

  onData(chunk) {
    this.bytesIn += chunk.length;
    this.inbox = this.inbox.length ? Buffer.concat([this.inbox, chunk]) : chunk;
    try {
      for (;;) {
        const header = readVarInt(this.inbox, 0);
        if (!header) break;
        const total = header.value;
        const end = header.offset + total;
        if (this.inbox.length < end) break;
        const body = this.inbox.subarray(header.offset, end);
        this.inbox = this.inbox.subarray(end);
        this.frames++;
        const packet = this.decode(body);
        if (packet) this.handlePacket(packet);
        else this.compressedFrames++;
      }
    } catch (e) {
      this.fail(new Error(`${this.username}: ${e.message}`));
    }
  }

  /**
   * Strips the compression header (if active) and returns the packet id and its payload, or null
   * for a compressed frame: nothing this client answers is ever large enough to be compressed.
   */
  decode(body) {
    let payload = body;
    if (this.threshold >= 0) {
      const dataLen = readVarInt(body, 0);
      if (dataLen.value !== 0) return null;
      payload = body.subarray(dataLen.offset);
    }
    const id = readVarInt(payload, 0);
    return { id: id.value, data: payload.subarray(id.offset) };
  }

  handlePacket({ id, data }) {
    if (this.state === STATE.LOGIN) return this.handleLogin(id, data);
    if (this.state === STATE.CONFIG) return this.handleConfig(id, data);
    return this.handlePlay(id, data);
  }

  handleLogin(id, data) {
    if (id === C_LOGIN_SET_COMPRESSION) {
      this.threshold = readVarInt(data, 0).value;
    } else if (id === C_LOGIN_SUCCESS) {
      this.send(LOGIN_ACKNOWLEDGED);
      this.state = STATE.CONFIG;
      // Client Information: locale, view distance, chat mode, colours, skin parts, main hand,
      // text filtering, server listings, particle status. Some servers wait for it before they
      // finish the configuration phase.
      this.send(
        S_CLIENT_INFORMATION,
        mcString('en_us'),
        Buffer.from([8]), // view distance
        varInt(0), // chat mode: enabled
        Buffer.from([1]), // chat colours on
        Buffer.from([0x7f]), // all skin parts
        varInt(1), // main hand: right
        Buffer.from([0]), // text filtering off
        Buffer.from([1]), // server listings allowed
        varInt(0), // particle status: all
      );
    } else if (id === C_LOGIN_DISCONNECT) {
      this.kicked = true;
      const reason = readVarInt(data, 0);
      this.fail(new Error(`${this.username}: refused at login: ${data.subarray(reason.offset, reason.offset + reason.value).toString('utf8').slice(0, 200)}`));
    } else if (id === C_LOGIN_ENCRYPTION) {
      this.fail(new Error(`${this.username}: server is in online mode (unexpected for a benchmark)`));
    }
  }

  handleConfig(id, data) {
    if (id === C_CONFIG_KNOWN_PACKS) {
      this.send(S_KNOWN_PACKS, varInt(0)); // we know no packs; the server sends its registries
    } else if (id === C_CONFIG_FINISH) {
      this.send(S_FINISH_CONFIGURATION);
      this.state = STATE.PLAY;
      if (!this.spawned) {
        this.spawned = true;
        this.onSpawn?.();
      }
    } else if (id === C_CONFIG_DISCONNECT) {
      this.kicked = true;
      this.fail(new Error(`${this.username}: disconnected during configuration`));
    } else if (id === C_CONFIG_KEEP_ALIVE) {
      this.send(S_CONFIG_KEEP_ALIVE, data.subarray(0, 8));
      this.keepAlives++;
    } else if (id === C_CONFIG_PING) {
      this.send(S_CONFIG_PONG, data.subarray(0, 4));
    }
  }

  handlePlay(id, data) {
    if (id === C_PLAY_KEEP_ALIVE) {
      this.send(S_PLAY_KEEP_ALIVE, data.subarray(0, 8));
      this.keepAlives++;
    } else if (id === C_PLAY_PONG_RESPONSE) {
      const now = process.hrtime.bigint();
      const key = data.readBigInt64BE(0);
      const waiter = this.pings.get(key);
      if (waiter) {
        this.pings.delete(key);
        waiter.resolve(Number(now - waiter.sent) / 1e6);
      }
    } else if (id === C_PLAY_POSITION) {
      this.onPosition(data);
    } else if (id === C_PLAY_CHUNK_BATCH_FINISHED) {
      // The server holds back further chunk batches until the client acknowledges this one.
      const rate = Buffer.alloc(4);
      rate.writeFloatBE(CHUNKS_PER_TICK);
      this.send(S_PLAY_CHUNK_BATCH_RECEIVED, rate);
    } else if (id === C_PLAY_PING) {
      this.send(S_PLAY_PONG, data.subarray(0, 4));
    } else if (id === C_PLAY_START_CONFIGURATION) {
      // A proxy that changes the player's backend sends the client back to configuration.
      this.send(S_PLAY_CONFIGURATION_ACKNOWLEDGED);
      this.state = STATE.CONFIG;
    } else if (id === C_PLAY_DISCONNECT) {
      this.kicked = true;
    }
    // Everything else (entity updates, time, small chunk-related packets) is consumed and dropped.
  }

  /**
   * Synchronize Player Position: the server placed (or corrected) the player. The client must
   * confirm the teleport or the server ignores its movement from then on.
   */
  onPosition(data) {
    const id = readVarInt(data, 0);
    let at = id.offset;
    const x = data.readDoubleBE(at);
    const y = data.readDoubleBE(at + 8);
    const z = data.readDoubleBE(at + 16);
    at += 24 + 24 + 8; // position, velocity, yaw and pitch
    const relative = data.readInt32BE(at);
    const old = this.position ?? { x: 0, y: 0, z: 0 };
    this.position = {
      x: relative & 0x01 ? old.x + x : x,
      y: relative & 0x02 ? old.y + y : y,
      z: relative & 0x04 ? old.z + z : z,
    };
    this.teleports++;
    this.send(S_PLAY_TELEPORT_CONFIRM, varInt(id.value));
  }

  /**
   * One client tick of a mover: a step along its path, on the ground, at sprint speed, turning
   * round at either end. Does nothing for a client that is not a mover or that the server has not
   * placed yet.
   */
  tick() {
    if (this.path === null || this.position === null || this.state !== STATE.PLAY || this.closed) return;
    if (this.outbound && this.travelled >= this.path.max) this.outbound = false;
    else if (!this.outbound && this.travelled <= this.path.min) this.outbound = true;
    const step = (this.outbound ? 1 : -1) * (MOVER_SPEED / MOVER_TICKS_PER_SEC);
    this.travelled += step;
    this.position.x += Math.cos(this.path.heading) * step;
    this.position.z += Math.sin(this.path.heading) * step;
    const packet = Buffer.alloc(25);
    packet.writeDoubleBE(this.position.x, 0);
    packet.writeDoubleBE(this.position.y, 8);
    packet.writeDoubleBE(this.position.z, 16);
    packet[24] = 0x01; // on ground
    this.send(S_PLAY_POSITION, packet);
  }

  /**
   * Sends a PLAY ping request and resolves with the round-trip time in milliseconds (fractional,
   * from the monotonic nanosecond clock), or null if the connection closed first. The caller applies
   * its own timeout.
   * @returns {Promise<number|null>}
   */
  ping() {
    if (this.closed || this.state !== STATE.PLAY) return Promise.resolve(null);
    const key = this.nextPing++;
    const payload = Buffer.alloc(8);
    payload.writeBigInt64BE(key);
    return new Promise((resolve) => {
      this.pings.set(key, { sent: process.hrtime.bigint(), resolve });
      this.send(S_PLAY_PING_REQUEST, payload);
    });
  }

  /** Builds a frame: VarInt length of (packet id + parts), then them. No compression (small packets). */
  frame(id, ...parts) {
    const inner = Buffer.concat([varInt(id), ...parts]);
    if (this.threshold < 0) return Buffer.concat([varInt(inner.length), inner]);
    // Compression active: prefix a data-length of 0, meaning "sent uncompressed".
    const withDataLen = Buffer.concat([varInt(0), inner]);
    return Buffer.concat([varInt(withDataLen.length), withDataLen]);
  }

  send(id, ...parts) {
    this.sendRaw(this.frame(id, ...parts));
  }

  sendRaw(buffer) {
    if (this.socket && !this.socket.destroyed) this.socket.write(buffer);
  }

  destroy() {
    this.socket?.destroy();
  }
}

/** Connects one idle client (or a mover, with `path`) and resolves the handle once it is in PLAY. */
export function connectIdle({ host, port, username, path = null }, timeoutMs) {
  return new RawClient({ host, port, username, path }).connect(timeoutMs);
}
