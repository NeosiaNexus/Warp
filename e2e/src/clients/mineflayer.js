// Real-protocol bots built on mineflayer, or on minecraft-protocol alone for 1.7 (legacy.js), which
// mineflayer no longer loads. Every packet a bot receives is fully parsed against the protocol
// definition of its version: a packet the proxy corrupted or mis-framed surfaces as an 'error' or a
// disconnect, which fails the scenario.
import { createRequire } from 'node:module';

import * as legacy from './legacy.js';

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
// Corrections to mineflayer
// ---------------------------------------------------------------------------

/** The movement packets mineflayer sends, at most one per physics tick. */
const MOVEMENT_PACKETS = new Set(['flying', 'look', 'position', 'position_look']);

/**
 * Ends the bot's ticks with a Client Tick End, as the game does from 1.21.2 and mineflayer never
 * does. From 26.3 the server counts the positions it receives between two of them, and kicks a
 * player that sends a second one ("Invalid move player packet received"). A tick end right after
 * each movement packet keeps every two of them in different ticks, as the game sends them.
 *
 * @param {object} client the bot's minecraft-protocol client
 * @param {string} version the version the bot speaks
 */
export function endTicks(client, version) {
  if (!minecraftData(version)?.protocol?.play?.toServer?.types?.packet_tick_end) return;
  const write = client.write.bind(client);
  client.write = (name, params) => {
    write(name, params);
    if (MOVEMENT_PACKETS.has(name) && client.state === 'play') write('tick_end', {});
  };
}

/**
 * Answers a resource pack offer with the pack's UUID. mineflayer accepts the packs offered in the
 * configuration phase, but passes their UUID as a `uuid-1345` object, which minecraft-protocol
 * writes as the nil UUID. From protocol 772 (1.21.8) a server only takes the answer for the pack it
 * offered as the end of the pack's configuration task, so the bot never left the phase.
 *
 * @param {object} client the bot's minecraft-protocol client
 */
export function answerPacksByUuid(client) {
  const write = client.write.bind(client);
  client.write = (name, params) =>
    write(name, name === 'resource_pack_receive' && params?.uuid !== undefined ? { ...params, uuid: String(params.uuid) } : params);
}

// ---------------------------------------------------------------------------
// Tab list
// ---------------------------------------------------------------------------

/** First protocol with the configuration phase (1.20.2), which gives the client a new tab list. */
const CONFIGURATION_PHASE = 764;

/**
 * The tab list as the vanilla client keeps it, rebuilt from the packets it receives: entries by
 * UUID from 1.8, by name on 1.7. mineflayer's `bot.players` cannot show what a server switch left
 * behind, as it starts over at every Join Game. The client keeps its tab list for the whole
 * connection before 1.20.2 (the player info map of its play packet listener, which a Join Game does
 * not replace). From 1.20.2 it gets a new one with each configuration phase: the model starts over
 * at the Join Game that follows it, before any player info of the new server.
 */
export class TabList {
  /** @param {number} protocol the protocol the client speaks */
  constructor(protocol) {
    this.protocol = protocol;
    this.entries = new Map(); // UUID (1.7: the name) → listed name
  }

  /** Applies a packet the client received: its minecraft-protocol name and parsed fields. */
  apply(name, data) {
    if (name === 'login') {
      if (this.protocol >= CONFIGURATION_PHASE) this.entries.clear();
    } else if (name === 'player_remove') {
      for (const uuid of data.players) this.entries.delete(uuid); // 1.19.3+
    } else if (name !== 'player_info') {
      // Nothing else changes the tab list.
    } else if (data.playerName !== undefined) {
      // 1.7: one name per packet, listed or not.
      if (data.online) this.entries.set(data.playerName, data.playerName);
      else this.entries.delete(data.playerName);
    } else if (typeof data.action === 'object') {
      // 1.19.3+: action flags; removals come as player_remove.
      if (data.action.add_player) for (const entry of data.data) this.entries.set(entry.uuid, entry.player.name);
    } else if (data.action === 'add_player') {
      for (const entry of data.data) this.entries.set(entry.uuid, entry.name);
    } else if (data.action === 'remove_player') {
      for (const entry of data.data) this.entries.delete(entry.uuid);
    }
  }

  /** The listed names, sorted. */
  names() {
    return [...this.entries.values()].sort();
  }
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

/** The bot library for `version`: mineflayer, or minecraft-protocol alone below 1.8.8. */
export function library(version) {
  return legacy.isLegacy(version) ? 'minecraft-protocol' : 'mineflayer';
}

/**
 * Connects a bot and resolves once it has spawned in the world.
 *
 * With `profileKeys` (from `createProfileKeys`), the bot plays a client logged in to a Microsoft
 * account, without the session server call. On 1.19 to 1.19.2 it sends the key and its certificate
 * in Login Start, announcing their `uuid`, and signs the verify token with the key instead of
 * encrypting it. From 1.19.3 it opens a chat session with them and signs its chat.
 */
export function connect({ host, port, version, username, profileKeys = null, spawnTimeoutMs = 45_000 }) {
  correctProtocolData(version);
  const createBot = legacy.isLegacy(version) ? legacy.createBot : mineflayer.createBot;
  return new Promise((resolve, reject) => {
    const bot = createBot({
      host,
      port,
      username,
      version,
      auth: profileKeys ? keyedAuth(profileKeys) : 'offline',
      checkTimeoutInterval: 120_000,
      hideErrors: true,
      logErrors: false,
    });
    endTicks(bot._client, version);
    answerPacksByUuid(bot._client);
    const handle = new Bot(bot, username, announcedProtocol(version), profileKeys !== null);
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

/**
 * Offline login (no Mojang account) that still hands minecraft-protocol a profile and its key, the
 * way its Microsoft login does: its Login Start and encryption code then send the key, and from
 * 1.19.3 it opens a chat session at Join Game when the server announced that profile's UUID, over an
 * encrypted connection only, and from then on signs chat.
 */
function keyedAuth(profileKeys) {
  return (client, options) => {
    client.username = options.username;
    // Undashed: minecraft-protocol compares it so with the UUID the server announced.
    client.session = { selectedProfile: { id: profileKeys.uuid.replace(/-/g, ''), name: options.username } };
    client.profileKeys = profileKeys;
    options.connect(client);
  };
}

class Bot {
  /**
   * @param {object} bot mineflayer bot, or its 1.7 stand-in ({@link legacy.createBot})
   * @param {string} username the name it logs in with (mineflayer only sets `bot.username` once
   *   the server has accepted the login)
   * @param {number} protocol the protocol it speaks
   * @param {boolean} keyed whether it has a profile key to sign chat with
   */
  constructor(bot, username, protocol, keyed = false) {
    this.bot = bot;
    this.username = username;
    this.keyed = keyed;
    this.stats = { packets: 0, chunks: 0, signedChat: 0, lastPacket: Date.now(), errors: [], kicked: null, ended: null };
    this.messages = [];
    /**
     * What the servers it played on said, in order: brands (with the protocol state and game mode
     * the bot was in when each arrived), resource pack offers, the UUIDs it was listed under.
     */
    this.brands = [];
    this.resourcePacks = [];
    this.listedAs = [];
    this.tabList = new TabList(protocol);
    this.quitting = false;
    this.chatSession = false;
    this.closed = new Promise((resolve) => bot.once('end', resolve));
    bot._client.on('packet', (data, meta) => {
      this.stats.packets++;
      this.stats.lastPacket = Date.now();
      if (meta.name === 'map_chunk') this.stats.chunks++;
      else if (meta.name === 'map_chunk_bulk') this.stats.chunks += data.meta?.length ?? 1; // 1.7, 1.8
      else if (meta.name === 'player_chat' && data.signature) this.stats.signedChat++;
      else if (RESOURCE_PACK_PACKETS.has(meta.name)) this.resourcePacks.push({ url: data.url, hash: data.hash, id: data.uuid });
      else if (meta.name === 'player_info') this.listedAs.push(...listedUuids(data, username));
      this.tabList.apply(meta.name, data);
    });
    // From 1.19.3 a server announces a player's chat session to everyone, the player included,
    // once it has taken it (Player Info Update, initialize chat).
    bot._client.on('player_info', (packet) => {
      const own = sameUuid(bot._client.uuid);
      if (Array.isArray(packet.data) && packet.data.some((p) => p.chatSession && own(p.uuid))) this.chatSession = true;
    });
    // mineflayer decodes the brand on the channel of the version: `MC|Brand` before 1.13.
    for (const channel of ['MC|Brand', 'minecraft:brand']) {
      bot._client.on(channel, (brand) => this.brands.push({ brand, state: bot._client.state, gameMode: this.gameMode() }));
    }
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

  /** The names in the bot's tab list, sorted ({@link TabList}). */
  listed() {
    return this.tabList.names();
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
    if (this.keyed && this.bot.supportFeature('seperateSignedChatCommandPacket')) {
      // From 1.20.5 a vanilla client sends a command in the signed command packet only when its
      // syntax has an argument to sign (/msg, /say); minecraft-protocol sends every command there
      // once it can sign. Commands here have none: send them as a vanilla client would.
      this.bot._client.write('chat_command', { command });
    } else {
      this.bot.chat(`/${command}`);
    }
    return reply ? this.waitForMessage(reply, `reply to /${command}`, timeoutMs, from) : null;
  }

  chat(message) {
    this.bot.chat(message);
  }

  /**
   * Waits until the server has taken the bot's chat session. A server applies it on its main
   * thread but reads chat as it arrives: a line said before is not signed for it, and the server's
   * signature chain then falls one step behind the bot's.
   */
  waitForChatSession(timeoutMs) {
    return this.waitFor(() => this.chatSession, 'chat session taken by the server', timeoutMs);
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

  /** Disconnects; resolves once the connection is closed (after 5 s at most). */
  quit() {
    this.quitting = true;
    this.bot.quit();
    let timer;
    const timeout = new Promise((resolve) => { timer = setTimeout(resolve, 5_000); });
    return Promise.race([this.closed, timeout]).finally(() => clearTimeout(timer));
  }
}

/** A predicate matching `uuid`, with or without dashes. */
function sameUuid(uuid) {
  const bare = String(uuid ?? '').replace(/-/g, '');
  return (other) => bare !== '' && String(other ?? '').replace(/-/g, '') === bare;
}

function text(reason) {
  return typeof reason === 'string' ? reason : JSON.stringify(reason);
}
