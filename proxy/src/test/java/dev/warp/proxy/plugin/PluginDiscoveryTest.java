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
package dev.warp.proxy.plugin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.warp.api.plugin.Capability;
import dev.warp.api.plugin.PluginDescription;

import java.util.Set;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("PluginDiscovery")
class PluginDiscoveryTest {

  @Test
  @DisplayName("should read the capabilities a descriptor declares")
  void readsCapabilities() {
    PluginDescription description =
        PluginDiscovery.parse(
            JsonParser.parseString(
                "{\"id\": \"via\", \"main\": \"v.Main\", \"capabilities\": [\"network-pipeline\"]}"));

    assertEquals(Set.of(Capability.NETWORK_PIPELINE), description.capabilities());
  }

  @Test
  @DisplayName("should read a descriptor without capabilities as declaring none")
  void noCapabilities() {
    PluginDescription description =
        PluginDiscovery.parse(JsonParser.parseString("{\"id\": \"plain\", \"main\": \"p.Main\"}"));

    assertEquals(Set.of(), description.capabilities());
  }

  @Test
  @DisplayName("should refuse a capability this proxy does not know, naming it")
  void refusesUnknownCapability() {
    IllegalArgumentException error =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                PluginDiscovery.parse(
                    JsonParser.parseString(
                        "{\"id\": \"future\", \"main\": \"f.Main\", \"capabilities\":"
                            + " [\"packets\"]}")));

    assertTrue(String.valueOf(error.getMessage()).contains("'packets'"));
  }
}
