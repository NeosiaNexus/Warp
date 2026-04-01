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
package dev.warp.protocol.packet.login;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.warp.protocol.ProtocolVersion;
import dev.warp.protocol.codec.McString;
import dev.warp.protocol.codec.McUuid;
import dev.warp.protocol.codec.VarInt;

import java.util.List;
import java.util.UUID;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.DecoderException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("Login packet codecs")
class LoginPacketsTest {

  // ---------------------------------------------------------------------------
  // LoginStart
  // ---------------------------------------------------------------------------

  @Nested
  @DisplayName("LoginStart")
  class LoginStartCodec {

    @Test
    @DisplayName("should roundtrip for 1.7 (name only)")
    void roundtrip17() {
      LoginStart original = new LoginStart("Steve", null);
      ByteBuf buf = Unpooled.buffer();
      try {
        LoginStart.CODEC.encode(original, buf, ProtocolVersion.MINECRAFT_1_8);
        LoginStart decoded = LoginStart.CODEC.decode(buf, ProtocolVersion.MINECRAFT_1_8);
        assertEquals("Steve", decoded.name());
        assertNull(decoded.playerUuid());
      } finally {
        buf.release();
      }
    }

    @Test
    @DisplayName("should decode for 1.19.1 (name + no signature + optional UUID)")
    void decode1191WithUuid() {
      // 1.19.1 decode reads: name, skipSignatureFields (boolean prefix), optional UUID
      // Encode does not write signature fields, so we construct the wire format manually
      UUID uuid = UUID.randomUUID();
      ByteBuf buf = Unpooled.buffer();
      try {
        McString.write(buf, "Alex", 16);
        buf.writeBoolean(false); // no signature data
        buf.writeBoolean(true); // UUID is present
        McUuid.write(buf, uuid);

        LoginStart decoded = LoginStart.CODEC.decode(buf, ProtocolVersion.MINECRAFT_1_19_1);
        assertEquals("Alex", decoded.name());
        assertEquals(uuid, decoded.playerUuid());
      } finally {
        buf.release();
      }
    }

    @Test
    @DisplayName("should roundtrip for 1.19.3+ (name + UUID)")
    void roundtrip1193() {
      UUID uuid = UUID.fromString("12345678-1234-1234-1234-123456789abc");
      LoginStart original = new LoginStart("Alex", uuid);
      ByteBuf buf = Unpooled.buffer();
      try {
        LoginStart.CODEC.encode(original, buf, ProtocolVersion.MINECRAFT_1_19_3);
        LoginStart decoded = LoginStart.CODEC.decode(buf, ProtocolVersion.MINECRAFT_1_19_3);
        assertEquals("Alex", decoded.name());
        assertEquals(uuid, decoded.playerUuid());
      } finally {
        buf.release();
      }
    }
  }

  // ---------------------------------------------------------------------------
  // LoginSuccess
  // ---------------------------------------------------------------------------

  @Nested
  @DisplayName("LoginSuccess")
  class LoginSuccessCodec {

    @Test
    @DisplayName("should roundtrip for 1.8 (no properties)")
    void roundtrip18() {
      UUID uuid = UUID.randomUUID();
      LoginSuccess original = new LoginSuccess(uuid, "Notch", List.of(), false);
      ByteBuf buf = Unpooled.buffer();
      try {
        LoginSuccess.CODEC.encode(original, buf, ProtocolVersion.MINECRAFT_1_8);
        LoginSuccess decoded = LoginSuccess.CODEC.decode(buf, ProtocolVersion.MINECRAFT_1_8);
        assertEquals(uuid, decoded.uuid());
        assertEquals("Notch", decoded.username());
        assertTrue(decoded.properties().isEmpty());
      } finally {
        buf.release();
      }
    }

    @Test
    @DisplayName("should roundtrip for 1.20.5+ (properties + strictErrorHandling)")
    void roundtrip1205() {
      UUID uuid = UUID.randomUUID();
      List<LoginSuccess.Property> props =
          List.of(new LoginSuccess.Property("textures", "base64data", "signature"));
      LoginSuccess original = new LoginSuccess(uuid, "jeb_", props, true);
      ByteBuf buf = Unpooled.buffer();
      try {
        LoginSuccess.CODEC.encode(original, buf, ProtocolVersion.MINECRAFT_1_20_5);
        LoginSuccess decoded = LoginSuccess.CODEC.decode(buf, ProtocolVersion.MINECRAFT_1_20_5);
        assertEquals(uuid, decoded.uuid());
        assertEquals("jeb_", decoded.username());
        assertEquals(1, decoded.properties().size());
        assertEquals("textures", decoded.properties().get(0).name());
        assertEquals("signature", decoded.properties().get(0).signature());
        assertTrue(decoded.strictErrorHandling());
      } finally {
        buf.release();
      }
    }
  }

  // ---------------------------------------------------------------------------
  // SetCompression
  // ---------------------------------------------------------------------------

  @Nested
  @DisplayName("SetCompression")
  class SetCompressionCodec {

    @Test
    @DisplayName("should roundtrip compression threshold")
    void roundtrip() {
      SetCompression original = new SetCompression(256);
      ByteBuf buf = Unpooled.buffer();
      try {
        SetCompression.CODEC.encode(original, buf, ProtocolVersion.MINECRAFT_1_21_4);
        SetCompression decoded = SetCompression.CODEC.decode(buf, ProtocolVersion.MINECRAFT_1_21_4);
        assertEquals(256, decoded.threshold());
      } finally {
        buf.release();
      }
    }
  }

  // ---------------------------------------------------------------------------
  // LoginPluginRequest / Response
  // ---------------------------------------------------------------------------

  @Nested
  @DisplayName("LoginPlugin")
  class LoginPluginCodec {

    @Test
    @DisplayName("should roundtrip login plugin request")
    void requestRoundtrip() {
      byte[] data = {0x01, 0x02, 0x03};
      LoginPluginRequest original = new LoginPluginRequest(42, "velocity:player_info", data);
      ByteBuf buf = Unpooled.buffer();
      try {
        LoginPluginRequest.CODEC.encode(original, buf, ProtocolVersion.MINECRAFT_1_21_4);
        LoginPluginRequest decoded =
            LoginPluginRequest.CODEC.decode(buf, ProtocolVersion.MINECRAFT_1_21_4);
        assertEquals(42, decoded.messageId());
        assertEquals("velocity:player_info", decoded.channel());
        assertArrayEquals(data, decoded.data());
      } finally {
        buf.release();
      }
    }

    @Test
    @DisplayName("should roundtrip login plugin response with data")
    void responseWithData() {
      byte[] data = {0x04, 0x05};
      LoginPluginResponse original = new LoginPluginResponse(42, true, data);
      ByteBuf buf = Unpooled.buffer();
      try {
        LoginPluginResponse.CODEC.encode(original, buf, ProtocolVersion.MINECRAFT_1_21_4);
        LoginPluginResponse decoded =
            LoginPluginResponse.CODEC.decode(buf, ProtocolVersion.MINECRAFT_1_21_4);
        assertEquals(42, decoded.messageId());
        assertTrue(decoded.successful());
        assertArrayEquals(data, decoded.data());
      } finally {
        buf.release();
      }
    }
  }

  // ---------------------------------------------------------------------------
  // EncryptionRequest
  // ---------------------------------------------------------------------------

  @Nested
  @DisplayName("EncryptionRequest")
  class EncryptionRequestCodec {

    @Test
    @DisplayName("should roundtrip at 1.8 without shouldAuthenticate")
    void roundtrip18() {
      byte[] publicKey = new byte[162];
      byte[] verifyToken = new byte[4];
      publicKey[0] = 0x30;
      verifyToken[0] = 0x01;
      EncryptionRequest original = new EncryptionRequest("", publicKey, verifyToken, true);
      ByteBuf buf = Unpooled.buffer();
      try {
        EncryptionRequest.CODEC.encode(original, buf, ProtocolVersion.MINECRAFT_1_8);
        EncryptionRequest decoded =
            EncryptionRequest.CODEC.decode(buf, ProtocolVersion.MINECRAFT_1_8);
        assertEquals("", decoded.serverId());
        assertArrayEquals(publicKey, decoded.publicKey());
        assertArrayEquals(verifyToken, decoded.verifyToken());
        assertTrue(decoded.shouldAuthenticate());
      } finally {
        buf.release();
      }
    }

    @Test
    @DisplayName("should roundtrip for 1.7.2 (short-prefixed arrays)")
    void roundtrip17() {
      EncryptionRequest original =
          new EncryptionRequest("", new byte[] {1, 2, 3}, new byte[] {4, 5, 6, 7}, true);
      ByteBuf buf = Unpooled.buffer();
      try {
        EncryptionRequest.CODEC.encode(original, buf, ProtocolVersion.MINECRAFT_1_7_2);
        EncryptionRequest decoded =
            EncryptionRequest.CODEC.decode(buf, ProtocolVersion.MINECRAFT_1_7_2);
        assertEquals("", decoded.serverId());
        assertArrayEquals(new byte[] {1, 2, 3}, decoded.publicKey());
        assertArrayEquals(new byte[] {4, 5, 6, 7}, decoded.verifyToken());
      } finally {
        buf.release();
      }
    }

    @Test
    @DisplayName("should roundtrip at 1.20.5 with shouldAuthenticate=true")
    void roundtrip1205() {
      byte[] publicKey = new byte[162];
      byte[] verifyToken = new byte[4];
      publicKey[0] = 0x30;
      verifyToken[0] = 0x02;
      EncryptionRequest original = new EncryptionRequest("", publicKey, verifyToken, true);
      ByteBuf buf = Unpooled.buffer();
      try {
        EncryptionRequest.CODEC.encode(original, buf, ProtocolVersion.MINECRAFT_1_20_5);
        EncryptionRequest decoded =
            EncryptionRequest.CODEC.decode(buf, ProtocolVersion.MINECRAFT_1_20_5);
        assertEquals("", decoded.serverId());
        assertArrayEquals(publicKey, decoded.publicKey());
        assertArrayEquals(verifyToken, decoded.verifyToken());
        assertTrue(decoded.shouldAuthenticate());
      } finally {
        buf.release();
      }
    }
  }

  // ---------------------------------------------------------------------------
  // EncryptionResponse
  // ---------------------------------------------------------------------------

  @Nested
  @DisplayName("EncryptionResponse")
  class EncryptionResponseCodec {

    @Test
    @DisplayName("should roundtrip at 1.8")
    void roundtrip18() {
      byte[] sharedSecret = new byte[128];
      byte[] verifyToken = new byte[128];
      sharedSecret[0] = 0x01;
      verifyToken[0] = 0x02;
      EncryptionResponse original = new EncryptionResponse(sharedSecret, verifyToken);
      ByteBuf buf = Unpooled.buffer();
      try {
        EncryptionResponse.CODEC.encode(original, buf, ProtocolVersion.MINECRAFT_1_8);
        EncryptionResponse decoded =
            EncryptionResponse.CODEC.decode(buf, ProtocolVersion.MINECRAFT_1_8);
        assertArrayEquals(sharedSecret, decoded.sharedSecret());
        assertArrayEquals(verifyToken, decoded.verifyToken());
      } finally {
        buf.release();
      }
    }

    @Test
    @DisplayName("should roundtrip for 1.7.2 (short-prefixed arrays)")
    void roundtrip17() {
      EncryptionResponse original = new EncryptionResponse(new byte[] {1, 2}, new byte[] {3, 4});
      ByteBuf buf = Unpooled.buffer();
      try {
        EncryptionResponse.CODEC.encode(original, buf, ProtocolVersion.MINECRAFT_1_7_2);
        EncryptionResponse decoded =
            EncryptionResponse.CODEC.decode(buf, ProtocolVersion.MINECRAFT_1_7_2);
        assertArrayEquals(new byte[] {1, 2}, decoded.sharedSecret());
        assertArrayEquals(new byte[] {3, 4}, decoded.verifyToken());
      } finally {
        buf.release();
      }
    }

    @Test
    @DisplayName("should roundtrip for 1.19 (normal path with boolean prefix)")
    void roundtrip119NormalPath() {
      EncryptionResponse original = new EncryptionResponse(new byte[] {1, 2}, new byte[] {3, 4});
      ByteBuf buf = Unpooled.buffer();
      try {
        EncryptionResponse.CODEC.encode(original, buf, ProtocolVersion.MINECRAFT_1_19);
        EncryptionResponse decoded =
            EncryptionResponse.CODEC.decode(buf, ProtocolVersion.MINECRAFT_1_19);
        assertArrayEquals(new byte[] {1, 2}, decoded.sharedSecret());
        assertArrayEquals(new byte[] {3, 4}, decoded.verifyToken());
      } finally {
        buf.release();
      }
    }
  }

  // ---------------------------------------------------------------------------
  // LoginSuccess (error cases)
  // ---------------------------------------------------------------------------

  @Nested
  @DisplayName("LoginSuccess error handling")
  class LoginSuccessErrors {

    @Test
    @DisplayName("should reject more than 64 properties")
    void rejectTooManyProperties() {
      ByteBuf buf = Unpooled.buffer();
      try {
        // Write UUID (128-bit)
        McUuid.write(buf, UUID.randomUUID());
        // Write username
        McString.write(buf, "TestPlayer", 16);
        // Write property count > 64
        VarInt.write(buf, 65);
        assertThrows(
            DecoderException.class,
            () -> LoginSuccess.CODEC.decode(buf, ProtocolVersion.MINECRAFT_1_20_5));
      } finally {
        buf.release();
      }
    }
  }
}
