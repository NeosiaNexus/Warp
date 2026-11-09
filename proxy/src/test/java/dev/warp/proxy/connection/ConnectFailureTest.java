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

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.warp.proxy.metrics.ServerConnectOutcome;

import java.io.IOException;
import java.net.ConnectException;
import java.nio.channels.ClosedChannelException;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeoutException;

import io.netty.channel.ConnectTimeoutException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Server connect failure outcomes")
class ConnectFailureTest {

  @Test
  @DisplayName("should count a connect timeout as a timeout, not a refusal")
  void connectTimeout() {
    // Netty's ConnectTimeoutException is a ConnectException: the order of the checks matters.
    assertEquals(
        ServerConnectOutcome.TIMEOUT,
        ConnectedPlayer.connectFailure(new ConnectTimeoutException("connection timed out")));
    assertEquals(
        ServerConnectOutcome.TIMEOUT,
        ConnectedPlayer.connectFailure(new CompletionException(new TimeoutException())));
  }

  @Test
  @DisplayName("should count a refused connection, a reset and anything else")
  void otherOutcomes() {
    assertEquals(
        ServerConnectOutcome.REFUSED,
        ConnectedPlayer.connectFailure(new ConnectException("Connection refused")));
    assertEquals(
        ServerConnectOutcome.RESET,
        ConnectedPlayer.connectFailure(new CompletionException(new ClosedChannelException())));
    assertEquals(
        ServerConnectOutcome.RESET, ConnectedPlayer.connectFailure(new IOException("reset")));
    assertEquals(
        ServerConnectOutcome.OTHER, ConnectedPlayer.connectFailure(new IllegalStateException()));
    assertEquals(ServerConnectOutcome.OTHER, ConnectedPlayer.connectFailure(null));
  }
}
