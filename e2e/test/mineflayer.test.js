// Corrections to minecraft-data and mineflayer (src/clients/mineflayer.js). The data ones are
// checked by parsing packets with the harness's strict parser: a packet must be read to its last
// byte.
import assert from 'node:assert/strict';
import { createRequire } from 'node:module';
import { describe, it } from 'node:test';

import { correctProtocolData, endTicks } from '../src/clients/mineflayer.js';

const require = createRequire(import.meta.url);
const { createDeserializer } = require('minecraft-protocol');
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

describe('mineflayer corrections', () => {
  /** A client that records the name of every packet written to it. */
  function recordingClient(state = 'play') {
    const sent = [];
    return { state, sent, write: (name) => sent.push(name) };
  }

  it('ends the tick after each movement packet, from 1.21.2', () => {
    const client = recordingClient();

    endTicks(client, '26.1');
    for (const name of ['position', 'chat_command', 'position_look', 'flying']) client.write(name, {});

    assert.deepEqual(client.sent, ['position', 'tick_end', 'chat_command', 'position_look', 'tick_end', 'flying', 'tick_end']);
  });

  it('leaves versions before 1.21.2 alone, which have no tick end', () => {
    const client = recordingClient();

    endTicks(client, '1.21.1');
    client.write('position', {});

    assert.deepEqual(client.sent, ['position']);
  });

  it('ends no tick outside the play state', () => {
    const client = recordingClient('configuration');

    endTicks(client, '26.1');
    client.write('position', {});

    assert.deepEqual(client.sent, ['position']);
  });
});
