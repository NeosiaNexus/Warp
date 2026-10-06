// The profile keys the harness signs in Mojang's place must be signed over exactly what vanilla and
// Warp verify, or the profile-key scenario would test the harness instead of Warp.
import assert from 'node:assert/strict';
import { verify } from 'node:crypto';
import { describe, it } from 'node:test';

import { createProfileKeys, createSigner, signedPayload, signerPem } from '../src/profile-keys.js';

const signer = createSigner(2048);
const uuid = '069a79f4-44e9-4726-a5be-fca90e38aaf5';
const expiresAt = 1_656_000_000_000;

describe('profile keys', () => {
  const keys = createProfileKeys({ signer, uuid, expiresAt });
  const der = keys.public.export({ type: 'spki', format: 'der' });

  it('signs the 1.19 certificate over the expiry and the key as RSA PEM text', () => {
    const payload = signedPayload(759, { der, uuid, expiresAt }).toString('ascii');
    const lines = payload.split('\n');

    assert.equal(lines[0], '1656000000000-----BEGIN RSA PUBLIC KEY-----');
    assert.ok(lines.slice(1, -3).every((line) => line.length === 76), 'full lines of 76 characters');
    assert.deepEqual(lines.slice(-2), ['-----END RSA PUBLIC KEY-----', '']);
    assert.equal(Buffer.from(lines.slice(1, -2).join(''), 'base64').compare(der), 0);
    assert.ok(verify('RSA-SHA1', Buffer.from(payload, 'ascii'), signer.publicKey, keys.signature));
  });

  it('signs the 1.19.1+ certificate over the UUID, the big-endian expiry and the DER key', () => {
    const payload = signedPayload(760, { der, uuid, expiresAt });

    assert.equal(payload.subarray(0, 24).toString('hex'), '069a79f444e94726a5befca90e38aaf5' + '00000181914ab000');
    assert.equal(payload.subarray(24).compare(der), 0);
    assert.ok(verify('RSA-SHA1', payload, signer.publicKey, keys.signatureV2));
  });

  it('hands Warp the signer as an SPKI PEM public key', () => {
    assert.match(signerPem(signer), /^-----BEGIN PUBLIC KEY-----\n[\s\S]+\n-----END PUBLIC KEY-----\n$/);
  });

  it('carries the player the key is issued to, whom the bot announces', () => {
    assert.equal(keys.uuid, uuid);
  });

  it('signs the same payload for a UUID with or without dashes', () => {
    const bare = uuid.replace(/-/g, '');
    assert.equal(signedPayload(760, { der, uuid: bare, expiresAt }).compare(signedPayload(760, { der, uuid, expiresAt })), 0);
  });
});
