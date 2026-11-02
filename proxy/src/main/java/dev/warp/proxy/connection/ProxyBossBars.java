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

import dev.warp.protocol.ProtocolVersion;
import dev.warp.protocol.packet.play.BossBar;
import dev.warp.protocol.packet.play.PlayPacket;
import dev.warp.protocol.text.ComponentCodec;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import net.kyori.adventure.text.Component;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The boss bars plugins show a player, kept on its client across server switches and updated live.
 *
 * <p>Each Adventure bar shown to the player gets a UUID of its own and a listener: a change to the
 * bar marks what changed, and the player's event loop then writes the current value, one packet per
 * changed field however many changes came in between, so that an animated bar costs a packet per
 * event loop turn at most and the client always ends with the bar's latest state.
 *
 * <p>The client shows the bars only while it is in a game. A client from 1.20.2 clears every bar
 * when it enters the configuration phase, for a switch or a reconfiguration of its server: from
 * then on no packet of a bar is written (an update to a bar the client no longer has disconnects
 * it, Velocity #1529, and the configuration phase has no boss bar packet at all, Velocity #1656),
 * and once the client is in the next game every bar is shown again, with its state of the moment.
 * Before 1.20.2 the client keeps the bars across the Join Game of a switch, and the proxy only
 * removes the server's own ({@link ServerLeftovers}). Bars shown before the first Join Game wait
 * for it the same way. Before 1.9 the client has no boss bar packet ({@code 0x0C} is another packet
 * in 1.8, BungeeCord #1769): nothing is tracked or sent.
 *
 * <p>Every method but the listener's runs on the player's event loop. The listener runs on whatever
 * thread changed the bar, and only hands the change over to the event loop.
 */
final class ProxyBossBars {

  private static final Logger logger = LoggerFactory.getLogger(ProxyBossBars.class);

  private static final int NAME = 1;
  private static final int PROGRESS = 1 << 1;
  private static final int STYLE = 1 << 2;
  private static final int FLAGS = 1 << 3;

  private final ConnectedPlayer player;
  private final ProtocolVersion version;

  /** The bars shown, in the order they were shown: a client lists them in that order. */
  private final List<Shown> bars = new ArrayList<>();

  /** Whether the client shows the bars: it is in a game and has not entered configuration since. */
  private boolean onClient;

  ProxyBossBars(ConnectedPlayer player) {
    this.player = player;
    this.version = player.protocolVersion();
  }

  /**
   * Returns whether the client has boss bars at all.
   *
   * @param version the client's protocol version
   * @return {@code true} from 1.9
   */
  static boolean supported(ProtocolVersion version) {
    return version.isAtLeast(ProtocolVersion.MINECRAFT_1_9);
  }

  /**
   * Shows a bar, at once if the client is in a game, once it is otherwise. Showing a bar already
   * shown does nothing.
   *
   * @param bar the bar
   */
  void show(net.kyori.adventure.bossbar.BossBar bar) {
    if (!supported(version) || find(bar) != null) {
      return;
    }
    Shown shown = new Shown(bar);
    bars.add(shown);
    bar.addListener(shown);
    if (onClient) {
      write(shown.add());
      player.clientConnection().flush();
    }
  }

  /**
   * Hides a bar shown with {@link #show}; does nothing for any other.
   *
   * @param bar the bar
   */
  void hide(net.kyori.adventure.bossbar.BossBar bar) {
    Shown shown = find(bar);
    if (shown == null) {
      return;
    }
    bars.remove(shown);
    bar.removeListener(shown);
    if (onClient) {
      write(BossBar.remove(shown.uuid));
      player.clientConnection().flush();
    }
  }

  /** The client is in a game: it shows every bar, with its current state. */
  void joinedGame() {
    if (onClient) {
      return; // before 1.20.2 the client kept them across the Join Game
    }
    onClient = true;
    for (Shown shown : List.copyOf(bars)) { // a failed write disconnects, which clears them
      shown.dirty.set(0); // the addition carries every field as it is now
      write(shown.add());
    }
  }

  /** The client enters the configuration phase, which clears its bars (1.20.2+). */
  void leftGame() {
    onClient = false;
  }

  /** The player is gone: every bar forgets it. */
  void clear() {
    List<Shown> shown = List.copyOf(bars);
    bars.clear();
    for (Shown each : shown) {
      each.bar.removeListener(each);
    }
  }

  /**
   * Returns the UUID a bar has on the client, for tests.
   *
   * @param bar the bar
   * @return its UUID, or {@code null} if it is not shown
   */
  @Nullable UUID uuidOf(net.kyori.adventure.bossbar.BossBar bar) {
    Shown shown = find(bar);
    return shown == null ? null : shown.uuid;
  }

  /** The bar shown as {@code bar}: Adventure bars are mutable, and compared by identity. */
  private @Nullable Shown find(net.kyori.adventure.bossbar.BossBar bar) {
    for (Shown shown : bars) {
      if (shown.bar == bar) {
        return shown;
      }
    }
    return null;
  }

  private void write(PlayPacket packet) {
    player.clientConnection().write(packet);
  }

  /** Writes what changed in a bar since it was last written. Event loop. */
  private void flush(Shown shown) {
    int changes = shown.dirty.getAndSet(0);
    if (changes == 0 || !onClient || find(shown.bar) != shown) {
      return; // written by the addition, or not on the client: shown again when it is
    }
    net.kyori.adventure.bossbar.BossBar bar = shown.bar;
    List<PlayPacket> packets = new ArrayList<>(4);
    if ((changes & NAME) != 0) {
      packets.add(new BossBar(shown.uuid, new BossBar.UpdateName(name(bar))));
    }
    if ((changes & PROGRESS) != 0) {
      packets.add(new BossBar(shown.uuid, new BossBar.UpdateProgress(bar.progress())));
    }
    if ((changes & STYLE) != 0) {
      packets.add(
          new BossBar(
              shown.uuid,
              new BossBar.UpdateStyle(
                  AdventureIds.color(bar.color()), AdventureIds.overlay(bar.overlay()))));
    }
    if ((changes & FLAGS) != 0) {
      packets.add(new BossBar(shown.uuid, new BossBar.UpdateFlags(flags(bar.flags()))));
    }
    packets.forEach(this::write);
    player.clientConnection().flush();
  }

  /**
   * Returns a bar's name encoded for the client: rendered in its language, and empty if it is too
   * large for the client to read, which would fail the whole packet.
   */
  private byte[] name(net.kyori.adventure.bossbar.BossBar bar) {
    try {
      return ComponentCodec.encode(player.render(bar.name()), version);
    } catch (IllegalArgumentException tooLarge) {
      logger.warn("A boss bar name is too large for the client of {}", player.username());
      return ComponentCodec.encode(Component.empty(), version);
    }
  }

  /**
   * Returns the flags of a bar as the packet carries them.
   *
   * @param flags the bar's flags
   * @return the packet's bit field
   */
  static int flags(Set<net.kyori.adventure.bossbar.BossBar.Flag> flags) {
    int value = 0;
    for (net.kyori.adventure.bossbar.BossBar.Flag flag : flags) {
      value |=
          switch (flag) {
            case DARKEN_SCREEN -> BossBar.DARKEN_SCREEN;
            case PLAY_BOSS_MUSIC -> BossBar.PLAY_BOSS_MUSIC;
            case CREATE_WORLD_FOG -> BossBar.CREATE_WORLD_FOG;
          };
    }
    return value;
  }

  /** A bar shown to the player: its UUID on the client, and the listener that follows it. */
  private final class Shown implements net.kyori.adventure.bossbar.BossBar.Listener {

    private final net.kyori.adventure.bossbar.BossBar bar;
    private final UUID uuid = UUID.randomUUID();

    /** The fields changed since the bar was last written; non-zero while a flush is scheduled. */
    private final AtomicInteger dirty = new AtomicInteger();

    Shown(net.kyori.adventure.bossbar.BossBar bar) {
      this.bar = bar;
    }

    /** The packet that shows the bar as it is now. */
    BossBar add() {
      return new BossBar(
          uuid,
          new BossBar.Add(
              name(bar),
              bar.progress(),
              AdventureIds.color(bar.color()),
              AdventureIds.overlay(bar.overlay()),
              flags(bar.flags())));
    }

    private void changed(int field) {
      if (dirty.getAndUpdate(fields -> fields | field) == 0) {
        player.onEventLoop(() -> flush(this));
      }
    }

    @Override
    public void bossBarNameChanged(
        net.kyori.adventure.bossbar.BossBar bar, Component oldName, Component newName) {
      changed(NAME);
    }

    @Override
    public void bossBarProgressChanged(
        net.kyori.adventure.bossbar.BossBar bar, float oldProgress, float newProgress) {
      changed(PROGRESS);
    }

    @Override
    public void bossBarColorChanged(
        net.kyori.adventure.bossbar.BossBar bar,
        net.kyori.adventure.bossbar.BossBar.Color oldColor,
        net.kyori.adventure.bossbar.BossBar.Color newColor) {
      changed(STYLE);
    }

    @Override
    public void bossBarOverlayChanged(
        net.kyori.adventure.bossbar.BossBar bar,
        net.kyori.adventure.bossbar.BossBar.Overlay oldOverlay,
        net.kyori.adventure.bossbar.BossBar.Overlay newOverlay) {
      changed(STYLE);
    }

    @Override
    public void bossBarFlagsChanged(
        net.kyori.adventure.bossbar.BossBar bar,
        Set<net.kyori.adventure.bossbar.BossBar.Flag> flagsAdded,
        Set<net.kyori.adventure.bossbar.BossBar.Flag> flagsRemoved) {
      changed(FLAGS);
    }
  }
}
