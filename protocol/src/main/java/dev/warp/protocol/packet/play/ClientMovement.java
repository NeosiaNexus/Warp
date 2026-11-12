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
package dev.warp.protocol.packet.play;

import dev.warp.protocol.ProtocolVersion;
import dev.warp.protocol.packet.PacketCodec;

import io.netty.buffer.ByteBuf;

/**
 * What a client says of its own movement each tick: one of the four serverbound movement packets
 * (position, position and rotation, rotation, flags only), decoded by the anti-bot's virtual world
 * and by nothing else: a backend reads them from the client directly. Each packet is its own record
 * so that the registry maps one id to one class; this interface gives them one shape.
 *
 * <p>Layouts: 1.7 carries the feet and head (stance) heights and {@code onGround}; 1.8 to 1.21.1
 * one height and {@code onGround}; 1.21.2 replaces the boolean with a flag byte (bit 0 on ground,
 * bit 1 against a wall). Rotation is two floats (yaw, pitch). The position packets carry the
 * position as absolute doubles in every version.
 */
public sealed interface ClientMovement extends PlayPacket
    permits ClientMovement.Position,
        ClientMovement.PositionRotation,
        ClientMovement.Rotation,
        ClientMovement.Flags {

  /** Bit of the 1.21.2+ flag byte set when the client stands on the ground. */
  int ON_GROUND_BIT = 0x01;

  /** Height of a player's eyes over its feet in 1.7, where the client sends both. */
  double LEGACY_STANCE = 1.62;

  /**
   * Returns whether the packet carries a position.
   *
   * @return {@code true} for {@link Position} and {@link PositionRotation}
   */
  boolean hasPosition();

  /**
   * Returns the x coordinate, when {@link #hasPosition()}.
   *
   * @return the x coordinate, else zero
   */
  double x();

  /**
   * Returns the feet height, when {@link #hasPosition()}.
   *
   * @return the y coordinate, else zero
   */
  double y();

  /**
   * Returns the z coordinate, when {@link #hasPosition()}.
   *
   * @return the z coordinate, else zero
   */
  double z();

  /**
   * Returns whether the client says it stands on the ground.
   *
   * @return the on-ground flag
   */
  boolean onGround();

  /**
   * The client's position (Set Player Position).
   *
   * @param x the x coordinate
   * @param y the feet height
   * @param z the z coordinate
   * @param onGround whether the client stands on the ground
   */
  record Position(double x, double y, double z, boolean onGround) implements ClientMovement {
    /** Codec for the serverbound position packet. */
    public static final PacketCodec<Position> CODEC =
        new PacketCodec<>() {
          @Override
          public Position decode(ByteBuf buf, ProtocolVersion version) {
            double x = buf.readDouble();
            double y = buf.readDouble();
            if (version.isOlderThan(ProtocolVersion.MINECRAFT_1_8)) {
              buf.readDouble(); // the head height, feet + 1.62
            }
            double z = buf.readDouble();
            return new Position(x, y, z, readOnGround(buf, version));
          }

          @Override
          public void encode(Position packet, ByteBuf buf, ProtocolVersion version) {
            buf.writeDouble(packet.x());
            buf.writeDouble(packet.y());
            if (version.isOlderThan(ProtocolVersion.MINECRAFT_1_8)) {
              buf.writeDouble(packet.y() + LEGACY_STANCE);
            }
            buf.writeDouble(packet.z());
            writeOnGround(buf, version, packet.onGround());
          }
        };

    @Override
    public boolean hasPosition() {
      return true;
    }
  }

  /**
   * The client's position and rotation (Set Player Position and Rotation): what a client sends
   * first after a teleport.
   *
   * @param x the x coordinate
   * @param y the feet height
   * @param z the z coordinate
   * @param yaw the yaw in degrees
   * @param pitch the pitch in degrees
   * @param onGround whether the client stands on the ground
   */
  record PositionRotation(double x, double y, double z, float yaw, float pitch, boolean onGround)
      implements ClientMovement {
    /** Codec for the serverbound position and rotation packet. */
    public static final PacketCodec<PositionRotation> CODEC =
        new PacketCodec<>() {
          @Override
          public PositionRotation decode(ByteBuf buf, ProtocolVersion version) {
            double x = buf.readDouble();
            double y = buf.readDouble();
            if (version.isOlderThan(ProtocolVersion.MINECRAFT_1_8)) {
              buf.readDouble(); // the head height, feet + 1.62
            }
            double z = buf.readDouble();
            float yaw = buf.readFloat();
            float pitch = buf.readFloat();
            return new PositionRotation(x, y, z, yaw, pitch, readOnGround(buf, version));
          }

          @Override
          public void encode(PositionRotation packet, ByteBuf buf, ProtocolVersion version) {
            buf.writeDouble(packet.x());
            buf.writeDouble(packet.y());
            if (version.isOlderThan(ProtocolVersion.MINECRAFT_1_8)) {
              buf.writeDouble(packet.y() + LEGACY_STANCE);
            }
            buf.writeDouble(packet.z());
            buf.writeFloat(packet.yaw());
            buf.writeFloat(packet.pitch());
            writeOnGround(buf, version, packet.onGround());
          }
        };

    @Override
    public boolean hasPosition() {
      return true;
    }
  }

  /**
   * The client's rotation only (Set Player Rotation).
   *
   * @param yaw the yaw in degrees
   * @param pitch the pitch in degrees
   * @param onGround whether the client stands on the ground
   */
  record Rotation(float yaw, float pitch, boolean onGround) implements ClientMovement {
    /** Codec for the serverbound rotation packet. */
    public static final PacketCodec<Rotation> CODEC =
        new PacketCodec<>() {
          @Override
          public Rotation decode(ByteBuf buf, ProtocolVersion version) {
            float yaw = buf.readFloat();
            float pitch = buf.readFloat();
            return new Rotation(yaw, pitch, readOnGround(buf, version));
          }

          @Override
          public void encode(Rotation packet, ByteBuf buf, ProtocolVersion version) {
            buf.writeFloat(packet.yaw());
            buf.writeFloat(packet.pitch());
            writeOnGround(buf, version, packet.onGround());
          }
        };

    @Override
    public boolean hasPosition() {
      return false;
    }

    @Override
    public double x() {
      return 0;
    }

    @Override
    public double y() {
      return 0;
    }

    @Override
    public double z() {
      return 0;
    }
  }

  /**
   * The client's movement flags only (Set Player Movement Flags, "Player" before 1.21.2): sent on
   * ticks where it did not move.
   *
   * @param onGround whether the client stands on the ground
   */
  record Flags(boolean onGround) implements ClientMovement {
    /** Codec for the serverbound flags-only packet. */
    public static final PacketCodec<Flags> CODEC =
        new PacketCodec<>() {
          @Override
          public Flags decode(ByteBuf buf, ProtocolVersion version) {
            return new Flags(readOnGround(buf, version));
          }

          @Override
          public void encode(Flags packet, ByteBuf buf, ProtocolVersion version) {
            writeOnGround(buf, version, packet.onGround());
          }
        };

    @Override
    public boolean hasPosition() {
      return false;
    }

    @Override
    public double x() {
      return 0;
    }

    @Override
    public double y() {
      return 0;
    }

    @Override
    public double z() {
      return 0;
    }
  }

  private static boolean readOnGround(ByteBuf buf, ProtocolVersion version) {
    if (version.isAtLeast(ProtocolVersion.MINECRAFT_1_21_2)) {
      return (buf.readUnsignedByte() & ON_GROUND_BIT) != 0;
    }
    return buf.readBoolean();
  }

  private static void writeOnGround(ByteBuf buf, ProtocolVersion version, boolean onGround) {
    if (version.isAtLeast(ProtocolVersion.MINECRAFT_1_21_2)) {
      buf.writeByte(onGround ? ON_GROUND_BIT : 0);
    } else {
      buf.writeBoolean(onGround);
    }
  }
}
