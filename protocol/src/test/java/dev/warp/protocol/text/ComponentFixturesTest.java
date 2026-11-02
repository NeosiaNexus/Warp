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
package dev.warp.protocol.text;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.warp.protocol.ProtocolState;
import dev.warp.protocol.ProtocolVersion;
import dev.warp.protocol.packet.Packet;
import dev.warp.protocol.packet.PacketCodec;
import dev.warp.protocol.packet.PacketDirection;
import dev.warp.protocol.packet.StateRegistry;
import dev.warp.protocol.packet.play.ClearTitles;
import dev.warp.protocol.packet.play.PlayDisconnect;
import dev.warp.protocol.packet.play.SetActionBarText;
import dev.warp.protocol.packet.play.SetSubtitleText;
import dev.warp.protocol.packet.play.SetTitleText;
import dev.warp.protocol.packet.play.SetTitleTimes;
import dev.warp.protocol.packet.play.SystemChatMessage;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.nbt.BinaryTag;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.ShadowColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Checks the encoder against what real servers send their players for the same components: {@code
 * vanilla-components.txt}, captured by {@code e2e/tools/component-fixtures.js} from the server of
 * each version on both sides of every change to components (Paper, or vanilla where Paper never
 * released, both writing components with the game's own serializer).
 *
 * <p>JSON must match byte for byte. NBT is compared tag by tag: the server writes compound entries
 * in the order of a hash map, which no other writer reproduces and the client ignores. Where a
 * version lacks a feature, the fixture holds what the server sends for Warp's substitute (the
 * nearest named colour, a keybind's translation), so the corpus below is the same for every
 * version.
 */
@DisplayName("ComponentCodec against real servers")
class ComponentFixturesTest {

  private static final String RESOURCE = "vanilla-components.txt";
  private static final Pattern FIELDS = Pattern.compile(" ");
  private static final UUID ENTITY = UUID.fromString("6a085b2c-19fb-4986-b453-231aa942bbec");

  /**
   * Fixtures Warp differs from on purpose: up to 1.9.4 the server takes a style holding only an
   * insertion for empty ({@code isEmpty} forgets the insertion) and leaves it out, which Warp keeps
   * and those clients read; Bukkit's {@code /kick} on 1.12.2 writes its reason word by word with
   * colour codes between them.
   */
  private static final Set<String> KNOWN_DIFFERENCES =
      Set.of("1.8.8 insertion", "1.9.4 insertion", "1.12.2 kick");

  /**
   * The chat type 1.19 gives {@code /tellraw}, {@code minecraft:tellraw_command}: an id of its chat
   * type registry, where Warp's messages take {@code minecraft:system}, 1.
   */
  private static final byte TELLRAW_CHAT_TYPE_1_19 = 7;

  /** The corpus, by name, as the tool's corpus names it. */
  static final Map<String, Component> CORPUS =
      Map.ofEntries(
          Map.entry("plain", Component.text("Hello, world")),
          Map.entry(
              "escapes",
              Component.text("Quote \" back \\ line\nnext\ttab \u0001 <>&=' \u00e9 \u20ac \u2028")),
          Map.entry(
              "styled",
              Component.text("Styled", NamedTextColor.RED)
                  .decoration(TextDecoration.BOLD, true)
                  .decoration(TextDecoration.ITALIC, false)
                  .decoration(TextDecoration.UNDERLINED, true)
                  .decoration(TextDecoration.STRIKETHROUGH, true)
                  .decoration(TextDecoration.OBFUSCATED, true)),
          Map.entry(
              "nested",
              Component.text("A", NamedTextColor.GOLD)
                  .append(Component.text("b").decoration(TextDecoration.BOLD, true))
                  .append(Component.text("c"))
                  .append(
                      Component.translatable(
                          "chat.type.text",
                          Component.text("x"),
                          Component.text("y", NamedTextColor.AQUA)))),
          Map.entry(
              "plain-children",
              Component.text("").append(Component.text("a"), Component.text("b"))),
          Map.entry("rgb", Component.text("rgb", TextColor.color(0xFF5556))),
          Map.entry("insertion", Component.text("ins").insertion("/server lobby")),
          Map.entry(
              "click-run",
              Component.text("run").clickEvent(ClickEvent.runCommand("/server lobby"))),
          Map.entry(
              "click-url",
              Component.text("url").clickEvent(ClickEvent.openUrl("https://example.com/a?b=c"))),
          Map.entry(
              "click-suggest",
              Component.text("suggest").clickEvent(ClickEvent.suggestCommand("/msg "))),
          Map.entry(
              "click-copy",
              Component.text("copy").clickEvent(ClickEvent.copyToClipboard("copied"))),
          Map.entry("click-page", Component.text("page").clickEvent(ClickEvent.changePage(2))),
          Map.entry(
              "hover-text",
              Component.text("hover")
                  .hoverEvent(HoverEvent.showText(Component.text("tip", NamedTextColor.GREEN)))),
          Map.entry(
              "hover-text-plain",
              Component.text("hover").hoverEvent(HoverEvent.showText(Component.text("tip")))),
          Map.entry(
              "hover-entity",
              Component.text("entity")
                  .hoverEvent(
                      HoverEvent.showEntity(Key.key("pig"), ENTITY, Component.text("Bob")))),
          Map.entry(
              "hover-item",
              Component.text("item").hoverEvent(HoverEvent.showItem(Key.key("diamond"), 2))),
          Map.entry("font", Component.text("font").font(Key.key("uniform"))),
          Map.entry(
              "shadow", Component.text("shadow").shadowColor(ShadowColor.shadowColor(0xFF000000))),
          Map.entry("keybind", Component.keybind("key.jump")),
          Map.entry("fallback", Component.translatable("warp.missing", "Fallback")));

  /** Corpus entries a version before the one given cannot hold whole, and gets a substitute for. */
  private static final Map<String, ProtocolVersion> SUBSTITUTED_BEFORE =
      Map.of(
          "rgb", ProtocolVersion.MINECRAFT_1_16,
          "keybind", ProtocolVersion.MINECRAFT_1_12,
          "fallback", ProtocolVersion.MINECRAFT_1_19_4,
          "hover-entity", ProtocolVersion.MINECRAFT_1_16,
          "hover-item", ProtocolVersion.MINECRAFT_1_16);

  /** One packet a server sent. */
  @SuppressWarnings("ArrayRecordComponent") // test data, never mutated
  record Fixture(
      String version, ProtocolVersion protocol, String name, String packet, byte[] body) {

    @Override
    public String toString() {
      return version + " " + name;
    }
  }

  static List<Fixture> fixtures() throws IOException {
    InputStream in = ComponentFixturesTest.class.getResourceAsStream(RESOURCE);
    assertNotNull(in, RESOURCE + " not found: run npm run component-fixtures in e2e/");
    List<Fixture> fixtures = new ArrayList<>();
    try (BufferedReader reader =
        new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
      for (String line = reader.readLine(); line != null; line = reader.readLine()) {
        if (line.isBlank() || line.startsWith("#")) {
          continue;
        }
        String[] fields = FIELDS.split(line);
        ProtocolVersion protocol = ProtocolVersion.byProtocolId(Integer.parseInt(fields[1]));
        assertNotNull(protocol, line);
        fixtures.add(
            new Fixture(
                fields[0], protocol, fields[2], fields[3], HexFormat.of().parseHex(fields[4])));
      }
    }
    return fixtures;
  }

  static Stream<Fixture> sameAsServers() throws IOException {
    return fixtures().stream().filter(f -> !KNOWN_DIFFERENCES.contains(f.toString()));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("sameAsServers")
  @DisplayName("should send what the server sends: the same JSON, or the same NBT tags")
  void sameAsServer(Fixture fixture) {
    byte[] warp = encode(fixture);
    byte[] server = fixture.body().clone();
    if (fixture.protocol().protocol() == ProtocolVersion.MINECRAFT_1_19.protocol()
        && fixture.packet().equals("system_chat")) {
      assertEquals(TELLRAW_CHAT_TYPE_1_19, server[server.length - 1]);
      server[server.length - 1] = 1; // minecraft:system
    }

    if (fixture.protocol().isOlderThan(ProtocolVersion.MINECRAFT_1_20_3)
        || !hasComponent(fixture)) {
      assertEquals(
          new String(server, StandardCharsets.ISO_8859_1),
          new String(warp, StandardCharsets.ISO_8859_1));
      assertArrayEquals(server, warp);
    } else {
      ByteBuf expected = Unpooled.wrappedBuffer(fixture.body());
      ByteBuf actual = Unpooled.wrappedBuffer(warp);
      BinaryTag expectedTag = NbtReader.readNameless(expected);
      BinaryTag actualTag = NbtReader.readNameless(actual);
      assertEquals(expectedTag, actualTag);
      assertEquals(
          ByteBufUtil.hexDump(expected), ByteBufUtil.hexDump(actual), "after the component");
    }
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("sameAsServers")
  @DisplayName("should read what the server sends as what Warp sends")
  void readsServers(Fixture fixture) {
    if (!hasComponent(fixture)) {
      return;
    }
    Component fromServer = decodeComponent(fixture, fixture.body());
    Component fromWarp = decodeComponent(fixture, encode(fixture));

    assertEquals(fromWarp, fromServer);
    ProtocolVersion whole = SUBSTITUTED_BEFORE.get(fixture.name());
    Component corpus = CORPUS.get(fixture.name());
    if (corpus != null && (whole == null || fixture.protocol().isAtLeast(whole))) {
      assertEquals(corpus, fromServer, "the server's component read back");
    }
  }

  @Test
  @DisplayName("should have fixtures of every corpus entry and of the version boundaries")
  void coverage() throws IOException {
    List<Fixture> fixtures = fixtures();
    for (String name : CORPUS.keySet()) {
      assertTrue(fixtures.stream().anyMatch(f -> f.name().equals(name)), name + " has no fixture");
    }
    for (int protocol :
        List.of(5, 47, 110, 340, 404, 498, 754, 759, 761, 762, 764, 765, 769, 770, 775)) {
      assertTrue(
          fixtures.stream().anyMatch(f -> f.protocol().protocol() == protocol),
          "no fixture of protocol " + protocol);
    }
  }

  // ---------------------------------------------------------------------------
  // Helpers
  // ---------------------------------------------------------------------------

  private static boolean hasComponent(Fixture fixture) {
    return !fixture.name().equals("title-times")
        && !fixture.name().equals("title-clear")
        && !fixture.name().equals("title-reset");
  }

  /** Reads the component a packet body starts with: after the action of a legacy Title packet. */
  private static Component decodeComponent(Fixture fixture, byte[] body) {
    ByteBuf buf = Unpooled.wrappedBuffer(body);
    if (fixture.packet().equals("title")) {
      buf.skipBytes(1);
    }
    return ComponentCodec.decode(buf, fixture.protocol(), ProtocolState.PLAY);
  }

  /** The body of the packet Warp sends for a fixture. */
  private static byte[] encode(Fixture fixture) {
    ProtocolVersion version = fixture.protocol();
    Packet packet =
        switch (fixture.name()) {
          case "title-times" -> new SetTitleTimes(10, 70, 20);
          case "title-subtitle" ->
              SetSubtitleText.of(Component.text("Sub", NamedTextColor.GRAY), version);
          case "title-title" ->
              SetTitleText.of(
                  Component.text("Title").decoration(TextDecoration.BOLD, true), version);
          case "title-actionbar" ->
              SetActionBarText.of(Component.text("Bar", NamedTextColor.YELLOW), version);
          case "title-clear" -> new ClearTitles(false);
          case "title-reset" -> new ClearTitles(true);
          case "kick" -> PlayDisconnect.of(Component.text("Kicked by fixtures"), version);
          default -> {
            Component component = CORPUS.get(fixture.name());
            assertNotNull(component, "no corpus entry " + fixture.name());
            yield SystemChatMessage.of(component, version);
          }
        };
    @SuppressWarnings("unchecked")
    PacketCodec<Packet> codec =
        (PacketCodec<Packet>)
            StateRegistry.get(ProtocolState.PLAY, PacketDirection.CLIENTBOUND)
                .encoding(version, packet.getClass())
                .codec();
    ByteBuf buf = Unpooled.buffer();
    try {
      codec.encode(packet, buf, version);
      return ByteBufUtil.getBytes(buf);
    } finally {
      buf.release();
    }
  }
}
