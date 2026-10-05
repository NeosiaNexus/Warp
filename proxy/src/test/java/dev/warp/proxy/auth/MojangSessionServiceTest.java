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
package dev.warp.proxy.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.warp.proxy.connection.GameProfile;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("MojangSessionService")
class MojangSessionServiceTest {

  private static final String PATH = "/session/minecraft/hasJoined";

  private HttpServer server;
  private final AtomicReference<String> lastQuery = new AtomicReference<>();
  private volatile int status = 200;
  private volatile String body = "";

  @BeforeEach
  void startServer() throws IOException {
    server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
    server.createContext(
        PATH,
        exchange -> {
          lastQuery.set(exchange.getRequestURI().getQuery());
          byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
          exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
          try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
          }
        });
    server.start();
  }

  @AfterEach
  void stopServer() {
    server.stop(0);
  }

  @Test
  @DisplayName("should query the configured endpoint and parse the profile")
  void customEndpoint() throws AuthenticationException {
    body =
        """
        {"id":"069a79f444e94726a5befca90e38aaf5","name":"Notch","properties":[
          {"name":"textures","value":"dGV4dHVyZXM=","signature":"c2lnbmF0dXJl"}]}
        """;

    GameProfile profile = service().hasJoined("Notch", "-1a2b");

    assertEquals("username=Notch&serverId=-1a2b", lastQuery.get());
    assertEquals(UUID.fromString("069a79f4-44e9-4726-a5be-fca90e38aaf5"), profile.uuid());
    assertEquals("Notch", profile.name());
    assertEquals(1, profile.properties().size());
    assertEquals("textures", profile.properties().get(0).name());
  }

  @Test
  @DisplayName("should reject a player who did not authenticate (204 No Content)")
  void notAuthenticated() {
    status = 204;
    assertThrows(AuthenticationException.class, () -> service().hasJoined("Notch", "hash"));
  }

  @Test
  @DisplayName("should report server errors as authentication failures")
  void serverError() {
    status = 503;
    body = "unavailable";
    assertThrows(AuthenticationException.class, () -> service().hasJoined("Notch", "hash"));
  }

  private MojangSessionService service() {
    return new MojangSessionService("http://127.0.0.1:" + server.getAddress().getPort() + PATH);
  }
}
