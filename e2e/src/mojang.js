// Mock Mojang for the whole run, on one local port:
//   - session server for online-mode runs: Warp is pointed at its `hasJoined` with
//     -Dmojang.sessionserver, and it vouches for every player;
//   - services key set (`/publickeys`), which backends from 1.20 fetch to verify the profile keys
//     that sign player chat: with it, a bot can open a chat session the backend accepts.
//
// It vouches for each player with the UUID an offline-mode backend gives it (backends get no player
// forwarding), so the bot and the backend agree on who signs: a chat signature covers the sender's
// UUID.
import { createHash, generateKeyPairSync, sign } from 'node:crypto';
import { createServer } from 'node:http';

/** How long a bot's profile key stays valid: far longer than any run. */
const PROFILE_KEY_LIFETIME_MS = 24 * 60 * 60 * 1000;

/**
 * The UUID an offline-mode server derives from a player name (Java's
 * `UUID.nameUUIDFromBytes("OfflinePlayer:" + name)`), as 32 hex digits.
 */
export function offlineUuid(name) {
  const hash = createHash('md5').update(`OfflinePlayer:${name}`, 'utf8').digest();
  hash[6] = (hash[6] & 0x0f) | 0x30; // version 3, name-based
  hash[8] = (hash[8] & 0x3f) | 0x80; // IETF variant
  return hash.toString('hex');
}

/**
 * Starts the mock.
 * @param {number} port local port (0 for any)
 * @returns {Promise<{host: string, hasJoinedUrl: string, publicKey: Buffer, profileKeys: Function, close: Function}>}
 */
export function startMockMojang(port) {
  // Mojang's key pair: signs the bots' profile keys, published as the services key set.
  const mojang = generateKeyPairSync('rsa', { modulusLength: 2048 });
  const publicKey = mojang.publicKey.export({ type: 'spki', format: 'der' });
  const keySet = JSON.stringify({
    profilePropertyKeys: [{ publicKey: publicKey.toString('base64') }],
    playerCertificateKeys: [{ publicKey: publicKey.toString('base64') }],
  });

  const server = createServer((req, res) => {
    const url = new URL(req.url, 'http://localhost');
    res.writeHead(200, { 'content-type': 'application/json' });
    if (url.pathname === '/publickeys') {
      res.end(keySet);
      return;
    }
    const name = url.searchParams.get('username') ?? 'unknown';
    res.end(JSON.stringify({ id: offlineUuid(name), name, properties: [] }));
  });

  /**
   * A profile key pair for `name`, signed the way Mojang signs them from 1.19.1 (version 2): over
   * the player's UUID, the expiry and the public key, with SHA1withRSA (`ProfilePublicKey.Data`).
   * Shaped as minecraft-protocol expects `client.profileKeys`.
   */
  function profileKeys(name) {
    const uuid = offlineUuid(name);
    const pair = generateKeyPairSync('rsa', { modulusLength: 2048 });
    const expiresOn = new Date(Date.now() + PROFILE_KEY_LIFETIME_MS);
    const expiry = Buffer.alloc(8);
    expiry.writeBigInt64BE(BigInt(expiresOn.getTime()));
    const payload = Buffer.concat([Buffer.from(uuid, 'hex'), expiry, pair.publicKey.export({ type: 'spki', format: 'der' })]);
    return {
      uuid,
      public: pair.publicKey,
      private: pair.privateKey,
      expiresOn,
      signatureV2: sign('sha1', payload, mojang.privateKey),
    };
  }

  return new Promise((resolve, reject) => {
    server.once('error', reject);
    server.listen(port, '127.0.0.1', () => {
      const host = `http://127.0.0.1:${server.address().port}`;
      resolve({
        host,
        hasJoinedUrl: `${host}/session/minecraft/hasJoined`,
        publicKey,
        profileKeys,
        close: () => new Promise((done) => server.close(done)),
      });
    });
  });
}
