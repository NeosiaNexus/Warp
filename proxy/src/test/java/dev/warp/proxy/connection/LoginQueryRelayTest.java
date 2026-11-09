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
package dev.warp.proxy.connection;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.warp.api.server.ServerInfo;
import dev.warp.protocol.ProtocolState;
import dev.warp.protocol.ProtocolVersion;
import dev.warp.protocol.codec.McString;
import dev.warp.protocol.codec.VarInt;
import dev.warp.protocol.packet.Packet;
import dev.warp.protocol.packet.PacketCodec;
import dev.warp.protocol.packet.PacketDirection;
import dev.warp.protocol.packet.PacketRegistry;
import dev.warp.protocol.packet.StateRegistry;
import dev.warp.protocol.packet.login.LoginDisconnect;
import dev.warp.protocol.packet.login.LoginPluginRequest;
import dev.warp.protocol.packet.login.LoginPluginResponse;
import dev.warp.protocol.packet.login.LoginSuccess;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("Login query relay")
class LoginQueryRelayTest {

  private static final ProtocolVersion VERSION = ProtocolVersion.MINECRAFT_1_20_1;
  private static final byte[] SECRET = "s3cret".getBytes(java.nio.charset.StandardCharsets.UTF_8);

  private SwitchHarness harness;
  private MinecraftConnection backend;
  private final AtomicBoolean released = new AtomicBoolean();

  @BeforeEach
  void setUp() {
    // The lobby's loss falls back to survival, whose connection a test plays with connectAnother.
    harness = new SwitchHarness(VERSION, List.of("survival"), ProtocolState.LOGIN);
    harness.holdConnections();
    harness.client.setHandshakeMarker(HandshakeMarkers.detect("play.example.com\0FML2\0", VERSION));
    // What the login handler does with a client's answer while it holds the client.
    harness.client.setSessionHandler(
        packet -> {
          if (packet instanceof LoginPluginResponse response) {
            var _ = harness.player.loginQueries().answered(response);
          }
        });
    backend = harness.backend(ProtocolState.LOGIN);
  }

  @AfterEach
  void tearDown() {
    harness.close();
  }

  /** Holds the client in its login state and logs in to the backend, as the first join does. */
  private void connect(byte[] forwardingSecret) {
    harness.player.loginQueries().hold(() -> released.set(true));
    login(backend, SwitchHarness.LOBBY, forwardingSecret);
  }

  /** Opens a login to {@code server}, which the switch takes as the one the player is joining. */
  private MinecraftConnection connectAnother(ServerInfo server) {
    MinecraftConnection another = harness.backend(ProtocolState.LOGIN);
    login(another, server, new byte[0]);
    return another;
  }

  private void login(MinecraftConnection connection, ServerInfo server, byte[] forwardingSecret) {
    harness.player.serverSwitch().connected(new BackendConnection(connection, server));
    connection.setSessionHandler(
        new BackendLoginSessionHandler(harness.player, connection, server, forwardingSecret));
    harness.runPendingTasks();
    // The Handshake (in the handshake state) and the Login Start (in the login state).
    assertEquals(2, harness.framesSentOn(connection).size());
  }

  private void fromBackend(Packet packet) {
    fromBackend(backend, packet);
  }

  private void fromBackend(MinecraftConnection connection, Packet packet) {
    harness.receive(connection, SwitchHarness.frame(VERSION, ProtocolState.LOGIN, packet));
  }

  private LoginSuccess loginSuccess() {
    return new LoginSuccess(harness.player.uuid(), "Steve", List.of(), false, null);
  }

  private static LoginPluginRequest fmlQuery(int id, int innerId) {
    return new LoginPluginRequest(
        id, "fml:loginwrapper", wrapped("fml:handshake", innerId, new byte[0]));
  }

  private void fromClient(LoginPluginResponse response) {
    PacketRegistry.Encoding encoding =
        StateRegistry.get(ProtocolState.LOGIN, PacketDirection.SERVERBOUND)
            .encoding(VERSION, LoginPluginResponse.class);
    @SuppressWarnings("unchecked")
    PacketCodec<Packet> codec = (PacketCodec<Packet>) encoding.codec();
    ByteBuf body = Unpooled.buffer();
    try {
      codec.encode(response, body, VERSION);
      ByteBuf frame = Unpooled.buffer();
      VarInt.write(frame, VarInt.size(encoding.packetId()) + body.readableBytes());
      VarInt.write(frame, encoding.packetId());
      frame.writeBytes(body);
      harness.fromClient(frame);
    } finally {
      body.release();
    }
  }

  /** An {@code fml:loginwrapper} payload: inner channel, inner length, inner id, inner body. */
  private static byte[] wrapped(String innerChannel, int innerId, byte[] innerBody) {
    ByteBuf buf = Unpooled.buffer();
    try {
      McString.write(buf, innerChannel);
      VarInt.write(buf, VarInt.size(innerId) + innerBody.length);
      VarInt.write(buf, innerId);
      buf.writeBytes(innerBody);
      byte[] bytes = new byte[buf.readableBytes()];
      buf.readBytes(bytes);
      return bytes;
    } finally {
      buf.release();
    }
  }

  private List<LoginPluginResponse> answersToBackend() {
    return answersTo(backend);
  }

  private List<LoginPluginResponse> answersTo(MinecraftConnection connection) {
    return harness.packetsSentToBackend(connection).stream()
        .filter(LoginPluginResponse.class::isInstance)
        .map(LoginPluginResponse.class::cast)
        .toList();
  }

  private List<Integer> recordedInnerIds() {
    return harness.player.loginQueries().recorded().stream()
        .map(LoginQueryRelay.RecordedQuery::innerPacketId)
        .toList();
  }

  // ---------------------------------------------------------------------------
  // The marker
  // ---------------------------------------------------------------------------

  @Nested
  @DisplayName("handshake")
  class Handshake {

    @Test
    @DisplayName("should carry the client's marker after the backend's host name")
    void carriesMarker() {
      harness.player.loginQueries().hold(() -> released.set(true));
      backend.setSessionHandler(
          new BackendLoginSessionHandler(
              harness.player, backend, SwitchHarness.LOBBY, new byte[0]));
      harness.runPendingTasks();

      ByteBuf handshake = Unpooled.wrappedBuffer(harness.framesSentOn(backend).getFirst());
      try {
        VarInt.read(handshake); // length
        assertEquals(0x00, VarInt.read(handshake), "handshake id");
        assertEquals(VERSION.protocol(), VarInt.read(handshake));
        assertEquals(
            SwitchHarness.LOBBY.address().getHostString() + "\0FML2\0", McString.read(handshake));
        assertEquals(SwitchHarness.LOBBY.address().getPort(), handshake.readUnsignedShort());
        assertEquals(2, VarInt.read(handshake), "next state login");
      } finally {
        handshake.release();
      }
    }
  }

  // ---------------------------------------------------------------------------
  // Relaying
  // ---------------------------------------------------------------------------

  @Nested
  @DisplayName("relay")
  class Relay {

    @BeforeEach
    void connectWithoutForwarding() {
      connect(new byte[0]);
    }

    @Test
    @DisplayName(
        "should relay a query to the client under a proxy id and the answer back under the backend's")
    void relaysQueryAndAnswer() {
      byte[] modList = wrapped("fml:handshake", 1, new byte[] {1, 2, 3});
      fromBackend(new LoginPluginRequest(7, "fml:loginwrapper", modList));

      List<SwitchHarness.Sent> toClient = harness.sentToClient();
      assertEquals(1, toClient.size());
      LoginPluginRequest relayed = toClient.getFirst().as(LoginPluginRequest.class);
      assertEquals(0, relayed.messageId(), "the proxy's own id");
      assertEquals("fml:loginwrapper", relayed.channel());
      assertArrayEquals(modList, relayed.data());
      assertEquals(1, harness.player.loginQueries().pendingCount());

      byte[] reply = wrapped("fml:handshake", 2, new byte[] {9});
      fromClient(new LoginPluginResponse(0, true, reply));

      List<LoginPluginResponse> answers = answersToBackend();
      assertEquals(1, answers.size());
      assertEquals(7, answers.getFirst().messageId(), "the backend's id");
      assertTrue(answers.getFirst().successful());
      assertArrayEquals(reply, answers.getFirst().data());
      assertEquals(0, harness.player.loginQueries().pendingCount());

      List<LoginQueryRelay.RecordedQuery> recorded = harness.player.loginQueries().recorded();
      assertEquals(1, recorded.size());
      assertEquals("fml:handshake", recorded.getFirst().innerChannel());
      assertEquals(1, recorded.getFirst().innerPacketId());
      assertArrayEquals(reply, recorded.getFirst().response());
      assertTrue(harness.player.loginQueries().recordingComplete());
    }

    @Test
    @DisplayName(
        "should answer each query to the backend under its own id, in the order the client answers")
    void keepsIdsApart() {
      fromBackend(
          new LoginPluginRequest(5, "fml:loginwrapper", wrapped("fml:handshake", 3, new byte[0])));
      fromBackend(
          new LoginPluginRequest(9, "fml:loginwrapper", wrapped("fml:handshake", 4, new byte[0])));
      List<SwitchHarness.Sent> toClient = harness.sentToClient();
      assertEquals(
          List.of(0, 1),
          toClient.stream().map(sent -> sent.as(LoginPluginRequest.class).messageId()).toList());

      fromClient(new LoginPluginResponse(1, true, wrapped("fml:handshake", 99, new byte[0])));
      fromClient(new LoginPluginResponse(0, true, wrapped("fml:handshake", 99, new byte[0])));

      assertEquals(
          List.of(9, 5), answersToBackend().stream().map(LoginPluginResponse::messageId).toList());
      assertEquals(
          List.of(3, 4),
          harness.player.loginQueries().recorded().stream()
              .map(LoginQueryRelay.RecordedQuery::innerPacketId)
              .toList(),
          "recorded in the order the backend asked");
    }

    @Test
    @DisplayName("should relay a channel it does not know as bytes")
    void relaysUnknownChannel() {
      byte[] payload = {4, 5, 6};
      fromBackend(new LoginPluginRequest(1, "somemod:login", payload));

      LoginPluginRequest relayed = harness.sentToClient().getFirst().as(LoginPluginRequest.class);
      assertEquals("somemod:login", relayed.channel());
      assertArrayEquals(payload, relayed.data());

      fromClient(new LoginPluginResponse(0, false, null));
      LoginPluginResponse answer = answersToBackend().getFirst();
      assertEquals(1, answer.messageId());
      assertFalse(answer.successful());
      assertNull(answer.data());
      assertTrue(
          harness.player.loginQueries().recorded().isEmpty(), "only fml:loginwrapper is recorded");
    }

    @Test
    @DisplayName("should drop an answer to a query it never relayed")
    void dropsUnknownId() {
      fromBackend(
          new LoginPluginRequest(5, "fml:loginwrapper", wrapped("fml:handshake", 1, new byte[0])));
      harness.sentToClient();

      fromClient(new LoginPluginResponse(42, true, new byte[] {1}));

      assertTrue(answersToBackend().isEmpty());
      assertEquals(1, harness.player.loginQueries().pendingCount(), "the real query still waits");
      assertTrue(harness.client.channel().isActive());
    }

    @Test
    @DisplayName("should refuse a query over 1 MiB on the backend's side and spare the client")
    void refusesOversized() {
      byte[] huge = new byte[LoginQueryRelay.MAX_PAYLOAD + 1];
      fromBackend(new LoginPluginRequest(3, "fml:loginwrapper", huge));

      assertTrue(harness.sentToClient().isEmpty(), "nothing reaches the client");
      LoginPluginResponse answer = answersToBackend().getFirst();
      assertEquals(3, answer.messageId());
      assertFalse(answer.successful());
      assertEquals(0, harness.player.loginQueries().pendingCount());
    }

    @Test
    @DisplayName("should drop the backend that leaves too many queries unanswered")
    void capsPending() {
      for (int id = 0; id <= LoginQueryRelay.MAX_PENDING; id++) {
        fromBackend(new LoginPluginRequest(id, "fml:loginwrapper", new byte[] {1}));
      }

      assertEquals(LoginQueryRelay.MAX_PENDING, harness.sentToClient().size());
      assertFalse(backend.channel().isActive(), "the backend is dropped");
      assertEquals(0, harness.player.loginQueries().pendingCount(), "its queries went with it");
    }

    @Test
    @DisplayName("should release the client's Login Success when the backend logs the player in")
    void releasesOnLoginSuccess() {
      fromBackend(
          new LoginPluginRequest(1, "fml:loginwrapper", wrapped("fml:handshake", 1, new byte[0])));
      harness.sentToClient();
      assertFalse(released.get());

      fromBackend(new LoginSuccess(harness.player.uuid(), "Steve", List.of(), false, null));

      assertTrue(released.get());
      assertFalse(harness.player.loginQueries().isHeld());
      assertEquals(
          0,
          harness.player.loginQueries().pendingCount(),
          "queries the backend did not wait for are dropped");
      assertEquals(
          ProtocolState.PLAY, backend.encoder().state(), "the backend moves on to its Join Game");
    }

    @Test
    @DisplayName(
        "should forget the queries of a backend that disconnects, and drop the client's late answers")
    void forgetsOnBackendDisconnect() {
      fromBackend(
          new LoginPluginRequest(1, "fml:loginwrapper", wrapped("fml:handshake", 1, new byte[0])));
      harness.sentToClient();

      backend.channel().close();
      harness.runPendingTasks();

      assertEquals(0, harness.player.loginQueries().pendingCount());
      assertTrue(
          harness.player.loginQueries().isHeld(), "the client stays held for the next server");
      fromClient(new LoginPluginResponse(0, true, new byte[] {1}));
      assertTrue(harness.client.channel().isActive());
    }
  }

  // ---------------------------------------------------------------------------
  // Several logins on one held client
  // ---------------------------------------------------------------------------

  @Nested
  @DisplayName("several logins")
  class SeveralLogins {

    @BeforeEach
    void connectWithoutForwarding() {
      connect(new byte[0]);
    }

    @Test
    @DisplayName(
        "should keep the client held when a backend the switch abandoned logs the player in")
    void ignoresLoginSuccessOfAbandonedBackend() {
      fromBackend(fmlQuery(1, 1));
      harness.sentToClient();
      MinecraftConnection joining = connectAnother(SwitchHarness.SURVIVAL);

      fromBackend(loginSuccess());

      assertFalse(released.get(), "the first login is not the one the player follows");
      assertTrue(harness.player.loginQueries().isHeld());
      assertEquals(0, harness.player.loginQueries().pendingCount(), "its queries went with it");
      assertEquals(
          ProtocolState.PLAY, backend.encoder().state(), "it still moves on, to be abandoned");

      fromBackend(joining, fmlQuery(1, 1));
      LoginPluginRequest relayed = harness.sentToClient().getFirst().as(LoginPluginRequest.class);
      assertEquals(1, relayed.messageId(), "the joining backend's query still reaches the client");
      fromClient(new LoginPluginResponse(1, true, wrapped("fml:handshake", 2, new byte[0])));
      assertEquals(1, answersTo(joining).size());

      fromBackend(joining, loginSuccess());

      assertTrue(released.get(), "the joining backend's Login Success releases the client");
      assertFalse(harness.player.loginQueries().isHeld());
    }

    @Test
    @DisplayName("should answer not understood to a backend the switch abandoned, not the client")
    void refusesQueriesOfAbandonedBackend() {
      connectAnother(SwitchHarness.SURVIVAL);

      fromBackend(fmlQuery(3, 1));

      assertTrue(harness.sentToClient().isEmpty());
      LoginPluginResponse answer = answersToBackend().getFirst();
      assertEquals(3, answer.messageId());
      assertFalse(answer.successful());
      assertEquals(0, harness.player.loginQueries().pendingCount());
    }

    @Test
    @DisplayName("should drop every pending query once the joining backend logs the player in")
    void dropsAllPendingOnRelease() {
      fromBackend(fmlQuery(1, 1));
      MinecraftConnection joining = connectAnother(SwitchHarness.SURVIVAL);
      fromBackend(joining, fmlQuery(1, 1));
      assertEquals(2, harness.sentToClient().size());

      fromBackend(joining, loginSuccess());

      assertTrue(released.get());
      assertEquals(0, harness.player.loginQueries().pendingCount());
      fromClient(new LoginPluginResponse(0, true, new byte[] {1}));
      assertTrue(answersToBackend().isEmpty(), "the abandoned backend gets no late answer");
    }
  }

  // ---------------------------------------------------------------------------
  // The record across logins
  // ---------------------------------------------------------------------------

  @Nested
  @DisplayName("record")
  class Record {

    @BeforeEach
    void connectWithoutForwarding() {
      connect(new byte[0]);
    }

    @Test
    @DisplayName(
        "should forget the exchanges of a backend that disconnects, so the record is the fallback's alone")
    void forgetsRecordOfBackendThatLeft() {
      fromBackend(fmlQuery(1, 1));
      fromBackend(fmlQuery(2, 3));
      harness.sentToClient();
      fromClient(new LoginPluginResponse(0, true, wrapped("fml:handshake", 2, new byte[0])));
      fromClient(new LoginPluginResponse(1, true, wrapped("fml:handshake", 3, new byte[0])));
      assertEquals(List.of(1, 3), recordedInnerIds());

      backend.channel().close();
      harness.runPendingTasks();

      assertTrue(harness.player.loginQueries().recorded().isEmpty());
      assertTrue(harness.player.loginQueries().recordingComplete());

      MinecraftConnection fallback = connectAnother(SwitchHarness.SURVIVAL);
      fromBackend(fallback, fmlQuery(1, 1));
      fromBackend(fallback, fmlQuery(2, 4));
      harness.sentToClient();
      fromClient(new LoginPluginResponse(2, true, wrapped("fml:handshake", 2, new byte[0])));
      fromClient(new LoginPluginResponse(3, true, wrapped("fml:handshake", 4, new byte[0])));

      assertEquals(List.of(1, 4), recordedInnerIds(), "the fallback's exchanges only");
      assertTrue(harness.player.loginQueries().recordingComplete());
    }

    @Test
    @DisplayName("should forget the exchanges of a backend that refuses the login")
    void forgetsRecordOnLoginDisconnect() {
      fromBackend(fmlQuery(1, 1));
      harness.sentToClient();
      fromClient(new LoginPluginResponse(0, true, wrapped("fml:handshake", 2, new byte[0])));
      assertEquals(1, harness.player.loginQueries().recorded().size());

      fromBackend(LoginDisconnect.ofPlainText("whitelist"));

      assertTrue(harness.player.loginQueries().recorded().isEmpty());
      assertTrue(harness.player.loginQueries().recordingComplete());
    }

    @Test
    @DisplayName("should start the record over, complete, after a cut record's backend leaves")
    void resetsCompletenessWhenBackendLeaves() {
      for (int id = 0; id <= LoginQueryRelay.MAX_RECORDED; id++) {
        fromBackend(fmlQuery(id, 3));
        harness.sentToClient();
        fromClient(new LoginPluginResponse(id, true, new byte[0]));
      }
      assertEquals(LoginQueryRelay.MAX_RECORDED, harness.player.loginQueries().recorded().size());
      assertFalse(harness.player.loginQueries().recordingComplete());

      backend.channel().close();
      harness.runPendingTasks();

      assertTrue(harness.player.loginQueries().recorded().isEmpty());
      assertTrue(harness.player.loginQueries().recordingComplete());
    }

    @Test
    @DisplayName("should record only the exchanges of the backend the player is joining")
    void recordsJoiningBackendOnly() {
      fromBackend(fmlQuery(1, 1));
      harness.sentToClient();
      fromClient(new LoginPluginResponse(0, true, wrapped("fml:handshake", 2, new byte[0])));
      assertEquals(
          List.of(1), recordedInnerIds(), "the first login's record, while it is the only one");

      MinecraftConnection joining = connectAnother(SwitchHarness.SURVIVAL);
      fromBackend(joining, fmlQuery(1, 1));
      fromBackend(joining, fmlQuery(2, 3));
      harness.sentToClient();
      fromClient(new LoginPluginResponse(1, true, wrapped("fml:handshake", 2, new byte[0])));
      assertEquals(
          List.of(1), recordedInnerIds(), "the joining backend's first exchange replaces it");

      fromBackend(loginSuccess());
      fromClient(new LoginPluginResponse(2, true, wrapped("fml:handshake", 3, new byte[0])));
      fromBackend(joining, loginSuccess());

      assertTrue(released.get());
      assertEquals(List.of(1, 3), recordedInnerIds(), "the joining backend's exchanges only");
      assertTrue(harness.player.loginQueries().recordingComplete());
    }
  }

  // ---------------------------------------------------------------------------
  // What is still answered on the proxy
  // ---------------------------------------------------------------------------

  @Nested
  @DisplayName("proxy's own answers")
  class ProxyAnswers {

    @Test
    @DisplayName(
        "should still answer the Velocity forwarding query itself, with nothing sent to the client")
    void answersForwardingQuery() {
      connect(SECRET);

      fromBackend(new LoginPluginRequest(4, PlayerForwarding.VELOCITY_CHANNEL, new byte[0]));

      assertTrue(harness.sentToClient().isEmpty());
      LoginPluginResponse answer = answersToBackend().getFirst();
      assertEquals(4, answer.messageId());
      assertTrue(answer.successful());
      assertArrayEquals(
          PlayerForwarding.buildVelocityForwardingData(harness.player, SECRET), answer.data());
      assertEquals(0, harness.player.loginQueries().pendingCount());
    }

    @Test
    @DisplayName("should answer not understood when the client is not held in its login state")
    void refusesWhenNotHeld() {
      backend.setSessionHandler(
          new BackendLoginSessionHandler(
              harness.player, backend, SwitchHarness.LOBBY, new byte[0]));
      harness.runPendingTasks();
      harness.framesSentOn(backend);

      fromBackend(
          new LoginPluginRequest(2, "fml:loginwrapper", wrapped("fml:handshake", 1, new byte[0])));

      assertTrue(harness.sentToClient().isEmpty());
      LoginPluginResponse answer = answersToBackend().getFirst();
      assertEquals(2, answer.messageId());
      assertFalse(answer.successful());
    }
  }
}
