// The mock Mojang (src/mojang.js): what Warp and the backends ask it, and what they verify.
import assert from 'node:assert/strict';
import { createPublicKey, verify } from 'node:crypto';
import { after, before, describe, it } from 'node:test';

import { offlineUuid, startMockMojang } from '../src/mojang.js';

/** Java: UUID.nameUUIDFromBytes("OfflinePlayer:Notch".getBytes(UTF_8)). */
const NOTCH_OFFLINE = 'b50ad385829d3141a2167e7d7539ba7f';

describe('mock Mojang', () => {
  let mojang;
  before(async () => {
    mojang = await startMockMojang(0);
  });
  after(() => mojang.close());

  it('derives the UUID an offline-mode server gives a player', () => {
    assert.equal(offlineUuid('Notch'), NOTCH_OFFLINE);
  });

  it('vouches for a player with that UUID, so that Warp and the backend agree on it', async () => {
    const response = await fetch(`${mojang.hasJoinedUrl}?username=Notch&serverId=-1f2e`);
    assert.deepEqual(await response.json(), { id: NOTCH_OFFLINE, name: 'Notch', properties: [] });
  });

  it('publishes its key as authlib reads the services key set', async () => {
    const keySet = await (await fetch(`${mojang.host}/publickeys`)).json();
    for (const keys of [keySet.profilePropertyKeys, keySet.playerCertificateKeys]) {
      assert.deepEqual(keys.map((k) => Buffer.from(k.publicKey, 'base64')), [mojang.publicKey]);
    }
  });

  it('signs a profile key over the UUID, the expiry and the key, as a server verifies it', () => {
    const keys = mojang.profileKeys('Notch');
    // ProfilePublicKey.Data.signedPayload: UUID, expiry in epoch millis (big endian), X.509 key.
    const expiry = Buffer.alloc(8);
    expiry.writeBigInt64BE(BigInt(keys.expiresOn.getTime()));
    const payload = Buffer.concat([Buffer.from(NOTCH_OFFLINE, 'hex'), expiry, keys.public.export({ type: 'spki', format: 'der' })]);
    const mojangKey = createPublicKey({ key: mojang.publicKey, format: 'der', type: 'spki' });

    assert.equal(keys.uuid, NOTCH_OFFLINE);
    assert.ok(verify('sha1', payload, mojangKey, keys.signatureV2), 'SHA1withRSA signature');
    assert.ok(keys.expiresOn.getTime() > Date.now() + 60 * 60 * 1000, 'valid for the whole run');
  });
});
