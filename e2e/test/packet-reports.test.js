// Mojang packet reports (src/packet-reports.js): the fixture format, and the drift check.
import assert from 'node:assert/strict';
import { describe, it } from 'node:test';

import { fixtureJson, packetDifferences, packetFixture } from '../src/packet-reports.js';

/** A data generator `packets.json`, keys in no particular order. */
const REPORT = {
  play: {
    serverbound: { 'minecraft:keep_alive': { protocol_id: 27 }, 'minecraft:chat_command': { protocol_id: 6 } },
    clientbound: { 'minecraft:keep_alive': { protocol_id: 43 } },
  },
  handshake: { serverbound: { 'minecraft:intention': { protocol_id: 0 } } },
};

describe('packetFixture', () => {
  it('keeps the name and id of every packet, sorted at every level', () => {
    const fixture = packetFixture('1.21.9', 773, REPORT);

    assert.equal(
      fixtureJson(fixture),
      `${JSON.stringify(
        {
          version: '1.21.9',
          protocol: 773,
          packets: {
            handshake: { serverbound: { 'minecraft:intention': 0 } },
            play: {
              clientbound: { 'minecraft:keep_alive': 43 },
              serverbound: { 'minecraft:chat_command': 6, 'minecraft:keep_alive': 27 },
            },
          },
        },
        null,
        2,
      )}\n`,
    );
  });

  it('refuses a packet without an id', () => {
    const report = { play: { serverbound: { 'minecraft:keep_alive': {} } } };

    assert.throws(() => packetFixture('1.21.9', 773, report), /play serverbound minecraft:keep_alive has no id/);
  });

  it('refuses a missing protocol', () => {
    assert.throws(() => packetFixture('1.21.9', undefined, REPORT), /protocol undefined is not a number/);
  });
});

describe('packetDifferences', () => {
  const fixture = packetFixture('1.21.9', 773, REPORT).packets;

  it('finds nothing in the same report', () => {
    assert.deepEqual(packetDifferences(fixture, packetFixture('1.21.10', 773, REPORT).packets), []);
  });

  it('names every packet whose id moved, appeared or disappeared', () => {
    const mojang = structuredClone(fixture);
    mojang.play.clientbound['minecraft:keep_alive'] = 44;
    delete mojang.play.serverbound['minecraft:chat_command'];
    mojang.play.serverbound['minecraft:chat_command_signed'] = 7;

    assert.deepEqual(packetDifferences(fixture, mojang), [
      'play clientbound minecraft:keep_alive: fixture 0x2B, Mojang 0x2C',
      'play serverbound minecraft:chat_command: fixture 0x06, Mojang absent',
      'play serverbound minecraft:chat_command_signed: fixture absent, Mojang 0x07',
    ]);
  });
});
