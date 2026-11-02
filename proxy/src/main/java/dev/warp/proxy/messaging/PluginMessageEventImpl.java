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

import dev.warp.api.messaging.ChannelIdentifier;
import dev.warp.api.messaging.PluginMessageEvent;
import dev.warp.api.player.Player;

import java.util.Objects;

/** {@link PluginMessageEvent} as the proxy fires it. */
public final class PluginMessageEventImpl implements PluginMessageEvent {

  private final ChannelIdentifier channel;
  private final Source source;
  private final Player player;
  private final byte[] data;
  private volatile Result result = new Handled();

  /**
   * Creates the event.
   *
   * @param channel the channel
   * @param source who sent the message
   * @param player the player it travels for
   * @param data the payload, not copied: the proxy hands over its own array
   */
  public PluginMessageEventImpl(
      ChannelIdentifier channel, Source source, Player player, byte[] data) {
    this.channel = channel;
    this.source = source;
    this.player = player;
    this.data = data;
  }

  @Override
  public ChannelIdentifier channel() {
    return channel;
  }

  @Override
  public Source source() {
    return source;
  }

  @Override
  public Player player() {
    return player;
  }

  @Override
  public byte[] data() {
    return data.clone();
  }

  @Override
  public Result result() {
    return result;
  }

  @Override
  public void setResult(Result result) {
    this.result = Objects.requireNonNull(result, "result");
  }
}
