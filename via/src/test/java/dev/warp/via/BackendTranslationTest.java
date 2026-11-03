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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.warp.api.network.BackendPipeline;
import dev.warp.api.server.RegisteredServer;
import dev.warp.via.BackendTranslation.Decision;

import java.util.List;

import com.viaversion.viaversion.api.protocol.ProtocolManager;
import com.viaversion.viaversion.api.protocol.ProtocolPathEntry;
import com.viaversion.viaversion.api.protocol.version.ProtocolVersion;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

@DisplayName("BackendTranslation")
class BackendTranslationTest {

  private final ProtocolManager protocols = mock(ProtocolManager.class);
  private final BackendVersions versions = mock(BackendVersions.class);
  private final BackendTranslation translation =
      new BackendTranslation(
          versions, () -> protocols, LoggerFactory.getLogger(BackendTranslationTest.class));

  @Test
  @DisplayName("should leave a connection alone when the player and the server versions match")
  void sameVersion() {
    assertEquals(
        Decision.NONE, translation.decide(ProtocolVersion.v1_21_4, ProtocolVersion.v1_21_4));
  }

  @Test
  @DisplayName("should leave a connection alone when the server's version is unknown")
  void unknownServer() {
    assertEquals(
        Decision.NONE, translation.decide(ProtocolVersion.v1_21_4, ProtocolVersion.unknown));
  }

  @Test
  @DisplayName("should translate when ViaVersion has a path between the two versions")
  void translates() {
    when(protocols.getProtocolPath(ProtocolVersion.v1_21_7, ProtocolVersion.v1_21_4))
        .thenReturn(List.of(mock(ProtocolPathEntry.class)));

    assertEquals(
        Decision.TRANSLATE, translation.decide(ProtocolVersion.v1_21_7, ProtocolVersion.v1_21_4));
  }

  @Test
  @DisplayName("should report a pair ViaVersion cannot translate, and leave it alone")
  void impossible() {
    assertEquals(
        Decision.IMPOSSIBLE, translation.decide(ProtocolVersion.v1_8, ProtocolVersion.v1_21_4));
  }

  @Test
  @DisplayName("should add no handler at all to a connection that needs no translation")
  void addsNothingWhenNotNeeded() {
    BackendPipeline pipeline = mock(BackendPipeline.class);
    RegisteredServer lobby = mock(RegisteredServer.class);
    when(lobby.name()).thenReturn("lobby");
    when(pipeline.server()).thenReturn(lobby);
    when(pipeline.protocolVersion()).thenReturn(769);
    when(versions.serverProtocolVersion("lobby")).thenReturn(ProtocolVersion.v1_21_4);

    translation.initialize(pipeline);

    verify(pipeline, never()).addHandler(any(), any(), any());
    verify(pipeline, never()).channel();
  }
}
