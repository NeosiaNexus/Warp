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
package dev.warp.proxy.connection;

import static dev.warp.proxy.connection.SwitchHarness.LOBBY;
import static dev.warp.proxy.connection.SwitchHarness.types;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.warp.api.player.Player;
import dev.warp.protocol.ProtocolState;
import dev.warp.protocol.ProtocolVersion;
import dev.warp.protocol.packet.play.EntitySoundEffect;
import dev.warp.protocol.packet.play.PlayPluginMessage;
import dev.warp.protocol.packet.play.SoundEffect;
import dev.warp.protocol.packet.play.StopSound;

import java.util.ArrayList;
import java.util.List;

import net.kyori.adventure.key.Key;
import net.kyori.adventure.sound.Sound;
import net.kyori.adventure.sound.SoundStop;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("Sounds played to a player")
class SoundsTest {

  private static final Key PLING = Key.key("block.note_block.pling");
  private static final Sound SOUND =
      Sound.sound()
          .type(PLING)
          .source(Sound.Source.PLAYER)
          .volume(0.5f)
          .pitch(1.5f)
          .seed(7)
          .build();

  private final List<SwitchHarness> harnesses = new ArrayList<>();

  @AfterEach
  void tearDown() {
    harnesses.forEach(SwitchHarness::close);
  }

  private SwitchHarness inGame(ProtocolVersion version) {
    SwitchHarness setup = new SwitchHarness(version, List.of(), ProtocolState.PLAY);
    harnesses.add(setup);
    MinecraftConnection lobby = setup.backend(ProtocolState.PLAY);
    setup.player.setBackendConnection(new BackendConnection(lobby, LOBBY));
    lobby.setSessionHandler(new BackendPlaySessionHandler(setup.player, lobby));
    setup.client.setSessionHandler(new ClientPlaySessionHandler(setup.player));
    setup.player.setEntityId(42);
    setup.player.joinedGame();
    setup.sentToClient();
    return setup;
  }

  @Nested
  @DisplayName("at a position")
  class Positioned {

    @Test
    @DisplayName("should play a sound by its key at a position, in eighths of a block")
    void modern() {
      SwitchHarness setup = inGame(ProtocolVersion.MINECRAFT_1_21_4);

      setup.player.api().playSound(SOUND, 1.5, 64, -2);

      SoundEffect sound = setup.sentToClient().getFirst().as(SoundEffect.class);
      assertEquals(
          new SoundEffect("minecraft:block.note_block.pling", 7, 12, 512, -16, 0.5f, 1.5f, 7),
          sound);
    }

    @Test
    @DisplayName("should play it on 1.7 and 1.8 too, with the pitch as a byte")
    void legacy() {
      SwitchHarness setup = inGame(ProtocolVersion.MINECRAFT_1_8);

      setup.player.api().playSound(SOUND, 0, 0, 0);

      SoundEffect sound = setup.sentToClient().getFirst().as(SoundEffect.class);
      assertEquals("minecraft:block.note_block.pling", sound.sound());
      assertEquals(94 / 63f, sound.pitch());
    }

    @Test
    @DisplayName("should give the ui source to a client before 1.21.6 as master")
    void uiSource() {
      SwitchHarness older = inGame(ProtocolVersion.MINECRAFT_1_21_5);
      SwitchHarness newer = inGame(ProtocolVersion.MINECRAFT_1_21_6);
      Sound ui = Sound.sound(PLING, Sound.Source.UI, 1, 1);

      older.player.api().playSound(ui, 0, 0, 0);
      newer.player.api().playSound(ui, 0, 0, 0);

      assertEquals(0, older.sentToClient().getFirst().as(SoundEffect.class).source());
      assertEquals(10, newer.sentToClient().getFirst().as(SoundEffect.class).source());
    }

    @Test
    @DisplayName("should refuse a key no client reads, longer than 256 characters")
    void tooLong() {
      Player player = inGame(ProtocolVersion.MINECRAFT_1_21_4).player.api();
      Sound sound = Sound.sound(Key.key("warp", "a".repeat(260)), Sound.Source.MASTER, 1, 1);

      assertThrows(IllegalArgumentException.class, () -> player.playSound(sound, 0, 0, 0));
      assertThrows(IllegalArgumentException.class, () -> player.playSound(sound));
    }

    @Test
    @DisplayName("should drop a sound while the client is not in a game")
    void notInGame() {
      SwitchHarness setup = inGame(ProtocolVersion.MINECRAFT_1_21_4);
      setup.player.startConfiguration();
      setup.sentToClient();

      setup.player.api().playSound(SOUND, 0, 0, 0);
      setup.player.api().playSound(SOUND);

      assertEquals(List.of(), setup.sentToClient());
    }
  }

  @Nested
  @DisplayName("following the player")
  class Following {

    @Test
    @DisplayName("should follow the player's own entity from 1.19.3")
    void self() {
      SwitchHarness setup = inGame(ProtocolVersion.MINECRAFT_1_19_3);

      setup.player.api().playSound(SOUND);
      setup.player.api().playSound(SOUND, Sound.Emitter.self());

      List<SwitchHarness.Sent> sent = setup.sentToClient();
      assertEquals(List.of(EntitySoundEffect.class, EntitySoundEffect.class), types(sent));
      assertEquals(42, sent.getFirst().as(EntitySoundEffect.class).entityId());
    }

    @Test
    @DisplayName("should do nothing before 1.19.3, whose client takes registry ids only")
    void beforeInlinedSounds() {
      SwitchHarness setup = inGame(ProtocolVersion.MINECRAFT_1_19_1);

      setup.player.api().playSound(SOUND);

      assertEquals(List.of(), setup.sentToClient());
    }

    @Test
    @DisplayName(
        "should do nothing for a player on another server, and refuse what is not a player")
    void otherEmitters() {
      SwitchHarness setup = inGame(ProtocolVersion.MINECRAFT_1_21_4);
      SwitchHarness other = inGame(ProtocolVersion.MINECRAFT_1_21_4);
      Player player = setup.player.api();

      player.playSound(SOUND, other.player.api());

      assertEquals(List.of(), setup.sentToClient());
      assertThrows(
          IllegalArgumentException.class, () -> player.playSound(SOUND, new Sound.Emitter() {}));
    }
  }

  @Nested
  @DisplayName("stopping")
  class Stopping {

    @Test
    @DisplayName("should stop sounds with a packet from 1.13")
    void modern() {
      SwitchHarness setup = inGame(ProtocolVersion.MINECRAFT_1_13);

      setup.player.api().stopSound(SoundStop.namedOnSource(PLING, Sound.Source.PLAYER));
      setup.player.api().stopSound(SoundStop.all());

      List<SwitchHarness.Sent> sent = setup.sentToClient();
      assertEquals(
          new StopSound(7, "minecraft:block.note_block.pling"),
          sent.getFirst().as(StopSound.class));
      assertEquals(new StopSound(-1, null), sent.get(1).as(StopSound.class));
    }

    @Test
    @DisplayName("should stop sounds with the MC|StopSound message from 1.9.4 to 1.12.2")
    void pluginMessage() {
      SwitchHarness setup = inGame(ProtocolVersion.MINECRAFT_1_12_2);

      setup.player.api().stopSound(SoundStop.source(Sound.Source.MUSIC));

      PlayPluginMessage message = setup.sentToClient().getFirst().as(PlayPluginMessage.class);
      assertEquals("MC|StopSound", message.channel());
    }

    @Test
    @DisplayName("should do nothing before 1.9.4, which cannot stop a sound the server started")
    void beforeStopSound() {
      SwitchHarness setup = inGame(ProtocolVersion.MINECRAFT_1_9);

      setup.player.api().stopSound(SoundStop.all());

      assertEquals(List.of(), setup.sentToClient());
    }
  }
}
