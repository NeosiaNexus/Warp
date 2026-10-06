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
package dev.warp.proxy.connection;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import dev.warp.protocol.ProtocolState;
import dev.warp.protocol.ProtocolVersion;
import dev.warp.protocol.codec.McString;
import dev.warp.protocol.codec.McUuid;
import dev.warp.protocol.codec.VarInt;
import dev.warp.protocol.packet.Packet;
import dev.warp.protocol.packet.PacketCodec;
import dev.warp.protocol.packet.PacketDirection;
import dev.warp.protocol.packet.StateRegistry;
import dev.warp.protocol.packet.config.ConfigDisconnect;
import dev.warp.protocol.packet.handshake.Handshake;
import dev.warp.protocol.packet.login.EncryptionRequest;
import dev.warp.protocol.packet.login.LoginAcknowledged;
import dev.warp.protocol.packet.login.LoginDisconnect;
import dev.warp.protocol.packet.login.LoginSuccess;
import dev.warp.protocol.packet.play.PlayDisconnect;
import dev.warp.proxy.auth.AuthenticationException;
import dev.warp.proxy.auth.MojangSessionService;
import dev.warp.proxy.auth.ProfileKeys;

import java.math.BigInteger;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.time.InstantSource;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Drives the client login through the pipeline {@link ServerChannelInitializer} builds, and reads
 * what the proxy sends back as a client would.
 */
@DisplayName("LoginSessionHandler")
class LoginSessionHandlerTest {

  private static final int LOGIN_NEXT_STATE = 2;

  // ---------------------------------------------------------------------------
  // Disconnect reasons
  // ---------------------------------------------------------------------------

  @Nested
  @DisplayName("disconnect reasons")
  class DisconnectReasons {

    static Stream<ProtocolVersion> allVersions() {
      return ProtocolVersion.values().stream();
    }

    static Stream<ProtocolVersion> versionsBefore1202() {
      return ProtocolVersion.values().stream()
          .filter(version -> version.isOlderThan(ProtocolVersion.MINECRAFT_1_20_2));
    }

    static Stream<ProtocolVersion> versionsFrom1203() {
      return ProtocolVersion.values().stream()
          .filter(version -> version.isAtLeast(ProtocolVersion.MINECRAFT_1_20_3));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("allVersions")
    @DisplayName("should refuse a login with a JSON string reason in every version")
    void loginStateJson(ProtocolVersion version) {
      EmbeddedChannel channel = loginChannel(version);
      try {
        // "x" is shorter than any valid username.
        sendLoginStart(channel, "x", version);

        byte[] reason = nextPacket(channel, ProtocolState.LOGIN, LoginDisconnect.class, version);
        assertArrayEquals(jsonString("{\"text\":\"Invalid username\"}"), reason);
        assertClosed(channel);
      } finally {
        channel.finishAndReleaseAll();
      }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("versionsBefore1202")
    @DisplayName("should report an unreachable server in the play state with a JSON string")
    void playStateJson(ProtocolVersion version) {
      EmbeddedChannel channel = loginChannel(version);
      try {
        sendLoginStart(channel, "Steve", version);
        channel.runPendingTasks();

        nextPacket(channel, ProtocolState.LOGIN, LoginSuccess.class, version);
        byte[] reason = nextPacket(channel, ProtocolState.PLAY, PlayDisconnect.class, version);
        assertArrayEquals(
            jsonString("{\"text\":\"Could not connect to any available server.\"}"), reason);
        assertClosed(channel);
      } finally {
        channel.finishAndReleaseAll();
      }
    }

    @Test
    @DisplayName("should report an unreachable server in the configuration state on 1.20.2 as JSON")
    void configurationStateJson() {
      ProtocolVersion version = ProtocolVersion.MINECRAFT_1_20_2;
      EmbeddedChannel channel = configurationChannel(version);
      try {
        byte[] reason =
            nextPacket(channel, ProtocolState.CONFIGURATION, ConfigDisconnect.class, version);
        assertArrayEquals(
            jsonString("{\"text\":\"Could not connect to any available server.\"}"), reason);
        assertClosed(channel);
      } finally {
        channel.finishAndReleaseAll();
      }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("versionsFrom1203")
    @DisplayName("should report an unreachable server in the configuration state as NBT")
    void configurationStateNbt(ProtocolVersion version) {
      EmbeddedChannel channel = configurationChannel(version);
      try {
        byte[] reason =
            nextPacket(channel, ProtocolState.CONFIGURATION, ConfigDisconnect.class, version);
        assertArrayEquals(nbtString("Could not connect to any available server."), reason);
        assertClosed(channel);
      } finally {
        channel.finishAndReleaseAll();
      }
    }

    /** Logs in offline, acknowledges the login and returns once the backend connection failed. */
    private EmbeddedChannel configurationChannel(ProtocolVersion version) {
      EmbeddedChannel channel = loginChannel(version);
      sendLoginStart(channel, "Steve", version);
      nextPacket(channel, ProtocolState.LOGIN, LoginSuccess.class, version);
      send(channel, ProtocolState.LOGIN, new LoginAcknowledged(), version);
      channel.runPendingTasks();
      return channel;
    }
  }

  // ---------------------------------------------------------------------------
  // Compression
  // ---------------------------------------------------------------------------

  @Nested
  @DisplayName("compression")
  class Compression {

    private static final int THRESHOLD = 256;

    static Stream<ProtocolVersion> versionsBefore18() {
      return ProtocolVersion.values().stream()
          .filter(version -> version.isOlderThan(ProtocolVersion.MINECRAFT_1_8));
    }

    static Stream<ProtocolVersion> versionsFrom18() {
      return ProtocolVersion.values().stream()
          .filter(version -> version.isAtLeast(ProtocolVersion.MINECRAFT_1_8));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("versionsFrom18")
    @DisplayName("should send Set Compression with the threshold before Login Success")
    void setCompressionFrom18(ProtocolVersion version) {
      EmbeddedChannel channel = loginChannel(version, TestLoginContexts.offline(THRESHOLD));
      try {
        sendLoginStart(channel, "Steve", version);

        // Frame length 3, packet id 0x03, VarInt 256: still uncompressed, as the client expects.
        assertArrayEquals(new byte[] {0x03, 0x03, (byte) 0x80, 0x02}, nextFrame(channel));
      } finally {
        channel.finishAndReleaseAll();
      }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("versionsBefore18")
    @DisplayName("should log a 1.7 client in uncompressed, as it predates compression")
    void noCompressionBefore18(ProtocolVersion version) {
      EmbeddedChannel channel = loginChannel(version, TestLoginContexts.offline(THRESHOLD));
      try {
        sendLoginStart(channel, "Steve", version);
        channel.runPendingTasks();

        // Plain frames: a length then the packet id, no data length for a compressed frame.
        nextPacket(channel, ProtocolState.LOGIN, LoginSuccess.class, version);
        byte[] reason = nextPacket(channel, ProtocolState.PLAY, PlayDisconnect.class, version);
        assertArrayEquals(
            jsonString("{\"text\":\"Could not connect to any available server.\"}"), reason);
      } finally {
        channel.finishAndReleaseAll();
      }
    }
  }

  // ---------------------------------------------------------------------------
  // Profile keys (1.19 to 1.19.2)
  // ---------------------------------------------------------------------------

  /**
   * A 1.19 to 1.19.2 client logged in to a Microsoft account sends its chat signing key in Login
   * Start, then signs the verify token with it instead of encrypting it. Everything the client
   * sends is written out here, independently of the codecs under test.
   */
  @Nested
  @DisplayName("profile keys (1.19 to 1.19.2)")
  class ProfileKeyLogin {

    /** Plays Mojang: signs the players' profile keys. */
    private static final KeyPair SIGNER = generateRsaKeyPair();

    /** The player's chat signing key. */
    private static final KeyPair PLAYER = generateRsaKeyPair();

    private static final UUID STEVE = UUID.fromString("f81d4fae-7dec-11d0-a765-00a0c91e6bf6");

    /**
     * The reason of every refusal over the key: the only one that 1.19, 1.19.1 and 1.19.2 clients
     * all translate.
     */
    private static final String REFUSED_KEY =
        "{\"translate\":\"multiplayer.disconnect.invalid_public_key_signature\"}";

    private final ProfileKeys profileKeys =
        new ProfileKeys(SIGNER.getPublic(), InstantSource.system());

    private final MojangSessionService sessionService = mock(MojangSessionService.class);

    /** The thread that authenticated the player, once it has. */
    private final AtomicReference<Thread> authThread = new AtomicReference<>();

    @BeforeEach
    void authenticateSteve() throws AuthenticationException {
      when(sessionService.hasJoined(eq("Steve"), anyString()))
          .thenAnswer(
              invocation -> {
                authThread.set(Thread.currentThread());
                return new GameProfile(STEVE, "Steve", List.of());
              });
    }

    static Stream<ProtocolVersion> profileKeyVersions() {
      return Stream.of(
          ProtocolVersion.MINECRAFT_1_19,
          ProtocolVersion.MINECRAFT_1_19_1,
          ProtocolVersion.MINECRAFT_1_19_2);
    }

    static Stream<ProtocolVersion> linkedKeyVersions() {
      return Stream.of(ProtocolVersion.MINECRAFT_1_19_1, ProtocolVersion.MINECRAFT_1_19_2);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("profileKeyVersions")
    @DisplayName("should log in a client that signs the verify token with its profile key")
    void logsInWithSignedVerifyToken(ProtocolVersion version) throws Exception {
      EmbeddedChannel channel = onlineChannel(version);
      try {
        sendLoginStart(channel, "Steve", version, validKey(version, STEVE), uuidFor(version));
        EncryptionRequest request = encryptionRequest(channel, version);
        byte[] secret = sharedSecret();

        sendSignedResponse(channel, request, secret, 0x0123_4567_89AB_CDEFL, PLAYER.getPrivate());
        ByteBuf out = awaitLogin(channel, secret);
        try {
          LoginSuccess success = decode(LoginSuccess.CODEC, out, LoginSuccess.class, version);

          assertEquals(STEVE, success.uuid());
          assertEquals("Steve", success.username());
          verify(sessionService).hasJoined("Steve", serverHash(secret, request.publicKey()));
        } finally {
          out.release();
        }
      } finally {
        channel.finishAndReleaseAll();
      }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("linkedKeyVersions")
    @DisplayName("should refuse a key signed for another player than the one who authenticates")
    void refusesKeyOfAnotherPlayer(ProtocolVersion version) throws Exception {
      UUID other = UUID.fromString("069a79f4-44e9-4726-a5be-fca90e38aaf5");
      EmbeddedChannel channel = onlineChannel(version);
      try {
        sendLoginStart(channel, "Steve", version, validKey(version, other), other);
        EncryptionRequest request = encryptionRequest(channel, version);
        byte[] secret = sharedSecret();

        sendSignedResponse(channel, request, secret, 42L, PLAYER.getPrivate());
        ByteBuf out = awaitLogin(channel, secret);
        try {
          byte[] reason = frameBody(out, ProtocolState.LOGIN, LoginDisconnect.class, version);

          assertArrayEquals(jsonString(REFUSED_KEY), reason);
          assertClosed(channel);
        } finally {
          out.release();
        }
      } finally {
        channel.finishAndReleaseAll();
      }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("profileKeyVersions")
    @DisplayName("should refuse an expired key before asking for encryption")
    void refusesExpiredKey(ProtocolVersion version) {
      long expired = System.currentTimeMillis() - 60_000;
      byte[] key = keyOnWire(version, STEVE, expired, SIGNER.getPrivate());

      assertRefusedAtLoginStart(version, key, uuidFor(version), REFUSED_KEY);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("profileKeyVersions")
    @DisplayName("should refuse a key that Mojang did not sign")
    void refusesForgedKey(ProtocolVersion version) {
      byte[] key = keyOnWire(version, STEVE, tomorrow(), PLAYER.getPrivate());

      assertRefusedAtLoginStart(version, key, uuidFor(version), REFUSED_KEY);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("linkedKeyVersions")
    @DisplayName("should refuse a 1.19.1+ key sent without the UUID it is signed for")
    void refusesLinkedKeyWithoutUuid(ProtocolVersion version) {
      assertRefusedAtLoginStart(version, validKey(version, STEVE), null, REFUSED_KEY);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("profileKeyVersions")
    @DisplayName("should refuse a verify token signed with another salt")
    void refusesBadTokenSignature(ProtocolVersion version) throws Exception {
      EmbeddedChannel channel = onlineChannel(version);
      try {
        sendLoginStart(channel, "Steve", version, validKey(version, STEVE), uuidFor(version));
        EncryptionRequest request = encryptionRequest(channel, version);

        byte[] signature = signToken(PLAYER.getPrivate(), request.verifyToken(), 1L);
        sendEncryptionResponse(
            channel, rsaEncrypt(request, sharedSecret()), signedToken(2L, signature));

        assertRefusedPlainText(channel, version, "Invalid verify token signature");
      } finally {
        channel.finishAndReleaseAll();
      }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("profileKeyVersions")
    @DisplayName("should refuse an encrypted verify token from a client that sent a key")
    void refusesEncryptedTokenWithKey(ProtocolVersion version) throws Exception {
      EmbeddedChannel channel = onlineChannel(version);
      try {
        sendLoginStart(channel, "Steve", version, validKey(version, STEVE), uuidFor(version));
        EncryptionRequest request = encryptionRequest(channel, version);

        sendEncryptionResponse(
            channel,
            rsaEncrypt(request, sharedSecret()),
            encryptedToken(version, rsaEncrypt(request, request.verifyToken())));

        assertRefusedPlainText(channel, version, "Verify token mismatch");
      } finally {
        channel.finishAndReleaseAll();
      }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("profileKeyVersions")
    @DisplayName("should refuse a signed verify token from a client that sent no key")
    void refusesSignedTokenWithoutKey(ProtocolVersion version) throws Exception {
      EmbeddedChannel channel = onlineChannel(version);
      try {
        sendLoginStart(channel, "Steve", version, null, uuidFor(version));
        EncryptionRequest request = encryptionRequest(channel, version);

        sendSignedResponse(channel, request, sharedSecret(), 42L, PLAYER.getPrivate());

        assertRefusedPlainText(channel, version, "Invalid verify token signature");
      } finally {
        channel.finishAndReleaseAll();
      }
    }

    static Stream<ProtocolVersion> versionsAroundProfileKeys() {
      return Stream.of(
          ProtocolVersion.MINECRAFT_1_8,
          ProtocolVersion.MINECRAFT_1_18_2,
          ProtocolVersion.MINECRAFT_1_19,
          ProtocolVersion.MINECRAFT_1_19_2,
          ProtocolVersion.MINECRAFT_1_19_3,
          ProtocolVersion.latest());
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("versionsAroundProfileKeys")
    @DisplayName("should log in a client without a key that encrypts the verify token")
    void logsInWithEncryptedVerifyToken(ProtocolVersion version) throws Exception {
      EmbeddedChannel channel = onlineChannel(version);
      try {
        sendLoginStart(channel, "Steve", version, null, uuidFor(version));
        EncryptionRequest request = encryptionRequest(channel, version);
        byte[] secret = sharedSecret();

        sendEncryptionResponse(
            channel,
            rsaEncrypt(request, secret),
            encryptedToken(version, rsaEncrypt(request, request.verifyToken())));
        ByteBuf out = awaitLogin(channel, secret);
        try {
          LoginSuccess success = decode(LoginSuccess.CODEC, out, LoginSuccess.class, version);

          assertEquals(STEVE, success.uuid());
        } finally {
          out.release();
        }
      } finally {
        channel.finishAndReleaseAll();
      }
    }

    static Stream<Arguments> keysIgnoredOffline() {
      return profileKeyVersions()
          .flatMap(
              version ->
                  Stream.of(
                      Arguments.of(version, "valid", SIGNER.getPrivate(), tomorrow()),
                      Arguments.of(version, "forged", PLAYER.getPrivate(), tomorrow()),
                      Arguments.of(version, "expired", SIGNER.getPrivate(), yesterday())));
    }

    @ParameterizedTest(name = "{0}, {1} key")
    @MethodSource("keysIgnoredOffline")
    @DisplayName("should ignore the key in offline mode, as vanilla 1.19.1+ does")
    void ignoresKeyOffline(
        ProtocolVersion version, String what, PrivateKey signer, long expiresAt) {
      EmbeddedChannel channel = loginChannel(version, TestLoginContexts.offline(profileKeys));
      try {
        byte[] key = keyOnWire(version, STEVE, expiresAt, signer);
        sendLoginStart(channel, "Steve", version, key, uuidFor(version));

        byte[] body = nextPacket(channel, ProtocolState.LOGIN, LoginSuccess.class, version);
        ByteBuf buf = Unpooled.wrappedBuffer(body);
        try {
          assertEquals(
              "Steve",
              LoginSuccess.CODEC.decode(buf, version).username(),
              "logged in with a " + what + " key");
        } finally {
          buf.release();
        }
      } finally {
        channel.finishAndReleaseAll();
      }
    }

    // -------------------------------------------------------------------------
    // Client side, written out by hand
    // -------------------------------------------------------------------------

    private EmbeddedChannel onlineChannel(ProtocolVersion version) {
      return loginChannel(version, TestLoginContexts.online(sessionService, profileKeys));
    }

    private void assertRefusedAtLoginStart(
        ProtocolVersion version, byte[] key, @Nullable UUID uuid, String reasonJson) {
      EmbeddedChannel channel = onlineChannel(version);
      try {
        sendLoginStart(channel, "Steve", version, key, uuid);

        byte[] reason = nextPacket(channel, ProtocolState.LOGIN, LoginDisconnect.class, version);
        assertArrayEquals(jsonString(reasonJson), reason);
        assertClosed(channel);
        verifyNoInteractions(sessionService);
      } finally {
        channel.finishAndReleaseAll();
      }
    }

    /** The proxy refused the encryption response in clear text, without authenticating. */
    private void assertRefusedPlainText(
        EmbeddedChannel channel, ProtocolVersion version, String reason) {
      byte[] body = nextPacket(channel, ProtocolState.LOGIN, LoginDisconnect.class, version);
      assertArrayEquals(jsonString("{\"text\":\"" + reason + "\"}"), body);
      assertClosed(channel);
      verifyNoInteractions(sessionService);
    }

    /** The UUID a client of {@code version} sends: none before 1.19.1. */
    private @Nullable UUID uuidFor(ProtocolVersion version) {
      return version.isAtLeast(ProtocolVersion.MINECRAFT_1_19_1) ? STEVE : null;
    }

    private static long tomorrow() {
      return System.currentTimeMillis() + 86_400_000L;
    }

    private static long yesterday() {
      return System.currentTimeMillis() - 86_400_000L;
    }

    /** A key that the signer signed for {@code holder}, valid until tomorrow. */
    private byte[] validKey(ProtocolVersion version, UUID holder) {
      return keyOnWire(version, holder, tomorrow(), SIGNER.getPrivate());
    }

    /**
     * The profile key as the client writes it after its presence flag: expiry, DER key and
     * signature, signed by {@code signer} over what Mojang signs in {@code version}.
     */
    private byte[] keyOnWire(
        ProtocolVersion version, UUID holder, long expiresAt, PrivateKey signer) {
      byte[] der = PLAYER.getPublic().getEncoded();
      byte[] signed;
      if (version.isOlderThan(ProtocolVersion.MINECRAFT_1_19_1)) {
        // Crypt.rsaPublicKeyToString: RSA PEM armour, MIME Base64 in 76-character lines.
        String pem =
            "-----BEGIN RSA PUBLIC KEY-----\n"
                + Base64.getMimeEncoder(76, new byte[] {'\n'}).encodeToString(der)
                + "\n-----END RSA PUBLIC KEY-----\n";
        signed = (expiresAt + pem).getBytes(StandardCharsets.US_ASCII);
      } else {
        signed =
            ByteBuffer.allocate(24 + der.length)
                .putLong(holder.getMostSignificantBits())
                .putLong(holder.getLeastSignificantBits())
                .putLong(expiresAt)
                .put(der)
                .array();
      }
      byte[] signature = sign("SHA1withRSA", signer, signed);
      ByteBuf buf = Unpooled.buffer();
      try {
        buf.writeLong(expiresAt);
        VarInt.write(buf, der.length);
        buf.writeBytes(der);
        VarInt.write(buf, signature.length);
        buf.writeBytes(signature);
        return ByteBufUtil.getBytes(buf);
      } finally {
        buf.release();
      }
    }

    private EncryptionRequest encryptionRequest(EmbeddedChannel channel, ProtocolVersion version) {
      byte[] body = nextPacket(channel, ProtocolState.LOGIN, EncryptionRequest.class, version);
      ByteBuf buf = Unpooled.wrappedBuffer(body);
      try {
        return EncryptionRequest.CODEC.decode(buf, version);
      } finally {
        buf.release();
      }
    }

    private void sendSignedResponse(
        EmbeddedChannel channel,
        EncryptionRequest request,
        byte[] secret,
        long salt,
        PrivateKey playerKey)
        throws GeneralSecurityException {
      byte[] signature = signToken(playerKey, request.verifyToken(), salt);
      sendEncryptionResponse(channel, rsaEncrypt(request, secret), signedToken(salt, signature));
    }

    /** The salt and signature form of the token proof: {@code false}, salt, signature. */
    private byte[] signedToken(long salt, byte[] signature) {
      ByteBuf buf = Unpooled.buffer();
      try {
        buf.writeBoolean(false);
        buf.writeLong(salt);
        VarInt.write(buf, signature.length);
        buf.writeBytes(signature);
        return ByteBufUtil.getBytes(buf);
      } finally {
        buf.release();
      }
    }

    /** The encrypted form of the token proof, behind a {@code true} flag in 1.19 to 1.19.2. */
    private byte[] encryptedToken(ProtocolVersion version, byte[] encrypted) {
      ByteBuf buf = Unpooled.buffer();
      try {
        if (version.isBetween(ProtocolVersion.MINECRAFT_1_19, ProtocolVersion.MINECRAFT_1_19_2)) {
          buf.writeBoolean(true);
        }
        VarInt.write(buf, encrypted.length);
        buf.writeBytes(encrypted);
        return ByteBufUtil.getBytes(buf);
      } finally {
        buf.release();
      }
    }

    /** Encryption Response: packet id 0x01, the encrypted shared secret, then the token proof. */
    private void sendEncryptionResponse(
        EmbeddedChannel channel, byte[] encryptedSecret, byte[] proof) {
      ByteBuf body = Unpooled.buffer();
      VarInt.write(body, 0x01);
      VarInt.write(body, encryptedSecret.length);
      body.writeBytes(encryptedSecret);
      body.writeBytes(proof);
      writeFrame(channel, body);
    }

    private byte[] signToken(PrivateKey playerKey, byte[] token, long salt)
        throws GeneralSecurityException {
      byte[] signed =
          ByteBuffer.allocate(token.length + Long.BYTES).put(token).putLong(salt).array();
      return sign("SHA256withRSA", playerKey, signed);
    }

    private byte[] rsaEncrypt(EncryptionRequest request, byte[] data)
        throws GeneralSecurityException {
      PublicKey proxyKey =
          KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(request.publicKey()));
      Cipher cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding");
      cipher.init(Cipher.ENCRYPT_MODE, proxyKey);
      return cipher.doFinal(data);
    }

    private byte[] sharedSecret() {
      byte[] secret = new byte[16];
      ThreadLocalRandom.current().nextBytes(secret);
      return secret;
    }

    /** The {@code hasJoined} server hash: Minecraft's signed hex SHA-1 of secret and key. */
    private String serverHash(byte[] secret, byte[] proxyKey) throws GeneralSecurityException {
      MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
      sha1.update(new byte[0]); // the empty server id
      sha1.update(secret);
      sha1.update(proxyKey);
      return new BigInteger(sha1.digest()).toString(16);
    }

    // -------------------------------------------------------------------------
    // Proxy side
    // -------------------------------------------------------------------------

    /**
     * Waits for the player's authentication, runs what it handed to the event loop, and returns
     * what the proxy sent since it enabled encryption, decrypted.
     */
    private ByteBuf awaitLogin(EmbeddedChannel channel, byte[] secret) throws Exception {
      long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
      while (authThread.get() == null) {
        if (System.nanoTime() > deadline) {
          throw new AssertionError("the proxy never authenticated the player");
        }
        Thread.sleep(5);
      }
      // Joining the thread orders its hand-off to the (single-threaded) embedded event loop before
      // the tasks run here.
      authThread.get().join(10_000);
      channel.runPendingTasks();

      Cipher cipher = Cipher.getInstance("AES/CFB8/NoPadding");
      cipher.init(
          Cipher.DECRYPT_MODE, new SecretKeySpec(secret, "AES"), new IvParameterSpec(secret));
      ByteBuf clear = Unpooled.buffer();
      for (ByteBuf encrypted; (encrypted = channel.readOutbound()) != null; ) {
        try {
          clear.writeBytes(cipher.update(ByteBufUtil.getBytes(encrypted)));
        } finally {
          encrypted.release();
        }
      }
      return clear;
    }

    private <T extends Packet> T decode(
        PacketCodec<T> codec, ByteBuf frames, Class<T> type, ProtocolVersion version) {
      ByteBuf body = Unpooled.wrappedBuffer(frameBody(frames, ProtocolState.LOGIN, type, version));
      try {
        T packet = codec.decode(body, version);
        assertEquals(0, body.readableBytes(), "bytes left after " + type.getSimpleName());
        return packet;
      } finally {
        body.release();
      }
    }

    private byte[] sign(String algorithm, PrivateKey key, byte[] data) {
      try {
        Signature signer = Signature.getInstance(algorithm);
        signer.initSign(key);
        signer.update(data);
        return signer.sign();
      } catch (GeneralSecurityException e) {
        throw new AssertionError(e);
      }
    }
  }

  // ---------------------------------------------------------------------------
  // Play session (26.2+)
  // ---------------------------------------------------------------------------

  @Nested
  @DisplayName("play session")
  class PlaySessionIds {

    private final ServerLoginContext context = TestLoginContexts.offline();

    /** The play session IDs {@link #loggedIn} received, in order. */
    private final List<UUID> sessionIds = new ArrayList<>();

    /** The length of the play session ID after the profile: a UUID from 26.2, nothing before. */
    static Stream<Arguments> sessionIdLengths() {
      return Stream.of(
          Arguments.of(ProtocolVersion.MINECRAFT_26_1, 0),
          Arguments.of(ProtocolVersion.MINECRAFT_26_2, 16),
          Arguments.of(ProtocolVersion.MINECRAFT_26_3, 16));
    }

    @ParameterizedTest(name = "{0}: {1} bytes")
    @MethodSource("sessionIdLengths")
    @DisplayName("should send the play session ID after the profile from 26.2")
    void sessionIdAfterProfile(ProtocolVersion version, int sessionIdLength) {
      EmbeddedChannel channel = loginChannel(version, context);
      try {
        sendLoginStart(channel, "Steve", version);

        byte[] body = nextPacket(channel, ProtocolState.LOGIN, LoginSuccess.class, version);

        byte[] profile = offlineProfile("Steve");
        assertEquals(profile.length + sessionIdLength, body.length);
        assertArrayEquals(profile, Arrays.copyOf(body, profile.length));
      } finally {
        channel.finishAndReleaseAll();
      }
    }

    @Test
    @DisplayName("should share one ID among the players online together, and draw a new one after")
    void sharedUntilEmpty() {
      EmbeddedChannel first = loggedIn(ProtocolVersion.MINECRAFT_26_2);
      EmbeddedChannel second = loggedIn(ProtocolVersion.MINECRAFT_26_3);
      first.finishAndReleaseAll();
      EmbeddedChannel third = loggedIn(ProtocolVersion.MINECRAFT_26_2);
      second.finishAndReleaseAll();
      third.finishAndReleaseAll();
      EmbeddedChannel later = loggedIn(ProtocolVersion.MINECRAFT_26_3);
      later.finishAndReleaseAll();

      UUID sessionId = sessionIds.getFirst();
      assertEquals(sessionId, sessionIds.get(1), "a player joining another");
      assertEquals(sessionId, sessionIds.get(2), "a player joining after the first left");
      assertNotEquals(sessionId, sessionIds.get(3), "the first player once the proxy emptied");
    }

    /** Logs {@code Steve} in, records the play session ID of its Login Success, and returns it. */
    private EmbeddedChannel loggedIn(ProtocolVersion version) {
      EmbeddedChannel channel = loginChannel(version, context);
      sendLoginStart(channel, "Steve", version);
      ByteBuf body =
          Unpooled.wrappedBuffer(
              nextPacket(channel, ProtocolState.LOGIN, LoginSuccess.class, version));
      try {
        body.skipBytes(body.readableBytes() - 16);
        sessionIds.add(McUuid.read(body));
        return channel;
      } finally {
        body.release();
      }
    }
  }

  // ---------------------------------------------------------------------------
  // Pipeline
  // ---------------------------------------------------------------------------

  /**
   * A client connection that has completed the handshake into the LOGIN state, on an offline-mode
   * proxy whose only server is unreachable ({@link TestLoginContexts#offline()}).
   */
  private EmbeddedChannel loginChannel(ProtocolVersion version) {
    return loginChannel(version, TestLoginContexts.offline());
  }

  /** A client connection in the LOGIN state, on a proxy configured by {@code context}. */
  private EmbeddedChannel loginChannel(ProtocolVersion version, ServerLoginContext context) {
    EmbeddedChannel channel = new ClientChannel();
    channel.pipeline().addLast(new ServerChannelInitializer(context));
    channel.runPendingTasks();
    send(
        channel,
        ProtocolState.HANDSHAKE,
        new Handshake(version.protocol(), "localhost", 25577, LOGIN_NEXT_STATE),
        version);
    return channel;
  }

  /** Writes {@code packet} to the proxy as a client would, in one frame. */
  @SuppressWarnings("unchecked")
  private <T extends Packet> void send(
      EmbeddedChannel channel, ProtocolState state, T packet, ProtocolVersion version) {
    var encoding =
        StateRegistry.get(state, PacketDirection.SERVERBOUND).encoding(version, packet.getClass());
    ByteBuf body = Unpooled.buffer();
    VarInt.write(body, encoding.packetId());
    ((PacketCodec<T>) encoding.codec()).encode(packet, body, version);
    writeFrame(channel, body);
  }

  /** Writes {@code body} (packet id and fields) to the proxy in one frame, and releases it. */
  private void writeFrame(EmbeddedChannel channel, ByteBuf body) {
    ByteBuf frame = Unpooled.buffer();
    VarInt.write(frame, body.readableBytes());
    frame.writeBytes(body);
    body.release();
    channel.writeInbound(frame);
  }

  /**
   * Writes a Login Start as a client of {@code version} does, written out here rather than with the
   * codec under test: the name, then from 1.19 a "no signature data" flag (until 1.19.2) and the
   * player's UUID (from 1.19.1, behind a presence flag until 1.20.1).
   */
  private void sendLoginStart(EmbeddedChannel channel, String name, ProtocolVersion version) {
    UUID uuid = UUID.nameUUIDFromBytes(name.getBytes(StandardCharsets.UTF_8));
    sendLoginStart(channel, name, version, null, uuid);
  }

  /**
   * Writes a Login Start as above, with the profile key {@code keyOnWire} (expiry, key and
   * signature as the client writes them, or {@code null} for none) and the optional {@code uuid}.
   */
  private void sendLoginStart(
      EmbeddedChannel channel,
      String name,
      ProtocolVersion version,
      byte @Nullable [] keyOnWire,
      @Nullable UUID uuid) {
    ByteBuf body = Unpooled.buffer();
    VarInt.write(body, 0x00);
    McString.write(body, name);
    if (version.isAtLeast(ProtocolVersion.MINECRAFT_1_19)
        && version.isOlderThan(ProtocolVersion.MINECRAFT_1_19_3)) {
      body.writeBoolean(keyOnWire != null);
      if (keyOnWire != null) {
        body.writeBytes(keyOnWire);
      }
    }
    if (version.isAtLeast(ProtocolVersion.MINECRAFT_1_19_1)) {
      if (version.isOlderThan(ProtocolVersion.MINECRAFT_1_20_2)) {
        body.writeBoolean(uuid != null);
      }
      if (uuid != null) {
        McUuid.write(body, uuid);
      }
    }
    writeFrame(channel, body);
  }

  /**
   * Reads the next frame the proxy sent, checks that it holds exactly one {@code type} packet, and
   * returns the packet's body.
   */
  private byte[] nextPacket(
      EmbeddedChannel channel,
      ProtocolState state,
      Class<? extends Packet> type,
      ProtocolVersion version) {
    ByteBuf frame = channel.readOutbound();
    assertNotNull(frame, "expected a " + type.getSimpleName());
    try {
      assertEquals(VarInt.read(frame), frame.readableBytes(), "frame length");
      int expectedId =
          StateRegistry.get(state, PacketDirection.CLIENTBOUND).packetId(version, type);
      assertEquals(expectedId, VarInt.read(frame), type.getSimpleName() + " packet id");
      return ByteBufUtil.getBytes(frame);
    } finally {
      frame.release();
    }
  }

  /** Reads the next frame the proxy sent, whole: its length prefix and its content. */
  private byte[] nextFrame(EmbeddedChannel channel) {
    ByteBuf frame = channel.readOutbound();
    assertNotNull(frame, "expected a frame");
    try {
      return ByteBufUtil.getBytes(frame);
    } finally {
      frame.release();
    }
  }

  /**
   * Reads the next frame from a stream of {@code frames}, checks that it holds exactly one {@code
   * type} packet, and returns the packet's body.
   */
  private byte[] frameBody(
      ByteBuf frames, ProtocolState state, Class<? extends Packet> type, ProtocolVersion version) {
    ByteBuf frame = frames.readSlice(VarInt.read(frames));
    int expectedId = StateRegistry.get(state, PacketDirection.CLIENTBOUND).packetId(version, type);
    assertEquals(expectedId, VarInt.read(frame), type.getSimpleName() + " packet id");
    return ByteBufUtil.getBytes(frame);
  }

  private void assertClosed(EmbeddedChannel channel) {
    channel.runPendingTasks();
    assertFalse(channel.isOpen(), "the connection must close after the reason");
  }

  // ---------------------------------------------------------------------------
  // Expected wire formats, written out independently of the code under test
  // ---------------------------------------------------------------------------

  /** A protocol string: VarInt byte length, then UTF-8. */
  private byte[] jsonString(String json) {
    byte[] utf8 = json.getBytes(StandardCharsets.UTF_8);
    ByteBuf buf = Unpooled.buffer();
    try {
      VarInt.write(buf, utf8.length);
      buf.writeBytes(utf8);
      return ByteBufUtil.getBytes(buf);
    } finally {
      buf.release();
    }
  }

  /**
   * The profile in an offline-mode Login Success from 1.20.5: the name-based UUID, the name, no
   * property.
   */
  private byte[] offlineProfile(String name) {
    ByteBuf buf = Unpooled.buffer();
    try {
      McUuid.write(
          buf, UUID.nameUUIDFromBytes(("OfflinePlayer:" + name).getBytes(StandardCharsets.UTF_8)));
      McString.write(buf, name);
      VarInt.write(buf, 0);
      return ByteBufUtil.getBytes(buf);
    } finally {
      buf.release();
    }
  }

  /** A network NBT string tag holding ASCII text: type 8, unsigned-short length, the bytes. */
  private byte[] nbtString(String ascii) {
    byte[] bytes = ascii.getBytes(StandardCharsets.US_ASCII);
    ByteBuf buf = Unpooled.buffer();
    try {
      buf.writeByte(0x08);
      buf.writeShort(bytes.length);
      buf.writeBytes(bytes);
      return ByteBufUtil.getBytes(buf);
    } finally {
      buf.release();
    }
  }

  private static KeyPair generateRsaKeyPair() {
    try {
      KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
      generator.initialize(2048);
      return generator.generateKeyPair();
    } catch (GeneralSecurityException e) {
      throw new AssertionError("RSA is a mandatory JCA algorithm", e);
    }
  }

  /** An embedded channel with the socket address of a real client connection. */
  private static final class ClientChannel extends EmbeddedChannel {

    private static final InetSocketAddress CLIENT_ADDRESS =
        new InetSocketAddress(InetAddress.getLoopbackAddress(), 50_000);

    @Override
    protected SocketAddress remoteAddress0() {
      return CLIENT_ADDRESS;
    }
  }
}
