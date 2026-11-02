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
package dev.warp.proxy.messaging;

import dev.warp.api.messaging.ChannelIdentifier;
import dev.warp.api.player.ConnectionResult;
import dev.warp.protocol.ProtocolVersion;
import dev.warp.proxy.connection.ConnectedPlayer;
import dev.warp.proxy.connection.LogThrottle;
import dev.warp.proxy.connection.MinecraftConnection;
import dev.warp.proxy.player.PlayerRegistry;
import dev.warp.proxy.server.BackendServer;
import dev.warp.proxy.server.ServerRegistry;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UTFDataFormatException;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.LongSupplier;
import java.util.stream.Collectors;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.gson.GsonComponentSerializer;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Answers the BungeeCord plugin messaging channel: the requests backend plugins send through a
 * player's connection to move players and query the network, as BungeeCord and Velocity answer
 * them.
 *
 * <p>Replies are byte for byte those of BungeeCord ({@code DownstreamBridge}) and Velocity ({@code
 * BungeeCordMessageResponder}), which agree on every reply to a well-formed request. Where they
 * differ, Warp takes the answer that leaves no plugin waiting or misled:
 *
 * <ul>
 *   <li>{@code GetPlayerServer} always answers, as BungeeCord does: with the name as asked and an
 *       empty server when the player is offline or on no server yet. Velocity says nothing then.
 *   <li>{@code PlayerCount} and {@code PlayerList} for an unknown server answer nothing, as on
 *       Velocity. BungeeCord answers the sub-channel's name alone, which a plugin reading the rest
 *       fails on.
 *   <li>{@code GetServer} names the server that asked, as on BungeeCord; Velocity names the
 *       player's current server, the same except during a switch.
 *   <li>{@code ServerIP} answers the host as configured, as on Velocity: an address name is kept,
 *       not resolved on the event loop. BungeeCord answers the resolved address, nothing if
 *       unresolved.
 *   <li>{@code Forward} and {@code ForwardToPlayer} deliver exactly the bytes BungeeCord does (the
 *       sub-channel, the length and the data, nothing after), and drop a message whose length does
 *       not match its data, where BungeeCord fails the connection and Velocity forwards the rest as
 *       it is.
 * </ul>
 *
 * <p>On top of both:
 *
 * <ul>
 *   <li>{@code Connect} and {@code ConnectOther} go through the proxy's own switch: {@code
 *       ServerPreConnectEvent} fires, the player stays where it is if the server refuses it, and is
 *       told when nothing happens (already there, already switching, refused by a plugin).
 *   <li>Each server only uses the sub-channels its {@code bungeecord-channel.allow} rule allows.
 *   <li>A {@code Forward} to a server nobody plays on waits up to {@code forward-hold} for a player
 *       to carry it ({@link HeldForwards}), {@code ONLINE} excepted.
 *   <li>Nothing that cannot reach a backend whole is sent: a backend refuses a plugin message from
 *       a client above 32767 bytes, and disconnects the player that carried it.
 *   <li>A malformed request, an unknown server or a refused sub-channel is logged (at most once a
 *       minute for each kind), never silently ignored, and never disconnects anyone.
 * </ul>
 *
 * <p>Requests are handled on the event loop of the player that carries them; the players they act
 * on are reached through their own thread-safe methods.
 */
public final class BungeeCordChannel {

  private static final Logger logger = LoggerFactory.getLogger(BungeeCordChannel.class);

  /** The channel's name before Minecraft 1.13. */
  public static final String LEGACY_NAME = "BungeeCord";

  /** The channel's name from Minecraft 1.13. */
  public static final String MODERN_NAME = "bungeecord:main";

  /** The channel before 1.13, as the API names it. */
  public static final ChannelIdentifier LEGACY = new ChannelIdentifier(LEGACY_NAME);

  /** The channel from 1.13, as the API names it. */
  public static final ChannelIdentifier MODERN = new ChannelIdentifier(MODERN_NAME);

  /**
   * The largest plugin message a backend accepts from a client: vanilla's limit on a serverbound
   * custom payload, which disconnects the player beyond it ({@code
   * ServerboundCustomPayloadPacket.MAX_PAYLOAD_SIZE}, and the same check since 1.8).
   */
  static final int MAX_SERVERBOUND_PAYLOAD = 32767;

  /** The target of {@code PlayerCount}, {@code PlayerList}, {@code Message}: every player. */
  private static final String ALL = "ALL";

  /** The target of {@code Forward} meaning every other server that has a player right now. */
  private static final String ONLINE = "ONLINE";

  /** The longest name a log line quotes from a backend's message. */
  private static final int MAX_LOGGED_NAME = 64;

  private static final Duration WARNING_INTERVAL = Duration.ofMinutes(1);

  private final BungeeCordSettings settings;
  private final PlayerRegistry players;
  private final ServerRegistry servers;
  private final HeldForwards held;

  private final LogThrottle malformedWarnings = new LogThrottle(WARNING_INTERVAL);
  private final LogThrottle deniedWarnings = new LogThrottle(WARNING_INTERVAL);
  private final LogThrottle unknownServerWarnings = new LogThrottle(WARNING_INTERVAL);
  private final LogThrottle tooLargeWarnings = new LogThrottle(WARNING_INTERVAL);
  private final LogThrottle droppedForwardWarnings = new LogThrottle(WARNING_INTERVAL);

  /**
   * Creates the channel's handler.
   *
   * @param settings the {@code bungeecord-channel} block
   * @param players the connected players
   * @param servers the backend servers
   */
  public BungeeCordChannel(
      BungeeCordSettings settings, PlayerRegistry players, ServerRegistry servers) {
    this(settings, players, servers, System::nanoTime);
  }

  /**
   * Creates the channel's handler on a given clock.
   *
   * @param settings the {@code bungeecord-channel} block
   * @param players the connected players
   * @param servers the backend servers
   * @param clock a monotonic clock in nanoseconds, for the held forwards
   */
  BungeeCordChannel(
      BungeeCordSettings settings,
      PlayerRegistry players,
      ServerRegistry servers,
      LongSupplier clock) {
    this.settings = settings;
    this.players = players;
    this.servers = servers;
    this.held = new HeldForwards(settings.forwardHold().toNanos(), clock, this::forwardsDropped);
  }

  /**
   * Returns a handler that answers nothing.
   *
   * @return the handler of a proxy with the channel disabled
   */
  public static BungeeCordChannel disabled() {
    return new BungeeCordChannel(
        BungeeCordSettings.DISABLED,
        new PlayerRegistry(),
        new ServerRegistry(Map.of(), null, List.of()));
  }

  // ---------------------------------------------------------------------------
  // Channel
  // ---------------------------------------------------------------------------

  /**
   * Returns whether a channel is the BungeeCord channel, under either of its names. Both are
   * accepted from every version, as BungeeCord and Velocity accept them.
   *
   * @param wireName the channel's name as it came on the wire
   * @return {@code true} for {@code BungeeCord} and {@code bungeecord:main}
   */
  public static boolean isChannel(String wireName) {
    return wireName.equals(MODERN_NAME) || wireName.equals(LEGACY_NAME);
  }

  /**
   * Returns the channel's name for a connection: Bukkit renamed it with the 1.13 channel names, and
   * a backend only hands its plugins the name of its own version.
   *
   * @param version the connection's protocol version
   * @return {@code bungeecord:main} from 1.13, {@code BungeeCord} before
   */
  public static String nameFor(ProtocolVersion version) {
    return version.isAtLeast(ProtocolVersion.MINECRAFT_1_13) ? MODERN_NAME : LEGACY_NAME;
  }

  /**
   * Returns the channel by the name it came with, as the API names it.
   *
   * @param wireName {@code BungeeCord} or {@code bungeecord:main}
   * @return the channel
   */
  public static ChannelIdentifier identifier(String wireName) {
    return wireName.equals(MODERN_NAME) ? MODERN : LEGACY;
  }

  /**
   * Returns whether the proxy answers the channel.
   *
   * @return {@code false} when {@code bungeecord-channel.enabled} is off
   */
  public boolean enabled() {
    return settings.enabled();
  }

  /**
   * Returns whether a server may use any sub-channel: the proxy then registers the channel on the
   * player's behalf with it, as BungeeCord and Velocity do, since Bukkit only lets plugins send on
   * a channel the client registered.
   *
   * @param server the server's name
   * @return {@code true} if its rule allows at least one sub-channel
   */
  public boolean speaksWith(String server) {
    return !settings.allowed(server).isEmpty();
  }

  // ---------------------------------------------------------------------------
  // Requests
  // ---------------------------------------------------------------------------

  /**
   * Answers a request a backend sent. Runs on the carrying player's event loop.
   *
   * @param carrier the player whose connection carried it
   * @param from the server that sent it
   * @param over the connection it came over, where replies go
   * @param payload the message's payload
   */
  public void handle(
      ConnectedPlayer carrier, BackendServer from, MinecraftConnection over, byte[] payload) {
    DataInputStream in = new DataInputStream(new ByteArrayInputStream(payload));
    String name;
    try {
      name = in.readUTF();
    } catch (IOException e) {
      malformed(from, "(no sub-channel)", e);
      return;
    }
    BungeeCordSubchannel subchannel = BungeeCordSubchannel.fromWireName(name);
    if (subchannel == null) {
      logger.debug(
          "Ignored unknown BungeeCord sub-channel '{}' from server '{}'",
          sanitize(name),
          from.name());
      return;
    }
    if (!settings.allowed(from.name()).contains(subchannel)) {
      long suppressed = deniedWarnings.tryAcquire();
      if (suppressed >= 0) {
        logger.warn(
            "Dropped {} from server '{}' on the BungeeCord channel: its bungeecord-channel.allow"
                + " rule does not allow that sub-channel{}",
            subchannel.wireName(),
            from.name(),
            LogThrottle.suffix(suppressed, "refused requests"));
      }
      return;
    }
    try {
      byte[] reply = answer(subchannel, in, carrier, from);
      if (reply != null) {
        reply(carrier, from, over, subchannel, reply);
      }
    } catch (TooLarge e) {
      tooLarge(from, subchannel, e.size);
    } catch (IOException e) {
      malformed(from, subchannel.wireName(), e);
    } catch (RuntimeException e) {
      // A chat component that is not valid JSON (MessageRaw, KickPlayerRaw), or one too large for
      // a client.
      malformed(from, subchannel.wireName(), e);
    }
  }

  /** Acts on a request and returns its reply, or {@code null} if it has none. */
  private byte @Nullable [] answer(
      BungeeCordSubchannel subchannel,
      DataInputStream in,
      ConnectedPlayer carrier,
      BackendServer from)
      throws IOException {
    return switch (subchannel) {
      case CONNECT -> {
        connect(carrier, in.readUTF(), from);
        yield null;
      }
      case CONNECT_OTHER -> {
        String playerName = in.readUTF();
        String serverName = in.readUTF();
        ConnectedPlayer player = players.get(playerName);
        if (player == null) {
          logger.debug(
              "ConnectOther from '{}': no player named '{}'", from.name(), sanitize(playerName));
        } else {
          connect(player, serverName, from);
        }
        yield null;
      }
      case IP -> {
        Reply reply = new Reply("IP");
        writeAddress(reply, carrier.remoteAddress());
        yield reply.bytes();
      }
      case IP_OTHER -> {
        ConnectedPlayer player = players.get(in.readUTF());
        if (player == null) {
          yield null;
        }
        Reply reply = new Reply("IPOther").string(player.username());
        writeAddress(reply, player.remoteAddress());
        yield reply.bytes();
      }
      case PLAYER_COUNT -> {
        String target = in.readUTF();
        if (target.equals(ALL)) {
          yield new Reply("PlayerCount").string(ALL).integer(players.count()).bytes();
        }
        BackendServer server = servers.getServer(target);
        yield server == null
            ? null
            : new Reply("PlayerCount").string(server.name()).integer(server.playerCount()).bytes();
      }
      case PLAYER_LIST -> {
        String target = in.readUTF();
        if (target.equals(ALL)) {
          yield new Reply("PlayerList").string(ALL).string(names(players.all())).bytes();
        }
        BackendServer server = servers.getServer(target);
        yield server == null
            ? null
            : new Reply("PlayerList")
                .string(server.name())
                .string(names(server.connectedPlayers()))
                .bytes();
      }
      case GET_SERVERS ->
          new Reply("GetServers")
              .string(
                  servers.allServers().stream()
                      .map(BackendServer::name)
                      .collect(Collectors.joining(", ")))
              .bytes();
      case GET_PLAYER_SERVER -> {
        String playerName = in.readUTF();
        ConnectedPlayer player = players.get(playerName);
        BackendServer server = player == null ? null : player.currentServer();
        yield new Reply("GetPlayerServer")
            .string(playerName)
            .string(server == null ? "" : server.name())
            .bytes();
      }
      case MESSAGE -> {
        String target = in.readUTF();
        message(target, LegacyComponentSerializer.legacySection().deserialize(in.readUTF()));
        yield null;
      }
      case MESSAGE_RAW -> {
        String target = in.readUTF();
        message(target, GsonComponentSerializer.gson().deserialize(in.readUTF()));
        yield null;
      }
      case GET_SERVER -> new Reply("GetServer").string(from.name()).bytes();
      case UUID -> new Reply("UUID").string(undashed(carrier.uuid())).bytes();
      case UUID_OTHER -> {
        ConnectedPlayer player = players.get(in.readUTF());
        yield player == null
            ? null
            : new Reply("UUIDOther")
                .string(player.username())
                .string(undashed(player.uuid()))
                .bytes();
      }
      case SERVER_IP -> {
        BackendServer server = servers.getServer(in.readUTF());
        yield server == null
            ? null
            : new Reply("ServerIP")
                .string(server.name())
                .string(server.address().getHostString())
                .unsignedShort(server.address().getPort())
                .bytes();
      }
      case KICK_PLAYER -> {
        ConnectedPlayer player = players.get(in.readUTF());
        if (player != null) {
          kick(player, LegacyComponentSerializer.legacySection().deserialize(in.readUTF()), from);
        }
        yield null;
      }
      case KICK_PLAYER_RAW -> {
        ConnectedPlayer player = players.get(in.readUTF());
        if (player != null) {
          kick(player, GsonComponentSerializer.gson().deserialize(in.readUTF()), from);
        }
        yield null;
      }
      case FORWARD -> {
        String target = in.readUTF();
        byte[] data = forwardedData(in, subchannel, from);
        if (data != null) {
          forward(target, data, from);
        }
        yield null;
      }
      case FORWARD_TO_PLAYER -> {
        String playerName = in.readUTF();
        byte[] data = forwardedData(in, subchannel, from);
        ConnectedPlayer player = players.get(playerName);
        if (data != null && player != null) {
          forwardToPlayer(player, data, from);
        }
        yield null;
      }
    };
  }

  // ---------------------------------------------------------------------------
  // Actions
  // ---------------------------------------------------------------------------

  /** Moves a player through the proxy's own switch, which fires ServerPreConnectEvent. */
  private void connect(ConnectedPlayer player, String serverName, BackendServer from) {
    BackendServer target = servers.getServer(serverName);
    if (target == null) {
      long suppressed = unknownServerWarnings.tryAcquire();
      if (suppressed >= 0) {
        logger.warn(
            "Server '{}' asked on the BungeeCord channel to move {} to '{}', which is not"
                + " registered{}",
            from.name(),
            player.username(),
            sanitize(serverName),
            LogThrottle.suffix(suppressed, "requests for unknown servers"));
      }
      return;
    }
    logger.debug("Server '{}' moves {} to '{}'", from.name(), player.username(), target.name());
    var _ =
        player
            .requestConnection(target, true)
            .thenAccept(
                result -> {
                  if (!(result instanceof ConnectionResult.Connected)) {
                    logger.debug(
                        "BungeeCord Connect of {} to '{}' from '{}': {}",
                        player.username(),
                        target.name(),
                        from.name(),
                        result);
                  }
                });
  }

  private void message(String target, Component message) {
    if (target.equals(ALL)) {
      for (ConnectedPlayer player : players.all()) {
        player.sendMessage(message);
      }
      return;
    }
    ConnectedPlayer player = players.get(target);
    if (player != null) {
      player.sendMessage(message);
    }
  }

  private void kick(ConnectedPlayer player, Component reason, BackendServer from) {
    logger.info(
        "Server '{}' disconnects {} on the BungeeCord channel", from.name(), player.username());
    player.api().disconnect(reason);
  }

  /**
   * Delivers a {@code Forward}: to one server, or to every server but the sender, through a player
   * playing there. {@code ALL} and a named server wait for a player when there is none, {@code
   * ONLINE} does not.
   */
  private void forward(String target, byte[] data, BackendServer from) {
    switch (target) {
      case ALL, ONLINE -> {
        boolean hold = target.equals(ALL);
        for (BackendServer server : servers.allServers()) {
          if (server != from) {
            deliverOrHold(server, data, from, hold);
          }
        }
      }
      default -> {
        BackendServer server = servers.getServer(target);
        if (server == null) {
          long suppressed = unknownServerWarnings.tryAcquire();
          if (suppressed >= 0) {
            logger.warn(
                "Server '{}' forwarded a message on the BungeeCord channel to '{}', which is not"
                    + " registered{}",
                from.name(),
                sanitize(target),
                LogThrottle.suffix(suppressed, "requests for unknown servers"));
          }
          return;
        }
        deliverOrHold(server, data, from, true);
      }
    }
  }

  private void deliverOrHold(BackendServer server, byte[] data, BackendServer from, boolean hold) {
    if (deliver(server, data)) {
      return;
    }
    if (!hold) {
      logger.debug("Forward from '{}' skips '{}': nobody plays there", from.name(), server.name());
      return;
    }
    if (settings.forwardHold().isZero()) {
      forwardsDropped(server.name(), from.name(), 1, "nobody plays there (forward-hold is 0)");
      return;
    }
    if (!held.hold(server, data, from.name())) {
      forwardsDropped(server.name(), from.name(), 1, "too many messages wait for a player already");
      return;
    }
    // A player may have arrived between the first attempt and the hold, and taken nothing.
    ConnectedPlayer carrier = server.anyPlayer(player -> player.currentServer() == server);
    if (carrier != null) {
      arrived(carrier, server);
    }
  }

  /** Sends a message to a server through any player playing there. */
  private static boolean deliver(BackendServer server, byte[] data) {
    return server.anyPlayer(player -> sendTo(player, server, data)) != null;
  }

  private static boolean sendTo(ConnectedPlayer player, BackendServer server, byte[] data) {
    return player.sendPlayPluginMessageToServer(server, nameFor(player.protocolVersion()), data);
  }

  private void forwardToPlayer(ConnectedPlayer player, byte[] data, BackendServer from) {
    BackendServer server = player.currentServer();
    if (server == null || !sendTo(player, server, data)) {
      logger.debug(
          "ForwardToPlayer from '{}': {} plays on no server right now",
          from.name(),
          player.username());
    }
  }

  /**
   * Delivers the forwards held for a server, now that a player plays there. Runs on that player's
   * event loop, once its connection to the server is in PLAY.
   *
   * @param player the player that arrived
   * @param server the server it now plays on
   */
  public void arrived(ConnectedPlayer player, BackendServer server) {
    if (!settings.enabled()) {
      return;
    }
    int delivered = held.deliver(server, data -> sendTo(player, server, data));
    if (delivered > 0) {
      logger.debug(
          "Delivered {} held forwards to '{}' through {}",
          delivered,
          server.name(),
          player.username());
    }
  }

  /** Drops the held forwards that waited too long. Called periodically. */
  public void expireHeld() {
    held.expire();
  }

  private void forwardsDropped(String server, String from, int count, String why) {
    long suppressed = droppedForwardWarnings.tryAcquire();
    if (suppressed >= 0) {
      logger.warn(
          "Dropped {} BungeeCord Forward message(s) from '{}' to '{}': {}{}",
          count,
          from,
          server,
          why,
          LogThrottle.suffix(suppressed, "dropped forwards"));
    }
  }

  // ---------------------------------------------------------------------------
  // Replies
  // ---------------------------------------------------------------------------

  private void reply(
      ConnectedPlayer carrier,
      BackendServer from,
      MinecraftConnection over,
      BungeeCordSubchannel subchannel,
      byte[] reply) {
    if (reply.length > MAX_SERVERBOUND_PAYLOAD) {
      tooLarge(from, subchannel, reply.length);
      return;
    }
    carrier.sendPlayPluginMessage(over, nameFor(carrier.protocolVersion()), reply);
  }

  /**
   * Returns what a {@code Forward} or {@code ForwardToPlayer} delivers: the sub-channel, the data's
   * length and the data, as BungeeCord writes them back, without whatever follows the data; or
   * {@code null} if that is too large for a backend to accept.
   *
   * @throws IOException if the message's length does not match its data
   */
  private byte @Nullable [] forwardedData(
      DataInputStream in, BungeeCordSubchannel subchannel, BackendServer from) throws IOException {
    byte[] rest = in.readAllBytes();
    DataInputStream data = new DataInputStream(new ByteArrayInputStream(rest));
    data.readUTF();
    int length = data.readUnsignedShort();
    if (data.skipBytes(length) != length) {
      throw new IOException("it announces " + length + " bytes of data and carries fewer");
    }
    int end = rest.length - data.available();
    if (end > MAX_SERVERBOUND_PAYLOAD) {
      tooLarge(from, subchannel, end);
      return null;
    }
    return end == rest.length ? rest : Arrays.copyOf(rest, end);
  }

  private void tooLarge(BackendServer from, BungeeCordSubchannel subchannel, int size) {
    long suppressed = tooLargeWarnings.tryAcquire();
    if (suppressed >= 0) {
      logger.warn(
          "Dropped a {} message of server '{}' on the BungeeCord channel: {} bytes, more than the"
              + " {} a backend accepts from a client (it would disconnect the player carrying"
              + " it){}",
          subchannel.wireName(),
          from.name(),
          size,
          MAX_SERVERBOUND_PAYLOAD,
          LogThrottle.suffix(suppressed, "messages too large"));
    }
  }

  private void malformed(BackendServer from, String subchannel, Exception e) {
    long suppressed = malformedWarnings.tryAcquire();
    if (suppressed >= 0) {
      logger.warn(
          "Ignored a malformed {} message of server '{}' on the BungeeCord channel: {}{}",
          subchannel,
          from.name(),
          e.getMessage(),
          LogThrottle.suffix(suppressed, "malformed messages"));
    }
  }

  // ---------------------------------------------------------------------------
  // Encoding
  // ---------------------------------------------------------------------------

  private static void writeAddress(Reply reply, InetSocketAddress address) throws IOException {
    reply.string(address.getHostString()).integer(address.getPort());
  }

  private static String names(Iterable<ConnectedPlayer> players) {
    StringBuilder names = new StringBuilder();
    for (ConnectedPlayer player : players) {
      if (!names.isEmpty()) {
        names.append(", ");
      }
      names.append(player.username());
    }
    return names.toString();
  }

  /**
   * A UUID without dashes, as BungeeCord and Velocity answer {@code UUID} and {@code UUIDOther}.
   */
  private static String undashed(UUID uuid) {
    return String.format(
        "%016x%016x", uuid.getMostSignificantBits(), uuid.getLeastSignificantBits());
  }

  /** A name a backend sent, made safe for one log line. */
  private static String sanitize(String name) {
    StringBuilder safe = new StringBuilder(Math.min(name.length(), MAX_LOGGED_NAME));
    for (int i = 0; i < name.length() && i < MAX_LOGGED_NAME; i++) {
      char c = name.charAt(i);
      safe.append(c < ' ' || c == 0x7F ? '?' : c);
    }
    return name.length() > MAX_LOGGED_NAME ? safe + "..." : safe.toString();
  }

  /** A reply, written as Guava's {@code ByteArrayDataOutput} writes it for BungeeCord. */
  private static final class Reply {

    private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    private final DataOutputStream out = new DataOutputStream(bytes);

    Reply(String subchannel) throws IOException {
      out.writeUTF(subchannel);
    }

    /**
     * Writes a string as {@link DataOutputStream#writeUTF}: length, then modified UTF-8.
     *
     * @throws TooLarge if it takes more than the 65535 bytes its length can say (the player list of
     *     a crowded network)
     */
    Reply string(String value) throws IOException {
      try {
        out.writeUTF(value);
      } catch (UTFDataFormatException e) {
        throw new TooLarge(bytes.size() + value.length());
      }
      return this;
    }

    Reply integer(int value) throws IOException {
      out.writeInt(value);
      return this;
    }

    Reply unsignedShort(int value) throws IOException {
      out.writeShort(value);
      return this;
    }

    byte[] bytes() {
      return bytes.toByteArray();
    }
  }

  /** A reply that cannot be written: one of its strings is longer than its length can say. */
  private static final class TooLarge extends IOException {

    private static final long serialVersionUID = 1L;

    /** At least how many bytes the reply would take. */
    private final int size;

    TooLarge(int size) {
      super("reply too large");
      this.size = size;
    }
  }
}
