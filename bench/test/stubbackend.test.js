// The stub backend of the idle scenario, driven by the raw client over a real socket, and its
// forwarding checks from fixed inputs.
import assert from 'node:assert/strict';
import { createHmac } from 'node:crypto';
import { createRequire } from 'node:module';
import { connect } from 'node:net';
import { after, before, describe, it } from 'node:test';

import { connectIdle, frameHandshake, mcString, varInt } from '../src/rawclient.js';
import { joinGameBody, readLegacyForwarding, readModernForwarding, StubServer } from '../src/stubbackend.js';

const pause = (ms) => new Promise((resolve) => setTimeout(resolve, ms));
const listen = async (options) => {
  const server = await new StubServer({ port: 0, secret: 'SECRET', compressionThreshold: 256, ...options }).listen();
  return { server, port: server.server.address().port };
};

describe('stub backend, no forwarding', () => {
  let stub;
  before(async () => { stub = await listen({ forwarding: 'none' }); });
  after(() => stub.server.close());

  it('should carry a client into the PLAY state and count it as a player', async () => {
    const client = await connectIdle({ host: '127.0.0.1', port: stub.port, username: 'idle_1' }, 5_000);
    assert.equal(client.spawned, true);
    assert.equal(client.threshold, 256, 'the stub sets the same compression threshold as the Paper backends');
    await pause(50); // the stub counts the player when the client's last configuration packet arrives
    const stats = stub.server.stats();
    assert.equal(stats.players, 1);
    assert.equal(stats.refused, 0);
    client.destroy();
  });

  it('should answer a PLAY ping request at once, in fractional milliseconds', async () => {
    const client = await connectIdle({ host: '127.0.0.1', port: stub.port, username: 'probe' }, 5_000);
    const rtt = await client.ping();
    assert.equal(typeof rtt, 'number');
    assert.ok(rtt > 0 && rtt < 1_000, `round trip ${rtt}`);
    assert.notEqual(rtt, Math.round(rtt), 'finer than a whole millisecond');
    client.destroy();
    await pause(20);
    assert.equal(await client.ping(), null, 'a closed client reports no round trip');
  });

  it('should forget a player whose connection closed', async () => {
    const client = await connectIdle({ host: '127.0.0.1', port: stub.port, username: 'leaver' }, 5_000);
    await pause(50);
    client.destroy();
    await pause(50);
    assert.equal(stub.server.stats().players, 0);
  });
});

describe('stub backend, forwarding required', () => {
  it('should refuse a login without BungeeCord forwarding data when legacy forwarding is on', async () => {
    const stub = await listen({ forwarding: 'legacy' });
    await assert.rejects(connectIdle({ host: '127.0.0.1', port: stub.port, username: 'direct' }, 2_000), /closed before spawn/);
    assert.equal(stub.server.stats().refused, 1);
    await stub.server.close();
  });

  it('should ask for Velocity forwarding and refuse a client that cannot answer it', async () => {
    const stub = await listen({ forwarding: 'modern' });
    const socket = connect(stub.port, '127.0.0.1');
    const received = [];
    socket.on('data', (chunk) => received.push(chunk));
    const loginStart = Buffer.concat([varInt(0x00), mcString('bob'), Buffer.alloc(16)]);
    socket.write(Buffer.concat([frameHandshake(stub.port, 2), varInt(loginStart.length), loginStart]));
    await new Promise((resolve) => setTimeout(resolve, 100));
    assert.ok(Buffer.concat(received).includes('velocity:player_info'), 'a login plugin request on the Velocity channel');
    // Login plugin response, message 1, "not understood".
    const response = Buffer.concat([varInt(0x02), varInt(1), Buffer.from([0])]);
    socket.write(Buffer.concat([varInt(response.length), response]));
    await new Promise((resolve) => socket.once('close', resolve));
    assert.equal(stub.server.stats().refused, 1);
    await stub.server.close();
  });
});

describe('forwarding payloads', () => {
  const uuid = Buffer.from('00112233445566778899aabbccddeeff', 'hex');
  const payload = Buffer.concat([varInt(1), mcString('127.0.0.1'), uuid, mcString('bob'), varInt(0)]);
  const signed = (secret) => Buffer.concat([createHmac('sha256', secret).update(payload).digest(), payload]);

  it('should accept a modern forwarding payload signed with the shared secret', () => {
    assert.deepEqual(readModernForwarding(signed('SECRET'), 'SECRET'), { uuid, name: 'bob' });
  });

  it('should reject a modern forwarding payload signed with another secret, or truncated', () => {
    assert.equal(readModernForwarding(signed('other'), 'SECRET'), null);
    assert.equal(readModernForwarding(Buffer.alloc(10), 'SECRET'), null);
  });

  it('should read the UUID of a BungeeCord handshake and reject a plain host', () => {
    assert.deepEqual(readLegacyForwarding('127.0.0.1\0' + '10.0.0.1\0' + '00112233445566778899aabbccddeeff'), uuid);
    assert.equal(readLegacyForwarding('127.0.0.1'), null);
  });
});

describe('Join Game', () => {
  it('should be a well-formed 1.21.4 login packet', (t) => {
    let protocol;
    try {
      protocol = createRequire(new URL('../../e2e/', import.meta.url))('minecraft-protocol');
    } catch {
      t.skip('the e2e dependencies are not installed');
      return;
    }
    const body = Buffer.concat([varInt(0x2c), joinGameBody(7)]);
    const parsed = protocol.createDeserializer({ state: 'play', isServer: false, version: '1.21.4' }).parsePacketBuffer(body);
    assert.equal(parsed.data.name, 'login');
    assert.equal(parsed.data.params.entityId, 7);
    assert.equal(parsed.data.params.worldState.name, 'minecraft:overworld');
    assert.equal(parsed.metadata.size, body.length, 'no byte left unread');
  });
});
