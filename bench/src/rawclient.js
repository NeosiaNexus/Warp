// A minimal, dependency-free Minecraft client that logs in offline and then sits in the PLAY state
// answering keep-alives. It exists for the idle-connection scenario, where thousands of these must
// run in one Node process: a full mineflayer bot runs physics and a world model per connection, far
// too heavy at that scale, while this client parses only the few packets it must answer and blindly
// drops the rest. It speaks one modern protocol (1.21.4, number 769): the idle scenario measures
// what a connection costs the proxy, which does not depend on the client's version.
//
// It handles compression (the proxy sets a threshold, so inbound frames may be zlib-compressed) and
// the 1.20.5+ configuration phase (known packs, finish configuration). It never deflates: every
// packet it sends is far below any threshold, so it sends them uncompressed (data length 0).
import { randomUUID } from 'node:crypto';
import { connect } from 'node:net';
import { inflateSync } from 'node:zlib';

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
const S_PLAY_KEEP_ALIVE = 0x1a;
const S_PLAY_PONG = 0x24;

const C_LOGIN_ENCRYPTION = 0x01;
const C_LOGIN_SUCCESS = 0x02;
const C_LOGIN_SET_COMPRESSION = 0x03;
const C_CONFIG_KNOWN_PACKS = 0x0e;
const C_CONFIG_FINISH = 0x03;
const C_CONFIG_KEEP_ALIVE = 0x04;
const C_CONFIG_PING = 0x05;
const C_PLAY_KEEP_ALIVE = 0x27;
const C_PLAY_PING = 0x21;

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
   */
  constructor({ host, port, username }) {
    this.host = host;
    this.port = port;
    this.username = username;
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
        this.handlePacket(this.decode(body));
      }
    } catch (e) {
      this.fail(new Error(`${this.username}: ${e.message}`));
    }
  }

  /** Strips the compression header (if active) and returns the packet id and its payload. */
  decode(body) {
    let payload = body;
    if (this.threshold >= 0) {
      const dataLen = readVarInt(body, 0);
      const compressed = body.subarray(dataLen.offset);
      payload = dataLen.value === 0 ? compressed : inflateSync(compressed);
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
      this.spawned = true;
      this.onSpawn?.();
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
    } else if (id === C_PLAY_PING) {
      this.send(S_PLAY_PONG, data.subarray(0, 4));
    }
    // Everything else (chunks, registries, entity updates) is consumed and dropped.
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

/** Connects one idle client and resolves the handle once it is in PLAY. */
export function connectIdle({ host, port, username }, timeoutMs) {
  return new RawClient({ host, port, username }).connect(timeoutMs);
}
