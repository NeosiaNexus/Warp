// Checksum-verified downloads into the cache. A cached file is reused only if it still matches its
// pinned checksum, so a corrupted or tampered cache entry is replaced rather than trusted.
import { createHash, randomUUID } from 'node:crypto';
import { createReadStream, existsSync, mkdirSync, renameSync, rmSync } from 'node:fs';
import { writeFile } from 'node:fs/promises';
import { dirname } from 'node:path';

export const USER_AGENT = 'warp-e2e (+https://github.com/NeosiaNexus/Warp)';

/** Downloads in progress, by target: concurrent callers (two ViaProxy bridges) share one fetch. */
const inFlight = new Map();

/**
 * Downloads `url` to `target` unless a file with the expected checksum is already there.
 * @param {{sha256?: string, sha1?: string}} checksum at least one is required
 * @returns {Promise<string>} `target`
 */
export function download(url, target, checksum) {
  let pending = inFlight.get(target);
  if (!pending) {
    pending = fetchVerified(url, target, checksum).finally(() => inFlight.delete(target));
    inFlight.set(target, pending);
  }
  return pending;
}

async function fetchVerified(url, target, checksum) {
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
  // A temporary name of its own, then an atomic rename: another run sharing the cache (two local
  // runs, say) may be writing the same file, and readers never see a partial one.
  const partial = `${target}.${randomUUID()}.part`;
  try {
    await writeFile(partial, body);
    renameSync(partial, target);
  } finally {
    rmSync(partial, { force: true });
  }
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
