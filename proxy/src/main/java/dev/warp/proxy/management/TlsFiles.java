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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.Objects;

import io.netty.buffer.ByteBufAllocator;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.SslHandler;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The TLS of the management port, from a PEM certificate chain and a PKCS#8 key. The files are
 * checked every minute and read again when they change, as cert-manager renews a Kubernetes secret
 * in place: new connections get the new certificate, open ones keep theirs. A renewal that cannot
 * be read keeps the previous certificate, and says why.
 */
final class TlsFiles {

  private static final Logger logger = LoggerFactory.getLogger(TlsFiles.class);

  /** How often the files are checked for a renewal. */
  static final long RELOAD_SECONDS = 60;

  private static final String[] PROTOCOLS = {"TLSv1.3", "TLSv1.2"};

  private final Path certificate;
  private final Path key;
  private volatile @Nullable SslContext context;
  private volatile @Nullable Stamp loaded;

  /** When both files were last modified, to tell a renewal. */
  private record Stamp(FileTime certificate, FileTime key) {}

  /**
   * Creates the TLS of the given files, not read yet.
   *
   * @param certificate the PEM certificate chain
   * @param key the PEM private key, in PKCS#8
   */
  TlsFiles(Path certificate, Path key) {
    this.certificate = certificate;
    this.key = key;
  }

  /**
   * Reads the files.
   *
   * @throws IllegalStateException if they cannot be read, with what to do
   */
  void load() {
    try {
      Stamp stamp = stamp();
      context =
          SslContextBuilder.forServer(certificate.toFile(), key.toFile())
              .protocols(PROTOCOLS)
              .build();
      loaded = stamp;
    } catch (IOException | RuntimeException e) {
      throw new IllegalStateException(
          "Cannot read the management TLS certificate "
              + certificate
              + " and key "
              + key
              + " ("
              + e.getMessage()
              + "). They must be PEM files, the key in PKCS#8 (BEGIN PRIVATE KEY): convert"
              + " an RSA or EC key with `openssl pkcs8 -topk8 -nocrypt -in key.pem -out"
              + " tls.key`, or set privateKey.encoding: PKCS8 in cert-manager's Certificate",
          e);
    }
  }

  /** Reads the files again if they changed since they were last read. */
  void reload() {
    try {
      if (Objects.equals(stamp(), loaded)) {
        return;
      }
      load();
      logger.info("Management TLS: read the renewed certificate {}", certificate);
    } catch (IOException | IllegalStateException e) {
      logger.warn("Management TLS: keeping the previous certificate: {}", e.getMessage());
    }
  }

  /**
   * Returns the TLS handler of a new connection.
   *
   * @param allocator the connection's allocator
   * @return the handler
   * @throws IllegalStateException if the files were never read
   */
  SslHandler newHandler(ByteBufAllocator allocator) {
    SslContext current = context;
    if (current == null) {
      throw new IllegalStateException("The management TLS certificate is not loaded");
    }
    return current.newHandler(allocator);
  }

  private Stamp stamp() throws IOException {
    return new Stamp(Files.getLastModifiedTime(certificate), Files.getLastModifiedTime(key));
  }
}
