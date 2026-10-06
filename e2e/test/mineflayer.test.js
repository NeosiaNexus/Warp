// Corrections to minecraft-data and the tab list model (src/clients/mineflayer.js), checked by
// parsing packets with the harness's strict parser: a packet must be read to its last byte.
import assert from 'node:assert/strict';
import { createRequire } from 'node:module';
import { describe, it } from 'node:test';

import { TabList, correctProtocolData } from '../src/clients/mineflayer.js';

const require = createRequire(import.meta.url);
const { createDeserializer, createSerializer } = require('minecraft-protocol');
const minecraftData = require('minecraft-data');

const varInt = (value) => {
  const bytes = [];
  do {
    let byte = value & 0x7f;
    value >>>= 7;
    if (value) byte |= 0x80;
    bytes.push(byte);
  } while (value);
  return Buffer.from(bytes);
};
const string = (text) => Buffer.concat([varInt(Buffer.byteLength(text)), Buffer.from(text)]);

/** declare_recipes (0x77 on 1.20.5 to 1.21.1) with one decorated pot recipe, as Paper sends it. */
const DECORATED_POT = Buffer.concat([
  varInt(0x77),
  varInt(1),
  string('minecraft:decorated_pot'),
  varInt(22), // crafting_decorated_pot in the game's recipe serializer registry
  varInt(3), // category: misc
]);

function parseStrictly(version, packet) {
  const parser = createDeserializer({ state: 'play', isServer: false, version, noErrorLogging: true });
  return new Promise((resolve, reject) => {
    parser.once('data', resolve).once('error', reject);
    parser.end(packet);
  });
}

describe('minecraft-data corrections', () => {
  for (const version of ['1.20.6', '1.21.1']) {
    it(`reads the recipe serializer ids of ${version} as the game registers them`, async () => {
      correctProtocolData(version);

      const packet = await parseStrictly(version, DECORATED_POT);

      assert.equal(packet.data.name, 'declare_recipes');
      assert.deepEqual(packet.data.params.recipes, [{ name: 'minecraft:decorated_pot', type: 'minecraft:crafting_decorated_pot', data: { category: 3 } }]);
    });
  }

  it('leaves versions without the error untouched', () => {
    const before = JSON.stringify(minecraftData('1.21.4').protocol);

    correctProtocolData('1.21.4');
    correctProtocolData('1.20.4');

    assert.equal(JSON.stringify(minecraftData('1.21.4').protocol), before);
  });
});

// ---------------------------------------------------------------------------
// Tab list model
// ---------------------------------------------------------------------------

const ALICE = '5c39a8cb-1a3a-4c86-9a47-0f0aaf0e3a01';
const BOB = '0f3e1b7c-7b5d-4b55-8e0a-2c3d4e5f6a7b';

/** A Join Game of `version`, which keeps the tab list before 1.20.2. */
const JOIN_GAME = {
  '1.7.10': { entityId: 1, gameMode: 1, dimension: 0, difficulty: 0, maxPlayers: 20, levelType: 'flat' },
  '1.12.2': { entityId: 1, gameMode: 1, dimension: 0, difficulty: 0, maxPlayers: 20, levelType: 'flat', reducedDebugInfo: false },
};

/** Serializes clientbound play packets as a server of `version` would, parses them strictly, and applies them. */
async function tabListAfter(version, packets) {
  const serializer = createSerializer({ state: 'play', isServer: true, version });
  const tabList = new TabList(minecraftData(version).version.version);
  for (const [name, params] of packets) {
    const packet = await parseStrictly(version, serializer.createPacketBuffer({ name, params }));
    tabList.apply(packet.data.name, packet.data.params);
  }
  return tabList.names();
}

describe('tab list model', () => {
  it('keys 1.7 entries by name, and keeps them across a Join Game', async () => {
    const names = await tabListAfter('1.7.10', [
      ['player_info', { playerName: 'Alice', online: true, ping: 5 }],
      ['player_info', { playerName: '§cBob', online: true, ping: 5 }],
      ['player_info', { playerName: 'Alice', online: true, ping: 40 }],
      ['login', JOIN_GAME['1.7.10']],
      ['player_info', { playerName: '§cBob', online: false, ping: 0 }],
    ]);

    assert.deepEqual(names, ['Alice']);
  });

  it('keys entries by UUID from 1.8, and keeps them across a Join Game before 1.20.2', async () => {
    const names = await tabListAfter('1.12.2', [
      ['player_info', { action: 'add_player', data: [{ uuid: ALICE, name: 'Alice', properties: [], gamemode: 0, ping: 1 }, { uuid: BOB, name: 'Bob', properties: [], gamemode: 0, ping: 1 }] }],
      ['player_info', { action: 'update_latency', data: [{ uuid: ALICE, ping: 9 }] }],
      ['login', JOIN_GAME['1.12.2']],
      ['player_info', { action: 'remove_player', data: [{ uuid: BOB }] }],
    ]);

    assert.deepEqual(names, ['Alice']);
  });

  it('reads the action flags and Player Info Remove from 1.19.3', async () => {
    const names = await tabListAfter('1.20.1', [
      ['player_info', { action: { add_player: true, update_listed: true }, data: [{ uuid: ALICE, player: { name: 'Alice', properties: [] }, listed: 1 }, { uuid: BOB, player: { name: 'Bob', properties: [] }, listed: 1 }] }],
      ['player_info', { action: { update_latency: true }, data: [{ uuid: BOB, latency: 3 }] }],
      ['player_remove', { players: [ALICE] }],
    ]);

    assert.deepEqual(names, ['Bob']);
  });

  it('starts over at the Join Game that ends a configuration phase, from 1.20.2', () => {
    const tabList = new TabList(764);
    tabList.apply('player_info', { action: { add_player: true }, data: [{ uuid: ALICE, player: { name: 'Alice' } }] });

    tabList.apply('login', {});

    assert.deepEqual(tabList.names(), []);
  });
});
