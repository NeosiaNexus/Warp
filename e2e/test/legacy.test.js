// The 1.7 bot (src/clients/legacy.js), against an in-process 1.7.10 server built with
// minecraft-protocol: it must answer as the vanilla client does, so that real servers keep it.
import assert from 'node:assert/strict';
import { once } from 'node:events';
import { createRequire } from 'node:module';
import { after, before, describe, it } from 'node:test';

import { createBot, isLegacy } from '../src/clients/legacy.js';
import { library } from '../src/clients/mineflayer.js';

const require = createRequire(import.meta.url);
const protocol = require('minecraft-protocol');

const VERSION = '1.7.10';

describe('1.7 bot', () => {
  let server;
  let port;

  before(async () => {
    server = protocol.createServer({ 'online-mode': false, version: VERSION, host: '127.0.0.1', port: 0 });
    await once(server, 'listening');
    port = server.socketServer.address().port;
  });

  after(() => server.close());

  /** Connects a bot, and resolves with it and the server side of its connection once both play. */
  async function join() {
    const joined = once(server, 'playerJoin');
    const bot = createBot({ host: '127.0.0.1', port, username: 'e2e_legacy', version: VERSION, auth: 'offline' });
    const [[player]] = await Promise.all([joined, once(bot._client, 'success')]);
    return { bot, player };
  }

  it('is used below mineflayer’s oldest version only', () => {
    assert.equal(isLegacy('1.7.10'), true);
    assert.equal(isLegacy('1.8.8'), false);
    assert.equal(library('1.7.10'), 'minecraft-protocol');
    assert.equal(library('1.21.4'), 'mineflayer');
  });

  it('follows the game mode, sends its settings, answers a teleport and spawns', async () => {
    const { bot, player } = await join();
    try {
      const settings = once(player, 'settings');
      player.write('login', { entityId: 1, gameMode: 1 | 0b1000, dimension: 0, difficulty: 0, maxPlayers: 20, levelType: 'flat' });
      assert.equal((await settings)[0].locale, 'en_US');
      assert.equal(bot.game.gameMode, 'creative', 'game mode without the hardcore bit');

      const answer = once(player, 'position_look');
      const spawned = once(bot, 'spawn');
      player.write('position', { x: 0.5, y: 5.62, z: 0.5, yaw: 90, pitch: 0, onGround: false });
      const [look] = await answer;
      await spawned;

      // minecraft-data calls the second field "stance" and the third "y": the feet come first.
      assert.equal(look.stance, 4, 'feet');
      assert.equal(look.y, 5.62, 'eyes');
      assert.deepEqual([look.x, look.z, look.yaw], [0.5, 0.5, 90]);
      await once(player, 'flying');

      player.write('respawn', { dimension: 0, difficulty: 0, gamemode: 2, levelType: 'flat' });
      player.write('game_state_change', { reason: 'change_game_mode', gameMode: 0 });
      const message = once(bot, 'message');
      player.write('chat', { message: JSON.stringify({ text: '', extra: [{ text: 'Servers: ' }, '[lobby]'] }) });
      assert.equal((await message)[0].toString(), 'Servers: [lobby]');
      assert.equal(bot.game.gameMode, 'survival', 'the last change wins');
    } finally {
      bot.quit();
    }
  });

  it('chats and reports a kick', async () => {
    const { bot, player } = await join();
    try {
      const chat = once(player, 'chat');
      bot.chat('/server survival');
      assert.equal((await chat)[0].message, '/server survival');

      const kicked = once(bot, 'kicked');
      player.write('kick_disconnect', { reason: '{"text":"bye"}' });
      assert.equal((await kicked)[0], '{"text":"bye"}');
    } finally {
      bot.quit();
    }
  });
});
