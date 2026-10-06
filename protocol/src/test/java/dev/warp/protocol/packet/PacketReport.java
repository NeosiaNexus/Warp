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
package dev.warp.protocol.packet;

import dev.warp.protocol.ProtocolState;
import dev.warp.protocol.ProtocolVersion;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Mojang's packet ids of one protocol: the packets report of the vanilla server's data generator
 * for one release, checked in as {@code reports/<version>.json} by {@code
 * e2e/tools/packet-reports.js}.
 *
 * @param version the release the report was generated from
 * @param protocol the protocol of that release, as its server jar declares it
 * @param packets per state and direction, the id of each packet under its Mojang name ({@code
 *     minecraft:keep_alive})
 */
record PacketReport(
    String version,
    int protocol,
    Map<ProtocolState, Map<PacketDirection, Map<String, Integer>>> packets) {

  /** The first release whose data generator reports packets. */
  static final ProtocolVersion FIRST_REPORTED = ProtocolVersion.MINECRAFT_1_21;

  /**
   * Returns Mojang's report of a version's protocol, if one is checked in: game versions sharing a
   * protocol share every packet id, so the report may come from any of them.
   *
   * @param version the version whose protocol to look up
   * @return the report of its protocol
   */
  static Optional<PacketReport> of(ProtocolVersion version) {
    return ProtocolVersion.values().stream()
        .filter(sibling -> sibling.protocol() == version.protocol())
        .map(sibling -> load(sibling.name()))
        .flatMap(Optional::stream)
        .findFirst();
  }

  /**
   * Returns the id of a packet, or nothing when the protocol has no packet of that name there.
   *
   * @param state the protocol state
   * @param direction the packet direction
   * @param name the packet's Mojang name
   * @return its packet id
   */
  OptionalInt id(ProtocolState state, PacketDirection direction, String name) {
    Integer id = packets.getOrDefault(state, Map.of()).getOrDefault(direction, Map.of()).get(name);
    return id == null ? OptionalInt.empty() : OptionalInt.of(id);
  }

  private static Optional<PacketReport> load(String version) {
    String resource = "reports/" + version + ".json";
    InputStream in = PacketReport.class.getResourceAsStream(resource);
    if (in == null) {
      return Optional.empty();
    }
    try (Reader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
      return Optional.of(parse(JsonParser.parseReader(reader).getAsJsonObject()));
    } catch (IOException e) {
      throw new UncheckedIOException(resource, e);
    } catch (RuntimeException e) {
      throw new IllegalStateException(resource + " is malformed: run npm run packet-reports", e);
    }
  }

  private static PacketReport parse(JsonObject report) {
    Map<ProtocolState, Map<PacketDirection, Map<String, Integer>>> packets =
        new EnumMap<>(ProtocolState.class);
    for (Map.Entry<String, JsonElement> state : report.getAsJsonObject("packets").entrySet()) {
      Map<PacketDirection, Map<String, Integer>> directions = new EnumMap<>(PacketDirection.class);
      for (Map.Entry<String, JsonElement> direction :
          state.getValue().getAsJsonObject().entrySet()) {
        Map<String, Integer> ids = new HashMap<>();
        for (Map.Entry<String, JsonElement> packet :
            direction.getValue().getAsJsonObject().entrySet()) {
          ids.put(packet.getKey(), packet.getValue().getAsInt());
        }
        directions.put(constant(PacketDirection.class, direction.getKey()), Map.copyOf(ids));
      }
      packets.put(constant(ProtocolState.class, state.getKey()), Map.copyOf(directions));
    }
    return new PacketReport(
        report.get("version").getAsString(),
        report.get("protocol").getAsInt(),
        Map.copyOf(packets));
  }

  /** Mojang's state or direction name ({@code configuration}) as Warp's constant. */
  private static <E extends Enum<E>> E constant(Class<E> type, String name) {
    return Enum.valueOf(type, name.toUpperCase(Locale.ROOT));
  }
}
