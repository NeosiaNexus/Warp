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
package dev.warp.proxy.antibot;

import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.warp.proxy.config.AntiBotConfig;
import dev.warp.proxy.config.LoggingConfig;
import dev.warp.proxy.event.WarpEventBus;
import dev.warp.proxy.limit.ClientLimits;
import dev.warp.proxy.logging.LogCapture;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@DisplayName("AntiBotService")
class AntiBotServiceTest {

  @TempDir private Path directory;

  @Test
  @DisplayName("should say which secret it generated, and in which file")
  void logsGeneratedSecrets() throws IOException, InterruptedException {
    try (LogCapture logs = new LogCapture()) {
      AntiBotService service =
          new AntiBotService(
              AntiBotConfig.DEFAULTS,
              true,
              ClientLimits.unlimited(),
              new WarpEventBus(Runnable::run),
              LoggingConfig.DEFAULTS,
              directory,
              _ -> null,
              System::currentTimeMillis);
      try {
        Path secret = directory.resolve(AntiBotConfig.Verified.DEFAULTS.secretFile());
        Path tokenKey = directory.resolve(AntiBotConfig.Verification.DEFAULTS.tokenSecretFile());
        assertTrue(Files.exists(secret));
        assertTrue(Files.exists(tokenKey));

        var _ =
            logs.await(
                r ->
                    r.message()
                        .equals(
                            "Anti-bot: generated the secret that hashes identities, in " + secret));
        var _ =
            logs.await(
                r ->
                    r.message()
                        .equals(
                            "Anti-bot: generated the key that signs verification tokens, in "
                                + tokenKey));
      } finally {
        service.close();
      }
    }
  }
}
