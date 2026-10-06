// Bots for the versions mineflayer no longer loads (1.7.x, below its oldest supported 1.8.8):
// minecraft-protocol alone, behind the small part of mineflayer's interface the harness uses. They
// log in, answer keep-alives (minecraft-protocol does), confirm teleports and send a movement
// packet every tick like the vanilla client, follow the game mode, and read the chat and the
// server's brand.
import { EventEmitter } from 'node:events';
import { createRequire } from 'node:module';

const require = createRequire(import.meta.url);
const protocol = require('minecraft-protocol');
const minecraftData = require('minecraft-data');
const mineflayer = require('mineflayer');
const prismarineChat = require('prismarine-chat');

/** Game modes by id; bit 3 of the Join Game's game mode is the hardcore flag. */
const GAME_MODES = ['survival', 'creative', 'adventure', 'spectator'];

/** Height of a standing player's eyes above its feet. */
const EYE_HEIGHT = 1.62;

/** A client tick: the vanilla client sends a movement packet every tick. */
const TICK_MS = 50;

/** Whether mineflayer refuses `version` (older than its oldest supported one). */
export function isLegacy(version) {
  const data = minecraftData(version)?.version;
  return Boolean(data) && data['<'](mineflayer.oldestSupportedVersion);
}

/**
 * Connects a bot. The returned object has the members of a mineflayer bot the harness reads:
 * `_client`, `game.gameMode`, `chat()`, `quit()`, and the `spawn`, `message`, `kicked`, `end` and
 * `error` events.
 */
export function createBot({ host, port, username, version, auth, checkTimeoutInterval }) {
  const bot = new EventEmitter();
  const client = protocol.createClient({ host, port, username, version, auth, checkTimeoutInterval, hideErrors: true });
  const ChatMessage = prismarineChat(version);
  let ticker = null;

  bot._client = client;
  bot.game = { gameMode: undefined };
  bot.chat = (message) => client.write('chat', { message });
  bot.quit = (reason = 'disconnect.quitting') => client.end(reason);

  client.on('login', (packet) => {
    bot.game.gameMode = GAME_MODES[packet.gameMode & 0b111];
    // Sent by the vanilla client as it joins (C15PacketClientSettings), read by Warp on 1.7 too.
    client.write('settings', { locale: 'en_US', viewDistance: 8, chatFlags: 0, chatColors: true, difficulty: 2, showCape: true });
  });
  client.on('respawn', (packet) => {
    bot.game.gameMode = GAME_MODES[packet.gamemode & 0b111];
  });
  client.on('game_state_change', (packet) => {
    if (packet.reason === 'change_game_mode') bot.game.gameMode = GAME_MODES[packet.gameMode];
  });
  client.on('position', (packet) => {
    // The server sends the eyes' height; the answer gives the feet first, then the eyes (minecraft-
    // data names them the other way round: Spigot's PacketPlayInFlying reads x, y, stance, z, and
    // kicks for an "Illegal stance" unless the second is below the third by 0.1 to 1.65).
    const { x, y, z, yaw, pitch } = packet;
    client.write('position_look', { x, stance: y - EYE_HEIGHT, y, z, yaw, pitch, onGround: true });
    if (ticker) return;
    ticker = setInterval(() => client.write('flying', { onGround: true }), TICK_MS);
    bot.emit('spawn');
  });
  client.on('chat', (packet) => {
    let json;
    try {
      json = JSON.parse(packet.message);
    } catch (e) {
      bot.emit('error', new Error(`chat is not JSON: ${packet.message} (${e.message})`));
      return;
    }
    bot.emit('message', new ChatMessage(json));
  });
  // The server's brand, on the event mineflayer decodes it to: on 1.7 the payload is the bare
  // UTF-8 string, without the length prefix of 1.8 and later.
  client.on('custom_payload', (packet) => {
    if (packet.channel === 'MC|Brand') client.emit('MC|Brand', packet.data.toString('utf8'));
  });
  client.on('kick_disconnect', (packet) => bot.emit('kicked', packet.reason));
  client.on('disconnect', (packet) => bot.emit('kicked', packet.reason)); // during login
  client.on('error', (error) => bot.emit('error', error));
  client.on('end', (reason) => {
    clearInterval(ticker);
    bot.emit('end', reason);
  });
  return bot;
}
