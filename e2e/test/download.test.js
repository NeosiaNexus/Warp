// Checksum-verified downloads (src/download.js), against a local HTTP server.
import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { mkdtempSync, readdirSync, readFileSync, rmSync } from 'node:fs';
import { createServer } from 'node:http';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { after, before, describe, it } from 'node:test';

import { download } from '../src/download.js';

const BODY = Buffer.from('a server jar, more or less');
const SHA256 = createHash('sha256').update(BODY).digest('hex');

describe('download', () => {
  let server;
  let url;
  let requests = 0;
  const dirs = [];
  const tempDir = () => {
    const dir = mkdtempSync(join(tmpdir(), 'warp-e2e-download-'));
    dirs.push(dir);
    return dir;
  };

  before(async () => {
    server = createServer((req, res) => {
      requests++;
      // Slow enough that concurrent callers overlap.
      setTimeout(() => res.end(BODY), 50);
    });
    await new Promise((resolve) => server.listen(0, '127.0.0.1', resolve));
    url = `http://127.0.0.1:${server.address().port}/tool.jar`;
  });

  after(() => {
    server.close();
    for (const dir of dirs) rmSync(dir, { recursive: true, force: true });
  });

  it('fetches once for concurrent callers of the same file, and leaves no partial file', async () => {
    const dir = tempDir();
    const target = join(dir, 'tool.jar');
    requests = 0;

    const paths = await Promise.all([download(url, target, { sha256: SHA256 }), download(url, target, { sha256: SHA256 })]);

    assert.deepEqual(paths, [target, target]);
    assert.equal(requests, 1);
    assert.deepEqual(readFileSync(target), BODY);
    assert.deepEqual(readdirSync(dir), ['tool.jar']);
  });

  it('reuses a cached file that still matches its checksum', async () => {
    const target = join(tempDir(), 'tool.jar');
    await download(url, target, { sha256: SHA256 });
    requests = 0;

    await download(url, target, { sha256: SHA256 });

    assert.equal(requests, 0);
  });

  it('rejects a file whose checksum does not match, and keeps nothing of it', async () => {
    const dir = tempDir();

    await assert.rejects(download(url, join(dir, 'tool.jar'), { sha256: '0'.repeat(64) }), /sha256 mismatch/);

    assert.deepEqual(readdirSync(dir), []);
  });

  it('refuses to download without a pinned checksum', async () => {
    await assert.rejects(download(url, join(tempDir(), 'tool.jar'), {}), /without a pinned checksum/);
  });
});
