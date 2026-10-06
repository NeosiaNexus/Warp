// The bots (src/clients/mineflayer.js): corrections to minecraft-data, checked by parsing packets
// with the harness's strict parser (a packet must be read to its last byte), and what bots read
// from the packets servers send.
import assert from 'node:assert/strict';
import { createRequire } from 'node:module';
import { describe, it } from 'node:test';

import { correctProtocolData, listedUuids } from '../src/clients/mineflayer.js';

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

/** A clientbound packet as a bot reads it, once a server has encoded it. */
function received(version, name, params) {
  const packet = createSerializer({ state: 'play', isServer: true, version }).createPacketBuffer({ name, params });
  return createDeserializer({ state: 'play', isServer: false, version, noErrorLogging: true }).parsePacketBuffer(packet).data.params;
}

describe('player listings', () => {
  const PLAYER = { name: 'e2e_forwarded', uuid: 'b9d8f59d-b9be-9abf-8372-413c0185f8dc' };
  const OTHER = { name: 'e2e_other', uuid: '077ede02-8786-33c5-a811-ebb70f18af8a' };

  it('read the UUID a server lists the player under, from one action per packet up to 1.19.2', () => {
    const entry = ({ name, uuid }) => ({ uuid, name, properties: [], gamemode: 1, ping: 0 });

    const packet = received('1.8.8', 'player_info', { action: 'add_player', data: [entry(OTHER), entry(PLAYER)] });

    assert.deepEqual(listedUuids(packet, PLAYER.name), [PLAYER.uuid]);
  });

  it('read it from a set of actions from 1.19.3', () => {
    const entry = ({ name, uuid }) => ({ uuid, player: { name, properties: [] }, listed: true });

    const packet = received('1.21.4', 'player_info', { action: { add_player: true, update_listed: true }, data: [entry(PLAYER), entry(OTHER)] });

    assert.deepEqual(listedUuids(packet, PLAYER.name), [PLAYER.uuid]);
  });

  it('ignore updates that add no player', () => {
    const legacy = received('1.8.8', 'player_info', { action: 'update_latency', data: [{ uuid: PLAYER.uuid, ping: 5 }] });
    const modern = received('1.21.4', 'player_info', { action: { update_latency: true }, data: [{ uuid: PLAYER.uuid, latency: 5 }] });

    assert.deepEqual(listedUuids(legacy, PLAYER.name), []);
    assert.deepEqual(listedUuids(modern, PLAYER.name), []);
  });
});
