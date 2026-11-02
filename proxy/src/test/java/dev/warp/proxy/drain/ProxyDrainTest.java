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
package dev.warp.proxy.drain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dev.warp.api.event.EventBus;
import dev.warp.api.event.player.PreLoginEvent;
import dev.warp.api.lifecycle.DrainTrigger;
import dev.warp.api.player.InboundConnection;
import dev.warp.protocol.ProtocolVersion;
import dev.warp.proxy.config.DrainConfig;
import dev.warp.proxy.event.PluginEventBus;
import dev.warp.proxy.event.WarpEventBus;
import dev.warp.proxy.event.player.PreLoginEventImpl;
import dev.warp.proxy.player.PlayerRegistry;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.time.Duration;

import net.kyori.adventure.text.Component;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("ProxyDrain")
class ProxyDrainTest {

  private static final String MESSAGE = "Restarting";
  private static final DrainConfig.Target TARGET =
      new DrainConfig.Target("play.example.net", 25565);

  private final WarpEventBus events = new WarpEventBus(Runnable::run);
  private final ManualScheduler clock = new ManualScheduler();

  @AfterEach
  void close() {
    events.close();
  }

  private ProxyDrain drain(DrainConfig.@Nullable Target target, DrainConfig.NewPlayers newPlayers) {
    return new ProxyDrain(
        new DrainConfig(
            target,
            false,
            Duration.ofSeconds(5),
            Duration.ofMillis(100),
            DrainConfig.CannotTransfer.KEEP,
            newPlayers,
            Duration.ofMinutes(30),
            MESSAGE),
        new PlayerRegistry(),
        events,
        clock,
        status -> {});
  }

  private static InboundConnection client(ProtocolVersion version, boolean transferred) {
    InboundConnection connection = mock(InboundConnection.class);
    when(connection.protocolVersion()).thenReturn(version.protocol());
    when(connection.isTransferred()).thenReturn(transferred);
    when(connection.remoteAddress())
        .thenReturn(new InetSocketAddress(InetAddress.getLoopbackAddress(), 50_000));
    return connection;
  }

  @Nested
  @DisplayName("logins while draining")
  class Logins {

    @Test
    @DisplayName("should accept a client it can transfer, when accepting new players")
    void accepts() {
      ProxyDrain drain = drain(TARGET, DrainConfig.NewPlayers.ACCEPT);

      assertNull(drain.refusal(client(ProtocolVersion.MINECRAFT_1_21_4, false)));
      assertNull(drain.refusal(client(ProtocolVersion.MINECRAFT_1_20_5, false)));
    }

    @Test
    @DisplayName("should refuse everyone when refusing new players")
    void refuses() {
      ProxyDrain drain = drain(TARGET, DrainConfig.NewPlayers.REFUSE);

      assertNotNull(drain.refusal(client(ProtocolVersion.MINECRAFT_1_21_4, false)));
    }

    @Test
    @DisplayName("should refuse a client it could not transfer, rather than keep it")
    void refusesOldClient() {
      ProxyDrain drain = drain(TARGET, DrainConfig.NewPlayers.ACCEPT);

      assertNotNull(drain.refusal(client(ProtocolVersion.MINECRAFT_1_20_4, false)));
      assertNotNull(drain.refusal(client(ProtocolVersion.MINECRAFT_1_8, false)));
    }

    @Test
    @DisplayName("should refuse everyone without a transfer address to send them to")
    void refusesWithoutTarget() {
      ProxyDrain drain = drain(null, DrainConfig.NewPlayers.ACCEPT);

      assertNotNull(drain.refusal(client(ProtocolVersion.MINECRAFT_1_21_4, false)));
    }

    @Test
    @DisplayName(
        "should refuse a client a transfer just sent, which another drain would bounce back")
    void refusesTransferred() {
      ProxyDrain drain = drain(TARGET, DrainConfig.NewPlayers.ACCEPT);

      assertNotNull(drain.refusal(client(ProtocolVersion.MINECRAFT_1_21_4, true)));
    }
  }

  @Nested
  @DisplayName("on the event bus")
  class OnTheBus {

    @Test
    @DisplayName("should refuse a login with the drain's message once draining, and count it")
    void refusesLogin() {
      ProxyDrain drain = drain(TARGET, DrainConfig.NewPlayers.REFUSE);
      var _ = drain.start(DrainTrigger.HTTP);

      PreLoginEvent event =
          events
              .fire(new PreLoginEventImpl(client(ProtocolVersion.MINECRAFT_1_21_4, false), "Steve"))
              .join();

      PreLoginEvent.Denied denied = assertInstanceOf(PreLoginEvent.Denied.class, event.result());
      assertEquals(Component.text(MESSAGE), denied.reason());
      assertEquals(1, drain.status().refused());
    }

    @Test
    @DisplayName("should run last, so that no plugin lets a refused player in")
    void runsLast() {
      ProxyDrain drain = drain(TARGET, DrainConfig.NewPlayers.REFUSE);
      EventBus plugin = new PluginEventBus(events, "lenient", null, s -> {});
      var _ =
          plugin.subscribe(
              PreLoginEvent.class,
              Integer.MIN_VALUE + 1,
              event -> event.setResult(new PreLoginEvent.Allowed()));
      var _ = drain.start(DrainTrigger.HTTP);

      PreLoginEvent event =
          events
              .fire(new PreLoginEventImpl(client(ProtocolVersion.MINECRAFT_1_21_4, false), "Steve"))
              .join();

      assertInstanceOf(PreLoginEvent.Denied.class, event.result());
    }

    @Test
    @DisplayName("should leave a plugin's own refusal alone, not counting it")
    void pluginRefusal() {
      ProxyDrain drain = drain(TARGET, DrainConfig.NewPlayers.REFUSE);
      EventBus plugin = new PluginEventBus(events, "bans", null, s -> {});
      var _ =
          plugin.subscribe(
              PreLoginEvent.class,
              event -> event.setResult(new PreLoginEvent.Denied(Component.text("Banned"))));
      var _ = drain.start(DrainTrigger.HTTP);

      PreLoginEvent event =
          events
              .fire(new PreLoginEventImpl(client(ProtocolVersion.MINECRAFT_1_21_4, false), "Steve"))
              .join();

      assertEquals(Component.text("Banned"), ((PreLoginEvent.Denied) event.result()).reason());
      assertEquals(0, drain.status().refused());
    }

    @Test
    @DisplayName("should leave logins alone, and add no handler, before draining")
    void notDraining() {
      drain(TARGET, DrainConfig.NewPlayers.REFUSE);

      PreLoginEvent event =
          events
              .fire(new PreLoginEventImpl(client(ProtocolVersion.MINECRAFT_1_21_4, false), "Steve"))
              .join();

      assertInstanceOf(PreLoginEvent.Allowed.class, event.result());
      assertEquals(false, events.hasHandlers(PreLoginEventImpl.class));
    }
  }
}
