// Player identities. Online-mode runs point Warp at a mock Mojang session server (with
// -Dmojang.sessionserver), which vouches for every player with a stable UUID derived from the name;
// offline-mode servers derive one from the name too, the way the game does.
import { createHash } from 'node:crypto';
import { createServer } from 'node:http';

/** The UUID the mock session server gives `name`. */
export function sessionUuid(name) {
  return dashed(createHash('sha256').update(`mock:${name}`).digest('hex').slice(0, 32));
}

/**
 * The UUID an offline-mode server gives `name`: version 3 (MD5) of `OfflinePlayer:<name>`, as Java's
 * `UUID.nameUUIDFromBytes` computes it.
 */
export function offlineUuid(name) {
  const bytes = createHash('md5').update(`OfflinePlayer:${name}`).digest();
  bytes[6] = (bytes[6] & 0x0f) | 0x30; // version 3
  bytes[8] = (bytes[8] & 0x3f) | 0x80; // IETF variant
  return dashed(bytes.toString('hex'));
}

const dashed = (hex) => `${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-${hex.slice(16, 20)}-${hex.slice(20)}`;

/** Starts the mock session server on `port` (0: one the system picks); `url` is its `hasJoined`. */
export function startSessionServer(port) {
  const requests = [];
  const server = createServer((req, res) => {
    const url = new URL(req.url, 'http://localhost');
    const name = url.searchParams.get('username') ?? 'unknown';
    requests.push({ path: url.pathname, name });
    res.writeHead(200, { 'content-type': 'application/json' });
    // Mojang writes profile ids without dashes.
    res.end(JSON.stringify({ id: sessionUuid(name).replaceAll('-', ''), name, properties: [] }));
  });
  return new Promise((resolve, reject) => {
    server.once('error', reject);
    server.listen(port, '127.0.0.1', () =>
      resolve({
        url: `http://127.0.0.1:${server.address().port}/session/minecraft/hasJoined`,
        requests,
        close: () => new Promise((done) => server.close(done)),
      }),
    );
  });
}
