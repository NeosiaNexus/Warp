// The flood scenario of the anti-bot research (docs/research/anti-bot.md, section 12): hostile raw
// connections against the defences Warp applies before login, all against the harness's own Warp on
// the loopback. It checks that memory stays bounded, that Warp writes no line per connection, that
// it closes what it must, and that a real player still joins meanwhile.
import { constants, createPublicKey, publicEncrypt, randomBytes } from 'node:crypto';
import { connect } from 'node:net';

import { NOT_JOINED_PREFIX } from './mojang.js';
import { sleep } from './proc.js';
import { parseJcmd } from './sampler.js';

/** The protocol the raw clients speak (1.21.4), whatever the run's version: the flood targets Warp. */
export const FLOOD_PROTOCOL = 769;

/** How many connections of each kind. Modest: CI runners and shared machines run this. */
export const FLOOD = { idle: 300, nullPings: 300, slowDrips: 100, oversized: 200, forged: 50 };

/** Bytes each oversized connection offers after announcing a 2 MiB first frame. */
const OVERSIZED_PAYLOAD = 1024 * 1024;

/** Growth of Netty's direct memory that fails the scenario; an unbounded proxy would buffer ~200 MiB. */
export const DIRECT_GROWTH_LIMIT_MIB = 32;

/** Warp log lines the flood may add: aggregated summaries, never one per connection. */
export const LOG_LINE_LIMIT = 10;

// ---------------------------------------------------------------------------
// Wire format
// ---------------------------------------------------------------------------

/** A VarInt. */
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

/** A protocol string: VarInt length, then UTF-8. */
export function mcString(text) {
  const bytes = Buffer.from(text, 'utf8');
  return Buffer.concat([varInt(bytes.length), bytes]);
}

/** A frame: VarInt length of the packet id and payload, then both. */
export function frame(id, ...parts) {
  const body = Buffer.concat([varInt(id), ...parts]);
  return Buffer.concat([varInt(body.length), body]);
}

/** A handshake for `nextState` (1 status, 2 login). */
export function handshake(port, nextState) {
  const portBytes = Buffer.alloc(2);
  portBytes.writeUInt16BE(port);
  return frame(0x00, varInt(FLOOD_PROTOCOL), mcString('127.0.0.1'), portBytes, varInt(nextState));
}

/** A 1.20.2+ Login Start: the name and a UUID. */
export function loginStart(name) {
  return frame(0x00, mcString(name), randomBytes(16));
}

/** Reads a VarInt at `offset`: `{value, length}`, or null if the buffer ends first. */
export function readVarInt(buf, offset) {
  let value = 0;
  for (let i = 0; i < 5 && offset + i < buf.length; i++) {
    const b = buf[offset + i];
    value |= (b & 0x7f) << (7 * i);
    if ((b & 0x80) === 0) return { value, length: i + 1 };
  }
  return null;
}

/**
 * Reads an uncompressed Encryption Request frame from the start of `buf`: its public key and verify
 * token, or null if the frame is incomplete or another packet.
 */
export function readEncryptionRequest(buf) {
  const length = readVarInt(buf, 0);
  if (!length || buf.length < length.length + length.value) return null;
  let at = length.length;
  const id = readVarInt(buf, at);
  if (!id || id.value !== 0x01) return null;
  at += id.length;
  const fields = [];
  for (let i = 0; i < 3; i++) {
    const size = readVarInt(buf, at);
    at += size.length;
    fields.push(buf.subarray(at, at + size.value));
    at += size.value;
  }
  return { publicKey: fields[1], verifyToken: fields[2] };
}

// ---------------------------------------------------------------------------
// Connections
// ---------------------------------------------------------------------------

/** Opens a connection to Warp; resolves once connected. Errors (resets) are expected and ignored. */
function open(port, held) {
  return new Promise((resolve) => {
    const socket = connect({ host: '127.0.0.1', port }, () => resolve(socket));
    socket.on('error', () => {});
    socket.on('close', () => resolve(socket));
    held.push(socket);
  });
}

/** Benign pings that warm Warp's allocator before the flood: several per event loop. */
const WARM_UP_PINGS = 400;

/** A complete server list ping; resolves once Warp answered and closed, or failed. */
function serverListPing(port) {
  return new Promise((resolve) => {
    const socket = connect({ host: '127.0.0.1', port }, () => {
      socket.write(Buffer.concat([handshake(port, 1), frame(0x00)]));
    });
    socket.setTimeout(5000, () => socket.destroy());
    socket.on('data', () => socket.end());
    socket.on('error', () => {});
    socket.on('close', resolve);
  });
}

/** A forged online login: a valid encryption response for a random secret, no session. */
function forgedLogin(port, index, held) {
  return new Promise((resolve) => {
    const socket = connect({ host: '127.0.0.1', port }, () => {
      socket.write(Buffer.concat([handshake(port, 2), loginStart(`${NOT_JOINED_PREFIX}${index}`)]));
    });
    held.push(socket);
    let received = Buffer.alloc(0);
    let answered = false;
    socket.on('data', (data) => {
      if (answered) return;
      received = Buffer.concat([received, data]);
      const request = readEncryptionRequest(received);
      if (!request) return;
      answered = true;
      const key = createPublicKey({ key: request.publicKey, format: 'der', type: 'spki' });
      const encrypt = (bytes) => publicEncrypt({ key, padding: constants.RSA_PKCS1_PADDING }, bytes);
      const secret = encrypt(randomBytes(16));
      const token = encrypt(request.verifyToken);
      socket.write(frame(0x01, varInt(secret.length), secret, varInt(token.length), token));
    });
    socket.on('error', () => {});
    socket.on('close', () => resolve(answered));
  });
}

// ---------------------------------------------------------------------------
// Scenario
// ---------------------------------------------------------------------------

/**
 * Floods Warp before login while a bot joins, and checks the bounds.
 * @param {object} ctx the run (see scenarios.js)
 * @param {(ctx: object, name: string) => Promise<object>} join joins a real bot
 * @param {string} lobbyMode the game mode of the lobby
 * @returns {Promise<string>} the summary
 */
export async function flood(ctx, join, lobbyMode) {
  const instance = ctx.warp.instance;
  if (!instance?.process) throw new Error('flood needs a Warp process to watch');
  const port = ctx.warp.port;
  const direct = async () => parseJcmd(await instance.jcmd(['VM.native_memory summary'])).direct ?? null;
  // Netty's allocator takes a chunk per event loop the first time each one reads: warm them all
  // with complete server list pings first, so that the reading measures what the flood makes Warp
  // hold, not a fixed cost a quiet proxy pays once.
  await Promise.all(Array.from({ length: WARM_UP_PINGS }, () => serverListPing(port)));
  await sleep(1000);
  const before = await direct();
  const logFrom = instance.process.mark();
  const held = [];
  let peak = before;
  let sampling = true;
  const sampler = (async () => {
    while (sampling) {
      const now = await direct().catch(() => null);
      if (now !== null && (peak === null || now > peak)) peak = now;
      await sleep(250);
    }
  })();

  // Connections that never finish: Warp's deadlines must close them.
  const idle = Promise.all(Array.from({ length: FLOOD.idle }, () => open(port, held)));
  const nullPings = Promise.all(
    Array.from({ length: FLOOD.nullPings }, async () => (await open(port, held)).write(handshake(port, 1))),
  );
  const drip = handshake(port, 2);
  const slowDrips = Promise.all(
    Array.from({ length: FLOOD.slowDrips }, async () => {
      const socket = await open(port, held);
      for (let i = 0; i < drip.length && !socket.destroyed; i++) {
        socket.write(drip.subarray(i, i + 1));
        await sleep(1000);
      }
    }),
  );
  // A 2 MiB first frame announced, then 1 MiB streamed: refused from its header.
  const oversized = Promise.all(
    Array.from({ length: FLOOD.oversized }, async () => {
      const socket = await open(port, held);
      socket.write(Buffer.from([0xff, 0xff, 0x7f, 0x00]));
      const chunk = Buffer.alloc(64 * 1024);
      for (let sent = 0; sent < OVERSIZED_PAYLOAD && !socket.destroyed; sent += chunk.length) {
        if (!socket.write(chunk)) await sleep(10);
      }
    }),
  );
  const forged = ctx.variant.online
    ? Promise.all(Array.from({ length: FLOOD.forged }, (_, i) => forgedLogin(port, i, held)))
    : Promise.resolve([]);

  // A real player joins in the middle of it.
  await sleep(1500);
  const bot = await join(ctx, 'e2e_survivor');
  let survivor;
  try {
    await bot.waitForGameMode(lobbyMode, 30_000);
    bot.healthy();
    survivor = `e2e_survivor joined the lobby during the flood`;
  } finally {
    bot.quit();
  }

  await Promise.all([idle, nullPings, oversized, slowDrips]);
  const forgedAnswered = (await forged).filter(Boolean).length;
  // The 5 s handshake and status deadlines, and a margin.
  await sleep(7000);
  sampling = false;
  await sampler;

  const open_ = held.filter((socket) => !socket.destroyed && socket.readyState === 'open').length;
  held.forEach((socket) => socket.destroy());
  const lines = instance.process.lines.slice(logFrom).filter((line) => !/LEAK|DEBUG|TRACE/.test(line));

  const problems = [];
  if (open_ > 0) problems.push(`${open_} hostile connections still open after the deadlines`);
  if (lines.length > LOG_LINE_LIMIT) {
    problems.push(`${lines.length} Warp log lines during the flood (limit ${LOG_LINE_LIMIT}):\n  ${lines.slice(0, 15).join('\n  ')}`);
  }
  const growth = before === null || peak === null ? null : peak - before;
  if (growth === null) problems.push('no direct memory reading: is native memory tracking on?');
  else if (growth > DIRECT_GROWTH_LIMIT_MIB) problems.push(`direct memory grew by ${growth} MiB (limit ${DIRECT_GROWTH_LIMIT_MIB})`);
  if (ctx.variant.online && forgedAnswered < FLOOD.forged) {
    problems.push(`only ${forgedAnswered} of ${FLOOD.forged} forged logins got an encryption request`);
  }
  if (problems.length) throw new Error(problems.join('; '));

  const kinds = `${FLOOD.idle} idle, ${FLOOD.nullPings} null pings, ${FLOOD.slowDrips} slow drips, ${FLOOD.oversized} x 2 MiB frames${ctx.variant.online ? `, ${FLOOD.forged} forged logins` : ''}`;
  return `${kinds}: all closed, direct memory ${before} -> peak ${peak} MiB (+${growth}), ${lines.length} log lines; ${survivor}`;
}
