// Checksum-verified downloads into the cache. A cached file is reused only if it still matches its
// pinned checksum, so a corrupted or tampered cache entry is replaced rather than trusted.
import { createHash } from 'node:crypto';
import { createReadStream, existsSync, mkdirSync, renameSync, rmSync } from 'node:fs';
import { writeFile } from 'node:fs/promises';
import { dirname } from 'node:path';

export const USER_AGENT = 'warp-e2e (+https://github.com/NeosiaNexus/Warp)';

/**
 * Downloads `url` to `target` unless a file with the expected checksum is already there.
 * @param {{sha256?: string, sha1?: string}} checksum at least one is required
 * @returns {Promise<string>} `target`
 */
export async function download(url, target, checksum) {
  const [algorithm, expected] = checksum.sha256 ? ['sha256', checksum.sha256] : ['sha1', checksum.sha1];
  if (!expected) throw new Error(`refusing to download ${url} without a pinned checksum`);
  if (existsSync(target) && (await hashFile(target, algorithm)) === expected) return target;

  mkdirSync(dirname(target), { recursive: true });
  const response = await fetch(url, { headers: { 'User-Agent': USER_AGENT } });
  if (!response.ok) throw new Error(`GET ${url}: HTTP ${response.status}`);
  const body = Buffer.from(await response.arrayBuffer());
  const actual = createHash(algorithm).update(body).digest('hex');
  if (actual !== expected) {
    throw new Error(`${url}: ${algorithm} mismatch (expected ${expected}, got ${actual})`);
  }
  const partial = `${target}.part`;
  await writeFile(partial, body);
  rmSync(target, { force: true });
  renameSync(partial, target);
  return target;
}

export function hashFile(path, algorithm) {
  return new Promise((resolve, reject) => {
    const hash = createHash(algorithm);
    createReadStream(path)
      .on('data', (chunk) => hash.update(chunk))
      .on('end', () => resolve(hash.digest('hex')))
      .on('error', reject);
  });
}
