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
package dev.warp.proxy.network;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.List;

import io.netty.util.NetUtil;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

@DisplayName("CidrRange")
class CidrRangeTest {

  // ---------------------------------------------------------------------------
  // Parsing
  // ---------------------------------------------------------------------------

  @Nested
  @DisplayName("parsing")
  class Parsing {

    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource({
      "10.0.0.0/8, 10.0.0.0/8",
      "10.0.0.1, 10.0.0.1/32",
      "0.0.0.0/0, 0.0.0.0/0",
      "192.168.1.128/25, 192.168.1.128/25",
      "2001:db8::/32, 2001:db8::/32",
      "2001:0DB8:0000::/48, 2001:db8::/48",
      "::1, ::1/128",
      "::/0, ::/0",
      "::ffff:10.0.0.0/104, 10.0.0.0/8",
      "::ffff:10.0.0.1, 10.0.0.1/32"
    })
    @DisplayName("should accept addresses and ranges and print them canonically")
    void canonical(String value, String expected) {
      CidrRange range = CidrRange.parse(value);

      assertEquals(expected, range.toString());
    }

    @Test
    @DisplayName("should treat an IPv4-mapped IPv6 range as the IPv4 range it maps")
    void mappedIsIpv4() {
      CidrRange range = CidrRange.parse("::ffff:192.0.2.0/120");

      assertTrue(range.isIpv4());
      assertEquals(24, range.prefixLength());
      assertEquals(CidrRange.parse("192.0.2.0/24"), range);
    }

    @ParameterizedTest(name = "[{0}]")
    @ValueSource(
        strings = {
          "",
          "localhost",
          "example.com/24",
          "10.0.0",
          "256.0.0.0/8",
          "10.0.0.0/",
          "10.0.0.0/33",
          "10.0.0.0/-1",
          "10.0.0.0/+8",
          "10.0.0.0/8/8",
          "10.0.0.0/abc",
          "2001:db8::/129",
          " 10.0.0.0/8"
        })
    @DisplayName("should refuse what is not an IP literal with a valid prefix")
    void invalid(String value) {
      IllegalArgumentException error =
          assertThrows(IllegalArgumentException.class, () -> CidrRange.parse(value));

      assertTrue(
          String.valueOf(error.getMessage()).contains("'" + value + "'"),
          String.valueOf(error.getMessage()));
    }

    @Test
    @DisplayName("should refuse a range with address bits past its prefix and name the right one")
    void hostBitsSet() {
      IllegalArgumentException error =
          assertThrows(IllegalArgumentException.class, () -> CidrRange.parse("10.1.2.3/8"));

      assertTrue(
          String.valueOf(error.getMessage()).contains("10.0.0.0/8"),
          String.valueOf(error.getMessage()));
    }

    @Test
    @DisplayName("should refuse an IPv6 range with address bits past its prefix")
    void ipv6HostBitsSet() {
      IllegalArgumentException error =
          assertThrows(IllegalArgumentException.class, () -> CidrRange.parse("2001:db8::1/64"));

      assertTrue(
          String.valueOf(error.getMessage()).contains("2001:db8::/64"),
          String.valueOf(error.getMessage()));
    }

    @Test
    @DisplayName("should compare ranges by network and prefix")
    void equality() {
      assertEquals(CidrRange.parse("10.0.0.0/8"), CidrRange.parse("10.0.0.0/8"));
      assertEquals(
          CidrRange.parse("10.0.0.0/8").hashCode(), CidrRange.parse("10.0.0.0/8").hashCode());
      assertNotEquals(CidrRange.parse("10.0.0.0/8"), CidrRange.parse("10.0.0.0/9"));
      assertNotEquals(CidrRange.parse("0.0.0.0/0"), CidrRange.parse("::/0"));
    }
  }

  // ---------------------------------------------------------------------------
  // IPv4 matching
  // ---------------------------------------------------------------------------

  @Nested
  @DisplayName("IPv4 matching")
  class Ipv4Matching {

    @ParameterizedTest(name = "{0} in {1}: {2}")
    @CsvSource({
      "10.0.0.0, 10.0.0.0/8, true",
      "10.255.255.255, 10.0.0.0/8, true",
      "11.0.0.0, 10.0.0.0/8, false",
      "9.255.255.255, 10.0.0.0/8, false",
      "192.168.1.200, 192.168.1.128/25, true",
      "192.168.1.127, 192.168.1.128/25, false",
      "203.0.113.7, 203.0.113.7, true",
      "203.0.113.8, 203.0.113.7, false",
      "1.2.3.4, 0.0.0.0/0, true",
      "255.255.255.255, 0.0.0.0/0, true",
      "172.16.0.1, 172.16.0.0/12, true",
      "172.32.0.1, 172.16.0.0/12, false"
    })
    @DisplayName("should match the addresses of the range only")
    void contains(String address, String range, boolean expected) {
      assertEquals(expected, CidrRange.parse(range).contains(address(address)));
    }

    @Test
    @DisplayName("should match an IPv4-mapped IPv6 address as its IPv4 address")
    void mappedAddress() throws UnknownHostException {
      CidrRange range = CidrRange.parse("10.0.0.0/8");

      // Built by hand: InetAddress turns a mapped address into an Inet4Address.
      byte[] mapped = NetUtil.createByteArrayFromIpAddressString("::ffff:10.1.2.3");
      Inet6Address inet6 = Inet6Address.getByAddress(null, mapped, -1);

      assertTrue(range.contains(inet6));
      assertFalse(CidrRange.parse("11.0.0.0/8").contains(inet6));
    }

    @Test
    @DisplayName("should not match IPv6 addresses")
    void notIpv6() {
      assertFalse(CidrRange.parse("0.0.0.0/0").contains(address("2001:db8::1")));
      assertFalse(CidrRange.parse("0.0.0.0/0").contains(address("::1")));
    }
  }

  // ---------------------------------------------------------------------------
  // IPv6 matching
  // ---------------------------------------------------------------------------

  @Nested
  @DisplayName("IPv6 matching")
  class Ipv6Matching {

    @ParameterizedTest(name = "{0} in {1}: {2}")
    @CsvSource({
      "2001:db8::1, 2001:db8::/32, true",
      "2001:db8:ffff:ffff:ffff:ffff:ffff:ffff, 2001:db8::/32, true",
      "2001:db9::, 2001:db8::/32, false",
      "2001:db8:0:0:8000::, 2001:db8::/65, false",
      "2001:db8:0:0:7fff::, 2001:db8::/65, true",
      "2001:db8::1:2, 2001:db8::1:0/112, true",
      "2001:db8::2:0, 2001:db8::1:0/112, false",
      "::1, ::1, true",
      "::2, ::1, false",
      "fe80::1, ::/0, true",
      "fd12:3456::1, fc00::/7, true",
      "fe00::1, fc00::/7, false"
    })
    @DisplayName("should match the addresses of the range only")
    void contains(String address, String range, boolean expected) {
      assertEquals(expected, CidrRange.parse(range).contains(address(address)));
    }

    @Test
    @DisplayName("should match an IPv4 address against an IPv6 range through its mapped form")
    void ipv4InMappedRange() {
      CidrRange mappedSpace = CidrRange.parse("::ffff:0:0/96");
      CidrRange everything = CidrRange.parse("::/0");

      assertTrue(mappedSpace.isIpv4(), "a /96 of the mapped space is IPv4's every address");
      assertTrue(mappedSpace.contains(address("198.51.100.1")));
      assertTrue(everything.contains(address("198.51.100.1")));
      assertFalse(CidrRange.parse("2001:db8::/32").contains(address("198.51.100.1")));
    }
  }

  // ---------------------------------------------------------------------------
  // Lists
  // ---------------------------------------------------------------------------

  @Nested
  @DisplayName("CidrRanges")
  class Lists {

    @Test
    @DisplayName("should contain an address when any of its ranges does")
    void anyRange() {
      CidrRanges ranges = CidrRanges.parse(List.of("10.0.0.0/8", "2001:db8::/32", "192.0.2.1"));

      assertTrue(ranges.contains(address("10.9.8.7")));
      assertTrue(ranges.contains(address("2001:db8::42")));
      assertTrue(ranges.contains(address("192.0.2.1")));
      assertFalse(ranges.contains(address("192.0.2.2")));
      assertFalse(ranges.contains(address("2001:db9::1")));
      assertEquals("[10.0.0.0/8, 2001:db8::/32, 192.0.2.1/32]", ranges.toString());
    }

    @Test
    @DisplayName("should contain no address when empty")
    void empty() {
      assertTrue(CidrRanges.none().isEmpty());
      assertFalse(CidrRanges.none().contains(address("127.0.0.1")));
      assertTrue(CidrRanges.parse(List.of()).isEmpty());
    }

    @Test
    @DisplayName("should refuse the whole list when one entry is invalid, naming it")
    void invalidEntry() {
      IllegalArgumentException error =
          assertThrows(
              IllegalArgumentException.class,
              () -> CidrRanges.parse(List.of("10.0.0.0/8", "lb.example.com")));

      assertTrue(
          String.valueOf(error.getMessage()).contains("lb.example.com"),
          String.valueOf(error.getMessage()));
    }
  }

  // ---------------------------------------------------------------------------
  // Helpers
  // ---------------------------------------------------------------------------

  /** Returns the address of an IP literal, without any name lookup. */
  private static InetAddress address(String literal) {
    try {
      return InetAddress.getByAddress(NetUtil.createByteArrayFromIpAddressString(literal));
    } catch (UnknownHostException e) {
      throw new AssertionError(e);
    }
  }
}
