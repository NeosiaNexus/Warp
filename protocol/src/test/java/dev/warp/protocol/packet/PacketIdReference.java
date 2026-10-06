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

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.NavigableMap;
import java.util.OptionalInt;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * The reference packet ids of {@code packet-ids.txt}: every packet of every state, at each protocol
 * Warp registers, generated from minecraft-data by {@code e2e/tools/packet-ids.js}.
 *
 * @param checked the protocols the table has ids for
 * @param unchecked the protocols Warp registers that minecraft-data has no data for
 * @param changes per {@code "state direction name"}, the id the packet takes from each protocol on
 *     ({@link #ABSENT} when it stops existing)
 */
record PacketIdReference(
    Set<Integer> checked,
    Set<Integer> unchecked,
    Map<String, NavigableMap<Integer, Integer>> changes) {

  private static final String RESOURCE = "packet-ids.txt";
  private static final Pattern FIELDS = Pattern.compile(" ");
  private static final int ABSENT = -1;

  /** Loads the table from the test resources. */
  static PacketIdReference load() {
    InputStream in = PacketIdReference.class.getResourceAsStream(RESOURCE);
    if (in == null) {
      throw new IllegalStateException(RESOURCE + " not found: run npm run packet-ids in e2e/");
    }
    try (BufferedReader reader =
        new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
      Set<Integer> checked = Set.of();
      Set<Integer> unchecked = Set.of();
      Map<String, NavigableMap<Integer, Integer>> changes = new HashMap<>();
      for (String line = reader.readLine(); line != null; line = reader.readLine()) {
        if (line.isBlank() || line.startsWith("#")) {
          continue;
        }
        String[] fields = FIELDS.split(line);
        switch (fields[0]) {
          case "checked" -> checked = protocols(fields);
          case "unchecked" -> unchecked = protocols(fields);
          default -> changes.put(fields[0] + ' ' + fields[1] + ' ' + fields[2], ids(fields));
        }
      }
      return new PacketIdReference(checked, unchecked, Map.copyOf(changes));
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /**
   * Returns the id of a packet at a checked protocol, or nothing when the packet does not exist
   * there.
   *
   * @param state the protocol state
   * @param direction the packet direction
   * @param name the packet's minecraft-data name
   * @param protocol a protocol among {@link #checked()}
   * @return the packet id, if the packet exists at this protocol
   */
  OptionalInt id(ProtocolState state, PacketDirection direction, String name, int protocol) {
    if (!checked.contains(protocol)) {
      throw new IllegalArgumentException("no reference ids for protocol " + protocol);
    }
    String key = (state + " " + direction + " " + name).toLowerCase(Locale.ROOT);
    NavigableMap<Integer, Integer> packet = changes.get(key);
    Map.Entry<Integer, Integer> change = packet == null ? null : packet.floorEntry(protocol);
    return change == null || change.getValue() == ABSENT
        ? OptionalInt.empty()
        : OptionalInt.of(change.getValue());
  }

  private static Set<Integer> protocols(String[] fields) {
    return Arrays.stream(fields, 1, fields.length)
        .map(Integer::valueOf)
        .collect(Collectors.toUnmodifiableSet());
  }

  /** Parses {@code 0x04@759 -@770}: the id from each protocol on. */
  private static NavigableMap<Integer, Integer> ids(String[] fields) {
    NavigableMap<Integer, Integer> ids = new TreeMap<>();
    for (int i = 3; i < fields.length; i++) {
      String change = fields[i];
      int at = change.indexOf('@');
      String id = change.substring(0, at);
      int protocol = Integer.parseInt(change.substring(at + 1));
      ids.put(protocol, id.equals("-") ? ABSENT : Integer.decode(id));
    }
    return ids;
  }
}
