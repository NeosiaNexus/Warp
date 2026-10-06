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

/** Connects a bot and resolves once it has spawned in the world. */
export function connect({ host, port, version, username, spawnTimeoutMs = 45_000 }) {
  correctProtocolData(version);
  return new Promise((resolve, reject) => {
    const bot = mineflayer.createBot({
      host,
      port,
      username,
      version,
      auth: 'offline',
      checkTimeoutInterval: 120_000,
      hideErrors: true,
      logErrors: false,
    });
    const handle = new Bot(bot, username);
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
 * UUIDs a Player Info packet adds `username` under: the backend's own view of who the player is.
 * Up to 1.19.2 the packet carries one action, from 1.19.3 a set of them.
 */
export function listedUuids(packet, username) {
  const adds = typeof packet.action === 'object' ? packet.action.add_player : packet.action === 'add_player';
  if (!adds) return [];
  return packet.data.filter((entry) => (entry.player?.name ?? entry.name) === username).map((entry) => entry.uuid);
}

/** Resource pack offers: one pack at a time up to 1.20.2, packs added by UUID from 1.20.3. */
const RESOURCE_PACK_PACKETS = new Set(['resource_pack_send', 'add_resource_pack']);

class Bot {
  /**
   * @param {object} bot mineflayer bot
   * @param {string} username the name it logs in with (mineflayer only sets `bot.username` once
   *   the server has accepted the login)
   */
  constructor(bot, username) {
    this.bot = bot;
    this.username = username;
    this.stats = { packets: 0, chunks: 0, errors: [], kicked: null, ended: null };
    this.messages = [];
    /** What the servers it played on said, in order: brands, resource pack offers, own UUIDs. */
    this.brands = [];
    this.resourcePacks = [];
    this.listedAs = [];
    this.quitting = false;
    bot._client.on('packet', (data, meta) => {
      this.stats.packets++;
      if (meta.name === 'map_chunk') this.stats.chunks++;
      else if (meta.name === 'map_chunk_bulk') this.stats.chunks += data.meta?.length ?? 1; // 1.8
      else if (RESOURCE_PACK_PACKETS.has(meta.name)) this.resourcePacks.push({ url: data.url, hash: data.hash, id: data.uuid });
      else if (meta.name === 'player_info') this.listedAs.push(...listedUuids(data, username));
    });
    // mineflayer decodes the brand on the channel of the version: `MC|Brand` before 1.13.
    for (const channel of ['MC|Brand', 'minecraft:brand']) bot._client.on(channel, (brand) => this.brands.push(brand));
    bot.on('error', (e) => this.stats.errors.push(String(e?.stack ?? e)));
    bot.on('kicked', (reason) => { this.stats.kicked = text(reason); });
    bot.on('end', (reason) => { if (!this.quitting) this.stats.ended = String(reason ?? 'unknown'); });
    bot.on('message', (message) => this.messages.push(message.toString()));
  }

  /** The UUID the proxy gave the player in its Login Success. */
  get uuid() {
    return this.bot._client.uuid;
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

  /**
   * Waits until one of the lists the bot keeps (`brands`, `resourcePacks`, `listedAs`) has an entry
   * at `index`, and returns it.
   */
  async waitForEntry(list, index, what, timeoutMs) {
    await this.waitFor(() => this[list].length > index, what, timeoutMs);
    return this[list][index];
  }

  /**
   * Resolves with the first chat line from index `from` on that matches `pattern`; on a time-out,
   * the error quotes the last lines received instead.
   */
  async waitForMessage(pattern, what, timeoutMs, from = 0) {
    let found;
    try {
      await this.waitFor(() => (found = this.messages.slice(from).find((m) => pattern.test(m))) !== undefined, what, timeoutMs);
    } catch (e) {
      const last = this.messages.slice(Math.max(from, this.messages.length - 3)).map((m) => JSON.stringify(m));
      e.message += last.length ? `; last chat lines: ${last.join(', ')}` : '; no chat line received';
      throw e;
    }
    return found;
  }

  /** Sends a chat command and resolves with the first chat line matching `reply`. */
  async command(command, reply, timeoutMs = 10_000) {
    const from = this.messages.length;
    this.bot.chat(`/${command}`);
    return reply ? this.waitForMessage(reply, `reply to /${command}`, timeoutMs, from) : null;
  }

  chat(message) {
    this.bot.chat(message);
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
