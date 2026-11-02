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

import static dev.warp.protocol.ProtocolVersion.MINECRAFT_1_17;
import static dev.warp.protocol.ProtocolVersion.MINECRAFT_1_20_3;

import dev.warp.protocol.ProtocolVersion;
import dev.warp.protocol.codec.McString;
import dev.warp.protocol.codec.McUuid;
import dev.warp.protocol.packet.PacketCodec;
import dev.warp.protocol.text.ComponentCodec;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Objects;
import java.util.UUID;

import io.netty.buffer.ByteBuf;
import net.kyori.adventure.text.Component;
import org.jspecify.annotations.Nullable;

/**
 * Server asks the client to apply a resource pack ({@code S→C}, from 1.8), in the play state and,
 * from 1.20.2, the configuration state, where the packet has the same layout.
 *
 * <p>Up to 1.20.2 this is the Resource Pack Send packet: the client holds one server pack, which a
 * new one replaces, and answers without naming the pack. 1.20.3 made it Add Resource Pack: the
 * client holds any number of packs, each named by a UUID it answers with, and Remove Resource Pack
 * ({@link ResourcePackPop}) unloads them. Layout by version (minecraft-data's {@code
 * packet_resource_pack_send} and {@code packet_add_resource_pack}):
 *
 * <ul>
 *   <li><b>1.8 to 1.16.5</b>: URL, hash (a hex SHA-1 of at most 40 characters);
 *   <li><b>1.17 to 1.20.2</b>: then whether it is required, and an optional prompt, JSON text;
 *   <li><b>1.20.3+</b>: the UUID first; the prompt is NBT.
 * </ul>
 *
 * <p>1.7 has no such packet: its server resource pack is the {@code MC|RPack} plugin message,
 * holding the URL alone ({@link #legacyPluginMessage}).
 *
 * <p>The proxy decodes the packs a server sends, which arrive once per login at most, to know which
 * packs the client holds, and sends its own.
 *
 * @param uuid the pack's UUID; not on the wire before 1.20.3, where a decoded pack gets one derived
 *     from its URL ({@link #legacyUuid})
 * @param url the URL the client downloads the pack from
 * @param hash the pack's SHA-1 in hex, or empty to let the client download it every time
 * @param required whether the client must accept it (from 1.17), or is disconnected
 * @param prompt the prompt shown with it (from 1.17), encoded for the client's version, or {@code
 *     null} for none
 */
public record ResourcePackPush(
    UUID uuid, String url, String hash, boolean required, byte @Nullable [] prompt)
    implements PlayPacket {

  /** The longest hash a client reads. */
  public static final int MAX_HASH_LENGTH = 40;

  /** The plugin message channel of a 1.7 server resource pack. */
  public static final String LEGACY_CHANNEL = "MC|RPack";

  /** Codec for reading and writing resource pack pushes. */
  public static final PacketCodec<ResourcePackPush> CODEC =
      new PacketCodec<>() {
        @Override
        public ResourcePackPush decode(ByteBuf buf, ProtocolVersion version) {
          UUID uuid = version.isAtLeast(MINECRAFT_1_20_3) ? McUuid.read(buf) : null;
          String url = McString.read(buf);
          String hash = McString.read(buf, MAX_HASH_LENGTH);
          boolean required = false;
          byte[] prompt = null;
          if (version.isAtLeast(MINECRAFT_1_17)) {
            required = buf.readBoolean();
            if (buf.readBoolean()) {
              prompt = ComponentCodec.readRaw(buf, version);
            }
          }
          return new ResourcePackPush(
              uuid != null ? uuid : legacyUuid(url), url, hash, required, prompt);
        }

        @Override
        public void encode(ResourcePackPush packet, ByteBuf buf, ProtocolVersion version) {
          if (version.isAtLeast(MINECRAFT_1_20_3)) {
            McUuid.write(buf, packet.uuid());
          }
          McString.write(buf, packet.url());
          McString.write(buf, packet.hash(), MAX_HASH_LENGTH);
          if (version.isAtLeast(MINECRAFT_1_17)) {
            buf.writeBoolean(packet.required());
            byte[] prompt = packet.prompt();
            buf.writeBoolean(prompt != null);
            if (prompt != null) {
              buf.writeBytes(prompt);
            }
          }
        }
      };

  /**
   * Creates the packet for a client of {@code version}.
   *
   * @param uuid the pack's UUID
   * @param url the URL
   * @param hash the SHA-1 in hex, or empty
   * @param required whether the client must accept it
   * @param prompt the prompt, or {@code null}
   * @param version the client's protocol version, from 1.8
   * @return the packet
   * @throws IllegalArgumentException if the hash is longer than 40 characters, or the prompt too
   *     large for the client
   */
  public static ResourcePackPush of(
      UUID uuid,
      String url,
      String hash,
      boolean required,
      @Nullable Component prompt,
      ProtocolVersion version) {
    if (hash.length() > MAX_HASH_LENGTH) {
      throw new IllegalArgumentException("A resource pack hash has at most 40 characters: " + hash);
    }
    byte[] encoded =
        prompt != null && version.isAtLeast(MINECRAFT_1_17)
            ? ComponentCodec.encode(prompt, version)
            : null;
    return new ResourcePackPush(uuid, url, hash, required, encoded);
  }

  /**
   * Returns the UUID a pack sent before 1.20.3 is known by, which carries none: derived from its
   * URL, as the 1.20.3 server derives it for a pack configured without one ({@code
   * UUID.nameUUIDFromBytes}).
   *
   * @param url the pack's URL
   * @return its UUID
   */
  public static UUID legacyUuid(String url) {
    return UUID.nameUUIDFromBytes(url.getBytes(StandardCharsets.UTF_8));
  }

  /**
   * Returns whether this push names the same pack as {@code other}: same UUID, URL and hash. A
   * client that loaded one has loaded the other.
   *
   * @param other another push
   * @return {@code true} for the same pack
   */
  public boolean samePack(ResourcePackPush other) {
    return uuid.equals(other.uuid) && url.equals(other.url) && hash.equals(other.hash);
  }

  /**
   * Returns the {@code MC|RPack} plugin message that applies this pack on a 1.7 client: the URL in
   * UTF-8, as the whole payload.
   *
   * @return the plugin message
   */
  public PlayPluginMessage legacyPluginMessage() {
    return new PlayPluginMessage(LEGACY_CHANNEL, url.getBytes(StandardCharsets.UTF_8));
  }

  @Override
  public boolean equals(Object other) {
    return other instanceof ResourcePackPush push
        && samePack(push)
        && required == push.required
        && Arrays.equals(prompt, push.prompt);
  }

  @Override
  public int hashCode() {
    return Objects.hash(uuid, url, hash, required, Arrays.hashCode(prompt));
  }
}
