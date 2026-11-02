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
import dev.warp.api.messaging.Messaging;

import java.util.Set;
import java.util.function.Consumer;

/** One plugin's channels: registered under its name, released with it. */
public final class PluginMessaging implements Messaging {

  private final ChannelRegistry registry;
  private final String owner;
  private final Consumer<Subscription> tracker;

  /**
   * Creates a plugin's registrar.
   *
   * @param registry the proxy's channels
   * @param owner the plugin's identifier
   * @param tracker receives every registration, to release it when the plugin goes away
   */
  public PluginMessaging(ChannelRegistry registry, String owner, Consumer<Subscription> tracker) {
    this.registry = registry;
    this.owner = owner;
    this.tracker = tracker;
  }

  @Override
  public Subscription register(ChannelIdentifier channel, Set<Origin> accepted) {
    Subscription subscription = registry.register(owner, channel, accepted);
    tracker.accept(subscription);
    return subscription;
  }
}
