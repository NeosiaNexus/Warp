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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.warp.protocol.ProtocolVersion;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

@DisplayName("SoundEventIds")
class SoundEventIdsTest {

  /** A line of sound-event-samples.txt, read straight from a release's registries.json. */
  private record Sample(int protocol, String kind, String value, String extra) {}

  private static List<Sample> samples() throws IOException {
    try (InputStream in =
        Objects.requireNonNull(
            SoundEventIdsTest.class.getResourceAsStream("sound-event-samples.txt"))) {
      return new String(in.readAllBytes(), StandardCharsets.UTF_8)
          .lines()
          .filter(line -> !line.startsWith("#"))
          .map(line -> line.split(" "))
          .map(f -> new Sample(Integer.parseInt(f[0]), f[1], f[2], f.length > 3 ? f[3] : ""))
          .toList();
    }
  }

  static Stream<Sample> counts() throws IOException {
    return samples().stream().filter(sample -> sample.kind().equals("count"));
  }

  static Stream<Sample> digests() throws IOException {
    return samples().stream().filter(sample -> sample.kind().equals("sha256"));
  }

  static Stream<Sample> ids() throws IOException {
    return samples().stream().filter(sample -> sample.kind().equals("id"));
  }

  static Stream<ProtocolVersion> byId() {
    return ProtocolVersion.values().stream()
        .filter(version -> version.isAtLeast(ProtocolVersion.MINECRAFT_1_14))
        .filter(version -> version.isOlderThan(ProtocolVersion.MINECRAFT_1_19_3));
  }

  private ProtocolVersion version(int protocol) {
    return Objects.requireNonNull(ProtocolVersion.byProtocolId(protocol));
  }

  private String sha256(List<String> names) throws NoSuchAlgorithmException {
    MessageDigest digest = MessageDigest.getInstance("SHA-256");
    for (String name : names) {
      digest.update((name + "\n").getBytes(StandardCharsets.UTF_8));
    }
    return HexFormat.of().formatHex(digest.digest());
  }

  private Map<Integer, List<String>> parse(String table) throws IOException {
    return SoundEventIds.parse(new BufferedReader(new StringReader(table)));
  }

  @Nested
  @DisplayName("against Mojang's reports")
  class Reports {

    @ParameterizedTest(name = "{0}")
    @MethodSource("dev.warp.protocol.packet.play.SoundEventIdsTest#byId")
    @DisplayName("should have the registry of every version naming a sound by its id")
    void everyVersion(ProtocolVersion version) {
      List<String> names = SoundEventIds.names(version.protocol());

      assertNotNull(names, version.toString());
      assertFalse(names.isEmpty());
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("dev.warp.protocol.packet.play.SoundEventIdsTest#counts")
    @DisplayName("should list as many sounds as the release's registry")
    void count(Sample sample) {
      List<String> names = SoundEventIds.names(sample.protocol());

      assertEquals(Integer.parseInt(sample.value()), Objects.requireNonNull(names).size());
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("dev.warp.protocol.packet.play.SoundEventIdsTest#digests")
    @DisplayName("should list every sound of the release's registry, in id order")
    void everySound(Sample sample) throws NoSuchAlgorithmException {
      List<String> names = SoundEventIds.names(sample.protocol());

      assertEquals(sample.value(), sha256(Objects.requireNonNull(names)));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("dev.warp.protocol.packet.play.SoundEventIdsTest#ids")
    @DisplayName("should give a sound the id of the release's registry, with or without namespace")
    void id(Sample sample) {
      ProtocolVersion version = version(sample.protocol());

      int namespaced = SoundEventIds.id("minecraft:" + sample.value(), version);
      int bare = SoundEventIds.id(sample.value(), version);

      assertEquals(Integer.parseInt(sample.extra()), namespaced);
      assertEquals(namespaced, bare);
    }
  }

  @Nested
  @DisplayName("lookups")
  class Lookups {

    @Test
    @DisplayName("should give no id to a sound outside the client's registry")
    void unknownSound() {
      // Pigstep is from 1.16, the warden from 1.19.
      assertEquals(
          -1, SoundEventIds.id("minecraft:music_disc.pigstep", ProtocolVersion.MINECRAFT_1_15_2));
      assertEquals(
          -1,
          SoundEventIds.id("minecraft:entity.warden.heartbeat", ProtocolVersion.MINECRAFT_1_18_2));
      assertEquals(-1, SoundEventIds.id("myserver:ambient.cave", ProtocolVersion.MINECRAFT_1_16_4));
    }

    @Test
    @DisplayName("should give no id to a client that does not name sounds by id")
    void otherVersions() {
      assertEquals(
          -1, SoundEventIds.id("minecraft:ambient.cave", ProtocolVersion.MINECRAFT_1_12_2));
      assertEquals(
          -1, SoundEventIds.id("minecraft:ambient.cave", ProtocolVersion.MINECRAFT_1_19_3));
      assertNull(SoundEventIds.names(ProtocolVersion.MINECRAFT_1_19_3.protocol()));
    }
  }

  @Nested
  @DisplayName("the table's format")
  class Format {

    @Test
    @DisplayName("should read a list, then lists made from the previous by inserting and removing")
    void edits() throws IOException {
      String table =
          """
          # a comment

          protocol 1 (a)
          x
          y
          z
          protocol 2 (b) from 1
          +1 w
          -3
          protocol 3 (c) from 2
          """;

      Map<Integer, List<String>> lists = parse(table);

      assertEquals(
          Map.of(1, List.of("x", "y", "z"), 2, List.of("x", "w", "y"), 3, List.of("x", "w", "y")),
          lists);
    }

    @Test
    @DisplayName("should read an empty table as no list")
    void empty() throws IOException {
      assertEquals(Map.of(), parse("# nothing\n"));
    }

    @Test
    @DisplayName("should refuse a name before any protocol, or a list from an unknown protocol")
    void malformed() {
      assertThrows(IllegalStateException.class, () -> parse("x\nprotocol 1 (a)\n"));
      assertThrows(IllegalStateException.class, () -> parse("protocol 2 (b) from 1\n"));
    }
  }
}
