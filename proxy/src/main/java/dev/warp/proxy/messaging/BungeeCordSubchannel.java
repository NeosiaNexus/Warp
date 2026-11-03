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
package dev.warp.proxy.messaging;

import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.jspecify.annotations.Nullable;

/**
 * The sub-channels of the BungeeCord channel: the first string of every message names one. These
 * are every sub-channel BungeeCord answers ({@code DownstreamBridge}), which Velocity answers too
 * ({@code BungeeCordMessageResponder}); neither added any other.
 */
public enum BungeeCordSubchannel {
  /** Moves the player whose connection carries the message to a server. */
  CONNECT("Connect"),
  /** Moves any player to a server. */
  CONNECT_OTHER("ConnectOther"),
  /** Answers the address the carrying player connects from. */
  IP("IP"),
  /** Answers the address any player connects from. */
  IP_OTHER("IPOther"),
  /** Answers how many players play on a server, or on the proxy. */
  PLAYER_COUNT("PlayerCount"),
  /** Answers the names of the players of a server, or of the proxy. */
  PLAYER_LIST("PlayerList"),
  /** Answers the names of the servers. */
  GET_SERVERS("GetServers"),
  /** Answers the server any player plays on. */
  GET_PLAYER_SERVER("GetPlayerServer"),
  /** Sends a chat message, in legacy text, to a player or to every player. */
  MESSAGE("Message"),
  /** Sends a chat message, in JSON, to a player or to every player. */
  MESSAGE_RAW("MessageRaw"),
  /** Answers the name of the server that asks. */
  GET_SERVER("GetServer"),
  /** Answers the carrying player's UUID. */
  UUID("UUID"),
  /** Answers any player's UUID. */
  UUID_OTHER("UUIDOther"),
  /** Answers the address of a server. */
  SERVER_IP("ServerIP"),
  /** Disconnects any player, with a reason in legacy text. */
  KICK_PLAYER("KickPlayer"),
  /** Disconnects any player, with a reason in JSON. */
  KICK_PLAYER_RAW("KickPlayerRaw"),
  /** Delivers data to the plugins of another server, or of every other server. */
  FORWARD("Forward"),
  /** Delivers data to the plugins of the server a player plays on. */
  FORWARD_TO_PLAYER("ForwardToPlayer");

  private static final Map<String, BungeeCordSubchannel> BY_WIRE_NAME =
      Stream.of(values())
          .collect(
              Collectors.toUnmodifiableMap(BungeeCordSubchannel::wireName, Function.identity()));

  private static final Map<String, BungeeCordSubchannel> BY_LOWERCASE_NAME =
      Stream.of(values())
          .collect(
              Collectors.toUnmodifiableMap(
                  sub -> sub.wireName().toLowerCase(Locale.ROOT), Function.identity()));

  private final String wireName;

  BungeeCordSubchannel(String wireName) {
    this.wireName = wireName;
  }

  /**
   * Returns the name messages carry, which is case-sensitive.
   *
   * @return the name, such as {@code PlayerCount}
   */
  public String wireName() {
    return wireName;
  }

  /**
   * Returns the sub-channel a message names, matched exactly as BungeeCord matches it.
   *
   * @param wireName the message's first string
   * @return the sub-channel, or {@code null} if none has that name
   */
  public static @Nullable BungeeCordSubchannel fromWireName(String wireName) {
    return BY_WIRE_NAME.get(wireName);
  }

  /**
   * Returns the sub-channel a configuration names, in any case.
   *
   * @param name the name, such as {@code KickPlayer} or {@code kickplayer}
   * @return the sub-channel, or {@code null} if none has that name
   */
  public static @Nullable BungeeCordSubchannel fromConfigName(String name) {
    return BY_LOWERCASE_NAME.get(name.toLowerCase(Locale.ROOT));
  }
}
