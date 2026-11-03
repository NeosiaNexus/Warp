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
package dev.warp.proxy.antibot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import dev.warp.api.antibot.Identity;

import java.net.InetAddress;
import java.util.Arrays;
import java.util.UUID;

import io.netty.util.NetUtil;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

@DisplayName("IdentityKeys")
class IdentityKeysTest {

  private static final byte[] SECRET = filled(1);
  private final IdentityKeys keys = new IdentityKeys(SECRET, 24, 48);

  @ParameterizedTest(name = "{0} -> {1}")
  @CsvSource({
    "203.0.113.77, 203.0.113.0/24",
    "::ffff:203.0.113.77, 203.0.113.0/24",
    "2001:db8:1:2:3:4:5:6, 2001:db8:1::/48",
    "2001:db8::1, 2001:db8::/48"
  })
  @DisplayName("should count an address as its network, IPv4-mapped as IPv4")
  void networks(String address, String network) {
    assertEquals(network, keys.network(address(address)));
  }

  @Test
  @DisplayName("should give a name the same key anywhere in its network, in any case")
  void sameNetwork() {
    assertEquals(
        keys.nameAt("Steve", address("203.0.113.7")),
        keys.nameAt("steve", address("203.0.113.200")));
    assertNotEquals(
        keys.nameAt("Steve", address("203.0.113.7")), keys.nameAt("Steve", address("203.0.114.7")));
    assertEquals(
        keys.key(new Identity.NameAtAddress("STEVE", address("203.0.113.9"))),
        keys.nameAt("steve", address("203.0.113.1")));
  }

  @Test
  @DisplayName("should keep accounts and names apart, and depend on the secret")
  void separated() {
    UUID uuid = UUID.fromString("069a79f4-44e9-4726-a5be-fca90e38aaf5");

    assertEquals(keys.account(uuid), keys.key(new Identity.Account(uuid)));
    assertNotEquals(keys.account(uuid), keys.nameAt(uuid.toString(), address("203.0.113.7")));
    assertNotEquals(keys.account(uuid), new IdentityKeys(filled(2), 24, 48).account(uuid));
  }

  private static byte[] filled(int value) {
    byte[] secret = new byte[32];
    Arrays.fill(secret, (byte) value);
    return secret;
  }

  private static InetAddress address(String literal) {
    try {
      return InetAddress.getByAddress(NetUtil.createByteArrayFromIpAddressString(literal));
    } catch (java.net.UnknownHostException e) {
      throw new AssertionError(e);
    }
  }
}
