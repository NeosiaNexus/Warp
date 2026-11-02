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
package dev.warp.protocol.command;

import dev.warp.protocol.ProtocolVersion;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import java.util.zip.GZIPInputStream;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.jspecify.annotations.Nullable;

/**
 * The fixtures {@code e2e/tools/command-trees.js} writes from Mojang's server jars: the argument
 * types of each protocol, and for one release per protocol the commands packet a vanilla server
 * sent an operator, with the data generator's report of the same tree.
 */
final class CommandFixtures {

  private static final String TYPES = "argument-types.json";

  private CommandFixtures() {}

  /**
   * One protocol's argument types, as Mojang's data has them.
   *
   * @param version the release they come from
   * @param source {@code registries.json} from 1.19, {@code server code} before
   * @param types the identifiers: in registry id order from 1.19, in registration order before
   */
  record Types(String version, String source, List<String> types) {}

  /** Every protocol's argument types, by protocol number. */
  static Map<Integer, Types> argumentTypes() {
    JsonObject root = JsonParser.parseString(text(TYPES)).getAsJsonObject();
    Map<Integer, Types> types = new HashMap<>();
    for (Map.Entry<String, JsonElement> entry : root.getAsJsonObject("protocols").entrySet()) {
      JsonObject value = entry.getValue().getAsJsonObject();
      List<String> identifiers = new ArrayList<>();
      value.getAsJsonArray("types").forEach(type -> identifiers.add(type.getAsString()));
      types.put(
          Integer.parseInt(entry.getKey()),
          new Types(
              value.get("version").getAsString(),
              value.get("source").getAsString(),
              List.copyOf(identifiers)));
    }
    return Map.copyOf(types);
  }

  /** One version per protocol from 1.13: versions sharing a protocol share its argument types. */
  static Stream<ProtocolVersion> commandProtocols() {
    Map<Integer, ProtocolVersion> byProtocol = new HashMap<>();
    ProtocolVersion.values().stream()
        .filter(version -> version.isAtLeast(CommandTreeCodec.FIRST))
        .forEach(version -> byProtocol.putIfAbsent(version.protocol(), version));
    return byProtocol.values().stream().sorted();
  }

  /** Every release whose commands packet was captured, oldest first. */
  static List<ProtocolVersion> capturedVersions() {
    return ProtocolVersion.values().stream()
        .filter(version -> CommandFixtures.class.getResource(capturePath(version)) != null)
        .toList();
  }

  /** The payload of the commands packet a vanilla server of {@code version} sent an operator. */
  static byte[] capture(ProtocolVersion version) {
    try (InputStream in = new GZIPInputStream(open(capturePath(version)))) {
      return in.readAllBytes();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /** The data generator's {@code commands.json} report of {@code version}. */
  static JsonObject report(ProtocolVersion version) {
    try (Reader reader =
        new InputStreamReader(
            new GZIPInputStream(open("trees/" + version.name() + ".json.gz")),
            StandardCharsets.UTF_8)) {
      return JsonParser.parseReader(reader).getAsJsonObject();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /** The first captured release of a protocol, if any. */
  static @Nullable ProtocolVersion capturedOf(int protocol) {
    return capturedVersions().stream()
        .filter(version -> version.protocol() == protocol)
        .findFirst()
        .orElse(null);
  }

  private static String capturePath(ProtocolVersion version) {
    return "trees/" + version.name() + ".bin.gz";
  }

  private static String text(String resource) {
    try (InputStream in = open(resource)) {
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private static InputStream open(String resource) {
    InputStream in = CommandFixtures.class.getResourceAsStream(resource);
    if (in == null) {
      throw new IllegalStateException(resource + " not found: run npm run command-trees in e2e/");
    }
    return in;
  }
}
