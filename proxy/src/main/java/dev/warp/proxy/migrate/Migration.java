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

import java.nio.file.Path;

import org.jspecify.annotations.Nullable;

/** A proxy Warp can replace: reads its installation, and produces the Warp configuration. */
sealed interface Migration permits VelocityMigration, BungeeCordMigration {

  /**
   * What a migration produced.
   *
   * @param warpConf the {@code warp.conf} to write
   * @param forwardingSecret the forwarding secret to write to {@code forwarding.secret}, or null
   *     when the source had none (Warp generates one on its first start)
   * @param report what happened to each source setting
   */
  record Result(String warpConf, @Nullable String forwardingSecret, Report report) {}

  /**
   * Returns the proxy's name, as the report shows it.
   *
   * @return the name, such as {@code Velocity}
   */
  String name();

  /**
   * Returns the file whose presence identifies an installation of this proxy.
   *
   * @return the file name, relative to the installation
   */
  String marker();

  /**
   * Migrates an installation.
   *
   * @param directory the installation, holding {@link #marker()}
   * @return the Warp configuration and the report
   * @throws MigrationException if the installation cannot be read or parsed
   */
  Result migrate(Path directory) throws MigrationException;
}
