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
package dev.warp.protocol.netty;

import dev.warp.protocol.ProtocolState;
import dev.warp.protocol.ProtocolVersion;
import dev.warp.protocol.codec.McIdentifier;
import dev.warp.protocol.packet.cookie.CookiePayload;

import java.util.Arrays;
import java.util.concurrent.ConcurrentHashMap;

import org.jspecify.annotations.Nullable;

/**
 * The smallest and largest frame each packet a client may send before it is logged in can take, in
 * the HANDSHAKE, STATUS and LOGIN states, for {@link FrameDecoder} to refuse a frame from its
 * header: as soon as its length and packet identifier are readable, before a byte of the rest is
 * buffered.
 *
 * <p>Without these bounds, a connection that has not even sent a handshake can announce a frame of
 * 2 MiB (the protocol's 3-byte length) and trickle it in, making the proxy buffer all of it.
 * Velocity closed the same hole on 2026-10-06 ({@code 374acad83}); Warp's bounds are tighter where
 * the proxy knows more than the protocol:
 *
 * <ul>
 *   <li>A Login Plugin Response can carry 1 MiB, but only in answer to a Login Plugin Request, and
 *       Warp sends a client none: an answer to nothing is bounded by its empty form, 7 bytes.
 *   <li>A Cookie Response can carry an identifier of 32,767 characters and 5 KiB of payload (about
 *       37 KiB), but only in answer to a Cookie Request: it is refused until the proxy asks for a
 *       cookie during the login ({@link #serverbound(ProtocolState, ProtocolVersion, boolean)}).
 * </ul>
 *
 * <p>Every bound is the encoded size of the packet with each field at the limit Warp's own codec
 * reads, so a frame within it decodes or fails on its content, never on its size, and a frame
 * outside it could not have decoded. Packet identifiers in these states are below {@code 0x80}, a
 * single VarInt byte: anything else, an overlong encoding included, is a packet no client sends.
 *
 * <p>Instances are immutable and shared.
 */
public final class FrameLimits {

  // ---------------------------------------------------------------------------
  // Packet bounds, in bytes, packet identifier included
  // ---------------------------------------------------------------------------

  /** The packet identifier, one byte in every pre-login state. */
  private static final int ID = 1;

  /** The longest VarInt. */
  private static final int VARINT = 5;

  /** A UUID: two longs. */
  private static final int UUID = 16;

  /**
   * Handshake: protocol (VarInt), server address (a string of 255 characters, 765 bytes of UTF-8
   * and its 2-byte length), port (unsigned short), next state (VarInt).
   */
  static final int HANDSHAKE_MIN = ID + 1 + 1 + 2 + 1;

  static final int HANDSHAKE_MAX = ID + VARINT + 2 + 255 * 3 + 2 + VARINT;

  /** Status Request: the identifier alone. */
  static final int STATUS_REQUEST = ID;

  /** Ping Request: a long. */
  static final int PING_REQUEST = ID + 8;

  /** Login Start up to 1.18.2: a name of 16 characters (48 bytes of UTF-8 and a 1-byte length). */
  static final int LOGIN_START_MIN = ID + 1;

  static final int LOGIN_START_MAX = ID + 1 + 16 * 3;

  /**
   * The profile key of a 1.19 to 1.19.2 Login Start: its presence, an expiry (long), a public key
   * of 512 bytes and a signature of 4,096, each with a 2-byte length.
   */
  static final int PROFILE_KEY_MAX = 1 + 8 + 2 + 512 + 2 + 4096;

  /** Encryption Response: two arrays of at most 256 bytes, each with a 2-byte length. */
  static final int ENCRYPTION_RESPONSE_MAX = ID + 2 * (2 + 256);

  /**
   * Encryption Response of 1.19 to 1.19.2, signed form: the secret, a presence flag, a salt (long)
   * and a signature of at most 256 bytes.
   */
  static final int SIGNED_ENCRYPTION_RESPONSE_MAX = ID + 2 + 256 + 1 + 8 + 2 + 256;

  /** Login Plugin Response answering no request: message id (VarInt) and a success flag. */
  static final int LOGIN_PLUGIN_RESPONSE_MIN = ID + 1 + 1;

  static final int LOGIN_PLUGIN_RESPONSE_MAX = ID + VARINT + 1;

  /** Login Acknowledged: the identifier alone. */
  static final int LOGIN_ACKNOWLEDGED = ID;

  /**
   * Cookie Response: an identifier (at most 32,767 characters, all ASCII, and a 3-byte length), a
   * presence flag, and a payload of at most {@link CookiePayload#MAX_SIZE} bytes with a 2-byte
   * length.
   */
  static final int COOKIE_RESPONSE_MIN = ID + 1 + 1;

  static final int COOKIE_RESPONSE_MAX =
      ID + 3 + McIdentifier.MAX_LENGTH + 1 + 2 + CookiePayload.MAX_SIZE;

  // ---------------------------------------------------------------------------
  // Shared instances
  // ---------------------------------------------------------------------------

  /** The HANDSHAKE state: the handshake alone, whatever the version. */
  public static final FrameLimits HANDSHAKE =
      new Builder(ProtocolState.HANDSHAKE).allow(0x00, HANDSHAKE_MIN, HANDSHAKE_MAX).build();

  /** The STATUS state: a request and a ping, whatever the version. */
  public static final FrameLimits STATUS =
      new Builder(ProtocolState.STATUS)
          .allow(0x00, STATUS_REQUEST, STATUS_REQUEST)
          .allow(0x01, PING_REQUEST, PING_REQUEST)
          .build();

  /** The LOGIN limits of each protocol, without and with cookie responses. */
  private static final ConcurrentHashMap<Integer, FrameLimits[]> LOGIN = new ConcurrentHashMap<>();

  private final ProtocolState state;

  /** Smallest frame of each packet identifier; unused where {@link #max} is 0. */
  private final int[] min;

  /** Largest frame of each packet identifier; 0 for a packet the state does not allow. */
  private final int[] max;

  private final int largest;

  private FrameLimits(ProtocolState state, int[] min, int[] max) {
    this.state = state;
    this.min = min;
    this.max = max;
    this.largest = Arrays.stream(max).max().orElse(0);
  }

  // ---------------------------------------------------------------------------
  // Lookup
  // ---------------------------------------------------------------------------

  /**
   * Returns the limits of what a client sends in {@code state}, refusing Cookie Responses.
   *
   * @param state the state the proxy reads the client in
   * @param version the client's protocol version, or any version before it is known
   * @return the limits, or {@code null} from CONFIGURATION on, where the client is logged in and
   *     frames are bounded by the protocol alone
   */
  public static @Nullable FrameLimits serverbound(ProtocolState state, ProtocolVersion version) {
    return serverbound(state, version, false);
  }

  /**
   * Returns the limits of what a client sends in {@code state}.
   *
   * @param state the state the proxy reads the client in
   * @param version the client's protocol version, or any version before it is known
   * @param cookieResponses whether the proxy asked the client for a cookie during its login, so
   *     that a Cookie Response (1.20.5+) is allowed in the LOGIN state
   * @return the limits, or {@code null} from CONFIGURATION on
   */
  public static @Nullable FrameLimits serverbound(
      ProtocolState state, ProtocolVersion version, boolean cookieResponses) {
    return switch (state) {
      case HANDSHAKE -> HANDSHAKE;
      case STATUS -> STATUS;
      case LOGIN ->
          LOGIN.computeIfAbsent(version.protocol(), _ -> login(version))[cookieResponses ? 1 : 0];
      case CONFIGURATION, PLAY -> null;
    };
  }

  private static FrameLimits[] login(ProtocolVersion version) {
    return new FrameLimits[] {login(version, false), login(version, true)};
  }

  private static FrameLimits login(ProtocolVersion version, boolean cookieResponses) {
    Builder builder = new Builder(ProtocolState.LOGIN);

    // Login Start: a UUID from 1.20.2, an optional one from 1.19.1, an optional key in 1.19.x.
    int loginStartMin = LOGIN_START_MIN;
    int loginStartMax = LOGIN_START_MAX;
    if (version.isAtLeast(ProtocolVersion.MINECRAFT_1_20_2)) {
      loginStartMin += UUID;
      loginStartMax += UUID;
    } else {
      boolean profileKey =
          version.isBetween(ProtocolVersion.MINECRAFT_1_19, ProtocolVersion.MINECRAFT_1_19_2);
      if (profileKey) {
        loginStartMin += 1;
        loginStartMax += PROFILE_KEY_MAX;
      }
      if (version.isAtLeast(ProtocolVersion.MINECRAFT_1_19_1)) {
        loginStartMin += 1;
        loginStartMax += 1 + UUID;
      }
    }
    builder.allow(0x00, loginStartMin, loginStartMax);

    // Encryption Response: two-byte array lengths before 1.8, VarInts after (an empty array then
    // takes one byte); a signed verify token in 1.19 to 1.19.2.
    int encryptionMin;
    int encryptionMax = ENCRYPTION_RESPONSE_MAX;
    if (version.isOlderThan(ProtocolVersion.MINECRAFT_1_8)) {
      encryptionMin = ID + 2 + 2;
    } else if (version.isBetween(
        ProtocolVersion.MINECRAFT_1_19, ProtocolVersion.MINECRAFT_1_19_2)) {
      encryptionMin = ID + 1 + 1 + 1;
      encryptionMax = SIGNED_ENCRYPTION_RESPONSE_MAX;
    } else {
      encryptionMin = ID + 1 + 1;
    }
    builder.allow(0x01, encryptionMin, encryptionMax);

    if (version.isAtLeast(ProtocolVersion.MINECRAFT_1_13)) {
      builder.allow(0x02, LOGIN_PLUGIN_RESPONSE_MIN, LOGIN_PLUGIN_RESPONSE_MAX);
    }
    if (version.isAtLeast(ProtocolVersion.MINECRAFT_1_20_2)) {
      builder.allow(0x03, LOGIN_ACKNOWLEDGED, LOGIN_ACKNOWLEDGED);
    }
    if (cookieResponses && version.isAtLeast(ProtocolVersion.MINECRAFT_1_20_5)) {
      builder.allow(0x04, COOKIE_RESPONSE_MIN, COOKIE_RESPONSE_MAX);
    }
    return builder.build();
  }

  // ---------------------------------------------------------------------------
  // Checks
  // ---------------------------------------------------------------------------

  /**
   * Returns the state these limits apply to.
   *
   * @return the state
   */
  public ProtocolState state() {
    return state;
  }

  /**
   * Returns the largest frame any packet allowed in the state can take: a longer frame is refused
   * from its length alone.
   *
   * @return the length, packet identifier included, length prefix excluded
   */
  public int largest() {
    return largest;
  }

  /**
   * Returns the largest frame of a packet, for tests and messages.
   *
   * @param packetId the identifier
   * @return the length, or 0 if the state does not allow the packet
   */
  public int largest(int packetId) {
    return allows(packetId) ? max[packetId] : 0;
  }

  /**
   * Returns whether the state allows a packet with this identifier.
   *
   * @param packetId the identifier, as its first byte reads it unsigned
   * @return {@code true} if a client may send it
   */
  public boolean allows(int packetId) {
    return packetId >= 0 && packetId < max.length && max[packetId] != 0;
  }

  /**
   * Returns whether a frame of {@code length} bytes can hold the packet {@code packetId}.
   *
   * @param packetId the identifier, as its first byte reads it unsigned
   * @param length the frame's length, packet identifier included
   * @return {@code true} if the packet is allowed and fits
   */
  public boolean allows(int packetId, int length) {
    return allows(packetId) && length >= min[packetId] && length <= max[packetId];
  }

  /**
   * Returns the smallest frame of a packet, for tests and messages.
   *
   * @param packetId the identifier
   * @return the length, or 0 if the state does not allow the packet
   */
  public int smallest(int packetId) {
    return allows(packetId) ? min[packetId] : 0;
  }

  @Override
  public String toString() {
    return "FrameLimits[" + state + ", largest " + largest + " bytes]";
  }

  /** Collects the bounds of one state. */
  private static final class Builder {

    private final ProtocolState state;
    private final int[] min = new int[8];
    private final int[] max = new int[8];
    private int count;

    Builder(ProtocolState state) {
      this.state = state;
    }

    Builder allow(int packetId, int smallest, int largestFrame) {
      min[packetId] = smallest;
      max[packetId] = largestFrame;
      count = Math.max(count, packetId + 1);
      return this;
    }

    FrameLimits build() {
      return new FrameLimits(state, Arrays.copyOf(min, count), Arrays.copyOf(max, count));
    }
  }
}
