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
package dev.warp.proxy.config;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.warp.proxy.config.ConfigurationException.Problem;

import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@DisplayName("bedrock configuration")
class BedrockConfigTest {

  private static final byte[] KEY = new byte[16];

  static {
    Arrays.fill(KEY, (byte) 0x42);
  }

  @TempDir private Path directory;

  private WarpConfig load(String content) throws Exception {
    Files.writeString(
        directory.resolve("warp.conf"),
        "forwarding { mode = \"none\" }\n" + content,
        StandardCharsets.UTF_8);
    return WarpConfig.load(directory);
  }

  private Problem only(String content) {
    ConfigurationException error = assertThrows(ConfigurationException.class, () -> load(content));
    assertEquals(1, error.problems().size(), error.problems().toString());
    return error.problems().getFirst();
  }

  private void writeKey(String name, byte[] key) throws Exception {
    Files.write(directory.resolve(name), key);
  }

  @Nested
  @DisplayName("defaults")
  class Defaults {

    @Test
    @DisplayName("should leave Floodgate off, with Floodgate's prefix and the loopback trusted")
    void defaults() throws Exception {
      BedrockConfig bedrock = load("").bedrock();

      assertFalse(bedrock.floodgate().enabled());
      assertEquals(".", bedrock.floodgate().usernamePrefix());
      assertFalse(bedrock.floodgate().forward());
      assertTrue(bedrock.trustedGeyser().contains(InetAddress.getLoopbackAddress()));
      assertTrue(bedrock.trustedGeyser().contains(InetAddress.ofLiteral("::1")));
      assertFalse(bedrock.trustedGeyser().contains(InetAddress.ofLiteral("10.0.0.1")));
      assertFalse(bedrock.forwardsTo("lobby"));
      assertEquals("floodgate disabled", bedrock.describe());
    }

    @Test
    @DisplayName("should read the generated file, whose bedrock block documents every key")
    void generated() throws Exception {
      WarpConfig fresh = WarpConfig.load(directory);
      String written = Files.readString(directory.resolve("warp.conf"));

      assertTrue(written.contains("bedrock {"), written);
      assertTrue(written.contains("key-file = \"key.pem\""), written);
      assertFalse(fresh.bedrock().floodgate().enabled());
    }
  }

  @Nested
  @DisplayName("floodgate")
  class Floodgate {

    @Test
    @DisplayName("should read the key file's raw bytes when enabled")
    void enabled() throws Exception {
      writeKey("key.pem", KEY);

      BedrockConfig bedrock =
          load("""
                  bedrock {
                    floodgate { enabled = true, username-prefix = "_", forward = true }
                    geyser { trusted = ["10.0.0.0/8"] }
                  }
                  """)
              .bedrock();

      assertTrue(bedrock.floodgate().enabled());
      assertArrayEquals(KEY, bedrock.floodgate().key());
      assertEquals("_", bedrock.floodgate().usernamePrefix());
      assertTrue(bedrock.forwardsTo("lobby"));
      assertTrue(bedrock.trustedGeyser().contains(InetAddress.ofLiteral("10.1.2.3")));
      assertFalse(bedrock.trustedGeyser().contains(InetAddress.getLoopbackAddress()));
      assertTrue(bedrock.describe().contains("key.pem"), bedrock.describe());
    }

    @Test
    @DisplayName("should read another key file, relative to the proxy directory")
    void otherFile() throws Exception {
      Files.createDirectories(directory.resolve("secrets"));
      writeKey("secrets/floodgate.pem", new byte[32]);

      BedrockConfig bedrock =
          load("bedrock.floodgate { enabled = true, key-file = \"secrets/floodgate.pem\" }")
              .bedrock();

      assertEquals(32, bedrock.floodgate().key().length);
    }

    @Test
    @DisplayName("should name the missing key file and say where to copy it from")
    void missingKey() {
      Problem problem = only("bedrock.floodgate.enabled = true");

      assertEquals("bedrock.floodgate.key-file", problem.key());
      assertTrue(problem.description().contains("key.pem"), problem.description());
    }

    @Test
    @DisplayName("should refuse a key of another length than 16, 24 or 32 bytes")
    void badKeyLength() throws Exception {
      writeKey("key.pem", "-----BEGIN KEY-----".getBytes(StandardCharsets.UTF_8));

      Problem problem = only("bedrock.floodgate.enabled = true");

      assertEquals("bedrock.floodgate.key-file", problem.key());
      assertTrue(problem.description().contains("19 bytes"), problem.description());
    }

    @Test
    @DisplayName("should refuse a prefix with a space or longer than 15 characters")
    void badPrefix() {
      assertEquals(
          "bedrock.floodgate.username-prefix",
          only("bedrock.floodgate.username-prefix = \"a b\"").key());
      assertEquals(
          "bedrock.floodgate.username-prefix",
          only("bedrock.floodgate.username-prefix = \"abcdefghijklmnop\"").key());
    }

    @Test
    @DisplayName("should refuse forwarding while Floodgate is off")
    void forwardWithoutFloodgate() {
      assertEquals("bedrock.floodgate.forward", only("bedrock.floodgate.forward = true").key());
    }
  }

  @Nested
  @DisplayName("servers.<name>.floodgate")
  class Servers {

    @Test
    @DisplayName("should override the global forward setting per server")
    void perServer() throws Exception {
      writeKey("key.pem", KEY);

      BedrockConfig bedrock =
          load("""
                  servers {
                    lobby { address = "localhost:25565", floodgate = false }
                    survival { address = "localhost:25566", floodgate = true }
                    creative { address = "localhost:25567" }
                  }
                  default-server = "lobby"
                  fallback-order = ["lobby"]
                  bedrock.floodgate.enabled = true
                  """)
              .bedrock();

      assertEquals(Map.of("lobby", false, "survival", true), bedrock.floodgateServers());
      assertFalse(bedrock.forwardsTo("lobby"));
      assertTrue(bedrock.forwardsTo("survival"));
      assertFalse(bedrock.forwardsTo("creative"));
    }

    @Test
    @DisplayName("should refuse a server asking for data while Floodgate is off")
    void withoutFloodgate() {
      Problem problem =
          only(
              """
              servers { lobby { address = "localhost:25565", floodgate = true } }
              default-server = "lobby"
              fallback-order = ["lobby"]
              """);

      assertEquals("servers.lobby.floodgate", problem.key());
    }

    @Test
    @DisplayName("should accept the flag as a known key of a server")
    void knownKey() throws Exception {
      writeKey("key.pem", KEY);

      BedrockConfig bedrock =
          load("""
                  servers { lobby { address = "localhost:25565", floodgate = false } }
                  default-server = "lobby"
                  fallback-order = ["lobby"]
                  bedrock.floodgate.enabled = true
                  """)
              .bedrock();

      assertEquals(Map.of("lobby", false), bedrock.floodgateServers());
    }
  }
}
