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

import dev.warp.protocol.codec.McString;
import dev.warp.protocol.codec.VarInt;
import dev.warp.protocol.packet.login.LoginPluginRequest;
import dev.warp.protocol.packet.login.LoginPluginResponse;
import dev.warp.proxy.logging.LogCode;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Relays a backend's login plugin requests (login queries, 1.13+) to the client and the client's
 * answers back, while the client is held in its login state for them.
 *
 * <p>A Forge client of 1.13 to 1.20.1 negotiates its mods with login queries on {@code
 * fml:loginwrapper} before Login Success, each needing exactly one answer, and the mod list the
 * client answers with is fixed for the connection. The proxy is the client's server, and sends its
 * own Login Success: for such a client, the {@link LoginSessionHandler} holds it until the first
 * backend has logged the player in, and this class carries the queries in between. Message ids are
 * per connection, so each backend query gets a proxy id on the way to the client, and the answer
 * goes back under the backend's id. Every channel but the proxy's own forwarding query is relayed
 * the same way, which also carries any other login-phase plugin protocol the client speaks.
 *
 * <p>The payloads are never deserialized beyond what the relay needs: for {@code fml:loginwrapper},
 * the inner channel and packet id, and a hash of the request, are recorded with the answer so that
 * a later server switch can replay the exchange to a server with the same mods (a later milestone).
 * Everything else is bytes.
 *
 * <p>Bounds: at most {@value #MAX_PENDING} queries in flight, each at most {@value #MAX_PAYLOAD}
 * bytes (the client's own limit; Forge splits bigger registries), at most {@value #MAX_RECORDED}
 * exchanges recorded. A backend that goes (disconnects, or refuses the login) takes its pending
 * queries with it: a late answer from the client is then dropped as unknown.
 *
 * <p>All methods run on the client's event loop, which the backend connection shares.
 */
final class LoginQueryRelay {

  private static final Logger logger = LoggerFactory.getLogger(LoginQueryRelay.class);

  /** The most queries a backend may leave unanswered at once. */
  static final int MAX_PENDING = 256;

  /** The largest login query payload a client accepts, 1 MiB: vanilla's and Forge's limit. */
  static final int MAX_PAYLOAD = 1 << 20;

  /** The most exchanges recorded for a later replay. */
  static final int MAX_RECORDED = 512;

  /** Forge's login channel of the FML2 era, whose exchanges are recorded. */
  static final String FML_LOGIN_WRAPPER = "fml:loginwrapper";

  private final ConnectedPlayer player;
  private final MinecraftConnection client;

  /** Releases the client's held Login Success; {@code null} when the client is not held. */
  private @Nullable Runnable release;

  /** Queries sent to the client, by proxy id, awaiting its answer. */
  private final Map<Integer, Pending> pending = new HashMap<>();

  private int nextId;

  private final List<RecordedQuery> recorded = new ArrayList<>();

  /** {@code false} once an exchange went unrecorded: a replay from the record is impossible. */
  private boolean recordingComplete = true;

  private boolean closed;

  LoginQueryRelay(ConnectedPlayer player) {
    this.player = player;
    this.client = player.clientConnection();
  }

  // ---------------------------------------------------------------------------
  // Holding the client's login
  // ---------------------------------------------------------------------------

  /**
   * Holds the client's Login Success until the first backend logs the player in: queries can be
   * relayed from now on.
   *
   * @param release sends the client its Login Success and moves it on
   */
  void hold(Runnable release) {
    this.release = release;
  }

  /**
   * Returns whether the client is held in its login state, so that queries can be relayed to it.
   *
   * @return {@code true} while held
   */
  boolean isHeld() {
    return release != null;
  }

  /**
   * The backend logged the player in: nothing more to relay, the client's Login Success goes out.
   * Pending queries are those the backend did not wait for; its login is over, so they are dropped.
   *
   * @param backend the backend that sent Login Success
   */
  void backendLoggedIn(MinecraftConnection backend) {
    backendGone(backend);
    Runnable held = this.release;
    if (held != null) {
      this.release = null;
      held.run();
    }
  }

  // ---------------------------------------------------------------------------
  // From the backend
  // ---------------------------------------------------------------------------

  /**
   * Relays a backend's query to the client under a proxy id.
   *
   * @param backend the backend that asked
   * @param request its query
   * @return {@code false} if the client is not held in its login state, which leaves the query for
   *     the caller to answer; {@code true} if it was relayed, or refused here for its size or
   *     because too many are in flight, in which case the backend got its answer or was closed
   */
  boolean relay(MinecraftConnection backend, LoginPluginRequest request) {
    if (closed || !isHeld() || !client.channel().isActive()) {
      return false;
    }
    if (request.data().length > MAX_PAYLOAD) {
      // The client would drop the connection over it; refusing it here lets the backend decide.
      logger.warn(
          LogCode.LOGIN_QUERY_TOO_LARGE.marker(),
          "Refused a {} byte login query on {} from {} for {}: clients accept {} bytes at most",
          request.data().length,
          request.channel(),
          backend.remoteAddress(),
          player.username(),
          MAX_PAYLOAD);
      backend.writeAndFlush(new LoginPluginResponse(request.messageId(), false, null));
      return true;
    }
    if (pending.size() >= MAX_PENDING) {
      logger.warn(
          LogCode.LOGIN_QUERIES_UNANSWERED.marker(),
          "Dropping {} for {}: {} login queries left unanswered",
          backend.remoteAddress(),
          player.username(),
          pending.size());
      backend.close();
      return true;
    }
    int proxyId = nextId++;
    pending.put(
        proxyId,
        new Pending(
            proxyId,
            backend,
            request.messageId(),
            request.channel(),
            FML_LOGIN_WRAPPER.equals(request.channel()) ? innerOf(request.data()) : null,
            sha256(request.data())));
    client.writeAndFlush(new LoginPluginRequest(proxyId, request.channel(), request.data()));
    return true;
  }

  /**
   * Drops the queries of a backend that left, or whose login is over.
   *
   * @param backend the backend
   */
  void backendGone(MinecraftConnection backend) {
    pending.values().removeIf(query -> query.backend == backend);
  }

  // ---------------------------------------------------------------------------
  // From the client
  // ---------------------------------------------------------------------------

  /**
   * Forwards the client's answer to the backend that asked, under the backend's id.
   *
   * @param response the client's answer
   * @return {@code false} if no query of that id is pending, which a game client never does
   */
  boolean answered(LoginPluginResponse response) {
    Pending query = pending.remove(response.messageId());
    if (query == null) {
      logger.debug(
          "Dropped an answer to login query {} that {} never got",
          response.messageId(),
          player.username());
      return false;
    }
    record(query, response);
    MinecraftConnection backend = query.backend;
    if (!backend.channel().isActive() || !backend.encoder().canEncode(LoginPluginResponse.class)) {
      logger.trace("Dropped a login query answer for a backend {} left", player.username());
      return true;
    }
    backend.writeAndFlush(
        new LoginPluginResponse(query.backendId, response.successful(), response.data()));
    return true;
  }

  // ---------------------------------------------------------------------------
  // Recording
  // ---------------------------------------------------------------------------

  private void record(Pending query, LoginPluginResponse response) {
    if (!FML_LOGIN_WRAPPER.equals(query.channel)) {
      return;
    }
    if (recorded.size() >= MAX_RECORDED) {
      recordingComplete = false;
      return;
    }
    Inner inner = query.inner != null ? query.inner : Inner.UNKNOWN;
    RecordedQuery exchange =
        new RecordedQuery(
            query.proxyId,
            query.channel,
            inner.channel(),
            inner.packetId(),
            query.requestHash,
            response.successful(),
            response.data());
    // In the order the backend asked, whatever order the client answers in.
    int at = recorded.size();
    while (at > 0 && recorded.get(at - 1).sequence() > exchange.sequence()) {
      at--;
    }
    recorded.add(at, exchange);
  }

  /**
   * Returns the {@code fml:loginwrapper} exchanges of the first login, in order, for a replay to a
   * server with the same mods.
   *
   * @return the exchanges, unmodifiable
   */
  List<RecordedQuery> recorded() {
    return Collections.unmodifiableList(recorded);
  }

  /**
   * Returns whether {@link #recorded()} holds every exchange of the first login.
   *
   * @return {@code false} if the record was cut at {@value #MAX_RECORDED} exchanges
   */
  boolean recordingComplete() {
    return recordingComplete;
  }

  /**
   * Returns how many queries await the client's answer.
   *
   * @return the count
   */
  int pendingCount() {
    return pending.size();
  }

  // ---------------------------------------------------------------------------
  // Lifecycle
  // ---------------------------------------------------------------------------

  /** The client left: nothing more is relayed. */
  void close() {
    closed = true;
    release = null;
    pending.clear();
  }

  // ---------------------------------------------------------------------------
  // Payload reading
  // ---------------------------------------------------------------------------

  /**
   * Reads the inner channel and packet id of an {@code fml:loginwrapper} payload: a string, the
   * inner packet's length, then the inner packet, whose first VarInt is its id. Nothing after.
   *
   * @return what was read, or {@code null} when the payload is not shaped like that
   */
  static @Nullable Inner innerOf(byte[] payload) {
    ByteBuf buf = Unpooled.wrappedBuffer(payload);
    try {
      String channel = McString.read(buf);
      int length = VarInt.read(buf);
      if (length <= 0 || length > buf.readableBytes()) {
        return null;
      }
      return new Inner(channel, VarInt.read(buf));
    } catch (RuntimeException malformed) {
      return null;
    } finally {
      buf.release();
    }
  }

  private static byte[] sha256(byte[] data) {
    try {
      return MessageDigest.getInstance("SHA-256").digest(data);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 is a required algorithm", e);
    }
  }

  // ---------------------------------------------------------------------------
  // Types
  // ---------------------------------------------------------------------------

  /**
   * The inner channel and packet of an {@code fml:loginwrapper} query.
   *
   * @param channel the inner channel, {@code fml:handshake} for the mod negotiation
   * @param packetId the inner packet id: 1 Mod List, 3 Registry, 4 Configuration Data
   */
  record Inner(String channel, int packetId) {
    /** A payload whose shape was not the wrapper's. */
    static final Inner UNKNOWN = new Inner("", -1);
  }

  /**
   * One {@code fml:loginwrapper} exchange of the first login.
   *
   * @param sequence the query's rank among all the queries relayed on this connection, from 0
   * @param channel the query's channel
   * @param innerChannel the wrapper's inner channel, empty if unread
   * @param innerPacketId the wrapper's inner packet id, {@code -1} if unread
   * @param requestHash SHA-256 of the query's payload, to tell whether another server asks the same
   * @param successful whether the client understood the query
   * @param response the client's answer, as sent, {@code null} when not understood
   */
  @SuppressWarnings("ArrayRecordComponent") // bytes as sent, never compared by equals
  record RecordedQuery(
      int sequence,
      String channel,
      String innerChannel,
      int innerPacketId,
      byte[] requestHash,
      boolean successful,
      byte @Nullable [] response) {}

  @SuppressWarnings("ArrayRecordComponent")
  private record Pending(
      int proxyId,
      MinecraftConnection backend,
      int backendId,
      String channel,
      @Nullable Inner inner,
      byte[] requestHash) {}
}
