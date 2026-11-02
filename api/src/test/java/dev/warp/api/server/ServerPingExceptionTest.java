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
package dev.warp.api.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.ConnectException;
import java.net.InetSocketAddress;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("ServerPingException")
class ServerPingExceptionTest {

  private static final ServerInfo LOBBY =
      new ServerInfo("lobby", InetSocketAddress.createUnresolved("lobby.internal", 25565));

  @Test
  @DisplayName("should carry the server, the reason and the cause, and name them in its message")
  void carriesDetails() {
    ConnectException cause = new ConnectException("Connection refused");

    ServerPingException exception =
        new ServerPingException(
            LOBBY, ServerPingException.Reason.UNREACHABLE, "cannot connect", cause);

    assertSame(LOBBY, exception.server());
    assertEquals(ServerPingException.Reason.UNREACHABLE, exception.reason());
    assertSame(cause, exception.getCause());
    String message = String.valueOf(exception.getMessage());
    assertTrue(message.contains("lobby"), message);
    assertTrue(message.contains("cannot connect"), message);
  }

  @Test
  @DisplayName("should be an IOException, so that I/O error handling catches it")
  void isIoException() {
    ServerPingException exception =
        new ServerPingException(LOBBY, ServerPingException.Reason.TIMED_OUT, "no answer", null);

    assertInstanceOf(IOException.class, exception);
  }
}
