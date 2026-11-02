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
package dev.warp.via;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.warp.api.server.RegisteredServer;
import dev.warp.api.server.ServerPing;
import dev.warp.api.server.ServerRegistry;

import java.io.IOException;
import java.net.ConnectException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

import com.viaversion.viaversion.api.protocol.version.ProtocolVersion;
import net.kyori.adventure.text.Component;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

@DisplayName("BackendVersions")
class BackendVersionsTest {

  @TempDir private Path directory;

  private final ServerRegistry registry = mock(ServerRegistry.class);
  private final RegisteredServer lobby = server("lobby");
  private final RegisteredServer survival = server("survival");
  private final RegisteredServer modded = server("modded");
  private BackendVersions versions;

  @BeforeEach
  void setUp() throws IOException {
    Files.writeString(
        directory.resolve("servers.yml"),
        """
        servers:
          lobby: auto
          survival: "1.21.4"
          modded: off
        """,
        StandardCharsets.UTF_8);
    ServerVersions settings =
        new ServerVersions(directory.resolve("servers.yml").toFile(), Logger.getAnonymousLogger());
    settings.reload();
    when(registry.servers()).thenReturn(List.of(lobby, survival, modded));
    versions =
        new BackendVersions(registry, settings, LoggerFactory.getLogger(BackendVersionsTest.class));
  }

  @Nested
  @DisplayName("lookup")
  class Lookup {

    @Test
    @DisplayName("should use the version set for a server, whatever it answers")
    void pinnedWins() {
      versions.setProtocolVersion("survival", 772);

      assertEquals(ProtocolVersion.v1_21_4, versions.serverProtocolVersion("survival"));
    }

    @Test
    @DisplayName("should know nothing of an auto server it never asked: no guessed version")
    void unknownUntilAsked() {
      assertFalse(versions.serverProtocolVersion("lobby").isKnown());
    }

    @Test
    @DisplayName("should know nothing of a server set to off")
    void offIsUnknown() {
      versions.setProtocolVersion("modded", 769);

      assertFalse(versions.serverProtocolVersion("modded").isKnown());
      assertTrue(versions.isOff("modded"));
    }
  }

  @Nested
  @DisplayName("detection")
  class Detection {

    @Test
    @DisplayName("should ask an unknown server once, announcing no version, before a connection")
    void asksOnceWithoutVersion() throws Exception {
      CompletableFuture<ServerPing> answer = new CompletableFuture<>();
      when(lobby.ping(anyInt())).thenReturn(answer);

      CompletableFuture<ProtocolVersion> first = versions.ensureKnown(lobby);
      CompletableFuture<ProtocolVersion> second = versions.ensureKnown(lobby);
      answer.complete(ping(769));

      assertSame(first, second);
      assertEquals(ProtocolVersion.v1_21_4, first.get(1, TimeUnit.SECONDS));
      assertEquals(ProtocolVersion.v1_21_4, versions.serverProtocolVersion("lobby"));
      verify(lobby, times(1)).ping(BackendVersions.UNKNOWN_PROTOCOL);
      verify(lobby, never()).ping();
    }

    @Test
    @DisplayName("should not ask a server whose version is set, or known already")
    void doesNotAskKnownServers() throws Exception {
      versions.setProtocolVersion("lobby", 772);

      assertEquals(ProtocolVersion.v1_21_4, versions.ensureKnown(survival).get());
      assertEquals(ProtocolVersion.v1_21_7, versions.ensureKnown(lobby).get());
      verify(survival, never()).ping(anyInt());
      verify(lobby, never()).ping(anyInt());
    }

    @Test
    @DisplayName("should leave the version unknown when the server does not answer, never failing")
    void unreachable() throws Exception {
      when(lobby.ping(anyInt()))
          .thenReturn(CompletableFuture.failedFuture(new ConnectException("refused")));

      assertFalse(versions.ensureKnown(lobby).get(1, TimeUnit.SECONDS).isKnown());
      assertFalse(versions.serverProtocolVersion("lobby").isKnown());
    }

    @Test
    @DisplayName("should not record a server that answers with no version")
    void ignoresNegativeVersions() {
      assertFalse(versions.record("lobby", -1, "?").isKnown());
      assertFalse(versions.detectedProtocolVersions().containsKey("lobby"));
    }

    @Test
    @DisplayName("should probe only the auto servers, and forget servers no longer registered")
    void probesAutoServers() {
      when(lobby.ping(anyInt())).thenReturn(CompletableFuture.completedFuture(ping(773)));
      versions.setProtocolVersion("gone", 769);

      versions.probeAllServers();

      verify(lobby).ping(BackendVersions.UNKNOWN_PROTOCOL);
      verify(survival, never()).ping(anyInt());
      verify(modded, never()).ping(anyInt());
      assertEquals(773, versions.detectedProtocolVersions().getInt("lobby"));
      assertFalse(versions.detectedProtocolVersions().containsKey("gone"));
    }

    @Test
    @DisplayName("should forget a detected version when asked to")
    void uncaches() {
      versions.setProtocolVersion("lobby", 769);

      assertEquals(769, versions.uncacheProtocolVersion("lobby"));
      assertEquals(-1, versions.uncacheProtocolVersion("lobby"));
    }
  }

  private static RegisteredServer server(String name) {
    RegisteredServer server = mock(RegisteredServer.class);
    when(server.name()).thenReturn(name);
    return server;
  }

  private static ServerPing ping(int protocol) {
    return new ServerPing(
        new ServerPing.Version("Paper", protocol), null, Component.text("motd"), null);
  }
}
