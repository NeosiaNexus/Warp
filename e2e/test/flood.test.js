import assert from 'node:assert/strict';
import { describe, it } from 'node:test';

import { frame, handshake, mcString, readEncryptionRequest, readVarInt, varInt } from '../src/flood.js';

describe('flood wire format', () => {
  it('writes and reads VarInts as the protocol does', () => {
    for (const value of [0, 1, 127, 128, 255, 25565, 2097151]) {
      const bytes = varInt(value);
      assert.deepEqual(readVarInt(bytes, 0), { value, length: bytes.length });
    }
    assert.deepEqual([...varInt(300)], [0xac, 0x02]);
  });

  it('frames a handshake with its length first', () => {
    const bytes = handshake(25577, 1);
    const length = readVarInt(bytes, 0);
    assert.equal(length.length + length.value, bytes.length);
    assert.equal(bytes[length.length], 0x00, 'packet id');
  });

  it('reads the key and token of an encryption request', () => {
    const key = Buffer.from([1, 2, 3]);
    const token = Buffer.from([9, 8, 7, 6]);
    const request = frame(0x01, mcString(''), varInt(key.length), key, varInt(token.length), token, Buffer.from([1]));

    const read = readEncryptionRequest(request);

    assert.deepEqual([...read.publicKey], [1, 2, 3]);
    assert.deepEqual([...read.verifyToken], [9, 8, 7, 6]);
    assert.equal(readEncryptionRequest(request.subarray(0, 4)), null, 'incomplete');
  });
});
