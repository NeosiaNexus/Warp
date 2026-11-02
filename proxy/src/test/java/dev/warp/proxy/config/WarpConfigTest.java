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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.warp.api.server.Favicon;
import dev.warp.proxy.status.StatusSettings;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.Random;
import java.util.zip.CRC32;

import javax.imageio.ImageIO;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@DisplayName("WarpConfig")
class WarpConfigTest {

  @TempDir private Path directory;

  @Nested
  @DisplayName("servers")
  class Servers {

    @Test
    @DisplayName("should generate a default configuration with one lobby server")
    void defaultFile() throws ConfigurationException {
      WarpConfig config = WarpConfig.load(directory);

      assertTrue(Files.exists(directory.resolve("warp.conf")));
      assertEquals(List.of("lobby"), List.copyOf(config.servers().keySet()));
      assertEquals("lobby", config.defaultServer());
      assertEquals(List.of("lobby"), config.fallbackOrder());
    }

    @Test
    @DisplayName("should accept an empty servers block, for servers registered at runtime")
    void emptyBlock() throws Exception {
      WarpConfig config = load("servers {}");

      assertTrue(config.servers().isEmpty());
      assertNull(config.defaultServer());
      assertTrue(config.fallbackOrder().isEmpty());
    }

    @Test
    @DisplayName("should refuse a default server when no server is configured")
    void defaultWithoutServers() {
      ConfigurationException error =
          assertThrows(
              ConfigurationException.class, () -> load("servers {}\ndefault-server = lobby"));

      assertTrue(String.valueOf(error.getMessage()).contains("no server is configured"));
    }

    @Test
    @DisplayName("should refuse a default server missing from the servers block")
    void unknownDefault() {
      assertThrows(
          ConfigurationException.class,
          () -> load("servers { lobby { address = \"localhost:25565\" } }\ndefault-server = hub"));
    }

    @Test
    @DisplayName("should refuse a servers value that is not a block")
    void notABlock() {
      assertThrows(ConfigurationException.class, () -> load("servers = [\"localhost:25565\"]"));
    }

    @Test
    @DisplayName("should keep the configured servers, lowercased")
    void configuredServers() throws Exception {
      WarpConfig config =
          load(
              """
              servers {
                Lobby { address = "localhost:25565" }
                survival { address = "localhost:25566" }
              }
              default-server = lobby
              fallback-order = [survival, lobby]
              """);

      assertEquals(new InetSocketAddress("localhost", 25566), config.servers().get("survival"));
      assertEquals("lobby", config.defaultServer());
      assertEquals(List.of("survival", "lobby"), config.fallbackOrder());
    }
  }

  @Nested
  @DisplayName("duplicate-login")
  class DuplicateLogin {

    @Test
    @DisplayName("should default to auto")
    void defaultsToAuto() throws Exception {
      assertEquals(DuplicateLoginPolicy.AUTO, load("").duplicateLoginPolicy());
    }

    @Test
    @DisplayName("should read kebab-case values")
    void readsValues() throws Exception {
      assertEquals(
          DuplicateLoginPolicy.KICK_EXISTING,
          load("duplicate-login = kick-existing").duplicateLoginPolicy());
      assertEquals(
          DuplicateLoginPolicy.REFUSE_NEW,
          load("duplicate-login = refuse-new").duplicateLoginPolicy());
    }

    @Test
    @DisplayName("should refuse an unknown value and name the accepted ones")
    void refusesUnknown() {
      ConfigurationException error =
          assertThrows(ConfigurationException.class, () -> load("duplicate-login = maybe"));

      assertTrue(String.valueOf(error.getMessage()).contains("kick-existing"));
    }

    @Test
    @DisplayName("auto should kick the existing session online and refuse the newcomer offline")
    void autoFollowsMode() {
      assertTrue(DuplicateLoginPolicy.AUTO.kicksExisting(true));
      assertEquals(false, DuplicateLoginPolicy.AUTO.kicksExisting(false));
      assertTrue(DuplicateLoginPolicy.KICK_EXISTING.kicksExisting(false));
      assertEquals(false, DuplicateLoginPolicy.REFUSE_NEW.kicksExisting(true));
    }
  }

  @Nested
  @DisplayName("status")
  class Status {

    @Test
    @DisplayName("should use the defaults without a status block")
    void defaults() throws Exception {
      StatusSettings status = load("").status();

      assertEquals(StatusSettings.defaults(), status);
      assertEquals(500, status.maxPlayers());
      assertEquals(12, status.sampleSize());
      assertTrue(status.showPlayerCount());
      assertNull(status.favicon());
    }

    @Test
    @DisplayName("should write a default file whose status block loads as the defaults")
    void defaultFile() throws ConfigurationException, IOException {
      WarpConfig config = WarpConfig.load(directory);

      String written = Files.readString(directory.resolve("warp.conf"));
      assertTrue(written.contains("status {"), written);
      assertTrue(written.contains(StatusSettings.defaultVersionName()), written);
      assertEquals(StatusSettings.defaults(), config.status());
    }

    @Test
    @DisplayName("should read the message of the day in MiniMessage")
    void motd() throws Exception {
      StatusSettings status = load("status { motd = \"<red>Hello <bold>world\" }").status();

      assertEquals("Hello world", plain(status.motd()));
      assertEquals(MiniMessage.miniMessage().deserialize("<red>Hello <bold>world"), status.motd());
    }

    @Test
    @DisplayName("should join a list of lines into a message of several lines")
    void motdLines() throws Exception {
      StatusSettings status =
          load("status { motd = [\"<aqua>First\", \"<gray>Second\"] }").status();

      assertEquals("First\nSecond", plain(status.motd()));
    }

    @Test
    @DisplayName("should refuse a message of the day that is not text")
    void motdNotText() {
      ConfigurationException error =
          assertThrows(ConfigurationException.class, () -> load("status { motd { a = 1 } }"));

      assertTrue(String.valueOf(error.getMessage()).contains("status.motd"), error.getMessage());
    }

    @Test
    @DisplayName("should read the limit, the counts, the sample size and the version name")
    void values() throws Exception {
      StatusSettings status =
          load("""
                  status {
                    max-players = 50
                    show-player-count = false
                    sample-size = 0
                    version-name = "Requires 1.20+"
                  }
                  """)
              .status();

      assertEquals(50, status.maxPlayers());
      assertFalse(status.showPlayerCount());
      assertEquals(0, status.sampleSize());
      assertEquals("Requires 1.20+", status.versionName());
    }

    @Test
    @DisplayName("should accept -1 as no limit")
    void noLimit() throws Exception {
      assertEquals(
          StatusSettings.NO_LIMIT, load("status { max-players = -1 }").status().maxPlayers());
    }

    @Test
    @DisplayName("should refuse a limit below -1")
    void limitTooLow() {
      ConfigurationException error =
          assertThrows(ConfigurationException.class, () -> load("status { max-players = -2 }"));

      assertTrue(
          String.valueOf(error.getMessage()).contains("status.max-players"), error.getMessage());
    }

    @Test
    @DisplayName("should refuse a limit that is not a number, naming the key")
    void limitNotNumber() {
      ConfigurationException error =
          assertThrows(ConfigurationException.class, () -> load("status { max-players = lots }"));

      assertTrue(
          String.valueOf(error.getMessage()).contains("status.max-players must be a whole number"),
          error.getMessage());
    }

    @Test
    @DisplayName("should refuse a negative sample size")
    void negativeSample() {
      assertThrows(ConfigurationException.class, () -> load("status { sample-size = -1 }"));
    }

    @Test
    @DisplayName("should refuse counts shown neither true nor false")
    void showCountNotBoolean() {
      assertThrows(
          ConfigurationException.class, () -> load("status { show-player-count = maybe }"));
    }

    @Test
    @DisplayName("should load a 64x64 PNG favicon as a data URI")
    void favicon() throws Exception {
      Files.write(directory.resolve("icon.png"), png(64, 64));

      StatusSettings status = load("status { favicon = \"icon.png\" }").status();

      Favicon icon = Objects.requireNonNull(status.favicon());
      assertTrue(icon.uri().startsWith(Favicon.URI_PREFIX));
    }

    @Test
    @DisplayName("should load server-icon.png by default when it exists")
    void defaultFavicon() throws Exception {
      Files.write(directory.resolve("server-icon.png"), png(64, 64));

      assertNotNull(load("").status().favicon());
    }

    @Test
    @DisplayName("should refuse a favicon of another size, saying which")
    void faviconWrongSize() throws IOException {
      Files.write(directory.resolve("icon.png"), png(128, 128));

      ConfigurationException error =
          assertThrows(
              ConfigurationException.class, () -> load("status { favicon = \"icon.png\" }"));

      assertTrue(String.valueOf(error.getMessage()).contains("128x128"), error.getMessage());
    }

    @Test
    @DisplayName("should refuse a favicon that is not a PNG image")
    void faviconNotPng() throws IOException {
      Files.writeString(directory.resolve("icon.png"), "not an image");

      ConfigurationException error =
          assertThrows(
              ConfigurationException.class, () -> load("status { favicon = \"icon.png\" }"));

      assertTrue(String.valueOf(error.getMessage()).contains("not a PNG"), error.getMessage());
    }

    @Test
    @DisplayName("should refuse a configured favicon that does not exist")
    void faviconMissing() {
      ConfigurationException error =
          assertThrows(
              ConfigurationException.class, () -> load("status { favicon = \"missing.png\" }"));

      assertTrue(String.valueOf(error.getMessage()).contains("missing.png"), error.getMessage());
    }

    @Test
    @DisplayName("should ignore the icon with an empty favicon")
    void noFavicon() throws Exception {
      Files.write(directory.resolve("server-icon.png"), png(64, 64));

      assertNull(load("status { favicon = \"\" }").status().favicon());
    }

    @Test
    @DisplayName("should refuse a favicon too big for the status answer, saying how big")
    void faviconTooBig() throws IOException {
      Files.write(directory.resolve("icon.png"), noisyPng());

      ConfigurationException error =
          assertThrows(
              ConfigurationException.class, () -> load("status { favicon = \"icon.png\" }"));

      assertTrue(
          String.valueOf(error.getMessage()).contains("32767 a client reads"), error.getMessage());
    }

    private static String plain(Component component) {
      return PlainTextComponentSerializer.plainText().serialize(component);
    }

    private static byte[] png(int width, int height) throws IOException {
      BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
      ByteArrayOutputStream out = new ByteArrayOutputStream();
      ImageIO.write(image, "png", out);
      return out.toByteArray();
    }

    /**
     * A valid 64x64 PNG made too big for a status answer by a text chunk of random data, as image
     * editors leave metadata in (SpigotMC/BungeeCord#828).
     */
    private static byte[] noisyPng() throws IOException {
      byte[] png = png(64, 64);
      byte[] padding = new byte[30_000];
      new Random(1).nextBytes(padding);
      ByteArrayOutputStream out = new ByteArrayOutputStream();
      out.write(png, 0, 33); // signature and header chunk
      byte[] type = "zzZz".getBytes(StandardCharsets.US_ASCII); // an ancillary private chunk
      out.write(ByteBuffer.allocate(4).putInt(padding.length).array());
      out.write(type);
      out.write(padding);
      CRC32 crc = new CRC32();
      crc.update(type);
      crc.update(padding);
      out.write(ByteBuffer.allocate(4).putInt((int) crc.getValue()).array());
      out.write(png, 33, png.length - 33);
      return out.toByteArray();
    }
  }

  private WarpConfig load(String content) throws IOException, ConfigurationException {
    Files.writeString(
        directory.resolve("warp.conf"),
        "forwarding { mode = none }\n" + content,
        StandardCharsets.UTF_8);
    return WarpConfig.load(directory);
  }
}
