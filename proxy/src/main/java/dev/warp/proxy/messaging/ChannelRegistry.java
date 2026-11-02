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

import dev.warp.api.event.Subscription;
import dev.warp.api.messaging.ChannelIdentifier;
import dev.warp.api.messaging.Messaging.Origin;
import dev.warp.protocol.packet.play.PlayPluginMessage;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.jspecify.annotations.Nullable;

/**
 * The plugin message channels plugins own, and the one the proxy speaks itself (the BungeeCord
 * channel, unless disabled). Looked up for every plugin message the proxy sees: one hash lookup,
 * and messages on channels nobody speaks pass through.
 *
 * <p>A plugin may register a channel the proxy speaks: it then takes it over, and the proxy speaks
 * it again once the registration is closed.
 */
public final class ChannelRegistry {

  private final Map<String, Registration> channels = new ConcurrentHashMap<>();
  private final BungeeCordChannel bungeeCord;

  /** Creates a registry where the proxy speaks no channel itself. */
  public ChannelRegistry() {
    this(BungeeCordChannel.disabled());
  }

  /**
   * Creates a registry where the proxy answers the BungeeCord channel, unless it is disabled.
   *
   * @param bungeeCord the handler of the BungeeCord channel
   */
  public ChannelRegistry(BungeeCordChannel bungeeCord) {
    this.bungeeCord = bungeeCord;
    if (bungeeCord.enabled()) {
      // The proxy reads the backends' messages on its own channel; every other stays blind.
      PlayPluginMessage.WATCHED_CHANNELS.add(BungeeCordChannel.LEGACY_NAME);
      PlayPluginMessage.WATCHED_CHANNELS.add(BungeeCordChannel.MODERN_NAME);
    }
  }

  /**
   * Returns the handler of the BungeeCord channel.
   *
   * @return the handler, which answers nothing when the channel is disabled
   */
  public BungeeCordChannel bungeeCord() {
    return bungeeCord;
  }

  /**
   * A channel and its owner.
   *
   * @param channel the channel
   * @param owner the identifier of the plugin that owns it
   * @param accepted the sides it accepts messages from
   */
  public record Registration(ChannelIdentifier channel, String owner, Set<Origin> accepted) {

    /**
     * Copies the accepted sides.
     *
     * @param channel the channel
     * @param owner the identifier of the plugin that owns it
     * @param accepted the sides it accepts messages from
     */
    public Registration {
      accepted = Set.copyOf(accepted);
    }

    /**
     * Returns whether the owner accepts messages from a side.
     *
     * @param origin the side
     * @return {@code true} if accepted
     */
    public boolean accepts(Origin origin) {
      return accepted.contains(origin);
    }
  }

  /**
   * Gives a channel to a plugin.
   *
   * @param owner the plugin's identifier
   * @param channel the channel
   * @param accepted the sides it accepts messages from
   * @return the registration, which releases the channel when closed
   * @throws IllegalStateException if another plugin owns it
   * @throws IllegalArgumentException if no side is accepted
   */
  public Subscription register(String owner, ChannelIdentifier channel, Set<Origin> accepted) {
    if (accepted.isEmpty()) {
      throw new IllegalArgumentException(
          "Channel " + channel + " must accept messages from players, servers or both");
    }
    Registration registration = new Registration(channel, owner, EnumSet.copyOf(accepted));
    Registration existing = channels.putIfAbsent(channel.id(), registration);
    if (existing != null) {
      throw new IllegalStateException(
          "Channel " + channel + " is already registered by plugin " + existing.owner());
    }
    // Backends' messages are only read on owned channels: the others are forwarded untouched.
    PlayPluginMessage.WATCHED_CHANNELS.add(channel.id());
    return new Subscription() {
      private volatile boolean active = true;

      @Override
      public boolean isActive() {
        return active;
      }

      @Override
      public void unsubscribe() {
        if (active) {
          active = false;
          if (channels.remove(channel.id(), registration) && !speaksItself(channel.id())) {
            PlayPluginMessage.WATCHED_CHANNELS.remove(channel.id());
          }
        }
      }
    };
  }

  /**
   * Returns the registration of a channel.
   *
   * @param wireName the channel's name as it came on the wire
   * @return the registration, or {@code null} if nobody owns the channel
   */
  public @Nullable Registration lookup(String wireName) {
    return channels.get(wireName);
  }

  /** Whether the proxy reads a channel for itself, whoever owns it. */
  private boolean speaksItself(String wireName) {
    return bungeeCord.enabled() && BungeeCordChannel.isChannel(wireName);
  }
}
