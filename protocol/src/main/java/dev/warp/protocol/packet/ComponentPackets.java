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

import static dev.warp.protocol.ProtocolVersion.MINECRAFT_1_11;
import static dev.warp.protocol.ProtocolVersion.MINECRAFT_1_17;
import static dev.warp.protocol.ProtocolVersion.MINECRAFT_1_8;

import dev.warp.protocol.ProtocolState;
import dev.warp.protocol.ProtocolVersion;
import dev.warp.protocol.packet.config.ConfigDisconnect;
import dev.warp.protocol.packet.login.LoginDisconnect;
import dev.warp.protocol.packet.play.ClearTitles;
import dev.warp.protocol.packet.play.PlayDisconnect;
import dev.warp.protocol.packet.play.PlayPacket;
import dev.warp.protocol.packet.play.SetActionBarText;
import dev.warp.protocol.packet.play.SetSubtitleText;
import dev.warp.protocol.packet.play.SetTitleText;
import dev.warp.protocol.packet.play.SetTitleTimes;
import dev.warp.protocol.packet.play.SystemChatMessage;
import dev.warp.protocol.packet.play.TabListHeaderFooter;
import dev.warp.protocol.text.ComponentCodec;

import java.time.Duration;
import java.util.List;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.title.Title;
import net.kyori.adventure.title.TitlePart;
import org.jspecify.annotations.Nullable;

/**
 * The packets that show a component to a client of a given version: a chat message, the action bar,
 * a title, the tab list header and footer, a disconnect reason. Each picks the packet the version
 * has and encodes the component for it ({@link ComponentCodec}).
 *
 * <table>
 *   <caption>Packets by version</caption>
 *   <tr><th>What</th><th>1.7</th><th>1.8 to 1.10</th><th>1.11 to 1.16.5</th><th>1.17+</th></tr>
 *   <tr><td>chat</td><td colspan="4">Chat Message, System Chat Message from 1.19</td></tr>
 *   <tr><td>action bar</td><td>none</td><td>chat, game info position</td><td>Title action</td>
 *       <td>Set Action Bar Text</td></tr>
 *   <tr><td>title</td><td>none</td><td colspan="2">Title actions</td><td>Set Title Text, Set
 *       Subtitle Text, Set Titles Animation, Clear Titles</td></tr>
 *   <tr><td>header, footer</td><td>none</td><td colspan="3">Tab List Header And Footer</td></tr>
 * </table>
 *
 * <p>A version without the packet gets nothing: an action bar on 1.7 would otherwise land in the
 * chat box, once per update.
 */
public final class ComponentPackets {

  /** Milliseconds in a game tick, the unit of title times. */
  private static final long MILLIS_PER_TICK = 50;

  private ComponentPackets() {}

  /**
   * Returns the packet that shows a message in the chat box.
   *
   * @param message the message
   * @param version the client's protocol version
   * @return the packet
   * @throws IllegalArgumentException if the message is too large for the client
   */
  public static PlayPacket chat(Component message, ProtocolVersion version) {
    return SystemChatMessage.of(message, version);
  }

  /**
   * Returns the packet that shows a message in the action bar, above the hotbar.
   *
   * <p>From 1.11 the action bar has a packet of its own (the Title packet's action up to 1.16.5).
   * Before, it is a chat message at the game info position whose styles the client drops, so the
   * formatting is written into the text as section sign codes ({@link
   * ComponentCodec#legacyActionBar}). 1.7 has no action bar.
   *
   * @param text the action bar text
   * @param version the client's protocol version
   * @return the packet, or {@code null} on 1.7
   * @throws IllegalArgumentException if the text is too large for the client
   */
  public static @Nullable PlayPacket actionBar(Component text, ProtocolVersion version) {
    if (version.isAtLeast(MINECRAFT_1_11)) {
      return SetActionBarText.of(text, version);
    }
    if (version.isAtLeast(MINECRAFT_1_8)) {
      return new SystemChatMessage(
          ComponentCodec.encode(ComponentCodec.legacyActionBar(text), version), true);
    }
    return null;
  }

  /**
   * Returns the packets that show a title: its times when it has some, its subtitle, then the
   * title, which displays both. The times apply to the titles that follow them, and a subtitle only
   * shows with a title, hence the order (Velocity #572).
   *
   * @param title the title
   * @param version the client's protocol version
   * @return the packets, none on 1.7
   * @throws IllegalArgumentException if a component is too large for the client
   */
  public static List<PlayPacket> title(Title title, ProtocolVersion version) {
    if (version.isOlderThan(MINECRAFT_1_8)) {
      return List.of();
    }
    PlayPacket subtitle = SetSubtitleText.of(title.subtitle(), version);
    PlayPacket main = SetTitleText.of(title.title(), version);
    Title.Times times = title.times();
    return times == null ? List.of(subtitle, main) : List.of(times(times), subtitle, main);
  }

  /**
   * Returns the packet that sets one part of the title: the title itself, which displays, the
   * subtitle or the times.
   *
   * @param part the part
   * @param value its value
   * @param version the client's protocol version
   * @param <T> the type of the part
   * @return the packet, or {@code null} on 1.7
   * @throws IllegalArgumentException if a component is too large for the client, or the part is
   *     unknown
   */
  public static <T> @Nullable PlayPacket titlePart(
      TitlePart<T> part, T value, ProtocolVersion version) {
    if (version.isOlderThan(MINECRAFT_1_8)) {
      return null;
    }
    if (part.equals(TitlePart.TITLE)) {
      return SetTitleText.of((Component) value, version);
    }
    if (part.equals(TitlePart.SUBTITLE)) {
      return SetSubtitleText.of((Component) value, version);
    }
    if (part.equals(TitlePart.TIMES)) {
      return times((Title.Times) value);
    }
    throw new IllegalArgumentException("Unknown title part " + part);
  }

  /**
   * Returns the packets that hide the title on screen, keeping its times.
   *
   * @param version the client's protocol version
   * @return the packets, none on 1.7
   */
  public static List<PlayPacket> clearTitle(ProtocolVersion version) {
    return version.isAtLeast(MINECRAFT_1_8) ? List.of(new ClearTitles(false)) : List.of();
  }

  /**
   * Returns the packets that hide the title and reset its times to their defaults. Up to 1.16.5 the
   * reset action alone shows the last subtitle again for a whole title duration (1.8.9 {@code
   * GuiIngame#displayTitle}): the title is hidden first.
   *
   * @param version the client's protocol version
   * @return the packets, none on 1.7
   */
  public static List<PlayPacket> resetTitle(ProtocolVersion version) {
    if (version.isAtLeast(MINECRAFT_1_17)) {
      return List.of(new ClearTitles(true));
    }
    if (version.isAtLeast(MINECRAFT_1_8)) {
      return List.of(new ClearTitles(false), new ClearTitles(true));
    }
    return List.of();
  }

  /**
   * Returns the packet that sets the text above and below the tab list.
   *
   * @param header the header, empty to hide it
   * @param footer the footer, empty to hide it
   * @param version the client's protocol version
   * @return the packet, or {@code null} on 1.7
   * @throws IllegalArgumentException if a component is too large for the client
   */
  public static @Nullable PlayPacket headerAndFooter(
      Component header, Component footer, ProtocolVersion version) {
    return version.isAtLeast(MINECRAFT_1_8)
        ? TabListHeaderFooter.of(header, footer, version)
        : null;
  }

  /**
   * Returns the disconnect packet of the state the client reads, with the reason encoded as that
   * state carries it: JSON in the login state of every version, JSON or NBT by version in the
   * configuration and play states (Velocity #1213: the format follows the state, never a flag).
   *
   * @param reason the reason shown to the player
   * @param state the state the client reads packets in, which is the state the proxy writes in
   * @param version the client's protocol version
   * @return the packet, or {@code null} in the handshake and status states, which have none
   * @throws IllegalArgumentException if the reason is too large for the client
   */
  public static @Nullable Packet disconnect(
      Component reason, ProtocolState state, ProtocolVersion version) {
    return switch (state) {
      case HANDSHAKE, STATUS -> null;
      case LOGIN -> LoginDisconnect.of(reason, version);
      case CONFIGURATION -> ConfigDisconnect.of(reason, version);
      case PLAY -> PlayDisconnect.of(reason, version);
    };
  }

  private static SetTitleTimes times(Title.Times times) {
    return new SetTitleTimes(ticks(times.fadeIn()), ticks(times.stay()), ticks(times.fadeOut()));
  }

  /** A duration in whole ticks, as the int the packet carries. */
  private static int ticks(Duration duration) {
    return Math.clamp(duration.toMillis() / MILLIS_PER_TICK, 0, Integer.MAX_VALUE);
  }
}
