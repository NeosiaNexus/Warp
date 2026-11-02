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
import dev.warp.protocol.codec.McUuid;
import dev.warp.protocol.codec.VarInt;
import dev.warp.protocol.packet.PacketCodec;
import dev.warp.protocol.packet.PacketWatch;
import dev.warp.protocol.text.ComponentCodec;

import java.util.UUID;

import io.netty.buffer.ByteBuf;
import io.netty.handler.codec.DecoderException;
import net.kyori.adventure.text.Component;

/**
 * Server adds, updates or removes a boss bar ({@code S→C}, from 1.9; the Boss Event packet).
 *
 * <p>Layout: the bar's UUID, a VarInt action, then the action's fields (Velocity's {@code
 * BossBarPacket}, minecraft-data's {@code packet_boss_bar}):
 *
 * <ul>
 *   <li>{@link Add}: name, float progress, VarInt color, VarInt overlay, unsigned byte flags;
 *   <li>{@link Remove}: nothing;
 *   <li>{@link UpdateProgress}, {@link UpdateName}, {@link UpdateStyle} (color then overlay),
 *       {@link UpdateFlags}: the fields they change.
 * </ul>
 *
 * <p>The name is a component, JSON before 1.20.3 and NBT from. The proxy writes every action for
 * the bars plugins show. Before 1.20.2 it also follows the bars a server shows, which survive the
 * Join Game of a server switch, to remove them when the player leaves: it watches the packet
 * ({@link #WATCH}) instead of decoding it, reading the UUID and the action in place and forwarding
 * the frame as received.
 *
 * @param uuid the boss bar's UUID
 * @param action what the packet does to the bar
 */
public record BossBar(UUID uuid, Action action) implements PlayPacket {

  /** Action id that shows a new boss bar. */
  public static final int ADD = 0;

  /** Action id that removes a boss bar. */
  public static final int REMOVE = 1;

  /** Action id that changes a bar's progress. */
  public static final int UPDATE_PROGRESS = 2;

  /** Action id that changes a bar's name. */
  public static final int UPDATE_NAME = 3;

  /** Action id that changes a bar's color and overlay. */
  public static final int UPDATE_STYLE = 4;

  /** Action id that changes a bar's flags. */
  public static final int UPDATE_FLAGS = 5;

  /** Flag that darkens the sky. */
  public static final int DARKEN_SCREEN = 0x1;

  /** Flag that plays the boss music (in 1.9, also creates fog). */
  public static final int PLAY_BOSS_MUSIC = 0x2;

  /** Flag that creates fog, from 1.10. */
  public static final int CREATE_WORLD_FOG = 0x4;

  /** What a boss bar packet does, by action. */
  public sealed interface Action
      permits Add, Added, Remove, UpdateProgress, UpdateName, UpdateStyle, UpdateFlags {

    /**
     * Returns the action's id on the wire.
     *
     * @return the id
     */
    int id();
  }

  /**
   * Shows a new bar.
   *
   * @param name the name, encoded for the client's version
   * @param progress the progress, from 0 to 1
   * @param color the color id: pink, blue, red, green, yellow, purple, white
   * @param overlay the overlay id: progress, then 6, 10, 12 and 20 notches
   * @param flags the flags ({@link #DARKEN_SCREEN}, {@link #PLAY_BOSS_MUSIC}, {@link
   *     #CREATE_WORLD_FOG})
   */
  @SuppressWarnings("ArrayRecordComponent") // never mutated
  public record Add(byte[] name, float progress, int color, int overlay, int flags)
      implements Action {
    @Override
    public int id() {
      return ADD;
    }
  }

  /** What {@link #WATCH} reports of a bar a server shows: it reads none of its fields. */
  public record Added() implements Action {
    @Override
    public int id() {
      return ADD;
    }
  }

  /** Removes a bar. */
  public record Remove() implements Action {
    @Override
    public int id() {
      return REMOVE;
    }
  }

  /**
   * Changes a bar's progress.
   *
   * @param progress the progress, from 0 to 1
   */
  public record UpdateProgress(float progress) implements Action {
    @Override
    public int id() {
      return UPDATE_PROGRESS;
    }
  }

  /**
   * Changes a bar's name.
   *
   * @param name the name, encoded for the client's version
   */
  @SuppressWarnings("ArrayRecordComponent") // never mutated
  public record UpdateName(byte[] name) implements Action {
    @Override
    public int id() {
      return UPDATE_NAME;
    }
  }

  /**
   * Changes a bar's color and overlay.
   *
   * @param color the color id
   * @param overlay the overlay id
   */
  public record UpdateStyle(int color, int overlay) implements Action {
    @Override
    public int id() {
      return UPDATE_STYLE;
    }
  }

  /**
   * Changes a bar's flags.
   *
   * @param flags the flags
   */
  public record UpdateFlags(int flags) implements Action {
    @Override
    public int id() {
      return UPDATE_FLAGS;
    }
  }

  /**
   * Creates the packet that shows a bar to a client of {@code version}.
   *
   * @param uuid the bar's UUID
   * @param name its name
   * @param progress its progress, from 0 to 1
   * @param color its color id
   * @param overlay its overlay id
   * @param flags its flags
   * @param version the client's protocol version, from 1.9
   * @return the packet
   * @throws IllegalArgumentException if the name is too large for the client
   */
  public static BossBar add(
      UUID uuid,
      Component name,
      float progress,
      int color,
      int overlay,
      int flags,
      ProtocolVersion version) {
    return new BossBar(
        uuid, new Add(ComponentCodec.encode(name, version), progress, color, overlay, flags));
  }

  /**
   * Creates the packet that renames a bar for a client of {@code version}.
   *
   * @param uuid the bar's UUID
   * @param name its new name
   * @param version the client's protocol version, from 1.9
   * @return the packet
   * @throws IllegalArgumentException if the name is too large for the client
   */
  public static BossBar name(UUID uuid, Component name, ProtocolVersion version) {
    return new BossBar(uuid, new UpdateName(ComponentCodec.encode(name, version)));
  }

  /**
   * Creates the packet that removes a boss bar.
   *
   * @param uuid the boss bar's UUID
   * @return the packet
   */
  public static BossBar remove(UUID uuid) {
    return new BossBar(uuid, new Remove());
  }

  /**
   * Reports the bars a server shows and removes: the UUID and the action, read in place. Updates,
   * which every animated bar sends many times a second, report nothing and allocate nothing.
   */
  public static final PacketWatch<BossBar> WATCH =
      (buf, version) -> {
        long mostSignificant = buf.readLong();
        long leastSignificant = buf.readLong();
        int action = VarInt.read(buf);
        return switch (action) {
          case ADD -> new BossBar(new UUID(mostSignificant, leastSignificant), new Added());
          case REMOVE -> new BossBar(new UUID(mostSignificant, leastSignificant), new Remove());
          default -> null;
        };
      };

  /** Codec for reading and writing every boss bar action. */
  public static final PacketCodec<BossBar> CODEC =
      new PacketCodec<>() {
        @Override
        public BossBar decode(ByteBuf buf, ProtocolVersion version) {
          UUID uuid = McUuid.read(buf);
          int id = VarInt.read(buf);
          Action action =
              switch (id) {
                case ADD ->
                    new Add(
                        ComponentCodec.readRaw(buf, version),
                        buf.readFloat(),
                        VarInt.read(buf),
                        VarInt.read(buf),
                        buf.readUnsignedByte());
                case REMOVE -> new Remove();
                case UPDATE_PROGRESS -> new UpdateProgress(buf.readFloat());
                case UPDATE_NAME -> new UpdateName(ComponentCodec.readRaw(buf, version));
                case UPDATE_STYLE -> new UpdateStyle(VarInt.read(buf), VarInt.read(buf));
                case UPDATE_FLAGS -> new UpdateFlags(buf.readUnsignedByte());
                default -> throw new DecoderException("Unknown boss bar action " + id);
              };
          return new BossBar(uuid, action);
        }

        @Override
        public void encode(BossBar packet, ByteBuf buf, ProtocolVersion version) {
          McUuid.write(buf, packet.uuid());
          Action action = packet.action();
          VarInt.write(buf, action.id());
          switch (action) {
            case Add(byte[] name, float progress, int color, int overlay, int flags) -> {
              buf.writeBytes(name);
              buf.writeFloat(progress);
              VarInt.write(buf, color);
              VarInt.write(buf, overlay);
              buf.writeByte(flags);
            }
            case Added _ ->
                throw new IllegalArgumentException("A watched addition carries no fields to write");
            case Remove _ -> {
              // nothing follows
            }
            case UpdateProgress(float progress) -> buf.writeFloat(progress);
            case UpdateName(byte[] name) -> buf.writeBytes(name);
            case UpdateStyle(int color, int overlay) -> {
              VarInt.write(buf, color);
              VarInt.write(buf, overlay);
            }
            case UpdateFlags(int flags) -> buf.writeByte(flags);
          }
        }
      };
}
