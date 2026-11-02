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

import static dev.warp.protocol.packet.play.SwitchPacketFixtures.encode;
import static dev.warp.protocol.packet.play.SwitchPacketFixtures.writeAndRead;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.warp.protocol.ProtocolVersion;
import dev.warp.protocol.codec.McString;
import dev.warp.protocol.packet.Packet;
import dev.warp.protocol.packet.PacketCodec;

import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.stream.Stream;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.DecoderException;
import net.kyori.adventure.text.Component;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

@DisplayName("Audience packets read back, and their optional fields")
class AudiencePacketCodecTest {

  private static final ProtocolVersion MODERN = ProtocolVersion.MINECRAFT_1_21_4;
  private static final UUID PACK = UUID.fromString("5c39a8cb-1a3a-4c86-9a47-0f0aaf0e3a01");
  private static final String URL = "https://warp.dev/pack.zip";
  private static final String HASH = "0123456789abcdef0123456789abcdef01234567";
  private static final String SOUND = "minecraft:entity.experience_orb.pickup";

  @Nested
  @DisplayName("stopping sounds")
  class StopSounds {

    static Stream<StopSound> stops() {
      return Stream.of(
          new StopSound(-1, null),
          new StopSound(0, null),
          new StopSound(-1, SOUND),
          new StopSound(7, SOUND));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("stops")
    @DisplayName("should read back what it writes, whichever of the source and the sound it names")
    void readsBack(StopSound stop) {
      StopSound read = writeAndRead(StopSound.CODEC, stop, MODERN);

      assertEquals(stop, read);
    }

    @Test
    @DisplayName("should flag and write the master source, whose index is 0")
    void masterSource() {
      byte[] written = encode(StopSound.CODEC, new StopSound(0, null), MODERN);

      assertArrayEquals(new byte[] {1, 0}, written);
    }

    @Test
    @DisplayName("should name the master source in the MC|StopSound message of older clients")
    void legacyMasterSource() {
      PlayPluginMessage message = new StopSound(0, null).legacyPluginMessage();

      assertArrayEquals(concat(string("master"), string("")), message.data());
    }
  }

  @Nested
  @DisplayName("positioned sounds")
  class PositionedSounds {

    @Test
    @DisplayName("should read back a sound with its pitch as a byte, before 1.10")
    void pitchAsAByte() {
      SoundEffect sound = new SoundEffect(SOUND, 0, 8, -16, 24, 0.5f, 2.0f, 0);

      SoundEffect read = writeAndRead(SoundEffect.CODEC, sound, ProtocolVersion.MINECRAFT_1_8);

      assertEquals(sound, read);
    }

    @Test
    @DisplayName("should read back a sound with its pitch as a float, its source and its seed")
    void pitchAsAFloat() {
      SoundEffect sound = new SoundEffect(SOUND, 7, 8, -16, 24, 0.5f, 1.5f, 42);

      SoundEffect read = writeAndRead(SoundEffect.CODEC, sound, MODERN);

      assertEquals(sound, read);
    }

    @Test
    @DisplayName("should name the registry id a server sent in place of a sound key")
    void registryIdInTheMessage() {
      byte[] holder = {5};

      DecoderException refused =
          assertThrows(DecoderException.class, () -> read(EntitySoundEffect.CODEC, holder));

      assertEquals("Sound registry id 4 instead of a key", refused.getMessage());
    }
  }

  @Nested
  @DisplayName("resource packs")
  class ResourcePacks {

    @Test
    @DisplayName("should write and read back a pack without a prompt, flagged as absent")
    void withoutPrompt() {
      ResourcePackPush push = ResourcePackPush.of(PACK, URL, HASH, false, null, MODERN);

      byte[] written = encode(ResourcePackPush.CODEC, push, MODERN);
      ResourcePackPush read = writeAndRead(ResourcePackPush.CODEC, push, MODERN);

      assertEquals(0, written[written.length - 1]);
      assertEquals(push, read);
      assertNull(read.prompt());
    }

    @Test
    @DisplayName("should leave out the prompt for clients before 1.17, which cannot show one")
    void noPromptBefore117() {
      ResourcePackPush push =
          ResourcePackPush.of(
              PACK, URL, HASH, true, Component.text("Please"), ProtocolVersion.MINECRAFT_1_16_4);

      assertNull(push.prompt());
    }

    @Test
    @DisplayName(
        "should know an old client's pack by the UUID a 1.20.3 server derives from its URL")
    void legacyUuid() {
      UUID derived = ResourcePackPush.legacyUuid(URL);

      assertEquals(UUID.nameUUIDFromBytes(URL.getBytes(StandardCharsets.UTF_8)), derived);
    }

    @Test
    @DisplayName("should not equal the push of another pack with the same flag and prompt")
    void otherPackIsNotEqual() {
      ResourcePackPush pack =
          ResourcePackPush.of(PACK, URL, HASH, true, Component.text("Please"), MODERN);

      ResourcePackPush other =
          ResourcePackPush.of(PACK, URL + "?v=2", HASH, true, Component.text("Please"), MODERN);

      assertNotEquals(pack, other);
    }

    @Test
    @DisplayName("should read back the removal of one pack, by its UUID")
    void removalOfOnePack() {
      ResourcePackPop read = writeAndRead(ResourcePackPop.CODEC, new ResourcePackPop(PACK), MODERN);

      assertEquals(PACK, read.uuid());
    }
  }

  // ---------------------------------------------------------------------------
  // Helpers
  // ---------------------------------------------------------------------------

  private static <T extends Packet> T read(PacketCodec<T> codec, byte[] bytes) {
    ByteBuf buf = Unpooled.wrappedBuffer(bytes);
    try {
      return codec.decode(buf, MODERN);
    } finally {
      buf.release();
    }
  }

  private static byte[] string(String value) {
    ByteBuf buf = Unpooled.buffer();
    try {
      McString.write(buf, value);
      return ByteBufUtil.getBytes(buf);
    } finally {
      buf.release();
    }
  }

  private static byte[] concat(byte[] a, byte[] b) {
    byte[] both = new byte[a.length + b.length];
    System.arraycopy(a, 0, both, 0, a.length);
    System.arraycopy(b, 0, both, a.length, b.length);
    return both;
  }
}
