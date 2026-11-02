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
package dev.warp.proxy.limit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.UnknownHostException;

import io.netty.util.NetUtil;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("AddressKey")
class AddressKeyTest {

  @Test
  @DisplayName("should key each IPv4 address apart, neighbours included")
  void ipv4() {
    assertEquals(key("203.0.113.7"), key("203.0.113.7"));
    assertNotEquals(key("203.0.113.7"), key("203.0.113.8"));
  }

  @Test
  @DisplayName("should key every address of an IPv6 /64 the same")
  void ipv6SameNetwork() {
    assertEquals(key("2001:db8:1:2::1"), key("2001:db8:1:2:ffff:ffff:ffff:ffff"));
  }

  @Test
  @DisplayName("should key two IPv6 /64 networks apart")
  void ipv6OtherNetwork() {
    assertNotEquals(key("2001:db8:1:2::1"), key("2001:db8:1:3::1"));
  }

  @Test
  @DisplayName("should key an IPv4-mapped IPv6 address as its IPv4 address")
  void mapped() throws UnknownHostException {
    byte[] mapped = NetUtil.createByteArrayFromIpAddressString("::ffff:203.0.113.7");

    assertEquals(key("203.0.113.7"), AddressKey.of(Inet6Address.getByAddress(null, mapped, -1)));
  }

  @Test
  @DisplayName("should not mix IPv4 addresses with IPv6 networks")
  void noCollision() {
    assertNotEquals(key("0.0.0.0"), key("::"));
    assertNotEquals(key("1.2.3.4"), key("::102:304"));
    assertNotEquals(key("255.255.255.255"), key("ffff:ffff::"));
  }

  private static long key(String literal) {
    try {
      return AddressKey.of(
          InetAddress.getByAddress(NetUtil.createByteArrayFromIpAddressString(literal)));
    } catch (UnknownHostException e) {
      throw new AssertionError(e);
    }
  }
}
