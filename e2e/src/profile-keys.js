// Profile keys (chat signing keys) for 1.19 to 1.19.2 bots. A client logged in to a Microsoft
// account sends its key and Mojang's certificate for it in Login Start, then signs the verify token
// with the key instead of encrypting it. The harness plays Mojang: it signs the bots' keys with its
// own key pair, which Warp is told to trust (-Dwarp.profilekeys.signer).
import { generateKeyPairSync, sign } from 'node:crypto';

/** First and last protocols whose Login Start carries a profile key (1.19, 1.19.1/1.19.2). */
export const FIRST_PROTOCOL = 759;
export const LAST_PROTOCOL = 760;
/** From this protocol (1.19.1) Mojang signs the player's UUID with the key. */
const LINKED_PROTOCOL = 760;

/** A stand-in for Mojang's key pair: 4096-bit RSA by default, like the Yggdrasil session key. */
export function createSigner(bits = 4096) {
  return generateKeyPairSync('rsa', { modulusLength: bits });
}

/** The signer's public key as Warp reads it: PEM `-----BEGIN PUBLIC KEY-----` (SPKI). */
export function signerPem(signer) {
  return signer.publicKey.export({ type: 'spki', format: 'pem' });
}

/**
 * A bot's profile keys in the shape minecraft-protocol sends (`client.profileKeys`): a 2048-bit RSA
 * key pair, as Mojang issues them, and the certificate in both formats (1.19 and 1.19.1+).
 *
 * @param {object} options
 * @param {{privateKey: import('node:crypto').KeyObject}} options.signer from {@link createSigner}
 * @param {string} options.uuid the player the key is issued to (dashed)
 * @param {number} options.expiresAt expiry, in milliseconds since the epoch
 */
export function createProfileKeys({ signer, uuid, expiresAt }) {
  const { publicKey, privateKey } = generateKeyPairSync('rsa', { modulusLength: 2048 });
  const der = publicKey.export({ type: 'spki', format: 'der' });
  return {
    public: publicKey,
    private: privateKey,
    expiresOn: new Date(expiresAt),
    signature: sign('RSA-SHA1', signedPayload(FIRST_PROTOCOL, { der, uuid, expiresAt }), signer.privateKey),
    signatureV2: sign('RSA-SHA1', signedPayload(LINKED_PROTOCOL, { der, uuid, expiresAt }), signer.privateKey),
  };
}

/**
 * What Mojang signs (vanilla `ProfilePublicKey.Data`, Velocity's `IdentifiedKeyImpl`):
 *  - 1.19: the expiry in decimal, then the key as RSA PEM text (MIME Base64, 76-character lines);
 *  - 1.19.1+: the UUID (16 bytes), the expiry (8 bytes, big-endian), then the DER key.
 */
export function signedPayload(protocol, { der, uuid, expiresAt }) {
  if (protocol < LINKED_PROTOCOL) {
    const base64 = der.toString('base64').match(/.{1,76}/g).join('\n');
    return Buffer.from(`${expiresAt}-----BEGIN RSA PUBLIC KEY-----\n${base64}\n-----END RSA PUBLIC KEY-----\n`, 'ascii');
  }
  const head = Buffer.alloc(24);
  head.write(uuid.replace(/-/g, ''), 0, 16, 'hex');
  head.writeBigInt64BE(BigInt(expiresAt), 16);
  return Buffer.concat([head, der]);
}
