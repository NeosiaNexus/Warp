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
package dev.warp.via;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.warp.via.ServerVersions.Auto;
import dev.warp.via.ServerVersions.Off;
import dev.warp.via.ServerVersions.Pinned;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import com.viaversion.viaversion.api.protocol.version.ProtocolVersion;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@DisplayName("ServerVersions (servers.yml)")
class ServerVersionsTest {

  @TempDir private Path directory;

  @Test
  @DisplayName("should write the default file, comments included, and detect every server")
  void writesDefaults() throws IOException {
    ServerVersions settings = load(null);

    String written = Files.readString(directory.resolve("servers.yml"));
    assertTrue(written.contains("# Which Minecraft version each backend server runs."), written);
    assertInstanceOf(Auto.class, settings.mode("lobby"));
    assertEquals(60, settings.detectionIntervalSeconds());
    assertEquals(3000, settings.detectionTimeoutMillis());
  }

  @Test
  @DisplayName("should read versions by name or protocol number, auto and off, ignoring case")
  void readsEntries() throws IOException {
    ServerVersions settings =
        load(
            """
            servers:
              Lobby: "1.21.4"
              survival: 772
              events: auto
              modded: off
            default: auto
            """);

    assertEquals(new Pinned(ProtocolVersion.v1_21_4), settings.mode("lobby"));
    assertEquals(new Pinned(ProtocolVersion.v1_21_7), settings.mode("SURVIVAL"));
    assertInstanceOf(Auto.class, settings.mode("events"));
    assertInstanceOf(Off.class, settings.mode("modded"));
  }

  @Test
  @DisplayName("should apply the default to servers it does not list")
  void appliesDefault() throws IOException {
    ServerVersions settings = load("servers: {}\ndefault: \"1.21.8\"\n");

    assertEquals(new Pinned(ProtocolVersion.v1_21_7), settings.mode("anything"));
  }

  @Test
  @DisplayName("should detect, with a warning, a server set to something that is not a version")
  void warnsOnNonsense() throws IOException {
    List<String> warnings = new ArrayList<>();
    ServerVersions settings = load("servers:\n  lobby: banana\n", warnings);

    assertInstanceOf(Auto.class, settings.mode("lobby"));
    assertEquals(1, warnings.size());
    assertTrue(warnings.getFirst().contains("'lobby' is set to 'banana'"), warnings.getFirst());
  }

  @Test
  @DisplayName("should keep a sane time-out and interval whatever is written")
  void boundsTimings() throws IOException {
    ServerVersions settings = load("detection-interval: -5\ndetection-timeout: 1\n");

    assertEquals(0, settings.detectionIntervalSeconds());
    assertEquals(100, settings.detectionTimeoutMillis());
  }

  private ServerVersions load(@Nullable String content) throws IOException {
    return load(content, new ArrayList<>());
  }

  private ServerVersions load(@Nullable String content, List<String> warnings) throws IOException {
    Path file = directory.resolve("servers.yml");
    if (content != null) {
      Files.writeString(file, content, StandardCharsets.UTF_8);
    }
    Logger logger = Logger.getAnonymousLogger();
    logger.setUseParentHandlers(false);
    logger.addHandler(
        new Handler() {
          @Override
          public void publish(LogRecord record) {
            warnings.add(record.getMessage());
          }

          @Override
          public void flush() {}

          @Override
          public void close() {}
        });
    ServerVersions settings = new ServerVersions(file.toFile(), logger);
    settings.reload();
    return settings;
  }
}
