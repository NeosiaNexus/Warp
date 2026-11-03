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
package dev.warp.protocol.packet.play;

import dev.warp.protocol.ProtocolVersion;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.jspecify.annotations.Nullable;

/**
 * The id each vanilla sound has in the sound event registry of the clients that name a sound
 * following an entity by its id: 1.14, which added the Entity Sound Effect packet, to 1.19.2 (from
 * 1.19.3 the server names the sound instead, see {@link EntitySoundEffect}).
 *
 * <p>Those clients build the registry themselves, the same for every server, so its ids are a
 * property of the protocol. They come from Mojang's own data generator, the {@code
 * minecraft:sound_event} registry of {@code reports/registries.json} of one release per protocol
 * ({@code sound-events.txt}, written by {@code e2e/tools/sound-events.js}). A sound outside a
 * client's registry, a resource pack's own or one a later version added, has no id there: no packet
 * can play it following an entity on that client.
 *
 * <p>The table is read on first use; each protocol's index is built the first time it is asked for.
 * Safe from any thread.
 */
public final class SoundEventIds {

  private static final Pattern HEADER =
      Pattern.compile("protocol (\\d+) \\(([^)]+)\\)(?: from (\\d+))?");

  private static final String NAMESPACE = "minecraft:";

  /** Each protocol's index, built from {@link Table#LISTS} on first use. */
  private static final Map<Integer, Map<String, Integer>> INDEXES = new ConcurrentHashMap<>();

  private SoundEventIds() {}

  /**
   * Returns the id of a sound in the registry of a client.
   *
   * @param sound the sound's key, {@code minecraft:} namespace or none
   * @param version the client's protocol version
   * @return the id, or {@code -1} if the client's registry has no such sound, or the client names
   *     sounds following an entity otherwise (before 1.14 and from 1.19.3)
   */
  public static int id(String sound, ProtocolVersion version) {
    // The table's names have no namespace: a sound of another namespace is in none.
    String name = sound.startsWith(NAMESPACE) ? sound.substring(NAMESPACE.length()) : sound;
    Integer id = INDEXES.computeIfAbsent(version.protocol(), SoundEventIds::index).get(name);
    return id == null ? -1 : id;
  }

  /**
   * Returns the names of a protocol's registry in id order, for tests.
   *
   * @param protocol the protocol number
   * @return the names without their namespace, or {@code null} if the table has no such protocol
   */
  static @Nullable List<String> names(int protocol) {
    return Table.LISTS.get(protocol);
  }

  private static Map<String, Integer> index(int protocol) {
    List<String> names = Table.LISTS.get(protocol);
    if (names == null) {
      return Map.of();
    }
    Map<String, Integer> index = HashMap.newHashMap(names.size());
    for (int id = 0; id < names.size(); id++) {
      index.put(names.get(id), id);
    }
    return Map.copyOf(index);
  }

  /**
   * Reads the table: {@code protocol P (release)} starts the list of protocol P, one name a line,
   * whose ids count from 0; {@code protocol P (release) from Q} starts from the list of Q, which
   * the next lines edit in order: {@code +I name} inserts {@code name} at id I, {@code -I} removes
   * the name at id I. Blank lines and lines starting with {@code #} are skipped.
   *
   * @param reader the table
   * @return each protocol's names in id order
   * @throws IOException if the table cannot be read
   * @throws IllegalStateException if a name comes before any protocol, or a list starts from a
   *     protocol not read yet
   */
  static Map<Integer, List<String>> parse(BufferedReader reader) throws IOException {
    Map<Integer, List<String>> lists = new HashMap<>();
    List<String> current = null;
    int protocol = -1;
    for (String line = reader.readLine(); line != null; line = reader.readLine()) {
      if (line.isEmpty() || line.charAt(0) == '#') {
        continue;
      }
      Matcher header = HEADER.matcher(line);
      if (header.matches()) {
        if (current != null) {
          lists.put(protocol, List.copyOf(current));
        }
        protocol = Integer.parseInt(header.group(1));
        String from = header.group(3);
        current = new ArrayList<>(from == null ? List.of() : base(lists, from, line));
      } else if (current == null) {
        throw new IllegalStateException("sound-events.txt: a name before any protocol: " + line);
      } else if (line.charAt(0) == '+') {
        int space = line.indexOf(' ');
        current.add(Integer.parseInt(line.substring(1, space)), line.substring(space + 1));
      } else if (line.charAt(0) == '-') {
        current.remove(Integer.parseInt(line.substring(1)));
      } else {
        current.add(line);
      }
    }
    if (current != null) {
      lists.put(protocol, List.copyOf(current));
    }
    return Map.copyOf(lists);
  }

  private static List<String> base(Map<Integer, List<String>> lists, String from, String line) {
    List<String> base = lists.get(Integer.parseInt(from));
    if (base == null) {
      throw new IllegalStateException("sound-events.txt: a list from an unknown protocol: " + line);
    }
    return base;
  }

  /** The table, read the first time a sound is looked up. */
  private static final class Table {

    private static final Map<Integer, List<String>> LISTS = load();

    private Table() {}

    private static Map<Integer, List<String>> load() {
      try (InputStream in =
              Objects.requireNonNull(
                  SoundEventIds.class.getResourceAsStream("sound-events.txt"),
                  "sound-events.txt is missing");
          BufferedReader reader =
              new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
        return parse(reader);
      } catch (IOException e) {
        throw new UncheckedIOException(e);
      }
    }
  }
}
