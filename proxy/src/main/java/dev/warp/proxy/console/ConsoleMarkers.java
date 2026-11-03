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
package dev.warp.proxy.console;

import org.slf4j.Marker;
import org.slf4j.MarkerFactory;

/** Markers that change where a log record shows. */
public final class ConsoleMarkers {

  /** The name of {@link #FILE_ONLY}, as Log4j sees it. */
  static final String FILE_ONLY_NAME = "WARP_FILE_ONLY";

  /**
   * Records for the log file (and the JSON format) only, which the text console leaves out: the
   * configuration dump, which the startup summary sums up.
   */
  public static final Marker FILE_ONLY = MarkerFactory.getMarker(FILE_ONLY_NAME);

  private ConsoleMarkers() {}
}
