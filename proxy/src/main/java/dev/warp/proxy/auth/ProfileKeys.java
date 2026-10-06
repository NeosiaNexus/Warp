/*
 * Copyright (C) 2026 Warp Contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program. If not, see <https://www.gnu.org/licenses/>.
 */
package dev.warp.proxy.auth;

import dev.warp.protocol.ProtocolVersion;
import dev.warp.protocol.packet.login.LoginStart.ProfilePublicKey;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.NoSuchAlgorithmException;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.X509EncodedKeySpec;
import java.time.Instant;
import java.time.InstantSource;
import java.util.Base64;
import java.util.UUID;

import org.jspecify.annotations.Nullable;

/**
 * Checks the profile public keys that 1.19 to 1.19.2 clients send in their Login Start, and the
 * verify token they sign with them, the way Velocity does.
 *
 * <p>A client logged in to a Microsoft account holds an RSA key pair to sign its chat, and a
 * certificate for it: an expiry and Mojang's signature. It sends the public key and the certificate
 * in its Login Start, then signs the verify token with the private key instead of encrypting it.
 *
 * <p>{@link #check} accepts a key only if it has not expired and Mojang signed it ({@code
 * SHA1withRSA}, with the Yggdrasil session key that authlib ships as {@code
 * yggdrasil_session_pubkey.der}) over:
 *
 * <ul>
 *   <li><b>1.19</b>: the expiry in decimal digits, then the key as PEM text ({@code -----BEGIN RSA
 *       PUBLIC KEY-----}, MIME Base64 in lines of 76 characters), in US-ASCII
 *   <li><b>1.19.1–1.19.2</b>: the UUID of the player the key belongs to (16 bytes), the expiry (8
 *       bytes, big-endian), then the key in DER: the UUID the client sent in its Login Start, which
 *       must also be the one it authenticates as
 * </ul>
 *
 * <p>The refusals carry the translation keys Velocity disconnects with: {@value #EXPIRED} for an
 * expired key, {@value #INVALID} for any other.
 *
 * <p>The trusted signer can be replaced with the {@value #SIGNER_PROPERTY} system property, the
 * path to an RSA public key (DER, or PEM {@code -----BEGIN PUBLIC KEY-----}): for an alternative
 * authentication server that issues its own certificates, or a test harness that plays Mojang.
 *
 * <p>Instances are immutable and thread-safe.
 *
 * @see <a
 *     href="https://github.com/PaperMC/Velocity/blob/843a47e2a38325309cd66133149fc9a984f76bb8/proxy/src/main/java/com/velocitypowered/proxy/crypto/IdentifiedKeyImpl.java">Velocity's
 *     IdentifiedKeyImpl</a>
 */
public final class ProfileKeys {

  /** System property naming the file of the key trusted to sign profile keys. */
  public static final String SIGNER_PROPERTY = "warp.profilekeys.signer";

  /** Refusal of an expired key, as Velocity words it. */
  public static final String EXPIRED = "multiplayer.disconnect.invalid_public_key_signature";

  /** Refusal of a key that is malformed, not signed by the trusted signer, or not the player's. */
  public static final String INVALID = "multiplayer.disconnect.invalid_public_key";

  /**
   * Mojang's Yggdrasil session key, DER-encoded: {@code yggdrasil_session_pubkey.der} of authlib
   * 3.5.41 (Minecraft 1.19) to 3.11.49 (1.19.2), and of Velocity. SHA-256 {@code
   * 4a3f31581c5b9bce0e53dd79a98d2da53fa5e116c3a16c3c36db991f152b2554}.
   */
  private static final String MOJANG_SIGNER_DER =
      "MIICIjANBgkqhkiG9w0BAQEFAAOCAg8AMIICCgKCAgEAylB4B6m5lz7jwrcFz6Fd/fnfUhcvlxsT"
          + "Sn5kIK/2aGG1C3kMy4VjhwlxF6BFUSnfxhNswPjh3ZitkBxEAFY25uzkJFRwHwVA9mdwjashXILt"
          + "R6OqdLXXFVyUPIURLOSWqGNBtb08EN5fMnG8iFLgEJIBMxs9BvF3s3/FhuHyPKiVTZmXY0WY4ZyY"
          + "qvoKR+XjaTRPPvBsDa4WI2u1zxXMeHlodT3lnCzVvyOYBLXL6CJgByuOxccJ8hnXfF9yY4F0aeL0"
          + "80Jz/3+EBNG8RO4ByhtBf4Ny8NQ6stWsjfeUIvH7bU/4zCYcYOq4WrInXHqS8qruDmIl7P5XXGca"
          + "buzQstPf/h2CRAUpP/PlHXcMlvewjmGU6MfDK+lifScNYwjPxRo4nKTGFZf/0aqHCh/EAsQyLKrO"
          + "IYRE0lDG3bzBh8ogIMLAugsAfBb6M3mqCqKaTMAf/VAjh5FFJnjS+7bE+bZEV0qwax1CEoPPJL1f"
          + "IQjOS8zj086gjpGRCtSy9+bTPTfTR/SJ+VUB5G2IeCItkNHpJX2ygojFZ9n5Fnj7R9ZnOM+L8nyI"
          + "jPu3aePvtcrXlyLhH/hvOfIOjPxOlqW+O5QwSFP4OEcyLAUgDdUgyW36Z5mB285uKW/ighzZsOTe"
          + "vVUG2QwDItObIV6i8RCxFbN2oDHyPaO5j1tTaBNyVt8CAwEAAQ==";

  /** PEM armour of the key Mojang signs in 1.19 ({@code Crypt.rsaPublicKeyToString}). */
  private static final String RSA_PEM_HEADER = "-----BEGIN RSA PUBLIC KEY-----\n";

  private static final String RSA_PEM_FOOTER = "\n-----END RSA PUBLIC KEY-----\n";

  private static final Base64.Encoder RSA_PEM_ENCODER =
      Base64.getMimeEncoder(76, new byte[] {'\n'});

  /** PEM armour accepted in a {@value #SIGNER_PROPERTY} file. */
  private static final String PEM_HEADER = "-----BEGIN PUBLIC KEY-----";

  private static final String PEM_FOOTER = "-----END PUBLIC KEY-----";

  /** Mojang's key, parsed once. */
  private static final PublicKey MOJANG_SIGNER;

  static {
    try {
      MOJANG_SIGNER = parseRsaPublicKey(Base64.getDecoder().decode(MOJANG_SIGNER_DER));
    } catch (InvalidKeySpecException e) {
      throw new ExceptionInInitializerError(e);
    }
  }

  private final PublicKey signer;
  private final InstantSource clock;

  /**
   * Creates a checker that trusts {@code signer} and tells expired keys by {@code clock}.
   *
   * @param signer the RSA key whose signature makes a profile key valid
   * @param clock the current time
   */
  public ProfileKeys(PublicKey signer, InstantSource clock) {
    this.signer = signer;
    this.clock = clock;
  }

  /**
   * Returns a checker that trusts Mojang, as every vanilla 1.19 to 1.19.2 server does.
   *
   * @return the checker
   */
  public static ProfileKeys mojang() {
    return new ProfileKeys(MOJANG_SIGNER, InstantSource.system());
  }

  /**
   * Returns a checker that trusts the key in the {@value #SIGNER_PROPERTY} file, or Mojang when the
   * property is not set.
   *
   * @return the checker
   * @throws IOException if the file cannot be read
   * @throws InvalidKeySpecException if the file holds no RSA public key
   */
  public static ProfileKeys fromSystemProperty() throws IOException, InvalidKeySpecException {
    String path = System.getProperty(SIGNER_PROPERTY);
    if (path == null) {
      return mojang();
    }
    return new ProfileKeys(readSigner(Path.of(path)), InstantSource.system());
  }

  /** Mojang's key, which {@link #mojang()} trusts. */
  static PublicKey mojangSigner() {
    return MOJANG_SIGNER;
  }

  // ---------------------------------------------------------------------------
  // Checks
  // ---------------------------------------------------------------------------

  /** Outcome of {@link #check}. */
  public sealed interface Verdict {

    /**
     * The key is valid.
     *
     * @param playerKey the player's public key, to verify what the client signs with it
     */
    record Accepted(PublicKey playerKey) implements Verdict {}

    /**
     * The key is refused.
     *
     * @param reason the translation key of the disconnect reason, {@link #EXPIRED} or {@link
     *     #INVALID}
     */
    record Refused(String reason) implements Verdict {}
  }

  /**
   * Checks a profile key a client sent in its Login Start, as Velocity's {@code
   * InitialLoginSessionHandler} does when the packet arrives.
   *
   * @param key the key, with its expiry and Mojang's signature
   * @param holder the UUID the client sent with it, or {@code null} if none: from 1.19.1 a key
   *     cannot be checked without one, and is refused
   * @param version the client's protocol version, 1.19 to 1.19.2
   * @return {@link Verdict.Accepted} with the parsed key, or {@link Verdict.Refused} with the
   *     reason
   */
  public Verdict check(ProfilePublicKey key, @Nullable UUID holder, ProtocolVersion version) {
    if (clock.instant().isAfter(Instant.ofEpochMilli(key.expiresAt()))) {
      return new Verdict.Refused(EXPIRED);
    }
    PublicKey playerKey;
    try {
      playerKey = parseRsaPublicKey(key.publicKey());
    } catch (InvalidKeySpecException e) {
      return new Verdict.Refused(INVALID);
    }
    byte[] signed;
    if (version.isOlderThan(ProtocolVersion.MINECRAFT_1_19_1)) {
      signed = signedPayloadV1(key.expiresAt(), playerKey);
    } else if (holder != null) {
      signed = signedPayloadV2(holder, key.expiresAt(), playerKey);
    } else {
      return new Verdict.Refused(INVALID);
    }
    return verify("SHA1withRSA", signer, key.keySignature(), signed)
        ? new Verdict.Accepted(playerKey)
        : new Verdict.Refused(INVALID);
  }

  /**
   * Whether a client signed the verify token with its profile key: {@code SHA256withRSA} over the
   * token, then the salt as 8 big-endian bytes. This is how a 1.19 to 1.19.2 client with a profile
   * key answers the encryption request.
   *
   * @param playerKey the player's public key, from {@link Verdict.Accepted}
   * @param verifyToken the token the server sent, in clear
   * @param salt the salt the client sent
   * @param signature the signature the client sent
   * @return whether the signature is valid; {@code false} for a malformed one
   */
  public static boolean signsVerifyToken(
      PublicKey playerKey, byte[] verifyToken, long salt, byte[] signature) {
    byte[] signed =
        ByteBuffer.allocate(verifyToken.length + Long.BYTES).put(verifyToken).putLong(salt).array();
    return verify("SHA256withRSA", playerKey, signature, signed);
  }

  // ---------------------------------------------------------------------------
  // Signed payloads
  // ---------------------------------------------------------------------------

  /** 1.19: {@code expiresAt + Crypt.rsaPublicKeyToString(key)}, US-ASCII. */
  static byte[] signedPayloadV1(long expiresAt, PublicKey playerKey) {
    String pem =
        RSA_PEM_HEADER + RSA_PEM_ENCODER.encodeToString(playerKey.getEncoded()) + RSA_PEM_FOOTER;
    return (expiresAt + pem).getBytes(StandardCharsets.US_ASCII);
  }

  /** 1.19.1 and 1.19.2: UUID, expiry and DER key, big-endian. */
  static byte[] signedPayloadV2(UUID holder, long expiresAt, PublicKey playerKey) {
    byte[] der = playerKey.getEncoded();
    // UUID (two longs) and expiry, then the key.
    return ByteBuffer.allocate(3 * Long.BYTES + der.length)
        .putLong(holder.getMostSignificantBits())
        .putLong(holder.getLeastSignificantBits())
        .putLong(expiresAt)
        .put(der)
        .array();
  }

  // ---------------------------------------------------------------------------
  // Keys and signatures
  // ---------------------------------------------------------------------------

  /** Verifies {@code signature}; a malformed signature or key is an invalid one. */
  private static boolean verify(
      String algorithm, PublicKey key, byte[] signature, byte[] signedData) {
    try {
      Signature verifier = Signature.getInstance(algorithm);
      verifier.initVerify(key);
      verifier.update(signedData);
      return verifier.verify(signature);
    } catch (GeneralSecurityException e) {
      return false;
    }
  }

  private static PublicKey parseRsaPublicKey(byte[] der) throws InvalidKeySpecException {
    try {
      return KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(der));
    } catch (NoSuchAlgorithmException e) {
      throw new AssertionError("RSA is a mandatory JCA algorithm", e);
    }
  }

  /** Reads an RSA public key from a DER file, or a PEM one ({@code -----BEGIN PUBLIC KEY-----}). */
  static PublicKey readSigner(Path file) throws IOException, InvalidKeySpecException {
    byte[] content = Files.readAllBytes(file);
    String text = new String(content, StandardCharsets.ISO_8859_1);
    int header = text.indexOf(PEM_HEADER);
    if (header < 0) {
      return parseRsaPublicKey(content);
    }
    int footer = text.indexOf(PEM_FOOTER, header);
    if (footer < 0) {
      throw new InvalidKeySpecException("PEM public key without " + PEM_FOOTER);
    }
    String base64 = text.substring(header + PEM_HEADER.length(), footer);
    try {
      return parseRsaPublicKey(Base64.getMimeDecoder().decode(base64));
    } catch (IllegalArgumentException e) {
      throw new InvalidKeySpecException("PEM public key with invalid Base64", e);
    }
  }
}
