// Command tree fixtures (src/command-trees.js): reading Mojang's argument type registry, the
// argument types a pre-1.19 server's bytecode registers, and the packet ids of a capture.
import assert from 'node:assert/strict';
import { describe, it } from 'node:test';

import { packetIds, registeredNames, registryInIdOrder } from '../src/command-trees.js';

describe('registryInIdOrder', () => {
  it('lists the entries by id, whatever their order in the report', () => {
    const registry = {
      entries: {
        'brigadier:float': { protocol_id: 1 },
        'brigadier:bool': { protocol_id: 0 },
        'minecraft:entity': { protocol_id: 2 },
      },
    };

    assert.deepEqual(registryInIdOrder(registry, '1.19'), ['brigadier:bool', 'brigadier:float', 'minecraft:entity']);
  });

  it('refuses a registry with a gap, a duplicate or no entries', () => {
    assert.throws(() => registryInIdOrder({ entries: { a: { protocol_id: 0 }, b: { protocol_id: 2 } } }, 'x'), /not dense/);
    assert.throws(() => registryInIdOrder({ entries: { a: { protocol_id: 0 }, b: { protocol_id: 0 } } }, 'x'), /duplicate/);
    assert.throws(() => registryInIdOrder(undefined, 'x'), /no minecraft:command_argument_type/);
  });
});

describe('registeredNames', () => {
  it('takes the last string constant each method loads before a class constant', () => {
    assert.deepEqual(registeredNames(registrationClass()), ['entity', 'game_profile']);
  });

  it('reads nothing from bytes that are no class file', () => {
    assert.deepEqual(registeredNames(Buffer.from('not a class')), []);
  });
});

describe('packetIds', () => {
  const ids = { 'login clientbound minecraft:game_profile': 2, 'play clientbound minecraft:commands': 0x11 };
  const lookup = (state, direction, mojang) => ids[`${state} ${direction} ${mojang}`] ?? 0;

  it('has no configuration ids before 1.20.2, and some from it', () => {
    assert.equal(packetIds(763, lookup).configuration, null);
    assert.equal(packetIds(763, lookup).login.acknowledged, null);
    assert.notEqual(packetIds(764, lookup).configuration, null);
    assert.equal(packetIds(763, lookup).commands, 0x11);
  });

  it('falls back to the older name of the login success packet', () => {
    const named = (state, direction, mojang) => (mojang === 'minecraft:login_finished' ? undefined : ids[`${state} ${direction} ${mojang}`] ?? 0);

    assert.equal(packetIds(767, named).login.success, 2);
  });

  it('refuses a protocol missing an id it needs', () => {
    assert.throws(() => packetIds(767, () => undefined), /no id for login clientbound/);
  });
});

/**
 * A minimal class file with one method registering two types the way the game's code does:
 * `ldc "entity"; ldc Foo.class; ldc "game_profile"; ldc_w Foo.class; return`.
 */
function registrationClass() {
  const utf8 = (s) => {
    const bytes = Buffer.from(s, 'utf8');
    return Buffer.concat([Buffer.from([1, bytes.length >> 8, bytes.length & 0xff]), bytes]);
  };
  const u2 = (v) => Buffer.from([v >> 8, v & 0xff]);
  const u4 = (v) => Buffer.from([v >>> 24, (v >> 16) & 0xff, (v >> 8) & 0xff, v & 0xff]);
  const pool = [
    utf8('entity'), // 1
    Buffer.concat([Buffer.from([8]), u2(1)]), // 2 String "entity"
    utf8('Foo'), // 3
    Buffer.concat([Buffer.from([7]), u2(3)]), // 4 Class Foo
    utf8('Code'), // 5
    utf8('m'), // 6
    utf8('()V'), // 7
    utf8('game_profile'), // 8
    Buffer.concat([Buffer.from([8]), u2(8)]), // 9 String "game_profile"
  ];
  const code = Buffer.from([0x12, 2, 0x12, 4, 0x12, 9, 0x13, 0, 4, 0xb1]);
  const codeAttribute = Buffer.concat([u2(2), u2(0), u4(code.length), code, u2(0), u2(0)]);
  return Buffer.concat([
    u4(0xcafebabe),
    u2(0),
    u2(52),
    u2(pool.length + 1),
    ...pool,
    u2(0x21), // access flags
    u2(4), // this class
    u2(4), // super class
    u2(0), // interfaces
    u2(0), // fields
    u2(1), // methods
    u2(0),
    u2(6),
    u2(7),
    u2(1),
    u2(5),
    u4(codeAttribute.length),
    codeAttribute,
    u2(0), // class attributes
  ]);
}
