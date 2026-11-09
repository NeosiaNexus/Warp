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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.warp.api.player.ClientPlatform;
import dev.warp.api.player.ClientPlatform.Forge;
import dev.warp.api.player.ClientPlatform.ForgeEra;
import dev.warp.protocol.ProtocolVersion;
import dev.warp.proxy.connection.HandshakeMarkers.Detected;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("HandshakeMarkers")
class HandshakeMarkersTest {

  private static final String HOST = "play.example.com";

  @Nested
  @DisplayName("legacy FML, 1.7 to 1.12")
  class Legacy {

    @Test
    @DisplayName("should detect \\0FML\\0 on 1.12.2 as the legacy era and forward it as is")
    void detectsLegacy() {
      Detected detected =
          HandshakeMarkers.detect(HOST + "\0FML\0", ProtocolVersion.MINECRAFT_1_12_2);

      assertEquals(new Forge(ForgeEra.LEGACY, 0), detected.platform());
      assertEquals("\0FML\0", detected.marker());
    }

    @Test
    @DisplayName("should ignore \\0FML\\0 on a protocol that never used it")
    void ignoresLegacyOnModern() {
      Detected detected = HandshakeMarkers.detect(HOST + "\0FML\0", ProtocolVersion.MINECRAFT_1_21);

      assertEquals(HandshakeMarkers.NONE, detected);
    }
  }

  @Nested
  @DisplayName("FML2, 1.13 to 1.20.1")
  class Fml2 {

    @Test
    @DisplayName("should detect \\0FML2\\0 on 1.16 as the FML2 era, network version 2")
    void detectsFml2() {
      Detected detected =
          HandshakeMarkers.detect(HOST + "\0FML2\0", ProtocolVersion.MINECRAFT_1_16_4);

      assertEquals(new Forge(ForgeEra.FML2, 2), detected.platform());
      assertEquals("\0FML2\0", detected.marker());
    }

    @Test
    @DisplayName(
        "should detect \\0FML3\\0 on 1.20.1 as the FML2 era and keep the 3 for the backend")
    void detectsFml3() {
      Detected detected =
          HandshakeMarkers.detect(HOST + "\0FML3\0", ProtocolVersion.MINECRAFT_1_20_1);

      assertEquals(new Forge(ForgeEra.FML2, 3), detected.platform());
      assertEquals("\0FML3\0", detected.marker());
    }

    @Test
    @DisplayName("should ignore \\0FML2\\0 on 1.20.2, where Forge sends \\0FORGE")
    void ignoresFml2OnModern() {
      Detected detected =
          HandshakeMarkers.detect(HOST + "\0FML2\0", ProtocolVersion.MINECRAFT_1_20_2);

      assertEquals(HandshakeMarkers.NONE, detected);
    }

    @Test
    @DisplayName("should say the FML2 era alone has login queries to relay")
    void loginQueries() {
      assertTrue(
          HandshakeMarkers.hasLoginQueries(
              new Forge(ForgeEra.FML2, 2), ProtocolVersion.MINECRAFT_1_20_1));
      assertFalse(
          HandshakeMarkers.hasLoginQueries(
              new Forge(ForgeEra.LEGACY, 0), ProtocolVersion.MINECRAFT_1_12_2));
      assertFalse(
          HandshakeMarkers.hasLoginQueries(
              new Forge(ForgeEra.MODERN, 4), ProtocolVersion.MINECRAFT_1_21));
      assertFalse(
          HandshakeMarkers.hasLoginQueries(ClientPlatform.JAVA, ProtocolVersion.MINECRAFT_1_20_1));
    }
  }

  @Nested
  @DisplayName("modern Forge, 1.20.2 and newer")
  class Modern {

    @Test
    @DisplayName("should detect \\0FORGE on 1.20.2 with no network version")
    void detectsBareForge() {
      Detected detected =
          HandshakeMarkers.detect(HOST + "\0FORGE", ProtocolVersion.MINECRAFT_1_20_2);

      assertEquals(new Forge(ForgeEra.MODERN, 0), detected.platform());
      assertEquals("\0FORGE", detected.marker());
    }

    @Test
    @DisplayName(
        "should detect \\0FORGE4 on 1.21 with network version 4, written without a trailing separator")
    void detectsForgeWithVersion() {
      Detected detected =
          HandshakeMarkers.detect(HOST + "\0FORGE4", ProtocolVersion.MINECRAFT_1_21);

      assertEquals(new Forge(ForgeEra.MODERN, 4), detected.platform());
      assertEquals("\0FORGE4", detected.marker());
    }

    @Test
    @DisplayName("should ignore \\0FORGE on a protocol before 1.20.2")
    void ignoresForgeOnOld() {
      Detected detected =
          HandshakeMarkers.detect(HOST + "\0FORGE", ProtocolVersion.MINECRAFT_1_20_1);

      assertEquals(HandshakeMarkers.NONE, detected);
    }

    @Test
    @DisplayName("should not take a suffix that merely starts with FORGE for a marker")
    void ignoresLookalike() {
      Detected detected =
          HandshakeMarkers.detect(HOST + "\0FORGEXYZ", ProtocolVersion.MINECRAFT_1_21);

      assertEquals(HandshakeMarkers.NONE, detected);
    }
  }

  @Nested
  @DisplayName("other suffixes")
  class Others {

    @Test
    @DisplayName("should give a vanilla host the Java platform and nothing to forward")
    void vanilla() {
      assertEquals(HandshakeMarkers.NONE, HandshakeMarkers.detect(HOST, ProtocolVersion.latest()));
      assertEquals(ClientPlatform.JAVA, HandshakeMarkers.NONE.platform());
    }

    @Test
    @DisplayName("should never forward a BungeeCord forwarding payload as a marker")
    void bungeeCordPayload() {
      Detected detected =
          HandshakeMarkers.detect(
              HOST + "\0203.0.113.7\0" + "069a79f444e94726a5befca90e38aaf5" + "\0[]",
              ProtocolVersion.MINECRAFT_1_20_1);

      assertEquals(HandshakeMarkers.NONE, detected);
    }

    @Test
    @DisplayName("should find the marker among other suffixes")
    void markerAmongOthers() {
      Detected detected =
          HandshakeMarkers.detect(HOST + "\0something\0FML2\0", ProtocolVersion.MINECRAFT_1_20_1);

      assertEquals(new Forge(ForgeEra.FML2, 2), detected.platform());
      assertEquals("\0FML2\0", detected.marker());
    }

    @Test
    @DisplayName("should read the marker without an era check when the protocol is unknown")
    void unknownVersion() {
      assertEquals(
          new Forge(ForgeEra.FML2, 2), HandshakeMarkers.detect(HOST + "\0FML2\0", null).platform());
      assertEquals(
          new Forge(ForgeEra.MODERN, 5),
          HandshakeMarkers.detect(HOST + "\0FORGE5", null).platform());
    }
  }
}
