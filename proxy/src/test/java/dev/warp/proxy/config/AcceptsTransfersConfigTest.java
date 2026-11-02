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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The {@code accepts-transfers} key of {@code warp.conf}. */
@DisplayName("WarpConfig accepts-transfers")
class AcceptsTransfersConfigTest {

  @Test
  @DisplayName("should refuse transfers by default, as vanilla and Velocity do")
  void offByDefault(@TempDir Path directory) throws ConfigurationException {
    WarpConfig config = WarpConfig.load(directory);

    assertFalse(config.acceptsTransfers());
  }

  @Test
  @DisplayName("should write the key, off and explained, into the default configuration")
  void documentedInDefaultFile(@TempDir Path directory) throws ConfigurationException, IOException {
    var _ = WarpConfig.load(directory);

    String written = Files.readString(directory.resolve("warp.conf"), StandardCharsets.UTF_8);
    assertTrue(written.contains("\naccepts-transfers = false\n"), written);
  }

  @Test
  @DisplayName("should accept transfers when the key is on")
  void on(@TempDir Path directory) throws ConfigurationException, IOException {
    write(directory, "accepts-transfers = true\n");

    assertTrue(WarpConfig.load(directory).acceptsTransfers());
  }

  @Test
  @DisplayName("should refuse transfers in a configuration written before the key existed")
  void absentFromOlderFile(@TempDir Path directory) throws ConfigurationException, IOException {
    write(directory, "online-mode = false\n");

    assertFalse(WarpConfig.load(directory).acceptsTransfers());
  }

  private static void write(Path directory, String content) throws IOException {
    Files.writeString(directory.resolve("warp.conf"), content, StandardCharsets.UTF_8);
  }
}
