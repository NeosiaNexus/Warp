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
package dev.warp.proxy.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@DisplayName("WarpConfig fleet")
class FleetConfigTest {

  @TempDir private Path directory;

  private FleetConfig load(String content) throws ConfigurationException, IOException {
    Files.writeString(directory.resolve("warp.conf"), content, StandardCharsets.UTF_8);
    return WarpConfig.load(directory).fleet();
  }

  private String rejects(String content) {
    return String.valueOf(
        assertThrows(ConfigurationException.class, () -> load(content)).getMessage());
  }

  @Test
  @DisplayName("should default to no store: this proxy alone, with the design's timings")
  void defaults() throws Exception {
    FleetConfig fleet = load("online-mode = false\n");

    assertFalse(fleet.enabled());
    assertEquals(FleetConfig.StoreType.NONE, fleet.store().type());
    assertEquals("main", fleet.network());
    assertNull(fleet.nodeId());
    assertNull(fleet.publicAddress());
    assertEquals(Duration.ofSeconds(2), fleet.heartbeat());
    assertEquals(Duration.ofSeconds(10), fleet.heartbeatTtl());
    assertEquals(Duration.ofSeconds(30), fleet.reconcile());
    assertEquals(Duration.ofSeconds(2), fleet.claimTimeout());
    assertEquals(FleetConfig.OnStoreDown.ALLOW, fleet.onStoreDown());
    assertFalse(fleet.networkCount(), "no store: the count is this proxy's");
  }

  @Test
  @DisplayName("should write the fleet section, explained, in a new file")
  void defaultFile() throws Exception {
    WarpConfig config = WarpConfig.load(directory);

    String written = Files.readString(directory.resolve("warp.conf"), StandardCharsets.UTF_8);
    assertTrue(written.contains("\nfleet {\n"), written);
    assertTrue(written.contains("    type = none\n"), written);
    assertTrue(written.contains("  heartbeat-ttl = 10s\n"), written);
    assertEquals(FleetConfig.DEFAULTS.store().type(), config.fleet().store().type());
  }

  @Nested
  @DisplayName("valkey")
  class Valkey {

    @Test
    @DisplayName("should read the store, its address, credentials and the node's identity")
    void full() throws Exception {
      Path password = directory.resolve("pw");
      Files.writeString(password, "s3cret\n");
      FleetConfig fleet =
          load(
              """
              online-mode = false
              fleet {
                store {
                  type = valkey
                  valkey {
                    address = "valkey.internal:6380"
                    tls = true
                    username = "warp"
                    password-file = "%s"
                    database = 2
                  }
                }
                network = "prod"
                node-id = "proxy-1"
                public-address = "play.example.org:25565"
                heartbeat = 1s
                heartbeat-ttl = 5s
                reconcile = 10s
                claim-timeout = 500ms
                on-store-down = refuse-new-logins
                status { network-count = false }
              }
              """
                  .formatted(password.toString().replace("\\", "\\\\")));

      assertTrue(fleet.enabled());
      assertEquals(FleetConfig.StoreType.VALKEY, fleet.store().type());
      assertEquals("valkey.internal:6380", fleet.store().valkey().address());
      assertTrue(fleet.store().valkey().tls());
      assertEquals("warp", fleet.store().valkey().username());
      assertEquals("s3cret", fleet.store().valkey().password());
      assertEquals(2, fleet.store().valkey().database());
      assertEquals("prod", fleet.network());
      assertEquals("proxy-1", fleet.nodeId());
      assertEquals("play.example.org:25565", fleet.publicAddress());
      assertEquals(Duration.ofSeconds(1), fleet.heartbeat());
      assertEquals(Duration.ofSeconds(5), fleet.heartbeatTtl());
      assertEquals(Duration.ofSeconds(10), fleet.reconcile());
      assertEquals(Duration.ofMillis(500), fleet.claimTimeout());
      assertEquals(FleetConfig.OnStoreDown.REFUSE_NEW_LOGINS, fleet.onStoreDown());
      assertFalse(fleet.networkCount());
    }

    @Test
    @DisplayName("should show the network count by default once a store is configured")
    void networkCountDefault() throws Exception {
      FleetConfig fleet = load("online-mode = false\nfleet { store { type = valkey } }\n");

      assertTrue(fleet.networkCount());
      assertEquals("valkey:6379", fleet.store().valkey().address());
      assertNull(fleet.store().valkey().password());
    }
  }

  @Nested
  @DisplayName("validation")
  class Validation {

    @Test
    @DisplayName("should reject an unknown store type with the allowed ones")
    void storeType() {
      String e = rejects("online-mode = false\nfleet { store { type = redis } }\n");

      assertTrue(e.contains("fleet.store.type must be one of none, valkey"), e);
    }

    @Test
    @DisplayName("should reject a heartbeat ttl under twice the heartbeat")
    void ttl() {
      String e = rejects("online-mode = false\nfleet { heartbeat = 5s\n heartbeat-ttl = 8s }\n");

      assertTrue(e.contains("heartbeat-ttl must be at least twice"), e);
    }

    @Test
    @DisplayName("should reject an invalid network name, node id and public address")
    void names() {
      assertTrue(
          rejects("online-mode = false\nfleet { network = \"a b\" }\n").contains("fleet.network"));
      assertTrue(
          rejects("online-mode = false\nfleet { node-id = \"a/b\" }\n").contains("fleet.node-id"));
      assertTrue(
          rejects("online-mode = false\nfleet { public-address = \"host:99999\" }\n")
              .contains("port"));
    }

    @Test
    @DisplayName("should reject a zero duration and an unknown on-store-down")
    void durations() {
      assertTrue(
          rejects("online-mode = false\nfleet { reconcile = 0s }\n").contains("fleet.reconcile"));
      assertTrue(
          rejects("online-mode = false\nfleet { on-store-down = panic }\n")
              .contains("fleet.on-store-down must be one of allow, refuse-new-logins"));
    }

    @Test
    @DisplayName("should reject an unreadable password file")
    void passwordFile() {
      String e =
          rejects(
              "online-mode = false\nfleet { store { type = valkey\n valkey { password-file = \""
                  + directory.resolve("missing").toString().replace("\\", "\\\\")
                  + "\" } } }\n");

      assertTrue(e.contains("password-file"), e);
    }
  }
}
