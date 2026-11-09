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

import dev.warp.protocol.packet.login.LoginSuccess;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.jspecify.annotations.Nullable;

/**
 * What a handoff ticket says, once opened: who the player is, as the proxy that wrote it
 * authenticated it, where it played, and for which transfer the ticket was written.
 *
 * @param uuid the player's UUID
 * @param name the player's name
 * @param properties the profile's properties, Mojang's signatures included (the skin)
 * @param server the backend the player was on, or {@code null} if it was on none
 * @param sourceNode the proxy that wrote the ticket, for the logs
 * @param issuedAtMillis when it was written, in epoch milliseconds
 * @param expiresAtMillis when it stops being valid, in epoch milliseconds
 * @param authenticated whether the writer authenticated the player with Mojang (online mode), or
 *     took it from a handoff that had: only then may the reader skip Mojang
 * @param trusted whether the writer's anti-bot trusts the account
 * @param targetHost the host of the transfer, cleaned as handshake hosts are
 * @param targetPort the port of the transfer
 * @param nonceHigh the first half of the random nonce that makes the ticket single-use
 * @param nonceLow the second half of the nonce
 * @param proof the random secret of the proof cookie, empty when not {@code authenticated}
 */
@SuppressWarnings("ArrayRecordComponent") // proof is copied in and never handed out mutable
public record HandoffTicket(
    UUID uuid,
    String name,
    List<LoginSuccess.Property> properties,
    @Nullable String server,
    String sourceNode,
    long issuedAtMillis,
    long expiresAtMillis,
    boolean authenticated,
    boolean trusted,
    String targetHost,
    int targetPort,
    long nonceHigh,
    long nonceLow,
    byte[] proof) {

  /** Most properties a ticket carries: Mojang sends one (the textures). */
  static final int MAX_PROPERTIES = 16;

  private static final int FLAG_AUTHENTICATED = 1;
  private static final int FLAG_TRUSTED = 2;

  /** Copies what is mutable. */
  public HandoffTicket {
    properties = List.copyOf(properties);
    proof = proof.clone();
  }

  /**
   * Returns the proof secret.
   *
   * @return a copy
   */
  @Override
  public byte[] proof() {
    return proof.clone();
  }

  /** Whether {@code candidate} is the proof secret, in constant time. */
  boolean proofIs(byte[] candidate) {
    return proof.length > 0 && java.security.MessageDigest.isEqual(proof, candidate);
  }

  // ---------------------------------------------------------------------------
  // Body: what the seal encrypts
  // ---------------------------------------------------------------------------

  /**
   * Writes the ticket's content: fixed fields first, strings as modified UTF-8 with a length.
   *
   * @return the plaintext the seal encrypts
   */
  byte[] encode() {
    ByteArrayOutputStream bytes = new ByteArrayOutputStream(1_024);
    try (DataOutputStream out = new DataOutputStream(bytes)) {
      out.writeLong(issuedAtMillis);
      out.writeLong(expiresAtMillis);
      out.writeByte((authenticated ? FLAG_AUTHENTICATED : 0) | (trusted ? FLAG_TRUSTED : 0));
      out.writeLong(uuid.getMostSignificantBits());
      out.writeLong(uuid.getLeastSignificantBits());
      out.writeUTF(name);
      out.writeBoolean(server != null);
      if (server != null) {
        out.writeUTF(server);
      }
      out.writeUTF(sourceNode);
      out.writeUTF(targetHost);
      out.writeShort(targetPort);
      out.writeLong(nonceHigh);
      out.writeLong(nonceLow);
      out.writeByte(proof.length);
      out.write(proof);
      out.writeByte(properties.size());
      for (LoginSuccess.Property property : properties) {
        out.writeUTF(property.name());
        out.writeUTF(property.value());
        String signature = property.signature();
        out.writeBoolean(signature != null);
        if (signature != null) {
          out.writeUTF(signature);
        }
      }
    } catch (IOException e) {
      throw new UncheckedIOException("a byte array stream does not fail", e);
    }
    return bytes.toByteArray();
  }

  /**
   * Reads what {@link #encode} wrote.
   *
   * @param body the plaintext
   * @return the ticket, or {@code null} if the body does not parse or has bytes left
   */
  static @Nullable HandoffTicket decode(byte[] body) {
    ByteArrayInputStream bytes = new ByteArrayInputStream(body);
    try (DataInputStream in = new DataInputStream(bytes)) {
      long issuedAt = in.readLong();
      long expiresAt = in.readLong();
      int flags = in.readUnsignedByte();
      UUID uuid = new UUID(in.readLong(), in.readLong());
      String name = in.readUTF();
      String server = in.readBoolean() ? in.readUTF() : null;
      String sourceNode = in.readUTF();
      String targetHost = in.readUTF();
      int targetPort = in.readUnsignedShort();
      long nonceHigh = in.readLong();
      long nonceLow = in.readLong();
      byte[] proof = new byte[in.readUnsignedByte()];
      in.readFully(proof);
      int count = in.readUnsignedByte();
      if (count > MAX_PROPERTIES) {
        return null;
      }
      List<LoginSuccess.Property> properties = new ArrayList<>(count);
      for (int i = 0; i < count; i++) {
        String propertyName = in.readUTF();
        String value = in.readUTF();
        String signature = in.readBoolean() ? in.readUTF() : null;
        properties.add(new LoginSuccess.Property(propertyName, value, signature));
      }
      if (bytes.available() != 0) {
        return null;
      }
      return new HandoffTicket(
          uuid,
          name,
          properties,
          server,
          sourceNode,
          issuedAt,
          expiresAt,
          (flags & FLAG_AUTHENTICATED) != 0,
          (flags & FLAG_TRUSTED) != 0,
          targetHost,
          targetPort,
          nonceHigh,
          nonceLow,
          proof);
    } catch (IOException e) {
      return null; // truncated, or not modified UTF-8
    }
  }

  @Override
  public String toString() {
    return "HandoffTicket[" + name + " (" + uuid + ") on " + server + " from " + sourceNode + "]";
  }
}
