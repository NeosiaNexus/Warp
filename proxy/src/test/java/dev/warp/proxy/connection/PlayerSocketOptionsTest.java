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

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import io.netty.channel.Channel;
import io.netty.channel.ChannelConfig;
import io.netty.channel.ChannelException;
import io.netty.channel.ChannelOption;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("PlayerSocketOptions")
class PlayerSocketOptionsTest {

  private final Channel channel = mock(Channel.class);
  private final ChannelConfig config = mock(ChannelConfig.class);

  @Test
  @DisplayName("should turn Nagle off, keep-alive on and mark the traffic low latency")
  void setsThePlayerOptions() {
    when(channel.config()).thenReturn(config);

    PlayerSocketOptions.apply(channel);

    verify(config).setOption(ChannelOption.TCP_NODELAY, true);
    verify(config).setOption(ChannelOption.SO_KEEPALIVE, true);
    verify(config).setOption(ChannelOption.IP_TOS, 0x18);
    verifyNoMoreInteractions(config);
  }

  @Test
  @DisplayName("should leave a socket that closed meanwhile to whoever sees the close")
  void toleratesAClosedSocket() {
    when(channel.config()).thenReturn(config);
    when(config.setOption(any(), any())).thenThrow(new ChannelException("closed"));

    assertDoesNotThrow(() -> PlayerSocketOptions.apply(channel));
  }
}
