// Mock Mojang session server for online-mode runs: Warp is pointed at it with
// -Dmojang.sessionserver, and it vouches for every player with a stable UUID derived from the name.
import { createHash } from 'node:crypto';
import { createServer } from 'node:http';

/** The UUID the mock session server gives `name` (dashed), the one its profile key must be for. */
export function mockUuid(name) {
  const id = mockId(name);
  return `${id.slice(0, 8)}-${id.slice(8, 12)}-${id.slice(12, 16)}-${id.slice(16, 20)}-${id.slice(20)}`;
}

function mockId(name) {
  return createHash('sha256').update(`mock:${name}`).digest('hex').slice(0, 32);
}

export function startSessionServer(port) {
  const requests = [];
  const server = createServer((req, res) => {
    const url = new URL(req.url, 'http://localhost');
    const name = url.searchParams.get('username') ?? 'unknown';
    requests.push({ path: url.pathname, name });
    res.writeHead(200, { 'content-type': 'application/json' });
    res.end(JSON.stringify({ id: mockId(name), name, properties: [] }));
  });
  return new Promise((resolve, reject) => {
    server.once('error', reject);
    server.listen(port, '127.0.0.1', () =>
      resolve({
        url: `http://127.0.0.1:${port}/session/minecraft/hasJoined`,
        requests,
        close: () => new Promise((done) => server.close(done)),
      }),
    );
  });
}
