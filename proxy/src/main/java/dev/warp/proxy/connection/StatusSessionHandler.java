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

import dev.warp.api.server.ServerPing;
import dev.warp.protocol.legacy.LegacyPing;
import dev.warp.protocol.netty.SessionHandler;
import dev.warp.protocol.packet.Packet;
import dev.warp.protocol.packet.status.PingRequest;
import dev.warp.protocol.packet.status.PongResponse;
import dev.warp.protocol.packet.status.StatusPacket;
import dev.warp.protocol.packet.status.StatusRequest;
import dev.warp.protocol.packet.status.StatusResponse;
import dev.warp.proxy.event.WarpEventBus;
import dev.warp.proxy.event.proxy.ProxyPingEventImpl;
import dev.warp.proxy.status.ServerList;

import java.util.function.Consumer;

import io.netty.channel.ChannelFutureListener;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Handles the server list ping (SLP) flow after a handshake with {@code nextState = 1}, and answers
 * legacy pings ({@link #answerLegacy}).
 *
 * <p>Expected packet flow:
 *
 * <pre>{@code
 * C → S: StatusRequest        (empty: asks for the entry)
 * S → C: StatusResponse       (JSON with version, players, MOTD, icon)
 * C → S: PingRequest          (long payload: client timestamp)
 * S → C: PongResponse         (echo payload)
 *        [connection closes]
 * }</pre>
 *
 * <p>The entry comes from {@link ServerList}: a cached answer when no plugin listens to {@link
 * dev.warp.api.event.proxy.ProxyPingEvent}, otherwise the entry the handlers leave, answered on the
 * event loop once they are done. A {@link StatusRequest} must come before any {@link PingRequest};
 * duplicate requests are ignored, and a ping that arrives while the handlers still hold the entry
 * is answered right after it.
 *
 * <p>A ping counts against its address's server list ping limit, apart from its logins: an address
 * over the limit is closed without an answer, and can still log in.
 */
final class StatusSessionHandler implements SessionHandler {

  private static final Logger logger = LoggerFactory.getLogger(StatusSessionHandler.class);

  private enum State {
    AWAITING_REQUEST,
    ANSWERING,
    ANSWERED
  }

  private final MinecraftConnection connection;
  private final ServerLoginContext context;

  /** The protocol the client's handshake announced, supported or not. */
  private final int clientProtocol;

  private State state = State.AWAITING_REQUEST;

  /** A ping that arrived while the handlers held the entry, answered after it. */
  private @Nullable PingRequest pendingPing;

  /** Whether the ping was over its address's limit: closing, whatever follows is dropped. */
  private boolean refused;

  /**
   * Creates the handler of one server list ping, counted against the proxy's limits.
   *
   * @param connection the client connection
   * @param context the proxy's shared state, whose server list answers
   * @param clientProtocol the protocol the client's handshake announced, supported or not
   */
  StatusSessionHandler(
      MinecraftConnection connection, ServerLoginContext context, int clientProtocol) {
    this.connection = connection;
    this.context = context;
    this.clientProtocol = clientProtocol;
  }

  @Override
  public void activated() {
    if (!context.limits().admitStatusPing(connection.remoteAddress())) {
      // Over the limit: no answer, and no work. Counted by the limits, never logged one by one.
      refused = true;
      connection.close();
    }
  }

  @Override
  public void handle(Packet packet) {
    if (refused) {
      return; // Requests that arrived with the refused handshake.
    }
    if (!(packet instanceof StatusPacket statusPacket)) {
      logger.warn("Unexpected packet in STATUS state: {}", packet.getClass().getSimpleName());
      connection.close();
      return;
    }

    // Exhaustive switch on the sealed StatusPacket hierarchy.
    switch (statusPacket) {
      case StatusRequest _ -> handleStatusRequest();
      case PingRequest ping -> handlePingRequest(ping);
      // Clientbound packets should never arrive from a client.
      case StatusResponse _, PongResponse _ -> connection.close();
    }
  }

  private void handleStatusRequest() {
    if (state != State.AWAITING_REQUEST) {
      return; // A duplicate: ignored, as vanilla does.
    }
    state = State.ANSWERING;
    ServerList serverList = context.serverList();
    if (!context.events().hasHandlers(ProxyPingEventImpl.class)) {
      answer(serverList.json(clientProtocol));
      return;
    }
    resolve(
        connection,
        context.events(),
        new WarpInboundConnection(connection, clientProtocol),
        serverList.proposal(clientProtocol),
        ping -> answer(serverList.json(ping, clientProtocol)));
  }

  private void answer(String json) {
    state = State.ANSWERED;
    connection.writeAndFlush(new StatusResponse(json));
    PingRequest pending = this.pendingPing;
    if (pending != null) {
      this.pendingPing = null;
      connection.writeAndClose(new PongResponse(pending.payload()));
    }
  }

  private void handlePingRequest(PingRequest ping) {
    switch (state) {
      // A ping before any request: a protocol violation.
      case AWAITING_REQUEST -> connection.close();
      case ANSWERING -> {
        if (pendingPing == null) {
          pendingPing = ping;
        }
      }
      // Echo the payload and close after the write reaches the socket.
      case ANSWERED -> connection.writeAndClose(new PongResponse(ping.payload()));
    }
  }

  // ---------------------------------------------------------------------------
  // Legacy ping
  // ---------------------------------------------------------------------------

  /**
   * Answers a legacy server list ping (Minecraft 1.6 and older, and load balancer health checks)
   * with the proxy's entry, through {@link dev.warp.api.event.proxy.ProxyPingEvent} like any ping,
   * then closes the connection.
   *
   * @param connection the client connection
   * @param context the proxy's shared state
   * @param ping the ping
   */
  static void answerLegacy(
      MinecraftConnection connection, ServerLoginContext context, LegacyPing ping) {
    String host = ping.host();
    if (host != null) {
      connection.setVirtualHost(host);
    }
    ServerList serverList = context.serverList();
    Consumer<ServerPing> answer =
        entry ->
            connection
                .channel()
                .writeAndFlush(serverList.legacyAnswer(entry, ping.format()))
                .addListener(ChannelFutureListener.CLOSE);
    if (!context.events().hasHandlers(ProxyPingEventImpl.class)) {
      answer.accept(serverList.legacyProposal());
      return;
    }
    resolve(
        connection,
        context.events(),
        new WarpInboundConnection(connection, WarpInboundConnection.LEGACY_PROTOCOL),
        serverList.legacyProposal(),
        answer);
  }

  // ---------------------------------------------------------------------------
  // Event
  // ---------------------------------------------------------------------------

  /**
   * Lets the ping handlers decide the entry, then answers with it on the connection's event loop,
   * or closes the connection if a handler chose not to answer.
   */
  private static void resolve(
      MinecraftConnection connection,
      WarpEventBus events,
      WarpInboundConnection inbound,
      ServerPing proposal,
      Consumer<ServerPing> answer) {
    var _ =
        events
            .fire(new ProxyPingEventImpl(inbound, proposal))
            .whenCompleteAsync(
                (event, _) -> {
                  ServerPing entry = event.answer();
                  if (!connection.channel().isActive()) {
                    return;
                  }
                  if (entry == null) {
                    connection.close();
                    return;
                  }
                  answer.accept(entry);
                },
                connection.channel().eventLoop());
  }
}
