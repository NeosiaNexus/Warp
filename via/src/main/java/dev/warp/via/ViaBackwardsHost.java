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

import com.viaversion.viabackwards.api.ViaBackwardsPlatform;

/**
 * ViaBackwards' platform: players older than their server. It registers its protocols into
 * ViaVersion when ViaVersion enables, and keeps its settings in {@code viabackwards.yml} next to
 * ViaVersion's.
 */
final class ViaBackwardsHost implements ViaBackwardsPlatform {

  private final Logger logger;
  private final File dataFolder;

  /**
   * Creates the platform.
   *
   * @param logger the logger, shared with ViaVersion
   * @param dataFolder the plugin's data directory
   */
  ViaBackwardsHost(Logger logger, File dataFolder) {
    this.logger = logger;
    this.dataFolder = dataFolder;
  }

  /** Reads {@code viabackwards.yml} and registers ViaBackwards' protocols. */
  void initialize() {
    init(new File(dataFolder, "viabackwards.yml"));
  }

  @Override
  public void disable() {
    // ViaBackwards only calls this when ViaVersion is too old for it, which the build rules out.
    logger.severe("ViaBackwards disabled itself: older players cannot join newer servers");
  }

  @Override
  public File getDataFolder() {
    return dataFolder;
  }

  @Override
  public Logger getLogger() {
    return logger;
  }
}
