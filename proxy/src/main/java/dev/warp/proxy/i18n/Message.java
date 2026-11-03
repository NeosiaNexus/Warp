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
package dev.warp.proxy.i18n;

import dev.warp.protocol.ProtocolVersion;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import net.kyori.adventure.text.ComponentLike;
import net.kyori.adventure.text.TranslatableComponent;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.kyori.adventure.text.minimessage.translation.Argument;
import org.jspecify.annotations.Nullable;

/**
 * Every message Warp itself shows players, by its translation key.
 *
 * <p>A message is sent as a translatable component and rendered for each player in its language
 * just before it is written (see {@link PlayerMessages#render}). Where vanilla has a message that
 * says the same thing, its key is used: a client that knows the key translates it itself, in any of
 * the game's languages; Warp renders it only for a client too old to know it, or when the operator
 * overrides it. Every other key starts with {@code warp.}.
 *
 * <p>A message's text is MiniMessage. Its placeholders are named: {@code <server>}, {@code
 * <reason>}... A vanilla message takes its arguments in order, {@code <arg:0>} first.
 */
public enum Message {

  // ---------------------------------------------------------------------------
  // Refused at login
  // ---------------------------------------------------------------------------

  /** Beyond the player limit. */
  SERVER_FULL(
      "multiplayer.disconnect.server_full", Kind.DISCONNECT, ProtocolVersion.MINECRAFT_1_13),
  /** A login that took longer than the login timeout. */
  SLOW_LOGIN("multiplayer.disconnect.slow_login", Kind.DISCONNECT, ProtocolVersion.MINECRAFT_1_12),
  /** The session server failed: nobody can log in for now. */
  AUTHENTICATION_SERVERS_DOWN(
      "multiplayer.disconnect.authservers_down", Kind.DISCONNECT, ProtocolVersion.MINECRAFT_1_12),
  /** A cookie or plugin response the proxy never asked for. */
  UNEXPECTED_QUERY_RESPONSE(
      "multiplayer.disconnect.unexpected_query_response",
      Kind.DISCONNECT,
      ProtocolVersion.MINECRAFT_1_13),
  /** A chat signing key that is invalid (1.19 to 1.19.2 clients only). */
  INVALID_PUBLIC_KEY_SIGNATURE(
      "multiplayer.disconnect.invalid_public_key_signature",
      Kind.DISCONNECT,
      ProtocolVersion.MINECRAFT_1_19),
  /** A transfer while {@code accepts-transfers} is off (1.20.5+ clients only). */
  TRANSFERS_DISABLED(
      "multiplayer.disconnect.transfers_disabled",
      Kind.DISCONNECT,
      ProtocolVersion.MINECRAFT_1_20_5),
  /** Logging in again too soon. */
  LOGIN_TOO_FAST("warp.login.too-fast", Kind.DISCONNECT, "seconds"),
  /** Too many logins in progress from one address. */
  TOO_MANY_LOGINS("warp.login.too-many-logins", Kind.DISCONNECT),
  /** Waited in line for authentication longer than the queue allows. */
  LOGIN_BUSY("warp.login.busy", Kind.DISCONNECT, "seconds"),
  /** An unknown player during the anti-bot's lockdown. */
  LOCKDOWN("warp.login.lockdown", Kind.DISCONNECT),
  /** A username no vanilla client sends. */
  INVALID_USERNAME("warp.login.invalid-username", Kind.DISCONNECT),
  /** A login packet sent out of order: a broken or modified client. */
  UNEXPECTED_PACKET("warp.login.unexpected-packet", Kind.DISCONNECT),
  /** The encryption handshake failed. */
  ENCRYPTION_FAILED("warp.login.encryption-failed", Kind.DISCONNECT),
  /** The session server does not know the player's session. */
  AUTHENTICATION_FAILED("warp.login.authentication-failed", Kind.DISCONNECT),
  /** The duplicate login policy keeps the session already connected. */
  ALREADY_CONNECTED("warp.login.already-connected", Kind.DISCONNECT),

  // ---------------------------------------------------------------------------
  // Disconnected
  // ---------------------------------------------------------------------------

  /** The same account logged in again, and the duplicate login policy keeps the newcomer. */
  DUPLICATE_LOGIN(
      "multiplayer.disconnect.duplicate_login", Kind.DISCONNECT, ProtocolVersion.MINECRAFT_1_12),
  /** The proxy shuts down. */
  SERVER_SHUTDOWN(
      "multiplayer.disconnect.server_shutdown", Kind.DISCONNECT, ProtocolVersion.MINECRAFT_1_12),
  /** The proxy drains and cannot transfer the player elsewhere. */
  RESTARTING("warp.disconnect.restarting", Kind.DISCONNECT),
  /** No server can take the player when it joins. */
  NO_SERVER_AVAILABLE("warp.disconnect.no-server-available", Kind.DISCONNECT),
  /** Every server of the fallback order failed. */
  NO_SERVER_LEFT("warp.disconnect.no-server-left", Kind.DISCONNECT),
  /** A plugin denied the player its first server. */
  INITIAL_SERVER_DENIED("warp.disconnect.initial-server-denied", Kind.DISCONNECT),
  /** A plugin denied a fallback server. */
  FALLBACK_DENIED("warp.disconnect.fallback-denied", Kind.DISCONNECT, "server"),
  /** A server switch took too long. */
  SWITCH_TIMED_OUT("warp.disconnect.switch-timed-out", Kind.DISCONNECT),
  /** Plugins took too long to decide where the player goes. */
  WAIT_TIMED_OUT("warp.disconnect.wait-timed-out", Kind.DISCONNECT),
  /** A reason too large for the client, replaced by this one. */
  REASON_TOO_LONG("warp.disconnect.reason-too-long", Kind.DISCONNECT),
  /** Something went wrong in the proxy itself; the reference is in the log too. */
  INTERNAL_ERROR("warp.disconnect.internal-error", Kind.DISCONNECT, "reference"),

  // ---------------------------------------------------------------------------
  // Chat
  // ---------------------------------------------------------------------------

  /** A server could not be reached or refused the player, which stays where it is. */
  CONNECT_FAILED("warp.connect.failed", Kind.CHAT, "server"),
  /** Same, with the reason the server gave. */
  CONNECT_FAILED_REASON("warp.connect.failed-reason", Kind.CHAT, "server", "reason"),
  /** A plugin denied a switch the player asked for. */
  CONNECT_DENIED("warp.connect.denied", Kind.CHAT, "server"),
  /** The player asked for a switch during another. */
  ALREADY_SWITCHING("warp.connect.already-switching", Kind.CHAT),
  /** The player asked for the server it is on. */
  ALREADY_ON_SERVER("warp.connect.already-on-server", Kind.CHAT, "server"),
  /** The player is moved to a fallback server. */
  CONNECTING("warp.connect.connecting", Kind.CHAT, "server"),
  /** The player was moved to a fallback after its server disconnected it with a reason. */
  FALLBACK_KICKED("warp.fallback.kicked", Kind.CHAT, "server", "fallback", "reason"),
  /** The player was moved to a fallback after its server went away. */
  FALLBACK_LOST("warp.fallback.lost", Kind.CHAT, "server", "fallback"),
  /** {@code /server} with a server that does not exist. */
  UNKNOWN_SERVER("warp.command.server.unknown", Kind.CHAT, "server"),
  /** {@code /server}: the list of servers. */
  SERVER_LIST("warp.command.server.list", Kind.CHAT, "servers"),
  /** {@code /server}: the hover text of a server of the list. */
  SERVER_LIST_HOVER("warp.command.server.hover", Kind.CHAT, "server"),
  /** A command failed in the proxy. */
  COMMAND_FAILED("warp.command.failed", Kind.CHAT);

  /** How a message reaches the player, which decides what an override may be. */
  public enum Kind {
    /** The reason on the disconnect screen: never empty. */
    DISCONNECT,
    /** A chat line: an empty override means the line is not sent. */
    CHAT
  }

  private final String key;
  private final Kind kind;

  @SuppressWarnings("ImmutableEnumChecker") // an enum of protocol versions: never changes
  private final @Nullable ProtocolVersion vanillaSince;

  @SuppressWarnings("ImmutableEnumChecker") // List.of: unmodifiable
  private final List<String> placeholders;

  Message(String key, Kind kind, ProtocolVersion vanillaSince) {
    this.key = key;
    this.kind = kind;
    this.vanillaSince = vanillaSince;
    this.placeholders = List.of();
  }

  Message(String key, Kind kind, String... placeholders) {
    this.key = key;
    this.kind = kind;
    this.vanillaSince = null;
    this.placeholders = List.of(placeholders);
  }

  /**
   * Returns the translation key.
   *
   * @return the key, such as {@code warp.login.lockdown}
   */
  public String key() {
    return key;
  }

  /**
   * Returns how the message reaches the player.
   *
   * @return its kind
   */
  public Kind kind() {
    return kind;
  }

  /**
   * Returns the first version whose client translates the key itself, for a vanilla key.
   *
   * @return the version, or {@code null} for one of Warp's own keys, which Warp always renders
   */
  public @Nullable ProtocolVersion vanillaSince() {
    return vanillaSince;
  }

  /**
   * Returns the names of the message's placeholders, in the order {@link #of} takes them.
   *
   * @return the names, empty for a message without any
   */
  public List<String> placeholders() {
    return placeholders;
  }

  /**
   * Returns the message as a component to send to a player, rendered in its language when sent.
   *
   * @param values one value per {@linkplain #placeholders() placeholder}, in order: a string,
   *     inserted as typed (a player named {@code <red>} stays a name), a number, a component, or a
   *     tag resolver such as {@code Formatter.choice} for plurals
   * @return the translatable component
   * @throws IllegalArgumentException if the number of values is not the number of placeholders
   */
  public TranslatableComponent of(Object... values) {
    if (values.length != placeholders.size()) {
      throw new IllegalArgumentException(
          key + " takes " + placeholders + ", got " + values.length + " values");
    }
    ComponentLike[] arguments = new ComponentLike[values.length];
    for (int i = 0; i < values.length; i++) {
      String name = placeholders.get(i);
      arguments[i] =
          switch (values[i]) {
            case TagResolver resolver -> Argument.tagResolver(resolver);
            case ComponentLike component -> Argument.component(name, component);
            case Number number -> Argument.numeric(name, number);
            case Object other -> Argument.string(name, other.toString());
          };
    }
    return net.kyori.adventure.text.Component.translatable(key, arguments);
  }

  /**
   * Returns the message whose key is {@code key}.
   *
   * @param key a translation key
   * @return the message, or {@code null} if no message has that key
   */
  public static @Nullable Message byKey(String key) {
    return BY_KEY.get(key);
  }

  /**
   * Returns the keys of every message.
   *
   * @return the keys, in the catalog's order
   */
  public static Set<String> keys() {
    return BY_KEY.keySet();
  }

  private static final Map<String, Message> BY_KEY;

  /** The newest version that brought a vanilla key Warp uses: clients from it know them all. */
  static final ProtocolVersion NEWEST_VANILLA;

  static {
    Map<String, Message> byKey = new LinkedHashMap<>();
    ProtocolVersion newest = ProtocolVersion.oldest();
    for (Message message : values()) {
      byKey.put(message.key, message);
      ProtocolVersion since = message.vanillaSince;
      if (since != null && newest.isOlderThan(since)) {
        newest = since;
      }
    }
    BY_KEY = Collections.unmodifiableMap(byKey);
    NEWEST_VANILLA = newest;
  }
}
