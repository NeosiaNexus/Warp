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

import java.io.File;
import java.util.logging.Logger;

import com.viaversion.viaversion.configuration.AbstractViaConfig;

/**
 * ViaVersion's own settings, in {@code config.yml} in the plugin's data directory: ViaVersion's
 * file as every platform has it, without the options of Bukkit and Velocity that do not apply.
 * Backend versions are in {@code servers.yml} (see {@link ServerVersions}).
 */
final class WarpViaConfig extends AbstractViaConfig {

  /**
   * Creates the settings; {@link #reload()} reads them.
   *
   * @param dataFolder the plugin's data directory
   * @param logger where ViaVersion reports invalid values
   */
  WarpViaConfig(File dataFolder, Logger logger) {
    super(new File(dataFolder, "config.yml"), logger);
  }

  /**
   * {@inheritDoc}
   *
   * <p>Never: a new ViaVersion comes with a new release of this plugin, which ViaVersion's own
   * update check knows nothing about.
   */
  @Override
  public boolean isCheckForUpdates() {
    return false;
  }
}
