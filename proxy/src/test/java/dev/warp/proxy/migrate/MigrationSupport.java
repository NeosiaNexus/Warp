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
package dev.warp.proxy.migrate;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import dev.warp.proxy.config.ConfigurationException;
import dev.warp.proxy.config.WarpConfig;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Assertions shared by the migration tests. */
final class MigrationSupport {

  private MigrationSupport() {}

  static Map<?, ?> table(Map<?, ?> parent, String key) {
    return (Map<?, ?>) Objects.requireNonNull(parent.get(key), key);
  }

  static String resource(String name) throws IOException {
    try (InputStream in =
        Objects.requireNonNull(
            MigrationSupport.class.getResourceAsStream("/migrate/" + name), name)) {
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    }
  }

  /**
   * Loads a generated {@code warp.conf} through Warp's own parser, and fails on any problem but the
   * {@code routes} block, which lands with the routing pull request (feat/routing-and-groups):
   * until then it is the one key Warp does not know yet.
   */
  static WarpConfig assertLoads(Path directory, String warpConf) throws IOException {
    Files.writeString(directory.resolve("warp.conf"), warpConf, StandardCharsets.UTF_8);
    try {
      return WarpConfig.load(directory);
    } catch (ConfigurationException e) {
      List<ConfigurationException.Problem> others =
          e.problems().stream()
              .filter(
                  p ->
                      p.key() == null
                          || !p.key().startsWith("routes") && !p.key().startsWith("groups"))
              .toList();
      if (!others.isEmpty()) {
        fail("warp.conf does not load:\n" + e.getMessage() + "\n---\n" + warpConf);
      }
      // Only the routes block is unknown: load the file without it to check everything else.
      String withoutRoutes = warpConf.replaceAll("(?s)\n(routes|groups) \\{.*?\n\\}\n", "\n");
      assertTrue(withoutRoutes.length() < warpConf.length(), "the routes block was expected");
      Files.writeString(directory.resolve("warp.conf"), withoutRoutes, StandardCharsets.UTF_8);
      try {
        return WarpConfig.load(directory);
      } catch (ConfigurationException again) {
        return fail("warp.conf does not load:\n" + again.getMessage() + "\n---\n" + warpConf);
      }
    }
  }

  /** Asserts that every source key of a report is migrated or dropped, with none missing. */
  static void assertAccountsFor(Report report, List<String> keys) {
    List<String> missing = keys.stream().filter(k -> !report.accountsFor(k)).toList();
    assertTrue(missing.isEmpty(), "keys neither migrated nor dropped: " + missing);
  }

  static Report.Migrated migrated(Report report, String source) {
    return report.migrated().stream()
        .filter(m -> m.source().equals(source))
        .findFirst()
        .orElseThrow(() -> new AssertionError(source + " was not migrated: " + report.dropped()));
  }

  static Report.Dropped dropped(Report report, String source) {
    return report.dropped().stream()
        .filter(d -> d.source().equals(source))
        .findFirst()
        .orElseThrow(() -> new AssertionError(source + " was not dropped: " + report.migrated()));
  }
}
