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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import dev.warp.api.event.player.LoginEvent;
import dev.warp.api.player.TransferResult;
import dev.warp.protocol.ProtocolState;
import dev.warp.protocol.ProtocolVersion;
import dev.warp.protocol.netty.TrafficObserver;
import dev.warp.proxy.connection.HandshakeIntent;
import dev.warp.proxy.limit.Refusal;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("ProxyMetrics")
class ProxyMetricsTest {

  private final MetricRegistry registry = new MetricRegistry(0);
  private final ProxyMetrics metrics = new ProxyMetrics(registry);

  @Nested
  @DisplayName("forwarding")
  class Forwarding {

    @Test
    @DisplayName("should count blind and decoded frames by direction, and compression by result")
    void traffic() {
      TrafficObserver client = metrics.clientTraffic();
      TrafficObserver backend = metrics.backendTraffic("lobby");
      client.blind(100);
      client.blind(50);
      client.decoded(10);
      client.passthrough(80, 200);
      backend.blind(1000);
      backend.decoded(30);
      backend.reencoded(90, 400);
      backend.passthrough(60, 100);

      RecordingVisitor out = RecordingVisitor.of(registry);

      assertEquals(2, out.value("warp_forwarded_packets_total{direction=c2s,mode=blind}"));
      assertEquals(150, out.value("warp_forwarded_bytes_total{direction=c2s,mode=blind}"));
      assertEquals(1, out.value("warp_forwarded_packets_total{direction=c2s,mode=decoded}"));
      assertEquals(1, out.value("warp_forwarded_packets_total{direction=s2c,mode=blind}"));
      assertEquals(
          1030,
          out.value("warp_forwarded_bytes_total{direction=s2c,mode=blind}")
              + out.value("warp_forwarded_bytes_total{direction=s2c,mode=decoded}"));
      // The client connection's forwarder writes towards the client: s2c.
      assertEquals(
          1, out.value("warp_compression_passthrough_total{direction=s2c,result=passthrough}"));
      assertEquals(80, out.value("warp_compression_bytes_total{direction=s2c,side=compressed}"));
      assertEquals(200, out.value("warp_compression_bytes_total{direction=s2c,side=raw}"));
      // The backend connection's forwarder writes towards the backend: c2s.
      assertEquals(
          1, out.value("warp_compression_passthrough_total{direction=c2s,result=recompressed}"));
      assertEquals(
          1, out.value("warp_compression_passthrough_total{direction=c2s,result=passthrough}"));
      assertEquals(1030, out.value("warp_server_bytes_total{server=lobby,direction=s2c}"));
      assertEquals(150, out.value("warp_server_bytes_total{server=lobby,direction=c2s}"));
    }

    @Test
    @DisplayName("should count refused frames by reason")
    void drops() {
      metrics.frameOversized();
      metrics.frameInvalid();
      metrics.frameInvalid();
      metrics.frameRefusedByState();

      RecordingVisitor out = RecordingVisitor.of(registry);

      assertEquals(1, out.value("warp_packet_drops_total{reason=oversized}"));
      assertEquals(2, out.value("warp_packet_drops_total{reason=invalid_frame}"));
      assertEquals(1, out.value("warp_packet_drops_total{reason=state}"));
    }
  }

  @Nested
  @DisplayName("connections and logins")
  class Logins {

    @Test
    @DisplayName("should move connections between states and count handshakes by intent")
    void states() {
      metrics.connectionAccepted(HandshakeIntent.LOGIN);
      metrics.connectionAccepted(HandshakeIntent.STATUS);
      metrics.connectionMoved(null, ProtocolState.HANDSHAKE);
      metrics.connectionMoved(ProtocolState.HANDSHAKE, ProtocolState.LOGIN);
      metrics.connectionMoved(null, ProtocolState.HANDSHAKE);
      metrics.connectionMoved(ProtocolState.HANDSHAKE, null);

      RecordingVisitor out = RecordingVisitor.of(registry);

      assertEquals(1, out.value("warp_connections_accepted_total{intent=login}"));
      assertEquals(1, out.value("warp_connections_accepted_total{intent=status}"));
      assertEquals(0, out.value("warp_connections_accepted_total{intent=transfer}"));
      assertEquals(0, out.value("warp_connections_active{state=handshake}"));
      assertEquals(1, out.value("warp_connections_active{state=login}"));
      assertEquals(0, out.value("warp_connections_active{state=play}"));
    }

    @Test
    @DisplayName("should count login outcomes with their duration, and players by version")
    void outcomes() {
      metrics.loginEnded(LoginOutcome.SUCCESS, 2_000_000_000L);
      metrics.loginEnded(LoginOutcome.of(Refusal.SERVER_FULL), 10_000_000L);
      metrics.loginEnded(LoginOutcome.PROTOCOL_UNSUPPORTED, -1);
      metrics.playerVersion(ProtocolVersion.MINECRAFT_1_8, 1);
      metrics.playerVersion(ProtocolVersion.MINECRAFT_1_8, 1);
      metrics.playerVersion(ProtocolVersion.MINECRAFT_1_8, -1);
      metrics.statusPing(true);

      RecordingVisitor out = RecordingVisitor.of(registry);

      assertEquals(1, out.value("warp_logins_total{outcome=success}"));
      assertEquals(1, out.value("warp_logins_total{outcome=refused_full}"));
      assertEquals(1, out.value("warp_logins_total{outcome=protocol_unsupported}"));
      assertEquals(1, out.value("warp_login_duration_seconds_count{outcome=success}"));
      assertEquals(2, out.value("warp_login_duration_seconds_sum{outcome=success}"), 1e-9);
      assertEquals(1, out.value("warp_login_duration_seconds_count{outcome=failed}"));
      assertEquals(1, out.value("warp_client_protocol_version{version=47}"));
      assertEquals(1, out.value("warp_status_pings_total{cached=true}"));
      assertEquals(LoginOutcome.REFUSED_ANTIBOT, LoginOutcome.of(Refusal.LOCKDOWN));
      assertEquals(LoginOutcome.AUTH_FAILED, LoginOutcome.of(Refusal.AUTHENTICATION_FAILED));
      assertEquals(LoginOutcome.OTHER, LoginOutcome.of(Refusal.INVALID_HANDSHAKE));
    }
  }

  @Nested
  @DisplayName("backends, drain and plugins")
  class Upstream {

    @Test
    @DisplayName("should count connects by server and outcome, switches, pings and transfers")
    void servers() {
      metrics.serverConnect("lobby", ServerConnectOutcome.SUCCESS, 50_000_000L);
      metrics.serverConnect("lobby", ServerConnectOutcome.REFUSED, -1);
      metrics.serverSwitch(ProxyMetrics.SwitchOutcome.FAILED);
      metrics.serverPinged("lobby", 20_000_000L);
      metrics.serverPinged("games", -1);
      metrics.drainTransfer(TransferResult.SENT);
      metrics.eventLoopLag(3_000_000L);

      RecordingVisitor out = RecordingVisitor.of(registry);

      assertEquals(1, out.value("warp_server_connects_total{server=lobby,outcome=success}"));
      assertEquals(1, out.value("warp_server_connects_total{server=lobby,outcome=refused}"));
      assertEquals(1, out.value("warp_server_connect_duration_seconds_count{server=lobby}"));
      assertEquals(1, out.value("warp_server_switches_total{outcome=failed}"));
      assertEquals(0.02, out.value("warp_server_rtt_seconds{server=lobby}"), 1e-9);
      assertEquals(1, out.value("warp_server_health{server=lobby}"));
      assertEquals(0, out.value("warp_server_health{server=games}"));
      assertFalse(out.samples().containsKey("warp_server_rtt_seconds{server=games}"));
      assertEquals(1, out.value("warp_drain_transfers_total{outcome=sent}"));
      assertEquals(1, out.value("warp_event_loop_lag_seconds_bucket{le=0.005}"));
      assertEquals(0, out.value("warp_event_loop_lag_seconds_bucket{le=0.0025}"));
    }

    @Test
    @DisplayName("should label plugin events by their API event name and errors by plugin")
    void events() {
      LoginEvent login = mock(LoginEvent.class);
      metrics.eventHandled(login, 1_500_000L);
      metrics.eventHandled(new Object(), 1_000L);
      metrics.handlerFailed("demo");

      RecordingVisitor out = RecordingVisitor.of(registry);

      assertEquals(1, out.value("warp_plugin_event_duration_seconds_count{event=LoginEvent}"));
      assertEquals(1, out.value("warp_plugin_event_duration_seconds_count{event=Object}"));
      assertEquals(1, out.value("warp_plugin_handler_errors_total{plugin=demo}"));
      assertTrue(out.hasFamily("warp_plugin_event_duration_seconds"));
      assertEquals("LoginEvent", ProxyMetrics.eventName(login.getClass()));
    }
  }
}
