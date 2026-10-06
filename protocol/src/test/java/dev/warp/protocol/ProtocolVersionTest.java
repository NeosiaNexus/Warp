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
package dev.warp.protocol;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

@DisplayName("ProtocolVersion")
class ProtocolVersionTest {

  @Nested
  @DisplayName("registry")
  class Registry {

    @Test
    @DisplayName("should list the versions in release order")
    void releaseOrder() {
      List<ProtocolVersion> versions = ProtocolVersion.values();

      List<String> outOfOrder = new ArrayList<>();
      for (int i = 1; i < versions.size(); i++) {
        if (versions.get(i).isOlderThan(versions.get(i - 1))) {
          outOfOrder.add(versions.get(i - 1).name() + " before " + versions.get(i).name());
        }
      }

      assertTrue(outOfOrder.isEmpty(), () -> String.join(", ", outOfOrder));
    }

    @Test
    @DisplayName("should register each game version once")
    void uniqueNames() {
      Set<String> names = new HashSet<>();

      List<String> duplicates =
          ProtocolVersion.values().stream()
              .map(ProtocolVersion::name)
              .filter(n -> !names.add(n))
              .toList();

      assertTrue(duplicates.isEmpty(), () -> String.join(", ", duplicates));
    }

    @ParameterizedTest(name = "{0} speaks protocol {1}")
    @CsvSource({"26.1, 775", "26.1.1, 775", "26.1.2, 775", "26.2, 776", "26.3, 777"})
    @DisplayName("should give each 26.x release the protocol its client jar declares")
    void protocolsOf26(String name, int protocol) {
      ProtocolVersion version =
          ProtocolVersion.values().stream()
              .filter(v -> v.name().equals(name))
              .findFirst()
              .orElseThrow();

      assertEquals(protocol, version.protocol());
    }
  }

  @Nested
  @DisplayName("byProtocolId")
  class ByProtocolId {

    @Test
    @DisplayName("should find 26.2 and 26.3 by their protocol")
    void newestProtocols() {
      assertSame(ProtocolVersion.MINECRAFT_26_2, ProtocolVersion.byProtocolId(776));
      assertSame(ProtocolVersion.MINECRAFT_26_3, ProtocolVersion.byProtocolId(777));
    }

    @Test
    @DisplayName("should return the first game version of a shared protocol")
    void sharedProtocol() {
      assertSame(ProtocolVersion.MINECRAFT_26_1, ProtocolVersion.byProtocolId(775));
    }

    @Test
    @DisplayName("should not know a protocol no release has")
    void unknownProtocol() {
      assertNull(ProtocolVersion.byProtocolId(ProtocolVersion.latest().protocol() + 1));
    }
  }
}
