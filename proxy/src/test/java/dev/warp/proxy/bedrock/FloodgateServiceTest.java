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
package dev.warp.proxy.bedrock;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.warp.api.player.ClientPlatform.Bedrock;
import dev.warp.api.player.ClientPlatform.DeviceOs;
import dev.warp.api.player.ClientPlatform.InputMode;
import dev.warp.api.player.ClientPlatform.UiProfile;
import dev.warp.proxy.bedrock.FloodgateService.Reason;
import dev.warp.proxy.bedrock.FloodgateService.Result;
import dev.warp.proxy.config.BedrockConfig;
import dev.warp.proxy.network.CidrRanges;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("FloodgateService")
class FloodgateServiceTest {

  static final byte[] KEY = FloodgateCipherTest.KEY;

  static final InetSocketAddress GEYSER_HOST =
      new InetSocketAddress(InetAddress.getLoopbackAddress(), 51_000);

  static final InetSocketAddress PLAYER = address("198.51.100.7", 51_000);

  static final InetSocketAddress STRANGER = address("203.0.113.9", 51_000);

  static InetSocketAddress address(String ip, int port) {
    return new InetSocketAddress(InetAddress.ofLiteral(ip), port);
  }

  static BedrockConfig config(String prefix, boolean forward, Map<String, Boolean> servers) {
    return new BedrockConfig(
        new BedrockConfig.Floodgate(true, KEY, "key.pem", prefix, forward),
        CidrRanges.parse(List.of("127.0.0.1/32", "::1/128")),
        servers);
  }

  static FloodgateService service() {
    return new FloodgateService(config(".", false, Map.of()));
  }

  static String blob(byte[] key, String plaintext) {
    return new FloodgateCipher(key).encrypt(plaintext);
  }

  static String hostname(String plaintext) {
    return "play.example.com\0" + blob(KEY, plaintext);
  }

  static BedrockIdentity verified(Result result) {
    return assertInstanceOf(Result.Verified.class, result).identity();
  }

  static Reason rejected(Result result) {
    return assertInstanceOf(Result.Rejected.class, result).reason();
  }

  @Test
  @DisplayName("should be enabled with a key and disabled without")
  void enabled() {
    assertTrue(service().enabled());
    assertFalse(FloodgateService.DISABLED.enabled());
    assertEquals(".", service().usernamePrefix());
    FloodgateService service = service();
    assertEquals(0, service.logRefusal(), "the first refusal is logged, nothing held back");
    assertEquals(-1, service.logRefusal(), "the next one within the interval is held back");
  }

  @Nested
  @DisplayName("read()")
  class Read {

    @Test
    @DisplayName("should take a handshake without Floodgate data for a Java client's")
    void none() {
      assertEquals(Result.NONE, service().read("play.example.com", GEYSER_HOST, GEYSER_HOST));
      assertEquals(
          Result.NONE, service().read("play.example.com\0FML2\0", GEYSER_HOST, GEYSER_HOST));
    }

    @Test
    @DisplayName("should ignore Floodgate data while Floodgate is off")
    void disabled() {
      FloodgateService off = FloodgateService.DISABLED;

      assertFalse(off.enabled());
      assertEquals(
          Result.NONE, off.read(hostname(FloodgateDataTest.GEYSER_PLAINTEXT), GEYSER_HOST, PLAYER));
    }

    @Test
    @DisplayName("should derive Floodgate's identity from verified data")
    void identity() {
      BedrockIdentity identity =
          verified(
              service().read(hostname(FloodgateDataTest.GEYSER_PLAINTEXT), GEYSER_HOST, PLAYER));

      assertEquals(new UUID(0, 2535428912345678L), identity.javaId());
      assertEquals("00000000-0000-0000-0009-01f58bac724e", identity.javaId().toString());
      assertEquals(".Steve_Jr", identity.javaName());
      assertNull(identity.realAddress(), "the data names the connection's own address");
      Bedrock platform = identity.platform();
      assertEquals(2535428912345678L, platform.xuid());
      assertEquals("Steve Jr", platform.bedrockName());
      assertEquals(DeviceOs.ANDROID, platform.deviceOs());
      assertEquals(UiProfile.CLASSIC, platform.uiProfile());
      assertEquals(InputMode.TOUCH, platform.inputMode());
      assertEquals("en_US", platform.language());
      assertTrue(platform.linkedJava().isEmpty());
      assertEquals(PLAYER.getAddress(), platform.address());
      assertEquals(platform.floodgateId(), identity.javaId());
      assertEquals(FloodgateDataTest.GEYSER_PLAINTEXT, identity.data().encode());
    }

    @Test
    @DisplayName("should find the data after other suffixes, as Floodgate does")
    void amongSuffixes() {
      String hostname =
          "play.example.com\0"
              + "203.0.113.9\0"
              + blob(KEY, FloodgateDataTest.GEYSER_PLAINTEXT)
              + "\0[]";

      assertEquals(".Steve_Jr", verified(service().read(hostname, GEYSER_HOST, PLAYER)).javaName());
    }

    @Test
    @DisplayName("should take the player's address from a trusted Geyser host")
    void trustedGeyser() {
      BedrockIdentity identity =
          verified(
              service()
                  .read(hostname(FloodgateDataTest.GEYSER_PLAINTEXT), GEYSER_HOST, GEYSER_HOST));

      assertEquals(address("198.51.100.7", GEYSER_HOST.getPort()), identity.realAddress());
    }

    @Test
    @DisplayName("should refuse data naming another address from an untrusted peer")
    void addressMismatch() {
      assertEquals(
          Reason.ADDRESS_MISMATCH,
          rejected(
              service().read(hostname(FloodgateDataTest.GEYSER_PLAINTEXT), STRANGER, STRANGER)));
    }

    @Test
    @DisplayName("should refuse the same blob twice")
    void replay() {
      FloodgateService service = service();
      String hostname = hostname(FloodgateDataTest.GEYSER_PLAINTEXT);

      verified(service.read(hostname, GEYSER_HOST, PLAYER));

      assertEquals(Reason.REPLAYED, rejected(service.read(hostname, GEYSER_HOST, PLAYER)));
      // A fresh encryption of the same player is a new login.
      verified(service.read(hostname(FloodgateDataTest.GEYSER_PLAINTEXT), GEYSER_HOST, PLAYER));
    }

    @Test
    @DisplayName("should refuse data under another key, an unknown version, or a malformed text")
    void notVerified() {
      String otherKey =
          "play.example.com\0"
              + blob(FloodgateCipherTest.OTHER_KEY, FloodgateDataTest.GEYSER_PLAINTEXT);
      String version1 =
          hostname(FloodgateDataTest.GEYSER_PLAINTEXT).replace("^Floodgate^>", "^Floodgate^?");

      assertEquals(Reason.NOT_VERIFIED, rejected(service().read(otherKey, GEYSER_HOST, PLAYER)));
      assertEquals(Reason.UNKNOWN_VERSION, rejected(service().read(version1, GEYSER_HOST, PLAYER)));
      assertEquals(
          Reason.MALFORMED, rejected(service().read(hostname("a\0b\0c"), GEYSER_HOST, PLAYER)));
      assertEquals(
          Reason.NOT_VERIFIED,
          rejected(service().read("play.example.com\0^Floodgate^>junk", GEYSER_HOST, PLAYER)));
    }

    @Test
    @DisplayName("should refuse an identity that cannot log in")
    void invalidIdentity() {
      String zeroXuid = FloodgateDataTest.GEYSER_PLAINTEXT.replace("2535428912345678", "0");
      String noName = FloodgateDataTest.GEYSER_PLAINTEXT.replace("Steve Jr", "");
      String hostIp = FloodgateDataTest.GEYSER_PLAINTEXT.replace("198.51.100.7", "evil.example");

      assertEquals(
          Reason.INVALID_IDENTITY,
          rejected(service().read(hostname(zeroXuid), GEYSER_HOST, PLAYER)));
      assertEquals(
          Reason.INVALID_IDENTITY, rejected(service().read(hostname(noName), GEYSER_HOST, PLAYER)));
      assertEquals(
          Reason.INVALID_IDENTITY, rejected(service().read(hostname(hostIp), GEYSER_HOST, PLAYER)));
    }

    @Test
    @DisplayName("should log in a linked account under its Java identity")
    void linked() {
      UUID java = UUID.fromString("069a79f4-44e9-4726-a5be-fca90e38aaf5");
      String plaintext =
          FloodgateDataTest.GEYSER_PLAINTEXT.replace(
              "\0null\0" + "0\0",
              "\0Notch;" + java + ";" + new UUID(0, 2535428912345678L) + "\0" + "0\0");

      BedrockIdentity identity = verified(service().read(hostname(plaintext), GEYSER_HOST, PLAYER));

      assertEquals(java, identity.javaId());
      assertEquals("Notch", identity.javaName());
      assertEquals("Steve Jr", identity.platform().bedrockName());
      var linked = identity.platform().linkedJava();
      assertTrue(linked.isPresent());
      assertEquals(java, linked.get().id());
      assertEquals("Notch", linked.get().name());
    }

    @Test
    @DisplayName("should refuse a linked account whose Java name is not one")
    void linkedBadName() {
      String plaintext =
          FloodgateDataTest.GEYSER_PLAINTEXT.replace(
              "\0null\0" + "0\0",
              "\0No;" + UUID.randomUUID() + ";" + new UUID(0, 2535428912345678L) + "\0" + "0\0");

      assertEquals(
          Reason.INVALID_IDENTITY,
          rejected(service().read(hostname(plaintext), GEYSER_HOST, PLAYER)));
    }
  }

  @Nested
  @DisplayName("names")
  class Names {

    @Test
    @DisplayName("should prefix the gamertag, replace its spaces and cut it to 16 characters")
    void javaName() {
      FloodgateService service = service();

      assertEquals(".Steve", service.javaName("Steve"));
      assertEquals(".Steve_Jr", service.javaName("Steve Jr"));
      assertEquals(".ABCDEFGHIJKLMNO", service.javaName("ABCDEFGHIJKLMNOP"));
      assertNull(service.javaName(""));
      assertNull(service.javaName("Ste\tve"));
      assertNull(service.javaName("a;b"));
    }

    @Test
    @DisplayName("should honour another prefix, or none")
    void prefix() {
      assertEquals("_Steve", new FloodgateService(config("_", false, Map.of())).javaName("Steve"));
      assertEquals("Steve", new FloodgateService(config("", false, Map.of())).javaName("Steve"));
    }

    @Test
    @DisplayName("should reserve prefixed names for Bedrock players, unless the prefix is empty")
    void reserves() {
      assertTrue(service().reservesName(".Steve"));
      assertFalse(service().reservesName("Steve"));
      assertFalse(new FloodgateService(config("", false, Map.of())).reservesName("Steve"));
      assertFalse(FloodgateService.DISABLED.reservesName(".Steve"));
    }
  }

  @Nested
  @DisplayName("forwarding")
  class Forwarding {

    @Test
    @DisplayName("should re-encrypt the same data for a backend, from-proxy set, under a new IV")
    void reEmits() throws Exception {
      FloodgateService service = service();
      FloodgateData data = FloodgateData.parse(FloodgateDataTest.GEYSER_PLAINTEXT);

      String blob = service.encodeForBackend(data);

      assertTrue(blob.startsWith(FloodgateCipher.HEADER), blob);
      FloodgateData read = FloodgateData.parse(new FloodgateCipher(KEY).decrypt(blob).plaintext());
      assertEquals(data.fromProxy(true), read);
      assertFalse(blob.equals(service.encodeForBackend(data)), "a fresh IV each time");
    }

    @Test
    @DisplayName("should forward to the servers the configuration names, or all when told to")
    void forwardsTo() {
      FloodgateService some = new FloodgateService(config(".", false, Map.of("survival", true)));
      FloodgateService all = new FloodgateService(config(".", true, Map.of("lobby", false)));

      assertTrue(some.forwardsTo("survival"));
      assertTrue(some.forwardsTo("Survival"));
      assertFalse(some.forwardsTo("lobby"));
      assertTrue(all.forwardsTo("survival"));
      assertFalse(all.forwardsTo("lobby"));
      assertFalse(FloodgateService.DISABLED.forwardsTo("survival"));
      assertThrows(
          IllegalStateException.class,
          () ->
              FloodgateService.DISABLED.encodeForBackend(
                  FloodgateData.parse(FloodgateDataTest.GEYSER_PLAINTEXT)));
    }
  }
}
