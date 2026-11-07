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
package dev.warp.proxy.management;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import io.netty.buffer.ByteBufAllocator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@DisplayName("TlsFiles")
class TlsFilesTest {

  @TempDir private Path directory;

  @Test
  @DisplayName("should refuse files that are not PEM, saying how to convert the key")
  void notPem() throws Exception {
    Path certificate = Files.writeString(directory.resolve("tls.crt"), "not a certificate");
    Path key =
        Files.writeString(
            directory.resolve("tls.key"),
            "-----BEGIN RSA PRIVATE KEY-----\nabc\n-----END RSA PRIVATE KEY-----\n",
            StandardCharsets.US_ASCII);
    TlsFiles tls = new TlsFiles(certificate, key);

    IllegalStateException error = assertThrows(IllegalStateException.class, tls::load);

    assertTrue(
        String.valueOf(error.getMessage()).contains("openssl pkcs8 -topk8"), error.getMessage());
  }

  @Test
  @DisplayName("should hand out no handler before the files were read")
  void notLoaded() {
    TlsFiles tls = new TlsFiles(directory.resolve("a"), directory.resolve("b"));

    assertThrows(IllegalStateException.class, () -> tls.newHandler(ByteBufAllocator.DEFAULT));
  }

  @Test
  @DisplayName("should keep running when a renewal cannot be read")
  void failedReload() {
    TlsFiles tls = new TlsFiles(directory.resolve("missing.crt"), directory.resolve("missing.key"));

    tls.reload();

    assertThrows(IllegalStateException.class, () -> tls.newHandler(ByteBufAllocator.DEFAULT));
  }
}
