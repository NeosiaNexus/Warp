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
package dev.warp.protocol.bench;

import dev.warp.protocol.ProtocolVersion;

/** Settings shared by every benchmark so that their per-packet scores decompose one another. */
public final class BenchmarkConfig {

  /** Packets processed per benchmark invocation; scores are normalised per packet. */
  public static final int PACKETS = 256;

  /** Compression threshold, as shipped by vanilla, Paper and Minestom. */
  public static final int THRESHOLD = 256;

  /** Corpus seed; one fixed value keeps every run byte-identical. */
  public static final long SEED = 0x5741_5250L;

  /** Protocol version whose packet registry decides which ids are blind-forwarded. */
  public static final ProtocolVersion VERSION = ProtocolVersion.MINECRAFT_26_1;

  private BenchmarkConfig() {}
}
