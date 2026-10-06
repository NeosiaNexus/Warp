// Resource packs (src/packs.js): valid archives, a SHA-1 that only depends on their content, and
// served at the URL the backends offer.
import assert from 'node:assert/strict';
import { describe, it } from 'node:test';
import { crc32 } from 'node:zlib';

import { resourcePack, sha1, startPackServer, storedZip } from '../src/packs.js';

/** Reads a zip of stored entries as unzip does: from the central directory at its end. */
function unzip(zip) {
  const end = zip.length - 22;
  assert.equal(zip.readUInt32LE(end), 0x06054b50, 'end of central directory');
  const files = {};
  let at = zip.readUInt32LE(end + 16);
  for (let i = 0; i < zip.readUInt16LE(end + 10); i++) {
    assert.equal(zip.readUInt32LE(at), 0x02014b50, 'central directory header');
    const name = zip.toString('ascii', at + 46, at + 46 + zip.readUInt16LE(at + 28));
    const local = zip.readUInt32LE(at + 42);
    assert.equal(zip.readUInt32LE(local), 0x04034b50, `${name}: local header`);
    const start = local + 30 + zip.readUInt16LE(local + 26) + zip.readUInt16LE(local + 28);
    const data = zip.subarray(start, start + zip.readUInt32LE(at + 24));
    assert.equal(crc32(data), zip.readUInt32LE(at + 16), `${name}: CRC-32`);
    files[name] = data.toString();
    at += 46 + zip.readUInt16LE(at + 28) + zip.readUInt16LE(at + 30) + zip.readUInt16LE(at + 32);
  }
  return files;
}

describe('resource packs', () => {
  it('are zips with a pack.mcmeta', () => {
    const { zip } = resourcePack('warp-e2e lobby');

    assert.deepEqual(Object.keys(unzip(zip)), ['pack.mcmeta']);
    assert.deepEqual(JSON.parse(unzip(zip)['pack.mcmeta']), { pack: { pack_format: 1, description: 'warp-e2e lobby' } });
  });

  it('keep every entry of an archive', () => {
    const zip = storedZip({ 'a.txt': Buffer.from('first'), 'dir/b.txt': Buffer.from('second') });

    assert.deepEqual(unzip(zip), { 'a.txt': 'first', 'dir/b.txt': 'second' });
  });

  it('have a SHA-1 that only depends on their content', () => {
    const lobby = resourcePack('warp-e2e lobby');

    assert.equal(resourcePack('warp-e2e lobby').sha1, lobby.sha1);
    assert.equal(sha1(lobby.zip), lobby.sha1);
    assert.notEqual(resourcePack('warp-e2e survival').sha1, lobby.sha1);
  });

  it('are served at the URL the backends offer, each with its own SHA-1 and id', async () => {
    const server = await startPackServer(['lobby', 'survival']);
    try {
      const { lobby, survival } = server.packs;
      const download = await fetch(lobby.url);
      const missing = await fetch(lobby.url.replace('lobby', 'nether'));

      assert.equal(download.headers.get('content-type'), 'application/zip');
      assert.equal(sha1(Buffer.from(await download.arrayBuffer())), lobby.sha1);
      assert.equal(missing.status, 404);
      assert.notEqual(lobby.sha1, survival.sha1);
      assert.notEqual(lobby.id, survival.id);
    } finally {
      await server.close();
    }
  });
});
