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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.warp.api.antibot.AttackLevel;
import dev.warp.api.antibot.Identity;
import dev.warp.protocol.ProtocolVersion;
import dev.warp.protocol.codec.McString;
import dev.warp.protocol.codec.VarInt;
import dev.warp.proxy.antibot.AntiBotService;
import dev.warp.proxy.auth.MojangSessionService;
import dev.warp.proxy.config.AntiBotConfig;
import dev.warp.proxy.config.LimitsConfig;
import dev.warp.proxy.config.LogFormat;
import dev.warp.proxy.config.LoggingConfig;
import dev.warp.proxy.config.TimeoutsConfig;
import dev.warp.proxy.event.WarpEventBus;
import dev.warp.proxy.limit.ClientLimits;
import dev.warp.proxy.limit.Refusal;
import dev.warp.proxy.network.CidrRanges;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Logins through Warp's pipeline with a live anti-bot: lockdown, learning, attacks. */
@DisplayName("anti-bot at login")
class AntiBotLoginTest {

  private static final ProtocolVersion VERSION = ProtocolVersion.MINECRAFT_1_21_4;

  @TempDir private Path directory;
  private ClientLimits limits = ClientLimits.unlimited();
  private @Nullable AntiBotService antiBot;
  private LoggingConfig logging = LoggingConfig.DEFAULTS;
  private LongSupplier clock = System::currentTimeMillis;

  AntiBotLoginTest() {}

  @AfterEach
  void close() {
    if (antiBot != null) {
      bots().close();
    }
  }

  @Nested
  @DisplayName("lockdown, offline")
  class Lockdown {

    @Test
    @DisplayName("should refuse an unknown name and network, and let a trusted one in")
    void onlyTrusted() throws Exception {
      start(AntiBotConfig.DEFAULTS.exempt());
      bots()
          .trustByCommand(new Identity.NameAtAddress("Alex", address("203.0.113.9")))
          .get(5, TimeUnit.SECONDS);
      bots().lockdown(true, "test");

      EmbeddedChannel unknown = login("Steve", "203.0.113.9");
      EmbeddedChannel trusted = login("Alex", "203.0.113.200");

      assertEquals(0x00, packetId(unknown), "Login Disconnect");
      assertEquals(0x02, packetId(trusted), "Login Success: the same name on its /24");
      assertEquals(1, limits.refusals(Refusal.LOCKDOWN));
    }

    @Test
    @DisplayName("should let exempt addresses in during a lockdown")
    void exempt() throws Exception {
      start(CidrRanges.parse(List.of("198.51.100.0/24")));
      bots().lockdown(true, "test");

      assertEquals(0x02, packetId(login("Steve", "198.51.100.7")));
    }
  }

  @Nested
  @DisplayName("learning")
  class Learning {

    @Test
    @DisplayName("should learn a player that joins outside attacks, and not during a lockdown")
    void learnsOutsideAttacks() throws Exception {
      start(AntiBotConfig.DEFAULTS.exempt());
      Identity steve = new Identity.NameAtAddress("Steve", address("203.0.113.9"));

      assertEquals(0x02, packetId(login("Steve", "203.0.113.9")));

      assertTrue(waitFor(() -> bots().verified().check(steve).isPresent()), "learned");
    }

    @Test
    @DisplayName("should detect a wave of unknown logins and stop learning during it")
    void attack() throws Exception {
      start(AntiBotConfig.DEFAULTS.exempt());
      for (int second = 0; second < 3; second++) {
        for (int i = 0; i < 20; i++) {
          login("bot_" + second + "_" + i, "203.0.113." + (i + 1));
        }
        bots().tick();
      }
      assertEquals(AttackLevel.ATTACK, bots().level());

      assertEquals(0x02, packetId(login("Late", "198.51.100.1")));
      Thread.sleep(200);

      assertFalse(
          bots()
              .verified()
              .check(new Identity.NameAtAddress("Late", address("198.51.100.1")))
              .isPresent(),
          "not learned during an attack");
    }
  }

  @Nested
  @DisplayName("shared addresses")
  class SharedAddresses {

    @Test
    @DisplayName(
        "should let a burst of logins from one address in when calm, and throttle it in attack")
    void calmThenAttack() throws Exception {
      limits = new ClientLimits(LimitsConfig.DEFAULTS, CidrRanges.none());
      start(AntiBotConfig.DEFAULTS.exempt());
      // A LAN party behind one address, out of an attack: all ten get in.
      for (int i = 0; i < 10; i++) {
        assertEquals(0x02, packetId(login("guest" + i, "198.51.100.7")), "calm login " + i);
      }

      // A wave of unknown logins from many addresses: the detector escalates.
      for (int second = 0; second < 3; second++) {
        for (int i = 0; i < 20; i++) {
          login("bot_" + second + "_" + i, "203.0." + (second + 1) + "." + (i + 1));
        }
        bots().tick();
      }
      assertEquals(AttackLevel.ATTACK, bots().level());
      assertEquals(0x02, packetId(login("first", "198.51.100.9")));
      assertEquals(0x02, packetId(login("second", "198.51.100.9")));

      assertEquals(0x00, packetId(login("third", "198.51.100.9")), "Login Disconnect: too fast");
      assertTrue(limits.refusals(Refusal.LOGIN_RATE) >= 1);
    }
  }

  @Nested
  @DisplayName("routine lines")
  class RoutineLines {

    @Test
    @DisplayName(
        "should hold back ping and connection lines during an attack and count them in its"
            + " summaries")
    void heldBackDuringAttack() throws Exception {
      AtomicLong now = new AtomicLong(1_000_000);
      clock = now::get;
      logging = new LoggingConfig(LogFormat.TEXT, true, true, false);
      start(AntiBotConfig.DEFAULTS.exempt());
      try (LogCapture logins = new LogCapture(LoginSessionHandler.class);
          LogCapture pings = new LogCapture(StatusSessionHandler.class);
          LogCapture attacks = new LogCapture(AntiBotService.class)) {
        // Calm: the switches apply.
        assertEquals(0x02, packetId(login("Steve", "198.51.100.1")));
        ping("198.51.100.2");
        assertTrue(logged(logins, "Player Steve"));
        assertTrue(logged(pings, "Server list ping from"));

        attackWaves();
        assertTrue(
            logged(
                attacks,
                "Until the attack is over, server list pings and player connection events are not"
                    + " logged one by one"));
        int loginLines = logins.events().size();
        int pingLines = pings.events().size();
        assertEquals(0x02, packetId(login("Late", "198.51.100.3")));
        for (int i = 0; i < 3; i++) {
          ping("198.51.100.4");
        }

        assertEquals(loginLines, logins.events().size(), "no login line during the attack");
        assertEquals(pingLines, pings.events().size(), "no ping line during the attack");
        assertEquals(1, bots().unlogged(AntiBotService.RoutineLine.CONNECTION));
        assertEquals(3, bots().unlogged(AntiBotService.RoutineLine.PING));
        String counted = "not logged one by one: 3 server list pings, 1 player connection events";
        now.addAndGet(AntiBotConfig.DEFAULTS.attack().reportInterval().toMillis());
        bots().tick();
        assertTrue(logged(attacks, "Attack in progress"), "a summary");
        assertTrue(logged(attacks, counted), "counted in the summary");

        // Calm again: the end of the attack counts them all, and the lines come back.
        for (int second = 0; second <= 60 && bots().level() == AttackLevel.ATTACK; second++) {
          bots().tick();
        }
        assertTrue(
            attacks.events().stream()
                .map(event -> event.getMessage().getFormattedMessage())
                .anyMatch(line -> line.startsWith("Attack over") && line.contains(counted)));
        assertEquals(0x02, packetId(login("After", "198.51.100.5")));
        assertTrue(logged(logins, "Player After"));
      }
    }
  }

  // ---------------------------------------------------------------------------
  // Helpers
  // ---------------------------------------------------------------------------

  private void start(CidrRanges exempt) throws IOException {
    AntiBotConfig d = AntiBotConfig.DEFAULTS;
    AntiBotConfig.Verified v = d.verified();
    AntiBotConfig config =
        new AntiBotConfig(
            true,
            exempt,
            new AntiBotConfig.Verified(
                v.expireAfterLastJoin(),
                1_000,
                24,
                48,
                false,
                v.secretFile(),
                v.sharedStoreTimeout()),
            d.attack(),
            d.blocklist(),
            d.checkTimeout(),
            // Learning from joins is what these tests watch: the probes have their own
            // (VerificationLoginTest).
            new AntiBotConfig.Verification(
                AntiBotConfig.Mode.NEVER,
                AntiBotConfig.Mode.NEVER,
                d.verification().maxConcurrent(),
                d.verification().queueSize(),
                d.verification().queueMaxWait(),
                d.verification().probeTimeout(),
                d.verification().tokenLifetime(),
                d.verification().tokenRotateAfter(),
                d.verification().tokenSecretFile(),
                d.verification().transferHost(),
                d.verification().transferPort()),
            AntiBotConfig.World.DEFAULTS);
    antiBot =
        new AntiBotService(
            config,
            false,
            limits,
            new WarpEventBus(Runnable::run),
            logging,
            directory,
            _ -> null,
            clock);
  }

  private AntiBotService bots() {
    return Objects.requireNonNull(antiBot, "start() first");
  }

  private EmbeddedChannel login(String name, String address) {
    EmbeddedChannel channel = connect(address);
    channel.writeInbound(handshake(2), loginStart(name));
    channel.runPendingTasks();
    return channel;
  }

  /** Sends a server list ping's handshake and request from {@code address}. */
  private void ping(String address) {
    EmbeddedChannel channel = connect(address);
    ByteBuf request = Unpooled.buffer();
    VarInt.write(request, 0x00);
    channel.writeInbound(handshake(1), frame(request));
    channel.runPendingTasks();
    channel.finishAndReleaseAll();
  }

  private EmbeddedChannel connect(String address) {
    ServerLoginContext context =
        TestLoginContexts.with(
            TestLoginContexts.withAntiBot(false, new MojangSessionService(), limits, bots()),
            TimeoutsConfig.DEFAULTS,
            logging);
    EmbeddedChannel channel =
        new ProxyProtocolTest.PeerChannel(new InetSocketAddress(address(address), 40000));
    channel.pipeline().addLast(new ServerChannelInitializer(context));
    return channel;
  }

  /** Three seconds of 20 unknown logins each, from 20 addresses: an attack. */
  private void attackWaves() {
    for (int second = 0; second < 3; second++) {
      for (int i = 0; i < 20; i++) {
        login("bot_" + second + "_" + i, "203.0.113." + (i + 1));
      }
      bots().tick();
    }
    assertEquals(AttackLevel.ATTACK, bots().level());
  }

  private static boolean logged(LogCapture log, String fragment) {
    return log.events().stream()
        .anyMatch(event -> event.getMessage().getFormattedMessage().contains(fragment));
  }

  private static int packetId(EmbeddedChannel channel) {
    ByteBuf frame = channel.readOutbound();
    assertNotNull(frame, "expected a packet");
    try {
      VarInt.read(frame);
      return VarInt.read(frame);
    } finally {
      frame.release();
    }
  }

  private static ByteBuf handshake(int nextState) {
    ByteBuf body = Unpooled.buffer();
    VarInt.write(body, 0x00);
    VarInt.write(body, VERSION.protocol());
    McString.write(body, "play.warp.dev");
    body.writeShort(25565);
    VarInt.write(body, nextState);
    return frame(body);
  }

  private static ByteBuf loginStart(String name) {
    ByteBuf body = Unpooled.buffer();
    VarInt.write(body, 0x00);
    McString.write(body, name);
    body.writeLong(0).writeLong(0);
    return frame(body);
  }

  private static ByteBuf frame(ByteBuf body) {
    ByteBuf frame = Unpooled.buffer();
    VarInt.write(frame, body.readableBytes());
    frame.writeBytes(body);
    body.release();
    return frame;
  }

  private static boolean waitFor(BooleanSupplier condition) throws InterruptedException {
    long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
    while (!condition.getAsBoolean()) {
      if (System.nanoTime() - deadline > 0) {
        return false;
      }
      Thread.sleep(10);
    }
    return true;
  }

  private static InetAddress address(String literal) {
    try {
      return InetAddress.getByName(literal);
    } catch (UnknownHostException e) {
      throw new AssertionError(e);
    }
  }
}
