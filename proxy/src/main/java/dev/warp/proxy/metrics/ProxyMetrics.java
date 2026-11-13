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
package dev.warp.proxy.metrics;

import dev.warp.api.metrics.Buckets;
import dev.warp.api.metrics.Counter;
import dev.warp.api.metrics.Gauge;
import dev.warp.api.metrics.Histogram;
import dev.warp.api.metrics.Metrics;
import dev.warp.api.player.TransferResult;
import dev.warp.protocol.ProtocolState;
import dev.warp.protocol.ProtocolVersion;
import dev.warp.protocol.netty.TrafficObserver;
import dev.warp.proxy.connection.HandshakeIntent;
import dev.warp.proxy.transfer.HandoffRejection;

import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The proxy's own metrics, resolved once here so that the handlers only increment: the catalogue of
 * {@code docs/metrics.md}, less the values a collector reads at scrape time ({@link
 * ProxyCollector}, {@link JvmCollector}, {@link AntiBotCollector}).
 *
 * <p>Every child here is safe to update from any thread; from an event loop with a slot ({@link
 * LoopIndex}) the update is a plain add on the loop's own slot.
 */
public final class ProxyMetrics {

  private static final String C2S = "c2s";
  private static final String S2C = "s2c";

  // Downstream
  private final Counter.Child[] connectionsAccepted;
  private final Gauge.Child[] connectionsActive;
  private final Counter.Child[] logins;
  private final Histogram.Child loginSucceededDuration;
  private final Histogram.Child loginFailedDuration;
  private final Counter.Child statusPingsCached;
  private final Counter.Child statusPingsFresh;
  private final Gauge clientProtocolVersion;
  private final ConcurrentHashMap<ProtocolVersion, Gauge.Child> versions =
      new ConcurrentHashMap<>();

  // Forwarding
  private final Counter forwardedPackets;
  private final Counter forwardedBytes;
  private final Counter compressionPassthrough;
  private final Counter compressionBytes;
  private final Counter.Child dropsOversized;
  private final Counter.Child dropsInvalidFrame;
  private final Counter.Child dropsState;

  // Upstream
  private final Counter serverConnects;
  private final Histogram serverConnectDuration;
  private final Counter.Child[] serverSwitches;
  private final Counter serverBytes;
  private final Gauge serverRtt;
  private final Gauge serverHealth;

  // Internals
  private final Histogram.Child eventLoopLag;
  private final Counter.Child[] drainTransfers;
  private final Counter.Child[][] transfers;
  private final Counter.Child[] handoffRejections;
  private final Histogram.Child handoffLoginDuration;
  private final Histogram pluginEventDuration;
  private final ClassValue<Histogram.Child> eventChildren;
  private final Counter pluginHandlerErrors;
  private final Metrics registry;

  /**
   * Registers the proxy's metrics.
   *
   * @param metrics the registry
   */
  public ProxyMetrics(Metrics metrics) {
    this.registry = metrics;
    Counter accepted =
        metrics.counter(
            "warp_connections_accepted_total",
            "TCP connections that reached a handshake, by what the client came for.",
            "intent");
    this.connectionsAccepted = new Counter.Child[HandshakeIntent.values().length];
    for (HandshakeIntent intent : HandshakeIntent.values()) {
      connectionsAccepted[intent.ordinal()] = accepted.labels(lower(intent));
    }
    Gauge active =
        metrics.gauge(
            "warp_connections_active", "Live client connections, by protocol state.", "state");
    this.connectionsActive = new Gauge.Child[ProtocolState.values().length];
    for (ProtocolState state : ProtocolState.values()) {
      connectionsActive[state.ordinal()] = active.labels(lower(state));
    }
    Counter loginsTotal =
        metrics.counter("warp_logins_total", "Login attempts, by outcome.", "outcome");
    this.logins = new Counter.Child[LoginOutcome.values().length];
    for (LoginOutcome outcome : LoginOutcome.values()) {
      logins[outcome.ordinal()] = loginsTotal.labels(outcome.label());
    }
    Histogram loginDuration =
        metrics.histogram(
            "warp_login_duration_seconds",
            "From the handshake to the login's end, by outcome.",
            Buckets.NETWORK,
            "outcome");
    this.loginSucceededDuration = loginDuration.labels("success");
    this.loginFailedDuration = loginDuration.labels("failed");
    Counter pings =
        metrics.counter(
            "warp_status_pings_total",
            "Server list pings, answered from the cache or built for a plugin.",
            "cached");
    this.statusPingsCached = pings.labels("true");
    this.statusPingsFresh = pings.labels("false");
    this.clientProtocolVersion =
        metrics.gauge(
            "warp_client_protocol_version",
            "Players online, by their client's protocol version.",
            "version");

    this.forwardedPackets =
        metrics.counter(
            "warp_forwarded_packets_total",
            "Packets handled in PLAY: blind (forwarded as received) or decoded (read by the proxy).",
            "direction",
            "mode");
    this.forwardedBytes =
        metrics.counter(
            "warp_forwarded_bytes_total",
            "Wire bytes of the packets handled in PLAY, blind or decoded.",
            "direction",
            "mode");
    this.compressionPassthrough =
        metrics.counter(
            "warp_compression_passthrough_total",
            "Compressed frames forwarded as received (passthrough) or inflated and compressed"
                + " again (recompressed).",
            "direction",
            "result");
    this.compressionBytes =
        metrics.counter(
            "warp_compression_bytes_total",
            "Bytes of the frames received compressed: on the wire (compressed) and once inflated"
                + " (raw); their ratio is the compression ratio.",
            "direction",
            "side");
    Counter drops =
        metrics.counter(
            "warp_packet_drops_total",
            "Frames refused: over the size limit, malformed, or not allowed in the state.",
            "reason");
    this.dropsOversized = drops.labels("oversized");
    this.dropsInvalidFrame = drops.labels("invalid_frame");
    this.dropsState = drops.labels("state");

    this.serverConnects =
        metrics.counter(
            "warp_server_connects_total",
            "Connection attempts to backend servers, by outcome.",
            "server",
            "outcome");
    this.serverConnectDuration =
        metrics.histogram(
            "warp_server_connect_duration_seconds",
            "From the connection attempt to the backend's acceptance.",
            Buckets.NETWORK,
            "server");
    Counter switches =
        metrics.counter(
            "warp_server_switches_total",
            "Server switches of connected players, by outcome.",
            "outcome");
    this.serverSwitches = new Counter.Child[SwitchOutcome.values().length];
    for (SwitchOutcome outcome : SwitchOutcome.values()) {
      serverSwitches[outcome.ordinal()] = switches.labels(lower(outcome));
    }
    this.serverBytes =
        metrics.counter(
            "warp_server_bytes_total",
            "Wire bytes forwarded to (c2s) and from (s2c) each backend.",
            "server",
            "direction");
    this.serverRtt =
        metrics.gauge(
            "warp_server_rtt_seconds",
            "Round trip of the proxy's last status ping to the backend.",
            "server");
    this.serverHealth =
        metrics.gauge(
            "warp_server_health",
            "1 when the last status ping was answered, 0 otherwise.",
            "server");

    this.eventLoopLag =
        metrics
            .histogram(
                "warp_event_loop_lag_seconds",
                "How late a task scheduled on an event loop ran: the loops' saturation.",
                Buckets.FAST)
            .labels();
    Counter transfers =
        metrics.counter(
            "warp_drain_transfers_total",
            "Players the drain tried to transfer, by result.",
            "outcome");
    this.drainTransfers = new Counter.Child[TransferResult.values().length];
    for (TransferResult result : TransferResult.values()) {
      drainTransfers[result.ordinal()] = transfers.labels(lower(result));
    }
    Counter handoffs =
        metrics.counter(
            "warp_transfers_total",
            "Transfers sent to another server (out) and logins that arrived by one (in), by"
                + " whether a handoff ticket went with them.",
            "direction",
            "handoff");
    this.transfers = new Counter.Child[TransferDirection.values().length][];
    for (TransferDirection direction : TransferDirection.values()) {
      Counter.Child[] row = new Counter.Child[HandoffUse.values().length];
      for (HandoffUse use : HandoffUse.values()) {
        if (direction == TransferDirection.OUT && use == HandoffUse.INVALID) {
          continue; // the source never writes an invalid ticket
        }
        row[use.ordinal()] = handoffs.labels(lower(direction), lower(use));
      }
      this.transfers[direction.ordinal()] = row;
    }
    Counter rejected =
        metrics.counter(
            "warp_transfer_handoffs_rejected_total",
            "Handoff tickets a transferred client presented and the proxy did not use, by reason.",
            "reason");
    this.handoffRejections = new Counter.Child[HandoffRejection.values().length];
    for (HandoffRejection reason : HandoffRejection.values()) {
      handoffRejections[reason.ordinal()] = rejected.labels(reason.label());
    }
    this.handoffLoginDuration =
        metrics
            .histogram(
                "warp_transfer_handoff_login_duration_seconds",
                "From the handshake to Login Success, for the logins a handoff ticket carried.",
                Buckets.NETWORK)
            .labels();
    this.pluginEventDuration =
        metrics.histogram(
            "warp_plugin_event_duration_seconds",
            "Time one handler took on one event, by event type.",
            Buckets.FAST,
            "event");
    this.eventChildren =
        new ClassValue<>() {
          @Override
          protected Histogram.Child computeValue(Class<?> type) {
            return pluginEventDuration.labels(eventName(type));
          }
        };
    this.pluginHandlerErrors =
        metrics.counter(
            "warp_plugin_handler_errors_total",
            "Uncaught exceptions from event handlers, by plugin.",
            "plugin");
    this.clientTraffic = new Traffic(C2S, S2C, null, null);
  }

  // ---------------------------------------------------------------------------
  // Downstream
  // ---------------------------------------------------------------------------

  /**
   * Returns the registry the proxy's metrics live in.
   *
   * @return the registry
   */
  public Metrics registry() {
    return registry;
  }

  /**
   * Counts a handshake.
   *
   * @param intent what the client came for
   */
  public void connectionAccepted(HandshakeIntent intent) {
    connectionsAccepted[intent.ordinal()].inc();
  }

  /**
   * Moves a client connection between protocol states.
   *
   * @param from the state it leaves, or {@code null} when it was just opened
   * @param to the state it enters, or {@code null} when it closed
   */
  public void connectionMoved(
      @org.jspecify.annotations.Nullable ProtocolState from,
      @org.jspecify.annotations.Nullable ProtocolState to) {
    if (from != null) {
      connectionsActive[from.ordinal()].dec();
    }
    if (to != null) {
      connectionsActive[to.ordinal()].inc();
    }
  }

  /**
   * Counts a login's end.
   *
   * @param outcome how it ended
   * @param nanos how long it took, from the handshake, or a negative value when unknown
   */
  public void loginEnded(LoginOutcome outcome, long nanos) {
    logins[outcome.ordinal()].inc();
    if (nanos >= 0) {
      (outcome == LoginOutcome.SUCCESS ? loginSucceededDuration : loginFailedDuration)
          .observeNanos(nanos);
    }
  }

  /**
   * Counts a server list ping.
   *
   * @param cached whether the cached answer was sent, or one built for the plugins
   */
  public void statusPing(boolean cached) {
    (cached ? statusPingsCached : statusPingsFresh).inc();
  }

  /**
   * Counts a player of this version in, or out.
   *
   * @param version the client's protocol version
   * @param delta 1 when the player joins, -1 when it leaves
   */
  public void playerVersion(ProtocolVersion version, int delta) {
    versions
        .computeIfAbsent(version, v -> clientProtocolVersion.labels(Integer.toString(v.protocol())))
        .add(delta);
  }

  // ---------------------------------------------------------------------------
  // Forwarding
  // ---------------------------------------------------------------------------

  /**
   * Returns the observer of a client connection: its decoder reads what the client sends (c2s), its
   * forwarder writes what the backend sent (s2c).
   *
   * @return the observer, shared by every client connection
   */
  public TrafficObserver clientTraffic() {
    return clientTraffic;
  }

  /**
   * Returns the observer of a connection to a backend: its decoder reads what the backend sends
   * (s2c), its forwarder writes what the client sent (c2s), and both count towards the server.
   *
   * @param server the backend's registered name
   * @return a new observer for that connection
   */
  public TrafficObserver backendTraffic(String server) {
    return new Traffic(S2C, C2S, serverBytes.labels(server, S2C), serverBytes.labels(server, C2S));
  }

  private final TrafficObserver clientTraffic;

  /** Counts for one side; the children are resolved when the observer is created. */
  private final class Traffic implements TrafficObserver {
    private final Counter.Child blindPackets;
    private final Counter.Child blindBytes;
    private final Counter.Child decodedPackets;
    private final Counter.Child decodedBytes;
    private final Counter.Child passthrough;
    private final Counter.Child recompressed;
    private final Counter.Child compressed;
    private final Counter.Child raw;
    private final Counter.@org.jspecify.annotations.Nullable Child serverIn;
    private final Counter.@org.jspecify.annotations.Nullable Child serverOut;

    Traffic(
        String decodeDirection,
        String forwardDirection,
        Counter.@org.jspecify.annotations.Nullable Child serverIn,
        Counter.@org.jspecify.annotations.Nullable Child serverOut) {
      this.blindPackets = forwardedPackets.labels(decodeDirection, "blind");
      this.blindBytes = forwardedBytes.labels(decodeDirection, "blind");
      this.decodedPackets = forwardedPackets.labels(decodeDirection, "decoded");
      this.decodedBytes = forwardedBytes.labels(decodeDirection, "decoded");
      this.passthrough = compressionPassthrough.labels(forwardDirection, "passthrough");
      this.recompressed = compressionPassthrough.labels(forwardDirection, "recompressed");
      this.compressed = compressionBytes.labels(forwardDirection, "compressed");
      this.raw = compressionBytes.labels(forwardDirection, "raw");
      this.serverIn = serverIn;
      this.serverOut = serverOut;
    }

    @Override
    public void blind(int wireBytes) {
      blindPackets.inc();
      blindBytes.add(wireBytes);
      if (serverIn != null) {
        serverIn.add(wireBytes);
      }
    }

    @Override
    public void decoded(int wireBytes) {
      decodedPackets.inc();
      decodedBytes.add(wireBytes);
      if (serverIn != null) {
        serverIn.add(wireBytes);
      }
    }

    @Override
    public void passthrough(int wireBytes, int rawBytes) {
      passthrough.inc();
      compressed.add(wireBytes);
      raw.add(rawBytes);
      if (serverOut != null) {
        serverOut.add(wireBytes);
      }
    }

    @Override
    public void reencoded(int wireBytes, int rawBytes) {
      recompressed.inc();
      compressed.add(wireBytes);
      raw.add(rawBytes);
      if (serverOut != null) {
        serverOut.add(wireBytes);
      }
    }
  }

  /** Counts a frame refused as over the size limit. */
  public void frameOversized() {
    dropsOversized.inc();
  }

  /** Counts a frame refused as malformed. */
  public void frameInvalid() {
    dropsInvalidFrame.inc();
  }

  /** Counts a frame refused as not allowed in the connection's state. */
  public void frameRefusedByState() {
    dropsState.inc();
  }

  // ---------------------------------------------------------------------------
  // Upstream
  // ---------------------------------------------------------------------------

  /**
   * Counts a connection attempt to a backend.
   *
   * @param server the backend's registered name
   * @param outcome how it ended
   * @param nanos how long it took, or a negative value when unknown
   */
  public void serverConnect(String server, ServerConnectOutcome outcome, long nanos) {
    serverConnects.labels(server, outcome.label()).inc();
    if (outcome == ServerConnectOutcome.SUCCESS && nanos >= 0) {
      serverConnectDuration.labels(server).observeNanos(nanos);
    }
  }

  /** The {@code outcome} label of {@code warp_server_switches_total}. */
  public enum SwitchOutcome {
    /** The player is on the new server. */
    SUCCESS,
    /** The new server refused or dropped the player, who stays where it was or falls back. */
    FAILED,
    /** A plugin or the player cancelled the switch. */
    CANCELLED
  }

  /**
   * Counts a server switch of a connected player.
   *
   * @param outcome how it ended
   */
  public void serverSwitch(SwitchOutcome outcome) {
    serverSwitches[outcome.ordinal()].inc();
  }

  /**
   * Records a backend's status ping.
   *
   * @param server the backend's registered name
   * @param rttNanos the round trip, or a negative value when the ping failed
   */
  public void serverPinged(String server, long rttNanos) {
    if (rttNanos >= 0) {
      serverRtt.labels(server).set(rttNanos / 1_000_000_000.0);
      serverHealth.labels(server).set(1);
    } else {
      serverHealth.labels(server).set(0);
    }
  }

  // ---------------------------------------------------------------------------
  // Internals
  // ---------------------------------------------------------------------------

  /**
   * Records how late a scheduled task ran on an event loop.
   *
   * @param lagNanos the lag
   */
  public void eventLoopLag(long lagNanos) {
    eventLoopLag.observeNanos(Math.max(0, lagNanos));
  }

  /**
   * Counts a transfer the drain attempted.
   *
   * @param result what the transfer did
   */
  public void drainTransfer(TransferResult result) {
    drainTransfers[result.ordinal()].inc();
  }

  /** Which way a transfer went, in {@code warp_transfers_total}. */
  public enum TransferDirection {
    /** A Transfer packet this proxy sent a player. */
    OUT,
    /** A login that arrived by a transfer. */
    IN
  }

  /** Whether a handoff ticket went with a transfer, in {@code warp_transfers_total}. */
  public enum HandoffUse {
    /** Written before the transfer (out), or read and used (in). */
    USED,
    /** None: the handoff is off, the target is not a peer, or the client held none. */
    ABSENT,
    /** Presented but not used (in only): see {@code warp_transfer_handoffs_rejected_total}. */
    INVALID
  }

  /**
   * Counts a transfer sent or a transferred login.
   *
   * @param direction out for a Transfer packet sent, in for a login that arrived by one
   * @param use whether a handoff ticket went with it
   * @throws IllegalArgumentException for an invalid ticket going out
   */
  public void transfer(TransferDirection direction, HandoffUse use) {
    Counter.Child child = transfers[direction.ordinal()][use.ordinal()];
    if (child == null) {
      throw new IllegalArgumentException("no " + use + " handoff goes " + direction);
    }
    child.inc();
  }

  /**
   * Counts a handoff ticket not used.
   *
   * @param reason why
   */
  public void handoffRejected(HandoffRejection reason) {
    handoffRejections[reason.ordinal()].inc();
  }

  /**
   * Records how long a login a handoff carried took, to Login Success.
   *
   * @param nanos the duration
   */
  public void handoffLogin(long nanos) {
    handoffLoginDuration.observeNanos(nanos);
  }

  /**
   * Records one handler's run on one event.
   *
   * @param event the event
   * @param nanos how long the handler took
   */
  public void eventHandled(Object event, long nanos) {
    eventChildren.get(event.getClass()).observeNanos(nanos);
  }

  /**
   * Counts an exception thrown by an event handler.
   *
   * @param plugin the plugin that registered the handler
   */
  public void handlerFailed(String plugin) {
    pluginHandlerErrors.labels(plugin).inc();
  }

  /**
   * Returns the {@code event} label of an event class: its API interface's simple name when the
   * class implements one from {@code dev.warp.api.event}, else its own simple name.
   *
   * @param type the event's class
   * @return the label value
   */
  static String eventName(Class<?> type) {
    for (Class<?> current = type; current != null; current = current.getSuperclass()) {
      for (Class<?> implemented : current.getInterfaces()) {
        if (implemented.getPackageName().startsWith("dev.warp.api.event")) {
          return implemented.getSimpleName();
        }
      }
    }
    String name = type.getSimpleName();
    return name.isEmpty() ? "anonymous" : name;
  }

  private static String lower(Enum<?> value) {
    return value.name().toLowerCase(Locale.ROOT);
  }
}
