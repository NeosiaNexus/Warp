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
package dev.warp.proxy.transfer;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.warp.protocol.packet.login.LoginSuccess;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("HandoffTicket")
class HandoffTicketTest {

  private static final UUID STEVE = UUID.fromString("8667ba71-b85a-4004-af54-457a9734eed7");
  private static final byte[] PROOF = {1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16};

  private static InetAddress address(String literal) {
    try {
      return InetAddress.getByName(literal);
    } catch (UnknownHostException e) {
      throw new AssertionError(e);
    }
  }

  private static HandoffTicket ticket(
      List<LoginSuccess.Property> properties,
      @Nullable InetAddress clientAddress,
      @Nullable String server,
      boolean authenticated,
      boolean trusted,
      byte[] proof) {
    return new HandoffTicket(
        STEVE,
        "Steve",
        properties,
        769,
        clientAddress,
        server,
        "warp-1:25565",
        1_800_000_000_000L,
        1_800_000_015_000L,
        authenticated,
        trusted,
        "play.warp.dev",
        25565,
        0x0123_4567_89AB_CDEFL,
        0xFEDC_BA98_7654_3210L,
        proof);
  }

  private static HandoffTicket full() {
    return ticket(
        List.of(
            new LoginSuccess.Property("textures", "dmFsdWU=", "c2lnbmF0dXJl"),
            new LoginSuccess.Property("unsigned", "", null)),
        address("203.0.113.9"),
        "survival",
        true,
        true,
        PROOF);
  }

  private static void assertSame(HandoffTicket expected, @Nullable HandoffTicket actual) {
    assertNotNull(actual);
    assertEquals(expected.uuid(), actual.uuid());
    assertEquals(expected.name(), actual.name());
    assertEquals(expected.properties(), actual.properties());
    assertEquals(expected.protocol(), actual.protocol());
    assertEquals(expected.clientAddress(), actual.clientAddress());
    assertEquals(expected.server(), actual.server());
    assertEquals(expected.sourceNode(), actual.sourceNode());
    assertEquals(expected.issuedAtMillis(), actual.issuedAtMillis());
    assertEquals(expected.expiresAtMillis(), actual.expiresAtMillis());
    assertEquals(expected.authenticated(), actual.authenticated());
    assertEquals(expected.trusted(), actual.trusted());
    assertEquals(expected.targetHost(), actual.targetHost());
    assertEquals(expected.targetPort(), actual.targetPort());
    assertEquals(expected.nonceHigh(), actual.nonceHigh());
    assertEquals(expected.nonceLow(), actual.nonceLow());
    assertArrayEquals(expected.proof(), actual.proof());
  }

  @Nested
  @DisplayName("encoding and decoding")
  class RoundTrip {

    @Test
    @DisplayName("should read back every field it wrote")
    void everyField() {
      HandoffTicket ticket = full();

      assertSame(ticket, HandoffTicket.decode(ticket.encode()));
    }

    @Test
    @DisplayName("should read back a ticket with nothing optional: no server, address or proof")
    void nothingOptional() {
      HandoffTicket ticket = ticket(List.of(), null, null, false, false, new byte[0]);

      HandoffTicket decoded = HandoffTicket.decode(ticket.encode());

      assertSame(ticket, decoded);
      assertNotNull(decoded);
      assertNull(decoded.server());
      assertNull(decoded.clientAddress());
      assertFalse(decoded.authenticated());
      assertFalse(decoded.trusted());
    }

    @Test
    @DisplayName("should keep the two flags apart")
    void flags() {
      HandoffTicket onlyAuthenticated =
          HandoffTicket.decode(ticket(List.of(), null, null, true, false, PROOF).encode());
      HandoffTicket onlyTrusted =
          HandoffTicket.decode(ticket(List.of(), null, null, false, true, PROOF).encode());

      assertNotNull(onlyAuthenticated);
      assertNotNull(onlyTrusted);
      assertTrue(onlyAuthenticated.authenticated());
      assertFalse(onlyAuthenticated.trusted());
      assertFalse(onlyTrusted.authenticated());
      assertTrue(onlyTrusted.trusted());
    }

    @Test
    @DisplayName("should read back an IPv6 client address")
    void ipv6() {
      HandoffTicket ticket = ticket(List.of(), address("2001:db8::7"), "lobby", true, false, PROOF);

      assertSame(ticket, HandoffTicket.decode(ticket.encode()));
    }

    @Test
    @DisplayName("should read back the largest port and the most properties a ticket carries")
    void bounds() {
      List<LoginSuccess.Property> properties = new ArrayList<>();
      for (int i = 0; i < HandoffTicket.MAX_PROPERTIES; i++) {
        properties.add(new LoginSuccess.Property("p" + i, "v" + i, i % 2 == 0 ? "s" + i : null));
      }
      HandoffTicket ticket =
          new HandoffTicket(
              STEVE,
              "Steve",
              properties,
              Integer.MAX_VALUE,
              null,
              "lobby",
              "",
              0,
              Long.MAX_VALUE,
              true,
              true,
              "",
              65_535,
              -1,
              Long.MIN_VALUE,
              new byte[255]);

      assertSame(ticket, HandoffTicket.decode(ticket.encode()));
    }

    @Test
    @DisplayName("should fail to write a string past 64 KiB, which no cookie holds anyway")
    void tooLong() {
      HandoffTicket ticket =
          ticket(
              List.of(new LoginSuccess.Property("textures", "x".repeat(70_000), null)),
              null,
              null,
              true,
              true,
              PROOF);

      assertThrows(UncheckedIOException.class, ticket::encode);
    }
  }

  @Nested
  @DisplayName("keeping its content its own")
  class Immutable {

    @Test
    @DisplayName("should copy the proof in and out")
    void proofCopied() {
      byte[] proof = PROOF.clone();
      HandoffTicket ticket = ticket(List.of(), null, null, true, true, proof);

      proof[0] = 99;
      ticket.proof()[1] = 99;

      assertArrayEquals(PROOF, ticket.proof());
      assertTrue(ticket.proofIs(PROOF));
    }

    @Test
    @DisplayName("should copy the properties")
    void propertiesCopied() {
      List<LoginSuccess.Property> properties = new ArrayList<>();
      properties.add(new LoginSuccess.Property("textures", "v", null));
      HandoffTicket ticket = ticket(properties, null, null, true, true, PROOF);

      properties.clear();

      assertEquals(1, ticket.properties().size());
      assertThrows(UnsupportedOperationException.class, () -> ticket.properties().clear());
    }

    @Test
    @DisplayName("should never print its proof or its nonce")
    void printed() {
      String printed = full().toString();

      assertEquals(
          "HandoffTicket[Steve (8667ba71-b85a-4004-af54-457a9734eed7) on survival from"
              + " warp-1:25565]",
          printed);
    }
  }

  @Nested
  @DisplayName("checking the proof")
  class Proof {

    @Test
    @DisplayName("should take its own proof, and no other")
    void matches() {
      HandoffTicket ticket = full();
      byte[] other = PROOF.clone();
      other[15] ^= 1;

      assertTrue(ticket.proofIs(PROOF.clone()));
      assertFalse(ticket.proofIs(other));
      assertFalse(ticket.proofIs(Arrays.copyOf(PROOF, 15)));
      assertFalse(ticket.proofIs(Arrays.copyOf(PROOF, 17)));
      assertFalse(ticket.proofIs(new byte[0]));
    }

    @Test
    @DisplayName("should never take an empty proof for the proof of a ticket without one")
    void emptyProof() {
      HandoffTicket ticket = ticket(List.of(), null, null, false, false, new byte[0]);

      assertFalse(ticket.proofIs(new byte[0]));
    }
  }

  @Nested
  @DisplayName("refusing what does not parse")
  class Malformed {

    @Test
    @DisplayName("should refuse every truncation of a body, without throwing")
    void truncated() {
      byte[] body = full().encode();

      for (int length = 0; length < body.length; length++) {
        assertNull(HandoffTicket.decode(Arrays.copyOf(body, length)), "length " + length);
      }
    }

    @Test
    @DisplayName("should refuse a body with bytes left over")
    void trailing() {
      byte[] body = full().encode();

      assertNull(HandoffTicket.decode(Arrays.copyOf(body, body.length + 1)));
    }

    @Test
    @DisplayName("should refuse more properties than a ticket carries")
    void tooManyProperties() throws IOException {
      assertNotNull(HandoffTicket.decode(body(4, HandoffTicket.MAX_PROPERTIES)));
      assertNull(HandoffTicket.decode(body(4, HandoffTicket.MAX_PROPERTIES + 1)));
    }

    @Test
    @DisplayName("should refuse a client address that is neither IPv4 nor IPv6")
    void badAddress() throws IOException {
      assertNotNull(HandoffTicket.decode(body(0, 0)));
      assertNotNull(HandoffTicket.decode(body(16, 0)));
      assertNull(HandoffTicket.decode(body(5, 0)));
      assertNull(HandoffTicket.decode(body(1, 0)));
    }

    @Test
    @DisplayName("should refuse a string that is not modified UTF-8")
    void badString() {
      byte[] body = ticket(List.of(), null, null, false, false, new byte[0]).encode();
      // The name follows two longs, the flags and the UUID: its first character.
      int name = 8 + 8 + 1 + 16 + 2;
      body[name] = (byte) 0xFF;

      assertNull(HandoffTicket.decode(body));
    }

    @Test
    @DisplayName("should never throw on random bytes, nor on a body with any byte changed")
    void fuzzed() {
      Random random = new Random(42);
      for (int i = 0; i < 2_000; i++) {
        byte[] garbage = new byte[random.nextInt(600)];
        random.nextBytes(garbage);
        HandoffTicket.decode(garbage);
      }
      byte[] body = full().encode();
      for (int i = 0; i < body.length; i++) {
        byte[] changed = body.clone();
        changed[i] ^= (byte) 0xFF;
        HandoffTicket.decode(changed);
      }
    }

    /** A body with an address of {@code addressLength} bytes and {@code properties} properties. */
    private static byte[] body(int addressLength, int properties) throws IOException {
      ByteArrayOutputStream bytes = new ByteArrayOutputStream();
      try (DataOutputStream out = new DataOutputStream(bytes)) {
        out.writeLong(1);
        out.writeLong(2);
        out.writeByte(3);
        out.writeLong(STEVE.getMostSignificantBits());
        out.writeLong(STEVE.getLeastSignificantBits());
        out.writeUTF("Steve");
        out.writeInt(769);
        out.writeByte(addressLength);
        out.write(new byte[addressLength]);
        out.writeBoolean(false);
        out.writeUTF("node");
        out.writeUTF("host");
        out.writeShort(25565);
        out.writeLong(4);
        out.writeLong(5);
        out.writeByte(0);
        out.writeByte(properties);
        for (int i = 0; i < properties; i++) {
          out.writeUTF("n");
          out.writeUTF("v");
          out.writeBoolean(false);
        }
      }
      return bytes.toByteArray();
    }
  }
}
