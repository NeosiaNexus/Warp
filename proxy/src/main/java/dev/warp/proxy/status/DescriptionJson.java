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
package dev.warp.proxy.status;

import dev.warp.protocol.ProtocolVersion;

import java.util.List;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.gson.GsonComponentSerializer;
import net.kyori.adventure.text.serializer.json.JSONOptions;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Writes a server list description as the JSON text each client version reads, with Adventure's
 * options for the Minecraft data version of each change to the text format: colors downsampled to
 * the 16 named ones and hover events in their legacy form before 1.16, compact text from 1.20.3,
 * shadow colors from 1.21.4, snake case events from 1.21.5, and so on.
 *
 * <p>Versions are grouped into formats, one serializer each, so that a description is written once
 * per format, not once per protocol.
 *
 * <p>Interim: the protocol module's component codec ({@code ComponentCodec.json}) writes the text
 * of each version byte for byte as vanilla does, and downgrades what old clients cannot read; this
 * class switches to it once merged.
 */
final class DescriptionJson {

  private static final Logger logger = LoggerFactory.getLogger(DescriptionJson.class);

  /**
   * The first version of each format after the first one, and the data version of its release, at
   * which Adventure's options change. Format 0 is everything older than 1.16.
   */
  private static final List<Change> CHANGES =
      List.of(
          new Change(ProtocolVersion.MINECRAFT_1_16, 2566),
          new Change(ProtocolVersion.MINECRAFT_1_20_3, 3698),
          new Change(ProtocolVersion.MINECRAFT_1_20_5, 3837),
          new Change(ProtocolVersion.MINECRAFT_1_21_4, 4189),
          new Change(ProtocolVersion.MINECRAFT_1_21_5, 4325),
          new Change(ProtocolVersion.MINECRAFT_1_21_6, 4435));

  /** The data version Adventure's first options apply to: no RGB, legacy hover events. */
  private static final int INITIAL_DATA_VERSION = 0;

  private static final GsonComponentSerializer[] SERIALIZERS = buildSerializers();

  private DescriptionJson() {}

  /**
   * Returns the format a client reads.
   *
   * @param protocol the client's protocol number, supported or not: a number below 1.16's (legacy
   *     pings included) reads the oldest format, one above every known version the newest
   * @return the format, from 0 (before 1.16) to {@link #formats()} minus 1
   */
  static int format(int protocol) {
    int format = 0;
    for (Change change : CHANGES) {
      if (protocol >= change.since().protocol()) {
        format++;
      }
    }
    return format;
  }

  /**
   * Returns how many formats there are.
   *
   * @return the number of formats
   */
  static int formats() {
    return SERIALIZERS.length;
  }

  /**
   * Writes a description.
   *
   * @param description the description
   * @param format the format, from {@link #format(int)}
   * @return the JSON text; the description's plain text if Adventure cannot write it in that format
   *     (a legacy hover event it has no serializer for), so that a ping is always answered
   */
  static String write(Component description, int format) {
    try {
      return SERIALIZERS[format].serialize(description);
    } catch (RuntimeException e) {
      logger.debug("Cannot write a description in format {}, sending its plain text", format, e);
      return SERIALIZERS[format].serialize(
          Component.text(PlainTextComponentSerializer.plainText().serialize(description)));
    }
  }

  private static GsonComponentSerializer[] buildSerializers() {
    GsonComponentSerializer[] serializers = new GsonComponentSerializer[CHANGES.size() + 1];
    serializers[0] = serializer(INITIAL_DATA_VERSION);
    for (int i = 0; i < CHANGES.size(); i++) {
      serializers[i + 1] = serializer(CHANGES.get(i).dataVersion());
    }
    return serializers;
  }

  private static GsonComponentSerializer serializer(int dataVersion) {
    return GsonComponentSerializer.builder()
        .options(JSONOptions.byDataVersion().at(dataVersion))
        .build();
  }

  /**
   * A change of the text format.
   *
   * @param since the first version with it
   * @param dataVersion the data version of that version's release
   */
  private record Change(ProtocolVersion since, int dataVersion) {}
}
