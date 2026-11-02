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

import static dev.warp.protocol.packet.play.AudiencePacketFixtures.bytes;
import static dev.warp.protocol.packet.play.SwitchPacketFixtures.encode;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.warp.protocol.ProtocolVersion;
import dev.warp.protocol.codec.McString;
import dev.warp.protocol.packet.Packet;
import dev.warp.protocol.packet.PacketCodec;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.UUID;
import java.util.stream.Stream;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.DecoderException;
import net.kyori.adventure.text.Component;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

@DisplayName("Audience packets against node-minecraft-protocol")
class AudiencePacketsTest {

  private static final UUID PACK = UUID.fromString("5c39a8cb-1a3a-4c86-9a47-0f0aaf0e3a01");
  private static final String URL = "https://warp.dev/pack.zip";
  private static final String HASH = "0123456789abcdef0123456789abcdef01234567";
  private static final String SOUND = "minecraft:entity.experience_orb.pickup";
  private static final int PLAYER_SOURCE = 7;

  /** Decodes {@code bytes}, checking that the codec reads every byte. */
  private static <T extends Packet> T decode(
      PacketCodec<T> codec, ProtocolVersion version, byte[] bytes) {
    ByteBuf buf = Unpooled.wrappedBuffer(bytes);
    try {
      T decoded = codec.decode(buf, version);
      assertFalse(buf.isReadable(), () -> buf.readableBytes() + " bytes left at " + version);
      return decoded;
    } finally {
      buf.release();
    }
  }

  @Nested
  @DisplayName("sounds")
  class Sounds {

    static Stream<ProtocolVersion> namedVersions() {
      return AudiencePacketFixtures.versionsWith("sound_named");
    }

    static Stream<ProtocolVersion> entityVersions() {
      return AudiencePacketFixtures.versionsWith("sound_entity");
    }

    static Stream<ProtocolVersion> entityIdVersions() {
      return AudiencePacketFixtures.versionsWith("sound_entity_id");
    }

    static Stream<ProtocolVersion> stopVersions() {
      return AudiencePacketFixtures.versionsWith("stop_sound_all");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("namedVersions")
    @DisplayName("should write a positioned sound by its key, as each version names it")
    void positioned(ProtocolVersion version) {
      SoundEffect sound = new SoundEffect(SOUND, PLAYER_SOURCE, 8, 520, -16, 0.5f, 1.5f, 42);

      byte[] written = encode(SoundEffect.CODEC, sound, version);

      assertArrayEquals(bytes(version, "sound_named"), written);
      SoundEffect read = decode(SoundEffect.CODEC, version, written);
      assertEquals(SOUND, read.sound());
      assertEquals(8, read.x());
      assertEquals(-16, read.z());
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("entityVersions")
    @DisplayName("should write a sound following an entity with its event inlined, from 1.19.3")
    void followingEntity(ProtocolVersion version) {
      EntitySoundEffect sound = EntitySoundEffect.named(SOUND, PLAYER_SOURCE, 42, 0.5f, 1.5f, 42);

      byte[] written = encode(EntitySoundEffect.CODEC, sound, version);

      assertArrayEquals(bytes(version, "sound_entity"), written);
      assertEquals(sound, decode(EntitySoundEffect.CODEC, version, written));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("entityIdVersions")
    @DisplayName("should write a sound following an entity by its registry id, 1.14 to 1.19.2")
    void followingEntityById(ProtocolVersion version) {
      long seed = version.isAtLeast(ProtocolVersion.MINECRAFT_1_19) ? 42 : 0;
      EntitySoundEffect sound =
          EntitySoundEffect.registered(300, PLAYER_SOURCE, 42, 0.5f, 1.5f, seed);

      byte[] written = encode(EntitySoundEffect.CODEC, sound, version);

      assertArrayEquals(bytes(version, "sound_entity_id"), written);
      assertEquals(sound, decode(EntitySoundEffect.CODEC, version, written));
    }

    @Test
    @DisplayName("should refuse a sound following an entity in the form the client lacks")
    void followingEntityInTheWrongForm() {
      EntitySoundEffect named = EntitySoundEffect.named(SOUND, PLAYER_SOURCE, 42, 1, 1, 0);
      EntitySoundEffect registered = EntitySoundEffect.registered(0, PLAYER_SOURCE, 42, 1, 1, 0);

      assertThrows(
          IllegalArgumentException.class,
          () -> encode(EntitySoundEffect.CODEC, named, ProtocolVersion.MINECRAFT_1_19_2));
      assertThrows(
          IllegalArgumentException.class,
          () -> encode(EntitySoundEffect.CODEC, registered, ProtocolVersion.MINECRAFT_1_19_3));
      // Id 0 is a sound: only a negative id is missing.
      assertArrayEquals(
          new byte[] {0, PLAYER_SOURCE, 42, 63, -128, 0, 0, 63, -128, 0, 0},
          encode(EntitySoundEffect.CODEC, registered, ProtocolVersion.MINECRAFT_1_14));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("stopVersions")
    @DisplayName("should stop every sound, or a key in a source, from 1.13")
    void stop(ProtocolVersion version) {
      StopSound all = new StopSound(-1, null);
      StopSound named = new StopSound(PLAYER_SOURCE, SOUND);

      assertArrayEquals(bytes(version, "stop_sound_all"), encode(StopSound.CODEC, all, version));
      assertArrayEquals(
          bytes(version, "stop_sound_named_on_source"), encode(StopSound.CODEC, named, version));
      assertEquals(
          named, decode(StopSound.CODEC, version, encode(StopSound.CODEC, named, version)));
    }

    @Test
    @DisplayName("should stop sounds with the MC|StopSound message before 1.13, empty for all")
    void legacyStop() {
      PlayPluginMessage named = new StopSound(PLAYER_SOURCE, SOUND).legacyPluginMessage();
      PlayPluginMessage all = new StopSound(-1, null).legacyPluginMessage();

      assertEquals("MC|StopSound", named.channel());
      assertArrayEquals(concat(string("player"), string(SOUND)), named.data());
      assertArrayEquals(new byte[] {0, 0}, all.data());
      // A source those clients lack is master, never an index out of their list.
      assertArrayEquals(
          concat(string("master"), string("")),
          new StopSound(10, null).legacyPluginMessage().data());
    }

    @Test
    @DisplayName("should give the ui source to clients from 1.21.6, master before")
    void uiSource() {
      assertEquals(10, SoundEffect.source(10, ProtocolVersion.MINECRAFT_1_21_6));
      assertEquals(0, SoundEffect.source(10, ProtocolVersion.MINECRAFT_1_21_5));
      assertEquals(9, SoundEffect.source(9, ProtocolVersion.MINECRAFT_1_9));
    }

    @Test
    @DisplayName("should place positions in eighths of a block, rounded and clamped")
    void fixedPoint() {
      assertEquals(12, SoundEffect.fixed(1.5));
      assertEquals(-4, SoundEffect.fixed(-0.5));
      assertEquals(Integer.MAX_VALUE, SoundEffect.fixed(1e12));
      assertEquals(Integer.MIN_VALUE, SoundEffect.fixed(-1e12));
    }

    @Test
    @DisplayName("should clamp the pitch byte before 1.10 and refuse a registry id when reading")
    void pitchByteAndRegistryIds() {
      byte[] loud =
          encode(
              SoundEffect.CODEC,
              new SoundEffect("a", 0, 0, 0, 0, 1, 10, 0),
              ProtocolVersion.MINECRAFT_1_8);
      assertEquals((byte) 255, loud[loud.length - 1]);
      byte[] registryId = {5};
      assertThrows(
          DecoderException.class,
          () -> decode(EntitySoundEffect.CODEC, ProtocolVersion.MINECRAFT_1_21_4, registryId));
    }

    @Test
    @DisplayName("should read past a fixed range, which a server may give an inlined sound")
    void fixedRange() {
      ProtocolVersion version = ProtocolVersion.MINECRAFT_1_21_4;
      byte[] written =
          encode(EntitySoundEffect.CODEC, EntitySoundEffect.named("a", 1, 2, 1, 1, 3), version);
      // The holder: 0, the name "a", then a present fixed range of 16 blocks in place of absent.
      byte[] ranged = new byte[written.length + Float.BYTES];
      System.arraycopy(written, 0, ranged, 0, 3);
      ranged[3] = 1;
      ByteBuf range = Unpooled.wrappedBuffer(ranged);
      range.setFloat(4, 16);
      System.arraycopy(written, 4, ranged, 4 + Float.BYTES, written.length - 4);

      assertEquals(
          EntitySoundEffect.named("a", 1, 2, 1, 1, 3),
          decode(EntitySoundEffect.CODEC, version, ranged));
    }

    private static byte[] string(String value) {
      ByteBuf buf = Unpooled.buffer();
      try {
        McString.write(buf, value);
        byte[] bytes = new byte[buf.readableBytes()];
        buf.readBytes(bytes);
        return bytes;
      } finally {
        buf.release();
      }
    }

    private static byte[] concat(byte[] a, byte[] b) {
      byte[] both = Arrays.copyOf(a, a.length + b.length);
      System.arraycopy(b, 0, both, a.length, b.length);
      return both;
    }
  }

  @Nested
  @DisplayName("boss bars")
  class BossBars {

    static Stream<ProtocolVersion> versions() {
      return AudiencePacketFixtures.versionsWith("boss_bar_add_styled");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("versions")
    @DisplayName("should write every action, with the name encoded for the version")
    void everyAction(ProtocolVersion version) {
      Component name = Component.text("Boss");

      assertArrayEquals(
          bytes(version, "boss_bar_add_styled"),
          encode(BossBar.CODEC, BossBar.add(PACK, name, 0.25f, 2, 3, 5, version), version));
      assertArrayEquals(
          bytes(version, "boss_bar_progress"),
          encode(BossBar.CODEC, new BossBar(PACK, new BossBar.UpdateProgress(0.75f)), version));
      assertArrayEquals(
          bytes(version, "boss_bar_name"),
          encode(BossBar.CODEC, BossBar.name(PACK, name, version), version));
      assertArrayEquals(
          bytes(version, "boss_bar_style"),
          encode(BossBar.CODEC, new BossBar(PACK, new BossBar.UpdateStyle(6, 4)), version));
      assertArrayEquals(
          bytes(version, "boss_bar_flags"),
          encode(BossBar.CODEC, new BossBar(PACK, new BossBar.UpdateFlags(2)), version));
      assertArrayEquals(
          bytes(version, "boss_bar_remove_any"),
          encode(BossBar.CODEC, BossBar.remove(PACK), version));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("versions")
    @DisplayName("should read back every action it writes")
    void readBack(ProtocolVersion version) {
      for (String fixture :
          new String[] {
            "boss_bar_add_styled",
            "boss_bar_progress",
            "boss_bar_name",
            "boss_bar_style",
            "boss_bar_flags",
            "boss_bar_remove_any"
          }) {
        byte[] wire = bytes(version, fixture);
        BossBar read = decode(BossBar.CODEC, version, wire);
        assertArrayEquals(wire, encode(BossBar.CODEC, read, version), fixture);
      }
    }

    @Test
    @DisplayName("should refuse to write a watched addition, which carries no fields")
    void refuseWatchedAddition() {
      assertThrows(
          IllegalArgumentException.class,
          () ->
              encode(
                  BossBar.CODEC,
                  new BossBar(PACK, new BossBar.Added()),
                  ProtocolVersion.MINECRAFT_1_12_2));
      byte[] unknownAction = new byte[17];
      unknownAction[16] = 9;
      assertThrows(
          DecoderException.class,
          () -> decode(BossBar.CODEC, ProtocolVersion.MINECRAFT_1_12_2, unknownAction));
    }
  }

  @Nested
  @DisplayName("resource packs")
  class ResourcePacks {

    static Stream<ProtocolVersion> pushVersions() {
      return AudiencePacketFixtures.versionsWith("resource_pack_push");
    }

    static Stream<ProtocolVersion> popVersions() {
      return AudiencePacketFixtures.versionsWith("resource_pack_pop");
    }

    static Stream<ProtocolVersion> configVersions() {
      return AudiencePacketFixtures.versionsWith("config_resource_pack_push");
    }

    static Stream<ProtocolVersion> configPopVersions() {
      return AudiencePacketFixtures.versionsWith("config_resource_pack_pop");
    }

    static Stream<ProtocolVersion> responseVersions() {
      return AudiencePacketFixtures.versionsWith("resource_pack_response");
    }

    private static ResourcePackPush pack(ProtocolVersion version) {
      return ResourcePackPush.of(PACK, URL, HASH, true, Component.text("Please"), version);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("pushVersions")
    @DisplayName(
        "should write a pack as each version carries it: UUID, required and prompt when it has them")
    void push(ProtocolVersion version) {
      assertArrayEquals(
          bytes(version, "resource_pack_push"),
          encode(ResourcePackPush.CODEC, pack(version), version));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("pushVersions")
    @DisplayName(
        "should read a server's pack back as it was, with a UUID derived from its URL before 1.20.3")
    void readPush(ProtocolVersion version) {
      byte[] wire = bytes(version, "resource_pack_push");

      ResourcePackPush read = decode(ResourcePackPush.CODEC, version, wire);

      assertArrayEquals(wire, encode(ResourcePackPush.CODEC, read, version));
      assertEquals(URL, read.url());
      assertEquals(HASH, read.hash());
      assertEquals(
          version.isAtLeast(ProtocolVersion.MINECRAFT_1_20_3)
              ? PACK
              : ResourcePackPush.legacyUuid(URL),
          read.uuid());
      assertEquals(version.isAtLeast(ProtocolVersion.MINECRAFT_1_17), read.required());
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("configVersions")
    @DisplayName("should write a pack in the configuration state with the same layout")
    void configPush(ProtocolVersion version) {
      assertArrayEquals(
          bytes(version, "config_resource_pack_push"),
          encode(ResourcePackPush.CODEC, pack(version), version));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("popVersions")
    @DisplayName("should remove one pack or all of them, from 1.20.3")
    void pop(ProtocolVersion version) {
      assertArrayEquals(
          bytes(version, "resource_pack_pop"),
          encode(ResourcePackPop.CODEC, new ResourcePackPop(PACK), version));
      assertArrayEquals(
          bytes(version, "resource_pack_pop_all"),
          encode(ResourcePackPop.CODEC, new ResourcePackPop(null), version));
      assertNull(
          decode(ResourcePackPop.CODEC, version, bytes(version, "resource_pack_pop_all")).uuid());
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("configPopVersions")
    @DisplayName("should remove a pack in the configuration state with the same layout")
    void configPop(ProtocolVersion version) {
      assertArrayEquals(
          bytes(version, "config_resource_pack_pop"),
          encode(ResourcePackPop.CODEC, new ResourcePackPop(PACK), version));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("responseVersions")
    @DisplayName("should read the client's answer: hash until 1.9.4, UUID from 1.20.3")
    void response(ProtocolVersion version) {
      byte[] wire = bytes(version, "resource_pack_response");

      ResourcePackResponse read = decode(ResourcePackResponse.CODEC, version, wire);

      assertEquals(ResourcePackResponse.ACCEPTED, read.result());
      assertEquals(version.isAtMost(ProtocolVersion.MINECRAFT_1_9_4) ? HASH : "", read.hash());
      assertEquals(
          version.isAtLeast(ProtocolVersion.MINECRAFT_1_20_3) ? PACK : new UUID(0, 0), read.uuid());
      assertArrayEquals(wire, encode(ResourcePackResponse.CODEC, read, version));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("configVersions")
    @DisplayName("should read the client's answer in the configuration state")
    void configResponse(ProtocolVersion version) {
      byte[] wire = bytes(version, "config_resource_pack_response");

      assertEquals(
          ResourcePackResponse.ACCEPTED,
          decode(ResourcePackResponse.CODEC, version, wire).result());
    }

    @Test
    @DisplayName("should tell final answers from intermediate ones")
    void finalAnswers() {
      assertFalse(ResourcePackResponse.isFinal(ResourcePackResponse.ACCEPTED));
      assertFalse(ResourcePackResponse.isFinal(ResourcePackResponse.DOWNLOADED));
      assertTrue(ResourcePackResponse.isFinal(ResourcePackResponse.SUCCESSFULLY_LOADED));
      assertTrue(ResourcePackResponse.isFinal(ResourcePackResponse.DISCARDED));
    }

    @Test
    @DisplayName("should send a 1.7 client the URL alone, in the MC|RPack message")
    void legacy() {
      PlayPluginMessage message = pack(ProtocolVersion.MINECRAFT_1_8).legacyPluginMessage();

      assertEquals("MC|RPack", message.channel());
      assertArrayEquals(URL.getBytes(StandardCharsets.UTF_8), message.data());
    }

    @Test
    @DisplayName(
        "should refuse a hash longer than 40 characters, and compare packs by UUID, URL and hash")
    void hashAndIdentity() {
      ProtocolVersion version = ProtocolVersion.MINECRAFT_1_21_4;
      assertThrows(
          IllegalArgumentException.class,
          () -> ResourcePackPush.of(PACK, URL, HASH + "0", false, null, version));
      ResourcePackPush pack = pack(version);
      ResourcePackPush unprompted = ResourcePackPush.of(PACK, URL, HASH, false, null, version);

      assertTrue(pack.samePack(unprompted));
      assertFalse(pack.equals(unprompted));
      assertFalse(pack.samePack(ResourcePackPush.of(PACK, URL, "", false, null, version)));
      assertFalse(
          pack.samePack(ResourcePackPush.of(PACK, URL + "?v=2", HASH, true, null, version)));
      assertFalse(
          pack.samePack(ResourcePackPush.of(UUID.randomUUID(), URL, HASH, true, null, version)));
      assertEquals(pack, pack(version));
      assertEquals(pack.hashCode(), pack(version).hashCode());
      assertFalse(pack.hashCode() == unprompted.hashCode());
      // Only whether it is required differs, then only the prompt.
      assertFalse(
          pack.equals(
              ResourcePackPush.of(PACK, URL, HASH, false, Component.text("Please"), version)));
      assertFalse(
          pack.equals(
              ResourcePackPush.of(PACK, URL, HASH, true, Component.text("Other"), version)));
      assertFalse(pack.equals(URL));
    }
  }

  @Nested
  @DisplayName("chat deletion and dialogs")
  class DeletionAndDialogs {

    static Stream<ProtocolVersion> deleteVersions() {
      return AudiencePacketFixtures.versionsWith("delete_chat");
    }

    static Stream<ProtocolVersion> dialogVersions() {
      return AudiencePacketFixtures.versionsWith("clear_dialog");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("deleteVersions")
    @DisplayName("should delete a message by its full signature, from 1.19.1")
    void deleteChat(ProtocolVersion version) {
      byte[] signature = new byte[DeleteChat.SIGNATURE_BYTES];
      Arrays.fill(signature, (byte) 7);

      byte[] written = encode(DeleteChat.CODEC, new DeleteChat(signature), version);

      assertArrayEquals(bytes(version, "delete_chat"), written);
      assertArrayEquals(signature, decode(DeleteChat.CODEC, version, written).signature());
    }

    @Test
    @DisplayName("should refuse a signature that is not 256 bytes, and a cached one when reading")
    void refuseOtherSignatures() {
      assertThrows(IllegalArgumentException.class, () -> new DeleteChat(new byte[255]));
      byte[] cached = {3};
      assertThrows(
          DecoderException.class,
          () -> decode(DeleteChat.CODEC, ProtocolVersion.MINECRAFT_1_21_4, cached));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("dialogVersions")
    @DisplayName("should close a dialog with an empty packet, from 1.21.6")
    void clearDialog(ProtocolVersion version) {
      assertArrayEquals(
          bytes(version, "clear_dialog"), encode(ClearDialog.CODEC, new ClearDialog(), version));
      assertArrayEquals(
          bytes(version, "config_clear_dialog"),
          encode(ClearDialog.CODEC, new ClearDialog(), version));
      assertEquals(new ClearDialog(), decode(ClearDialog.CODEC, version, new byte[0]));
    }
  }
}
