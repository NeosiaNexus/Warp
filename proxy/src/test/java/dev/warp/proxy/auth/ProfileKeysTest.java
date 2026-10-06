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

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.warp.protocol.ProtocolVersion;
import dev.warp.protocol.packet.login.LoginStart.ProfilePublicKey;
import dev.warp.proxy.auth.ProfileKeys.Problem;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.spec.InvalidKeySpecException;
import java.time.Instant;
import java.time.InstantSource;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;
import java.util.UUID;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

@DisplayName("ProfileKeys")
class ProfileKeysTest {

  /** Plays Mojang: signs the players' keys. */
  private static final KeyPair SIGNER = generate(2048);

  /** A player's chat signing key, 2048 bits as Mojang issues them. */
  private static final KeyPair PLAYER = generate(2048);

  private static final Instant NOW = Instant.parse("2022-07-28T12:00:00Z");

  /** One day after {@link #NOW}, the expiry of the keys below. */
  private static final long EXPIRES_AT = NOW.plusSeconds(86_400).toEpochMilli();

  private static final UUID HOLDER = UUID.fromString("069a79f4-44e9-4726-a5be-fca90e38aaf5");

  private static final ProfileKeys KEYS =
      new ProfileKeys(SIGNER.getPublic(), InstantSource.fixed(NOW));

  // ---------------------------------------------------------------------------
  // Mojang's key
  // ---------------------------------------------------------------------------

  @Nested
  @DisplayName("Mojang's key")
  class MojangKey {

    @Test
    @DisplayName("should be authlib's yggdrasil_session_pubkey.der byte for byte")
    void isAuthlibKey() throws GeneralSecurityException {
      byte[] der = ProfileKeys.mojangSigner().getEncoded();

      // sha256sum of yggdrasil_session_pubkey.der in authlib 3.5.41 (1.19) and 3.11.49 (1.19.2)
      assertEquals(
          "4a3f31581c5b9bce0e53dd79a98d2da53fa5e116c3a16c3c36db991f152b2554",
          HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(der)));
    }

    @Test
    @DisplayName("should verify what Mojang signs with it: a skin from sessionserver.mojang.com")
    void verifiesMojangSignature() throws GeneralSecurityException {
      // Notch's textures property, as GET /session/minecraft/profile/<id>?unsigned=false returned
      // it on 2026-10-06: Mojang signs it (SHA1withRSA) with the key that signs profile keys.
      String value =
          "ewogICJ0aW1lc3RhbXAiIDogMTc5MTI4NTQ5MzY4MCwKICAicHJvZmlsZUlkIiA6ICIwNjlhNzlm"
              + "NDQ0ZTk0NzI2YTViZWZjYTkwZTM4YWFmNSIsCiAgInByb2ZpbGVOYW1lIiA6ICJOb3RjaCIsCiAg"
              + "InNpZ25hdHVyZVJlcXVpcmVkIiA6IHRydWUsCiAgInRleHR1cmVzIiA6IHsKICAgICJTS0lOIiA6"
              + "IHsKICAgICAgInVybCIgOiAiaHR0cDovL3RleHR1cmVzLm1pbmVjcmFmdC5uZXQvdGV4dHVyZS8y"
              + "OTIwMDlhNDkyNWI1OGYwMmM3N2RhZGMzZWNlZjA3ZWE0Yzc0NzJmNjRlMGZkYzMyY2U1NTIyNDg5"
              + "MzYyNjgwIgogICAgfQogIH0KfQ==";
      String signature =
          "T7hba/FkgF1Rz2aPMLc++5apSowk1V0XAnwzTY7pY9g4euP0bEhgD4N9l53zxZW0uXs+Ijcn5pHp"
              + "nXYW+53rVweZNMWjsBkBvzQdG4/d61b1Ha3wxMMXnpfxCB5pat3sGPm3mfOUoz35STzanjKyV+J0"
              + "qXwbYkEJqIQNkw6pt/OJCtvzYTtCX7jQjk6CIlD/E8F/D88dPiEGrK9377X5kuE5g2p2GfTL+z6e"
              + "P4+b0PeGyh0qCH5Parg/CqLl9OpWlU4JEupHH0XH2WcCS59lQo0TQZ7zIfX5pvZKkDga3Zqa0xyd"
              + "15WkUF214PVCRDYV+AP74WbArXfrrVNQ8L4WKwv9Jh49sC8BV2QJjVZAe5bEfzJnjODXin2pwPz+"
              + "y/cr8YsiUiEmjdQZgSYEzp2k0ilEkWtZxuinh6mUvAX9T521A+ZDpblyFfFzcwxylZuwFE2RVFZf"
              + "bTnmmJKCT2PyrW4zYfRESeGo0x08JbJ+brFZ/S4eM8GLeS5Lt6CTJ9Eq8FnUAO7QU24xQ0UGhaMJ"
              + "o5gY4bbhD+DUb9pg6xAhIt69uR1Poj8FzoD2KuN1NmaVHB2dr585DWAUWrD1h42N2gumfCAFErR3"
              + "PcHcYfB3xO9z7alv914zCFkPxC2Eb0vf4RNS+xEweAHTupZa32gTYGyDGbzxsCfmO6SvhqFlRUs=";

      Signature verifier = Signature.getInstance("SHA1withRSA");
      verifier.initVerify(ProfileKeys.mojangSigner());
      verifier.update(value.getBytes(StandardCharsets.US_ASCII));

      assertTrue(verifier.verify(Base64.getDecoder().decode(signature)));
    }
  }

  // ---------------------------------------------------------------------------
  // What Mojang signs
  // ---------------------------------------------------------------------------

  @Nested
  @DisplayName("signed payload")
  class SignedPayload {

    @Test
    @DisplayName("should be the expiry then the key as RSA PEM text in 1.19")
    void v1() {
      byte[] payload = ProfileKeys.signedPayloadV1(EXPIRES_AT, PLAYER.getPublic());

      // Crypt.rsaPublicKeyToString: MIME Base64, 76-character lines joined by \n, no CRs.
      String base64 = Base64.getEncoder().encodeToString(PLAYER.getPublic().getEncoded());
      StringBuilder pem = new StringBuilder("-----BEGIN RSA PUBLIC KEY-----\n");
      for (int i = 0; i < base64.length(); i += 76) {
        pem.append(base64, i, Math.min(i + 76, base64.length()));
        pem.append(i + 76 < base64.length() ? "\n" : "");
      }
      pem.append("\n-----END RSA PUBLIC KEY-----\n");
      assertEquals(EXPIRES_AT + pem.toString(), new String(payload, StandardCharsets.US_ASCII));
    }

    @Test
    @DisplayName("should be the UUID, the expiry and the DER key from 1.19.1")
    void v2() {
      byte[] payload = ProfileKeys.signedPayloadV2(HOLDER, EXPIRES_AT, PLAYER.getPublic());

      byte[] der = PLAYER.getPublic().getEncoded();
      String expectedHead = "069a79f444e94726a5befca90e38aaf5" + String.format("%016x", EXPIRES_AT);
      assertEquals(expectedHead, HexFormat.of().formatHex(payload, 0, 24));
      assertArrayEquals(der, Arrays.copyOfRange(payload, 24, payload.length));
    }
  }

  // ---------------------------------------------------------------------------
  // Checking a key
  // ---------------------------------------------------------------------------

  @Nested
  @DisplayName("check")
  class Check {

    @Test
    @DisplayName("should accept a 1.19 key the signer signed, without a UUID")
    void acceptsV1() {
      ProfilePublicKey key = key(ProfileKeys.signedPayloadV1(EXPIRES_AT, PLAYER.getPublic()));

      ProfileKeys.Verdict verdict = KEYS.check(key, null, ProtocolVersion.MINECRAFT_1_19);

      var accepted = assertInstanceOf(ProfileKeys.Verdict.Accepted.class, verdict);
      assertEquals(PLAYER.getPublic(), accepted.playerKey());
    }

    static Stream<ProtocolVersion> linkedVersions() {
      return Stream.of(ProtocolVersion.MINECRAFT_1_19_1, ProtocolVersion.MINECRAFT_1_19_2);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("linkedVersions")
    @DisplayName("should accept a 1.19.1+ key the signer signed for the UUID the client sent")
    void acceptsV2(ProtocolVersion version) {
      ProfilePublicKey key =
          key(ProfileKeys.signedPayloadV2(HOLDER, EXPIRES_AT, PLAYER.getPublic()));

      assertInstanceOf(ProfileKeys.Verdict.Accepted.class, KEYS.check(key, HOLDER, version));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("linkedVersions")
    @DisplayName("should refuse a 1.19.1+ key for another UUID, or without one")
    void refusesV2ForAnotherPlayer(ProtocolVersion version) {
      ProfilePublicKey key =
          key(ProfileKeys.signedPayloadV2(HOLDER, EXPIRES_AT, PLAYER.getPublic()));

      assertRefused(Problem.NOT_SIGNED, KEYS.check(key, UUID.randomUUID(), version));
      assertRefused(Problem.NO_HOLDER, KEYS.check(key, null, version));
    }

    @Test
    @DisplayName("should refuse a key signed in the format of the other revision")
    void refusesOtherRevision() {
      ProfilePublicKey v1 = key(ProfileKeys.signedPayloadV1(EXPIRES_AT, PLAYER.getPublic()));
      ProfilePublicKey v2 =
          key(ProfileKeys.signedPayloadV2(HOLDER, EXPIRES_AT, PLAYER.getPublic()));

      assertRefused(Problem.NOT_SIGNED, KEYS.check(v1, HOLDER, ProtocolVersion.MINECRAFT_1_19_2));
      assertRefused(Problem.NOT_SIGNED, KEYS.check(v2, null, ProtocolVersion.MINECRAFT_1_19));
    }

    @Test
    @DisplayName("should refuse a key another signer signed")
    void refusesOtherSigner() {
      KeyPair forger = generate(2048);
      byte[] payload = ProfileKeys.signedPayloadV1(EXPIRES_AT, PLAYER.getPublic());
      ProfilePublicKey key =
          new ProfilePublicKey(
              EXPIRES_AT, PLAYER.getPublic().getEncoded(), sign(forger.getPrivate(), payload));

      assertRefused(Problem.NOT_SIGNED, KEYS.check(key, null, ProtocolVersion.MINECRAFT_1_19));
    }

    @Test
    @DisplayName("should refuse a key whose expiry was changed after signing")
    void refusesChangedExpiry() {
      byte[] payload = ProfileKeys.signedPayloadV1(EXPIRES_AT, PLAYER.getPublic());
      ProfilePublicKey key =
          new ProfilePublicKey(
              EXPIRES_AT + 1, PLAYER.getPublic().getEncoded(), sign(SIGNER.getPrivate(), payload));

      assertRefused(Problem.NOT_SIGNED, KEYS.check(key, null, ProtocolVersion.MINECRAFT_1_19));
    }

    @Test
    @DisplayName("should refuse a key that expired, from the millisecond after its expiry")
    void refusesExpired() {
      long expiry = NOW.toEpochMilli();
      ProfilePublicKey key =
          new ProfilePublicKey(
              expiry,
              PLAYER.getPublic().getEncoded(),
              sign(SIGNER.getPrivate(), ProfileKeys.signedPayloadV1(expiry, PLAYER.getPublic())));
      ProfileKeys oneMilliLater =
          new ProfileKeys(SIGNER.getPublic(), InstantSource.fixed(NOW.plusMillis(1)));

      assertInstanceOf(
          ProfileKeys.Verdict.Accepted.class,
          KEYS.check(key, null, ProtocolVersion.MINECRAFT_1_19),
          "valid until its expiry");
      assertRefused(
          Problem.EXPIRED, oneMilliLater.check(key, null, ProtocolVersion.MINECRAFT_1_19));
    }

    @Test
    @DisplayName(
        "should refuse key bytes that are not an RSA public key, and a malformed signature")
    void refusesMalformed() {
      ProfilePublicKey notAKey =
          new ProfilePublicKey(EXPIRES_AT, new byte[] {1, 2, 3}, new byte[0]);
      ProfilePublicKey shortSignature =
          new ProfilePublicKey(EXPIRES_AT, PLAYER.getPublic().getEncoded(), new byte[] {1});

      assertRefused(Problem.MALFORMED, KEYS.check(notAKey, null, ProtocolVersion.MINECRAFT_1_19));
      assertRefused(
          Problem.NOT_SIGNED, KEYS.check(shortSignature, null, ProtocolVersion.MINECRAFT_1_19));
    }

    private ProfilePublicKey key(byte[] payload) {
      return new ProfilePublicKey(
          EXPIRES_AT, PLAYER.getPublic().getEncoded(), sign(SIGNER.getPrivate(), payload));
    }

    private void assertRefused(Problem problem, ProfileKeys.Verdict verdict) {
      assertEquals(new ProfileKeys.Verdict.Refused(problem), verdict);
    }
  }

  // ---------------------------------------------------------------------------
  // The signed verify token
  // ---------------------------------------------------------------------------

  @Nested
  @DisplayName("signsVerifyToken")
  class SignsVerifyToken {

    private static final byte[] TOKEN = {0x0A, 0x0B, 0x0C, 0x0D};
    private static final long SALT = 0x8877665544332211L;

    @Test
    @DisplayName("should accept SHA256withRSA over the token and the big-endian salt")
    void accepts() throws GeneralSecurityException {
      Signature signer = Signature.getInstance("SHA256withRSA");
      signer.initSign(PLAYER.getPrivate());
      signer.update(TOKEN);
      signer.update(HexFormat.of().parseHex("8877665544332211"));

      assertTrue(ProfileKeys.signsVerifyToken(PLAYER.getPublic(), TOKEN, SALT, signer.sign()));
    }

    @Test
    @DisplayName("should refuse a signature of another token or salt, or by another key")
    void refusesOthers() throws GeneralSecurityException {
      byte[] signature = signToken(PLAYER.getPrivate(), TOKEN, SALT);

      assertFalse(
          ProfileKeys.signsVerifyToken(
              PLAYER.getPublic(), new byte[] {0, 0, 0, 0}, SALT, signature));
      assertFalse(ProfileKeys.signsVerifyToken(PLAYER.getPublic(), TOKEN, SALT + 1, signature));
      assertFalse(ProfileKeys.signsVerifyToken(SIGNER.getPublic(), TOKEN, SALT, signature));
      assertFalse(ProfileKeys.signsVerifyToken(PLAYER.getPublic(), TOKEN, SALT, new byte[] {1}));
    }

    private byte[] signToken(PrivateKey key, byte[] token, long salt)
        throws GeneralSecurityException {
      Signature signer = Signature.getInstance("SHA256withRSA");
      signer.initSign(key);
      signer.update(token);
      signer.update(ByteBuffer.allocate(Long.BYTES).putLong(salt).array());
      return signer.sign();
    }
  }

  // ---------------------------------------------------------------------------
  // Replacing the signer
  // ---------------------------------------------------------------------------

  @Nested
  @DisplayName("signer file")
  class SignerFile {

    @TempDir private Path dir;

    @Test
    @DisplayName("should read a DER public key")
    void readsDer() throws IOException, InvalidKeySpecException {
      Path file = Files.write(dir.resolve("signer.der"), SIGNER.getPublic().getEncoded());

      assertEquals(SIGNER.getPublic(), ProfileKeys.readSigner(file));
    }

    @Test
    @DisplayName("should read a PEM public key")
    void readsPem() throws IOException, InvalidKeySpecException {
      String pem =
          "-----BEGIN PUBLIC KEY-----\n"
              + Base64.getMimeEncoder(64, new byte[] {'\n'})
                  .encodeToString(SIGNER.getPublic().getEncoded())
              + "\n-----END PUBLIC KEY-----\n";
      Path file = Files.writeString(dir.resolve("signer.pem"), pem);

      assertEquals(SIGNER.getPublic(), ProfileKeys.readSigner(file));
    }

    @Test
    @DisplayName("should refuse a file that holds no RSA public key")
    void refusesGarbage() throws IOException {
      Path garbage = Files.writeString(dir.resolve("garbage"), "not a key");
      Path truncated =
          Files.writeString(dir.resolve("truncated.pem"), "-----BEGIN PUBLIC KEY-----\nMII");

      assertThrows(InvalidKeySpecException.class, () -> ProfileKeys.readSigner(garbage));
      assertThrows(InvalidKeySpecException.class, () -> ProfileKeys.readSigner(truncated));
    }

    @Test
    @DisplayName("should trust the key the system property names")
    void trustsSystemProperty() throws IOException, InvalidKeySpecException {
      Path file = Files.write(dir.resolve("signer.der"), SIGNER.getPublic().getEncoded());
      byte[] payload = ProfileKeys.signedPayloadV1(Long.MAX_VALUE, PLAYER.getPublic());
      ProfilePublicKey key =
          new ProfilePublicKey(
              Long.MAX_VALUE, PLAYER.getPublic().getEncoded(), sign(SIGNER.getPrivate(), payload));

      System.setProperty(ProfileKeys.SIGNER_PROPERTY, file.toString());
      ProfileKeys keys;
      try {
        keys = ProfileKeys.fromSystemProperty();
      } finally {
        System.clearProperty(ProfileKeys.SIGNER_PROPERTY);
      }

      assertInstanceOf(
          ProfileKeys.Verdict.Accepted.class,
          keys.check(key, null, ProtocolVersion.MINECRAFT_1_19));
      assertInstanceOf(
          ProfileKeys.Verdict.Refused.class,
          ProfileKeys.mojang().check(key, null, ProtocolVersion.MINECRAFT_1_19));
    }
  }

  // ---------------------------------------------------------------------------
  // Helpers
  // ---------------------------------------------------------------------------

  private static byte[] sign(PrivateKey key, byte[] payload) {
    try {
      Signature signer = Signature.getInstance("SHA1withRSA");
      signer.initSign(key);
      signer.update(payload);
      return signer.sign();
    } catch (GeneralSecurityException e) {
      throw new AssertionError(e);
    }
  }

  private static KeyPair generate(int bits) {
    try {
      KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
      generator.initialize(bits);
      return generator.generateKeyPair();
    } catch (GeneralSecurityException e) {
      throw new AssertionError("RSA is a mandatory JCA algorithm", e);
    }
  }
}
