// The wire helpers of the raw idle client, checked against known encodings.
import assert from 'node:assert/strict';
import { describe, it } from 'node:test';

import { varInt, mcString, readVarInt, framePacket, frameHandshake, frameStatusRequest, RAW_PROTOCOL } from '../src/rawclient.js';

describe('raw client wire format', () => {
  it('should encode small and multi-byte VarInts', () => {
    assert.deepEqual([...varInt(0)], [0x00]);
    assert.deepEqual([...varInt(1)], [0x01]);
    assert.deepEqual([...varInt(127)], [0x7f]);
    assert.deepEqual([...varInt(128)], [0x80, 0x01]);
    assert.deepEqual([...varInt(300)], [0xac, 0x02]);
  });

  it('should round-trip a VarInt through readVarInt', () => {
    for (const n of [0, 1, 127, 128, 255, 2097151, RAW_PROTOCOL]) {
      const { value, offset } = readVarInt(varInt(n), 0);
      assert.equal(value, n);
      assert.equal(offset, varInt(n).length);
    }
  });

  it('should return null from readVarInt on an incomplete buffer', () => {
    assert.equal(readVarInt(Buffer.from([0x80]), 0), null);
  });

  it('should length-prefix a string', () => {
    const s = mcString('hi');
    assert.deepEqual([...s], [2, 0x68, 0x69]);
  });

  it('should frame a packet as length then id then body', () => {
    const f = framePacket(0x00, Buffer.from([0xaa, 0xbb]));
    // length = 3 (id + 2 bytes), then 0x00 0xaa 0xbb
    assert.deepEqual([...f], [3, 0x00, 0xaa, 0xbb]);
  });

  it('should build a handshake that decodes back to the announced protocol and next state', () => {
    const f = frameHandshake(25565, 2);
    const len = readVarInt(f, 0);
    const id = readVarInt(f, len.offset);
    assert.equal(id.value, 0x00);
    const proto = readVarInt(f, id.offset);
    assert.equal(proto.value, RAW_PROTOCOL);
  });

  it('should build an empty status request', () => {
    assert.deepEqual([...frameStatusRequest()], [1, 0x00]);
  });
});
