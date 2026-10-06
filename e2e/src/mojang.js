// Mock Mojang for the whole run, on one local port:
//   - session server for online-mode runs: Warp is pointed at its `hasJoined` with
//     -Dmojang.sessionserver, and it vouches for every player;
//   - Mojang's key pair (`signer`), which signs the bots' profile keys. Warp trusts it through
//     -Dwarp.profilekeys.signer (1.19 to 1.19.2 logins), and it is published as the services key set
//     (`/publickeys`), which backends from 1.20 fetch to verify the chat sessions those keys open.
//
// It vouches for each player with the UUID an offline-mode backend gives it, so that the bot and the
// backend agree on who signs, with player info forwarding or without: a chat signature covers the
// sender's UUID. The exception is FORWARDED_PLAYER, which only forwarding can name right.
import { createHash } from 'node:crypto';
import { createServer } from 'node:http';

import { createProfileKeys, createSigner } from './profile-keys.js';

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
 * The one player the mock knows under a UUID of its own, as Mojang knows every player, rather than
 * under its offline one: a backend lists it under that UUID only when it takes Warp's word on who
 * the player is (player info forwarding). The forwarding scenario logs in with it.
 */
export const FORWARDED_PLAYER = 'e2e_forwarded';

/** The UUID the mock vouches for `name` with, as 32 hex digits. */
export function mojangUuid(name) {
  if (name !== FORWARDED_PLAYER) return offlineUuid(name);
  const hash = createHash('sha256').update(`mojang:${name}`, 'utf8').digest().subarray(0, 16);
  hash[6] = (hash[6] & 0x0f) | 0x40; // version 4, as Mojang's
  hash[8] = (hash[8] & 0x3f) | 0x80; // IETF variant
  return hash.toString('hex');
}

/**
 * Starts the mock.
 * @param {number} port local port (0 for any)
 * @param {object} signer Mojang's key pair, from `createSigner`
 * @returns {Promise<{host: string, hasJoinedUrl: string, signer: object, profileKeys: Function, close: Function}>}
 */
export function startMockMojang(port, signer = createSigner()) {
  const publicKey = signer.publicKey.export({ type: 'spki', format: 'der' }).toString('base64');
  const keySet = JSON.stringify({
    profilePropertyKeys: [{ publicKey }],
    playerCertificateKeys: [{ publicKey }],
  });

  const server = createServer((req, res) => {
    const url = new URL(req.url, 'http://localhost');
    res.writeHead(200, { 'content-type': 'application/json' });
    if (url.pathname === '/publickeys') {
      res.end(keySet);
      return;
    }
    const name = url.searchParams.get('username') ?? 'unknown';
    res.end(JSON.stringify({ id: mojangUuid(name), name, properties: [] }));
  });

  /** A valid profile key for `name`, issued to the UUID the mock vouches for. */
  const profileKeys = (name) =>
    createProfileKeys({ signer, uuid: mojangUuid(name), expiresAt: Date.now() + PROFILE_KEY_LIFETIME_MS });

  return new Promise((resolve, reject) => {
    server.once('error', reject);
    server.listen(port, '127.0.0.1', () => {
      const host = `http://127.0.0.1:${server.address().port}`;
      resolve({
        host,
        hasJoinedUrl: `${host}/session/minecraft/hasJoined`,
        signer,
        profileKeys,
        close: () => new Promise((done) => server.close(done)),
      });
    });
  });
}
