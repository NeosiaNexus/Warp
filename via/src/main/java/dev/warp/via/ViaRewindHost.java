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

import com.viaversion.viarewind.api.ViaRewindPlatform;

/**
 * ViaRewind's platform: players of 1.7 and 1.8 on servers of 1.9 and later. It registers its
 * protocols into ViaVersion when ViaVersion enables, after ViaBackwards (whose protocol classes
 * they build on), and keeps its settings in {@code viarewind.yml} next to ViaVersion's.
 *
 * <p>1.7 has no compression: ViaRewind drops the backend's Set Compression and puts compression
 * handlers of its own right below ViaVersion's on that connection, so that Warp's own layers stay
 * uncompressed there, as for any 1.7 connection. Their names carry the plugin's prefix, like its
 * other handlers: ViaRewind looks them up by name, and must never find the proxy's own.
 */
final class ViaRewindHost implements ViaRewindPlatform {

  /** The name of the compressor ViaRewind adds for a 1.7 player, next to ViaVersion's encoder. */
  static final String COMPRESSOR = WarpVia.ID + ":rewind-compressor";

  /** The name of its decompressor, next to ViaVersion's decoder. */
  static final String DECOMPRESSOR = WarpVia.ID + ":rewind-decompressor";

  private final Logger logger;
  private final File dataFolder;

  /**
   * Creates the platform.
   *
   * @param logger the logger, shared with ViaVersion
   * @param dataFolder the plugin's data directory
   */
  ViaRewindHost(Logger logger, File dataFolder) {
    this.logger = logger;
    this.dataFolder = dataFolder;
  }

  /** Reads {@code viarewind.yml} and registers ViaRewind's protocols. */
  void initialize() {
    init(new File(dataFolder, "viarewind.yml"));
  }

  @Override
  public File getDataFolder() {
    return dataFolder;
  }

  @Override
  public Logger getLogger() {
    return logger;
  }

  @Override
  public String compressHandlerName() {
    return COMPRESSOR;
  }

  @Override
  public String decompressHandlerName() {
    return DECOMPRESSOR;
  }
}
