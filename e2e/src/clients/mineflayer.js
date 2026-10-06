// Real-protocol bots built on mineflayer. Every packet a bot receives is fully parsed against the
// protocol definition of its version: a packet the proxy corrupted or mis-framed surfaces as an
// 'error' or a disconnect, which fails the scenario.
import { createRequire } from 'node:module';

const require = createRequire(import.meta.url);
const mineflayer = require('mineflayer');
const protocol = require('minecraft-protocol');
const minecraftData = require('minecraft-data');
const { FullPacketParser } = require('protodef');

const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));

// Out of the box, protodef only console.logs a packet it could not fully read and then drops it,
// so a frame the proxy corrupted can go unnoticed. Strict mode: unread trailing bytes and partial
// reads are parse errors, which the bot reports through its 'error' event.
FullPacketParser.prototype._transform = function strictTransform(chunk, encoding, callback) {
  let packet;
  try {
    packet = this.parsePacketBuffer(chunk);
  } catch (e) {
    return callback(e);
  }
  if (packet.metadata.size !== chunk.length) {
    const error = new Error(
      `${packet.data?.name ?? 'packet'}: read ${packet.metadata.size} of ${chunk.length} bytes`,
    );
    error.buffer = chunk;
    return callback(error);
  }
  this.push(packet);
  return callback();
};

// ---------------------------------------------------------------------------
// Corrections to minecraft-data
// ---------------------------------------------------------------------------

const REMOVED_RECIPE_SERIALIZER = 'minecraft:crafting_special_banneraddpattern';

/**
 * Fixes known errors in minecraft-data's protocol definitions, in memory, before minecraft-protocol
 * compiles them. Each one was found because a `--direct` run (bots straight to the server, no
 * Warp) failed the same way, and each is a no-op once fixed upstream.
 *
 * Recipe serializers, 1.20.5 to 1.21.1 (the versions that send them as registry ids): the list
 * still has `crafting_special_banneraddpattern`, which the game no longer registers, so every id
 * from 11 on is read one too high. Simple recipes share one layout and survive it, but the
 * decorated pot recipe is read as `smithing_trim` and derails the rest of `declare_recipes`.
 */
export function correctProtocolData(version) {
  const declareRecipes = minecraftData(version)?.protocol?.play?.toClient?.types?.packet_declare_recipes;
  const serializers = declareRecipes && findMapper(declareRecipes, (names) => names.includes(REMOVED_RECIPE_SERIALIZER));
  if (!serializers) return;
  const names = Object.entries(serializers.mappings)
    .sort(([a], [b]) => Number(a) - Number(b))
    .map(([, name]) => name)
    .filter((name) => name !== REMOVED_RECIPE_SERIALIZER);
  serializers.mappings = Object.fromEntries(names.map((name, id) => [String(id), name]));
}

/** First `["mapper", {type: "varint", mappings}]` in a ProtoDef type whose names satisfy `test`. */
function findMapper(type, test) {
  if (!type || typeof type !== 'object') return null;
  if (type.type === 'varint' && type.mappings && test(Object.values(type.mappings))) return type;
  for (const child of Object.values(type)) {
    const found = findMapper(child, test);
    if (found) return found;
  }
  return null;
}

// ---------------------------------------------------------------------------
// Bots
// ---------------------------------------------------------------------------

export const name = 'mineflayer';

/**
 * Protocol number this client announces for `version`. minecraft-data silently maps versions it
 * has no data for to a neighbour ("1.21.7" → 767, the 1.21 protocol), so callers must check this
 * against the protocol they meant to test.
 */
export function announcedProtocol(version) {
  return minecraftData(version)?.version?.version ?? null;
}

/** Server list ping through the proxy. */
export async function ping({ host, port, version }) {
  correctProtocolData(version);
  return protocol.ping({ host, port, version, closeTimeout: 10_000 });
}

/**
 * Connects a bot and resolves once it has spawned in the world.
 * @param {object|null} profileKeys a profile key pair Mojang signed (see mojang.js): the bot then
 *   opens a chat session and signs its chat, as a client logged in with a Microsoft account does
 */
export function connect({ host, port, version, username, profileKeys = null, spawnTimeoutMs = 45_000 }) {
  correctProtocolData(version);
  return new Promise((resolve, reject) => {
    const bot = mineflayer.createBot({
      host,
      port,
      username,
      version,
      auth: profileKeys ? keyedAuth(profileKeys) : 'offline',
      checkTimeoutInterval: 120_000,
      hideErrors: true,
      logErrors: false,
    });
    const handle = new Bot(bot, username, profileKeys !== null);
    const fail = (why) => {
      clearTimeout(timer);
      bot.quit();
      reject(new Error(`${username}: ${why}`));
    };
    const timer = setTimeout(() => fail(`no spawn within ${spawnTimeoutMs / 1000}s`), spawnTimeoutMs);
    bot.once('spawn', () => {
      clearTimeout(timer);
      resolve(handle);
    });
    bot.once('kicked', (reason) => fail(`kicked before spawn: ${text(reason)}`));
    bot.once('end', (reason) => fail(`connection ended before spawn: ${reason}`));
    bot.once('error', (e) => fail(`error before spawn: ${e?.stack ?? e}`));
  });
}

/**
 * Offline login (no Mojang account) that still hands minecraft-protocol a profile and its key, the
 * way its Microsoft login does. It opens a chat session at Join Game when the server announced that
 * profile's UUID, over an encrypted connection only, and from then on signs chat.
 */
function keyedAuth(profileKeys) {
  return (client, options) => {
    client.username = options.username;
    client.session = { selectedProfile: { id: profileKeys.uuid, name: options.username } };
    client.profileKeys = profileKeys;
    options.connect(client);
  };
}

class Bot {
  /**
   * @param {object} bot mineflayer bot
   * @param {string} username the name it logs in with (mineflayer only sets `bot.username` once
   *   the server has accepted the login)
   * @param {boolean} keyed whether it has a profile key to sign chat with
   */
  constructor(bot, username, keyed = false) {
    this.bot = bot;
    this.username = username;
    this.keyed = keyed;
    this.stats = { packets: 0, chunks: 0, signedChat: 0, errors: [], kicked: null, ended: null };
    this.messages = [];
    this.quitting = false;
    bot._client.on('packet', (data, meta) => {
      this.stats.packets++;
      if (meta.name === 'map_chunk') this.stats.chunks++;
      else if (meta.name === 'map_chunk_bulk') this.stats.chunks += data.meta?.length ?? 1; // 1.8
      else if (meta.name === 'player_chat' && data.signature) this.stats.signedChat++;
    });
    bot.on('error', (e) => this.stats.errors.push(String(e?.stack ?? e)));
    bot.on('kicked', (reason) => { this.stats.kicked = text(reason); });
    bot.on('end', (reason) => { if (!this.quitting) this.stats.ended = String(reason ?? 'unknown'); });
    bot.on('message', (message) => this.messages.push(message.toString()));
  }

  /** `survival`, `creative`, `adventure` or `spectator`, as last announced by the server. */
  gameMode() {
    return this.bot.game?.gameMode;
  }

  async waitFor(condition, what, timeoutMs) {
    const deadline = Date.now() + timeoutMs;
    while (!condition()) {
      this.healthy();
      if (Date.now() > deadline) throw new Error(`${this.username}: ${what} not reached within ${timeoutMs / 1000}s`);
      await sleep(100);
    }
  }

  /** Waits until `count` more chunks have arrived; returns how many arrived. */
  async waitForChunks(count, timeoutMs, since = this.stats.chunks) {
    await this.waitFor(() => this.stats.chunks - since >= count, `${count} chunks (got ${this.stats.chunks - since})`, timeoutMs);
    return this.stats.chunks - since;
  }

  waitForGameMode(mode, timeoutMs) {
    return this.waitFor(() => this.gameMode() === mode, `game mode ${mode} (is ${this.gameMode()})`, timeoutMs);
  }

  /** Sends a chat command and resolves with the first chat line matching `reply`. */
  async command(command, reply, timeoutMs = 10_000) {
    const from = this.messages.length;
    if (this.keyed && this.bot.supportFeature('seperateSignedChatCommandPacket')) {
      // From 1.20.5 a vanilla client sends a command in the signed command packet only when its
      // syntax has an argument to sign (/msg, /say); minecraft-protocol sends every command there
      // once it can sign. Commands here have none: send them as a vanilla client would.
      this.bot._client.write('chat_command', { command });
    } else {
      this.bot.chat(`/${command}`);
    }
    if (!reply) return null;
    let found = null;
    await this.waitFor(() => (found = this.messages.slice(from).find((m) => reply.test(m))) !== undefined, `reply to /${command}`, timeoutMs);
    return found;
  }

  chat(message) {
    this.bot.chat(message);
  }

  /** Says `message` in chat and resolves once the server has sent it back to the bot. */
  async say(message, timeoutMs = 10_000) {
    const from = this.messages.length;
    this.bot.chat(message);
    await this.waitFor(() => this.messages.slice(from).some((m) => m.endsWith(message)), `chat line "${message}" back`, timeoutMs);
  }

  /** Throws if the bot saw a protocol error, was kicked, or lost its connection. */
  healthy() {
    const { errors, kicked, ended } = this.stats;
    if (errors.length) throw new Error(`${this.username}: ${errors.slice(0, 3).join(' | ')}`);
    if (kicked) throw new Error(`${this.username} kicked: ${kicked}`);
    if (ended) throw new Error(`${this.username} disconnected: ${ended}`);
  }

  quit() {
    this.quitting = true;
    this.bot.quit();
  }
}

function text(reason) {
  return typeof reason === 'string' ? reason : JSON.stringify(reason);
}
