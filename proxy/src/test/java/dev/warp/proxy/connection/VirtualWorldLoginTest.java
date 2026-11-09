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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.warp.api.antibot.Identity;
import dev.warp.api.antibot.VerificationMethod;
import dev.warp.protocol.ProtocolState;
import dev.warp.protocol.ProtocolVersion;
import dev.warp.protocol.codec.McString;
import dev.warp.protocol.codec.McUuid;
import dev.warp.protocol.codec.VarInt;
import dev.warp.protocol.packet.Packet;
import dev.warp.protocol.packet.PacketCodec;
import dev.warp.protocol.packet.PacketDirection;
import dev.warp.protocol.packet.PacketRegistry;
import dev.warp.protocol.packet.StateRegistry;
import dev.warp.protocol.packet.config.AcknowledgeFinishConfiguration;
import dev.warp.protocol.packet.config.ClientInformation;
import dev.warp.protocol.packet.config.ConfigPing;
import dev.warp.protocol.packet.config.ConfigPluginMessage;
import dev.warp.protocol.packet.config.ConfigPong;
import dev.warp.protocol.packet.config.FinishConfiguration;
import dev.warp.protocol.packet.config.KnownPacks;
import dev.warp.protocol.packet.config.RegistryData;
import dev.warp.protocol.packet.cookie.CookieRequest;
import dev.warp.protocol.packet.cookie.CookieResponse;
import dev.warp.protocol.packet.cookie.StoreCookie;
import dev.warp.protocol.packet.login.LoginAcknowledged;
import dev.warp.protocol.packet.login.LoginSuccess;
import dev.warp.protocol.packet.play.BlockUpdate;
import dev.warp.protocol.packet.play.ClientMovement;
import dev.warp.protocol.packet.play.EmptyChunk;
import dev.warp.protocol.packet.play.GameEvent;
import dev.warp.protocol.packet.play.JoinGame;
import dev.warp.protocol.packet.play.KeepAlive;
import dev.warp.protocol.packet.play.PlayDisconnect;
import dev.warp.protocol.packet.play.PlayPing;
import dev.warp.protocol.packet.play.PlayPong;
import dev.warp.protocol.packet.play.SetDefaultSpawnPosition;
import dev.warp.protocol.packet.play.SyncPlayerPosition;
import dev.warp.protocol.packet.play.SystemChatMessage;
import dev.warp.protocol.packet.play.TeleportConfirm;
import dev.warp.protocol.packet.play.TickEnd;
import dev.warp.protocol.packet.play.WindowConfirmation;
import dev.warp.proxy.antibot.AntiBotService;
import dev.warp.proxy.antibot.Probe;
import dev.warp.proxy.auth.MojangSessionService;
import dev.warp.proxy.config.AntiBotConfig;
import dev.warp.proxy.config.LoggingConfig;
import dev.warp.proxy.event.WarpEventBus;
import dev.warp.proxy.limit.ClientLimits;
import dev.warp.proxy.limit.Refusal;
import dev.warp.proxy.network.CidrRanges;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * The world check and the hold through Warp's pipeline, as a client of each version family sees
 * them: the registries of the virtual world (1.20.2+), the Join Game, the teleport, the chunk and
 * the platform, then a fall a vanilla client makes, a fall a script makes, and the wait for a
 * server in the world.
 */
@DisplayName("the virtual world at login")
class VirtualWorldLoginTest {

  private static final String HOST = "play.warp.dev";
  private static final int PORT = 25565;
  private static final ClientInformation SETTINGS =
      new ClientInformation("en_us", (byte) 10, 0, true, (byte) 0x7f, 1, false, true, 0);

  @TempDir private Path directory;

  private final ClientLimits limits = ClientLimits.unlimited();
  private final WarpEventBus events = new WarpEventBus(Runnable::run);
  private final AtomicLong millis = new AtomicLong(1_800_000_000_000L);
  private final AtomicLong nanos = new AtomicLong(TimeUnit.HOURS.toNanos(1));
  private @Nullable AntiBotService antiBot;
  private @Nullable ServerLoginContext lastContext;

  static Stream<ProtocolVersion> families() {
    return Stream.of(
        ProtocolVersion.MINECRAFT_1_8,
        ProtocolVersion.MINECRAFT_1_12_2,
        ProtocolVersion.MINECRAFT_1_16_4,
        ProtocolVersion.MINECRAFT_1_19_2,
        ProtocolVersion.MINECRAFT_1_20_1,
        ProtocolVersion.MINECRAFT_1_20_4,
        ProtocolVersion.MINECRAFT_1_21_4,
        ProtocolVersion.MINECRAFT_26_3);
  }

  @AfterEach
  void close() {
    if (antiBot != null) {
      antiBot.close();
    }
  }

  // ---------------------------------------------------------------------------
  // The world check
  // ---------------------------------------------------------------------------

  @Nested
  @DisplayName("the world check")
  class Check {

    @ParameterizedTest(name = "{0}")
    @MethodSource("dev.warp.proxy.connection.VirtualWorldLoginTest#families")
    @DisplayName("should give the client a world after the probe and trust a vanilla fall")
    void vanillaPasses(ProtocolVersion version) throws Exception {
      start(true, false);
      EmbeddedChannel channel = login("Steve", "203.0.113.9", version);
      probe(channel, version);

      World world = enterWorld(channel, version);
      fallAsVanilla(channel, version, world);

      Identity steve = new Identity.NameAtAddress("Steve", address("203.0.113.9"));
      assertTrue(waitFor(() -> bots().verified().check(steve).isPresent()), "trusted");
      assertEquals(VerificationMethod.PROBE, bots().verified().check(steve).orElseThrow().method());
      assertEquals(1, bots().status().world().checksPassed());
      assertEquals(0, bots().status().world().checksFailed());
      assertEquals(0, limits.refusals(Refusal.VERIFICATION_FAILED));
      assertEquals(0, limits.refusals(Refusal.WORLD_CHECK_FAILED));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("dev.warp.proxy.connection.VirtualWorldLoginTest#families")
    @DisplayName("should disconnect a script that lands without falling, after one retry")
    void scriptFails(ProtocolVersion version) throws Exception {
      start(true, false);
      EmbeddedChannel channel = login("bot", "203.0.113.10", version);
      probe(channel, version);

      World world = enterWorld(channel, version);
      confirmAndStand(channel, version, world);
      // Straight onto the platform, no ticks of fall: a mismatch, and a new height to try.
      send(
          channel,
          new ClientMovement.Position(world.x, world.platformTop(), world.z, true),
          version);
      World again = nextTeleport(channel, version, world);
      assertTrue(again.y != world.y || again.platformTop() != world.platformTop(), "a new height");
      confirmAndStand(channel, version, again);
      send(
          channel,
          new ClientMovement.Position(again.x, again.platformTop(), again.z, true),
          version);

      assertDisconnected(channel, version);
      assertEquals(1, bots().status().world().checksRetried());
      assertEquals(1, bots().status().world().checksFailed());
      assertEquals(1, limits.refusals(Refusal.WORLD_CHECK_FAILED));
      assertEquals(0, limits.refusals(Refusal.VERIFICATION_FAILED), "physics never strike");
      assertTrue(
          bots()
              .verified()
              .check(new Identity.NameAtAddress("bot", address("203.0.113.10")))
              .isEmpty());
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("dev.warp.proxy.connection.VirtualWorldLoginTest#families")
    @DisplayName("should tolerate one wrong fall and trust a right one from the new height")
    void stumbleTolerated(ProtocolVersion version) throws Exception {
      start(true, false);
      EmbeddedChannel channel = login("Lagging", "203.0.113.11", version);
      probe(channel, version);

      World world = enterWorld(channel, version);
      confirmAndStand(channel, version, world);
      send(channel, new ClientMovement.Position(world.x, world.y - 0.5, world.z, false), version);
      World again = nextTeleport(channel, version, world);
      fallAsVanilla(channel, version, again);

      Identity lagging = new Identity.NameAtAddress("Lagging", address("203.0.113.11"));
      assertTrue(
          waitFor(() -> bots().verified().check(lagging).isPresent()), "trusted: one flag only");
      assertEquals(1, bots().status().world().checksRetried());
      assertEquals(1, bots().status().world().checksPassed());
    }

    @Test
    @DisplayName("should disconnect a client that confirms a teleport it was never sent")
    void wrongTeleportId() throws Exception {
      ProtocolVersion version = ProtocolVersion.MINECRAFT_1_21_4;
      start(true, false);
      EmbeddedChannel channel = login("bot", "203.0.113.12", version);
      probe(channel, version);

      World world = enterWorld(channel, version);
      send(channel, new TeleportConfirm(world.teleportId + 1), version);

      assertDisconnected(channel, version);
      assertEquals(1, limits.refusals(Refusal.VERIFICATION_FAILED), "a protocol impossibility");
    }

    @Test
    @DisplayName("should skip the world for a 1.20.5+ client with no core pack in common")
    void noKnownPack() throws Exception {
      ProtocolVersion version = ProtocolVersion.MINECRAFT_1_21_4;
      start(true, false);
      EmbeddedChannel channel = login("Via", "203.0.113.13", version);
      probe(channel, version);
      nextPacket(channel, ProtocolState.CONFIGURATION, KnownPacks.class, version);

      send(
          channel,
          ProtocolState.CONFIGURATION,
          new KnownPacks(List.of(new KnownPacks.Pack("minecraft", "core", "1.21.9"))),
          version);

      ByteBuf next = channel.readOutbound();
      assertNotNull(next, "the player goes on without the world: no server here, a disconnect");
      try {
        VarInt.read(next);
        int registryData =
            StateRegistry.get(ProtocolState.CONFIGURATION, PacketDirection.CLIENTBOUND)
                .packetId(version, RegistryData.class);
        assertTrue(VarInt.read(next) != registryData, "no registry data, no world");
      } finally {
        next.release();
      }
      assertEquals(0, bots().status().world().checksPassed());
      assertEquals(0, bots().status().world().checksFailed());
      // Let in, not trusted: the probe alone does not make a known identity here.
      assertTrue(
          bots()
              .verified()
              .check(new Identity.NameAtAddress("Via", address("203.0.113.13")))
              .isEmpty());
      assertEquals(0, limits.refusals(Refusal.VERIFICATION_FAILED));
    }
  }

  // ---------------------------------------------------------------------------
  // The hold
  // ---------------------------------------------------------------------------

  @Nested
  @DisplayName("the hold")
  class Hold {

    @ParameterizedTest(name = "{0}")
    @MethodSource("dev.warp.proxy.connection.VirtualWorldLoginTest#families")
    @DisplayName("should keep a verified player in the world when no server exists, then expire")
    void heldThenExpired(ProtocolVersion version) throws Exception {
      start(true, true);
      EmbeddedChannel channel = login("Steve", "203.0.113.9", version);
      probe(channel, version);
      World world = enterWorld(channel, version);
      fallAsVanilla(channel, version, world);

      channel.runPendingTasks();
      assertTrue(channel.isActive(), "held, not disconnected");
      assertEquals(1, bots().status().world().holds());
      assertEquals(1, bots().status().world().holding());
      assertTrue(sawChat(channel, version), "told why it waits");

      advance(channel, Duration.ofSeconds(6));
      assertTrue(channel.isActive(), "still waiting after a retry found nothing");
      advance(channel, Duration.ofMinutes(5));
      assertDisconnected(channel, version);
      assertEquals(1, bots().status().world().holdsExpired());
      assertEquals(0, bots().status().world().holding());
    }

    @Test
    @DisplayName("should disconnect as before when the hold is off")
    void holdOff() throws Exception {
      ProtocolVersion version = ProtocolVersion.MINECRAFT_1_21_4;
      start(true, false);
      EmbeddedChannel channel = login("Steve", "203.0.113.9", version);
      probe(channel, version);
      World world = enterWorld(channel, version);
      fallAsVanilla(channel, version, world);

      assertDisconnected(channel, version);
      assertEquals(0, bots().status().world().holds());
    }
  }

  // ---------------------------------------------------------------------------
  // Helpers: the anti-bot
  // ---------------------------------------------------------------------------

  private void start(boolean check, boolean hold) throws IOException {
    AntiBotConfig d = AntiBotConfig.DEFAULTS;
    AntiBotConfig.Verified v = d.verified();
    AntiBotConfig config =
        new AntiBotConfig(
            true,
            CidrRanges.none(),
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
            d.verification(),
            new AntiBotConfig.World(check, hold, Duration.ofSeconds(5), Duration.ofMinutes(5)));
    antiBot =
        new AntiBotService(
            config,
            false,
            limits,
            events,
            LoggingConfig.DEFAULTS,
            directory,
            _ -> null,
            millis::get,
            nanos::get);
  }

  private AntiBotService bots() {
    return Objects.requireNonNull(antiBot, "start() first");
  }

  private void advance(EmbeddedChannel channel, Duration duration) {
    nanos.addAndGet(duration.toNanos());
    millis.addAndGet(duration.toMillis());
    channel.advanceTimeBy(duration.toMillis(), TimeUnit.MILLISECONDS);
    channel.runScheduledPendingTasks();
    channel.runPendingTasks();
  }

  // ---------------------------------------------------------------------------
  // Helpers: the client, through the probe and into the world
  // ---------------------------------------------------------------------------

  private EmbeddedChannel login(String name, String address, ProtocolVersion version) {
    ServerLoginContext context =
        TestLoginContexts.withAntiBot(
            false, -1, new MojangSessionService(), events, limits, bots());
    EmbeddedChannel channel =
        new ProxyProtocolTest.PeerChannel(new InetSocketAddress(address(address), 40000));
    channel.pipeline().addLast(new ServerChannelInitializer(context));
    this.lastContext = context;
    channel.writeInbound(handshake(version), loginStart(name, version));
    channel.runPendingTasks();
    LoginSuccess success = nextPacket(channel, ProtocolState.LOGIN, LoginSuccess.class, version);
    assertEquals(name, success.username());
    return channel;
  }

  /** Answers the probe as a vanilla client does, in either of its forms. */
  private void probe(EmbeddedChannel channel, ProtocolVersion version) {
    if (!version.supportsConfigurationState()) {
      KeepAlive keepAlive = nextPacket(channel, ProtocolState.PLAY, KeepAlive.class, version);
      send(channel, ProtocolState.PLAY, new KeepAlive(keepAlive.id()), version);
      return;
    }
    send(channel, ProtocolState.LOGIN, new LoginAcknowledged(), version);
    ConfigPing ping = nextPacket(channel, ProtocolState.CONFIGURATION, ConfigPing.class, version);
    KeepAlive keepAlive =
        nextPacket(channel, ProtocolState.CONFIGURATION, KeepAlive.class, version);
    byte @Nullable [] cookie = null;
    if (version.isAtLeast(ProtocolVersion.MINECRAFT_1_20_5)) {
      StoreCookie stored =
          nextPacket(channel, ProtocolState.CONFIGURATION, StoreCookie.class, version);
      nextPacket(channel, ProtocolState.CONFIGURATION, CookieRequest.class, version);
      cookie = stored.payload();
    }
    send(channel, ProtocolState.CONFIGURATION, SETTINGS, version);
    send(channel, ProtocolState.CONFIGURATION, brand(), version);
    send(channel, ProtocolState.CONFIGURATION, new ConfigPong(ping.id()), version);
    send(channel, ProtocolState.CONFIGURATION, new KeepAlive(keepAlive.id()), version);
    if (cookie != null) {
      send(
          channel,
          ProtocolState.CONFIGURATION,
          new CookieResponse(Probe.COOKIE_KEY, cookie),
          version);
    }
  }

  /** The world as the client received it. */
  private record World(int teleportId, double x, double y, double z, int platformY, int echo) {
    double platformTop() {
      return platformY + 1;
    }
  }

  /** Goes through the world's configuration (1.20.2+) and reads the world's packets. */
  private World enterWorld(EmbeddedChannel channel, ProtocolVersion version) {
    if (version.supportsConfigurationState()) {
      if (version.isAtLeast(ProtocolVersion.MINECRAFT_1_20_5)) {
        KnownPacks offer =
            nextPacket(channel, ProtocolState.CONFIGURATION, KnownPacks.class, version);
        assertTrue(
            offer.packs().contains(new KnownPacks.Pack("minecraft", "core", version.name())),
            offer.toString());
        send(
            channel,
            ProtocolState.CONFIGURATION,
            new KnownPacks(List.of(new KnownPacks.Pack("minecraft", "core", version.name()))),
            version);
        for (int i = 0; i < 3; i++) {
          nextPacket(channel, ProtocolState.CONFIGURATION, RegistryData.class, version);
        }
      } else {
        RegistryData data =
            nextPacket(channel, ProtocolState.CONFIGURATION, RegistryData.class, version);
        assertEquals(10, data.rawEntries()[0], "an NBT compound");
      }
      nextPacket(channel, ProtocolState.CONFIGURATION, FinishConfiguration.class, version);
      send(channel, ProtocolState.CONFIGURATION, new AcknowledgeFinishConfiguration(), version);
    }
    PacketRegistry play = StateRegistry.get(ProtocolState.PLAY, PacketDirection.CLIENTBOUND);
    JoinGame joinGame = nextPacket(channel, ProtocolState.PLAY, JoinGame.class, version);
    assertTrue(joinGame.entityId() > 0);
    if (version.isAtLeast(ProtocolVersion.MINECRAFT_1_19_3)) {
      nextRaw(channel, play.packetId(version, SetDefaultSpawnPosition.class)).release();
    }
    World teleport = readTeleport(channel, version, -1);
    if (version.isAtLeast(ProtocolVersion.MINECRAFT_1_20_3)) {
      nextRaw(channel, play.packetId(version, GameEvent.class)).release();
    }
    nextRaw(channel, play.packetId(version, EmptyChunk.class)).release();
    int platformY = readPlatform(channel, version);
    int echo = readEcho(channel, version);
    assertNull(channel.readOutbound(), "nothing more before the client answers");
    return new World(teleport.teleportId, teleport.x, teleport.y, teleport.z, platformY, echo);
  }

  /** The retry: a new teleport and a new platform. */
  private World nextTeleport(EmbeddedChannel channel, ProtocolVersion version, World previous) {
    World teleport = readTeleport(channel, version, previous.echo);
    int platformY = readPlatform(channel, version);
    return new World(
        teleport.teleportId, teleport.x, teleport.y, teleport.z, platformY, previous.echo);
  }

  private World readTeleport(EmbeddedChannel channel, ProtocolVersion version, int echo) {
    PacketRegistry play = StateRegistry.get(ProtocolState.PLAY, PacketDirection.CLIENTBOUND);
    ByteBuf body = nextRaw(channel, play.packetId(version, SyncPlayerPosition.class));
    try {
      int teleportId = 1;
      double x;
      double y;
      double z;
      if (version.isAtLeast(ProtocolVersion.MINECRAFT_1_21_2)) {
        teleportId = VarInt.read(body);
        x = body.readDouble();
        y = body.readDouble();
        z = body.readDouble();
      } else {
        x = body.readDouble();
        y = body.readDouble();
        z = body.readDouble();
        if (version.isOlderThan(ProtocolVersion.MINECRAFT_1_8)) {
          y -= ClientMovement.LEGACY_STANCE;
        } else {
          body.skipBytes(4 + 4 + 1);
          if (version.isAtLeast(ProtocolVersion.MINECRAFT_1_9)) {
            teleportId = VarInt.read(body);
          }
        }
      }
      return new World(teleportId, x, y, z, 0, echo);
    } finally {
      body.release();
    }
  }

  private int readPlatform(EmbeddedChannel channel, ProtocolVersion version) {
    PacketRegistry play = StateRegistry.get(ProtocolState.PLAY, PacketDirection.CLIENTBOUND);
    int platformY = -1;
    for (int i = 0; i < 9; i++) {
      ByteBuf body = nextRaw(channel, play.packetId(version, BlockUpdate.class));
      try {
        int y;
        if (version.isOlderThan(ProtocolVersion.MINECRAFT_1_8)) {
          body.readInt();
          y = body.readUnsignedByte();
        } else {
          long position = body.readLong();
          y =
              version.isAtLeast(ProtocolVersion.MINECRAFT_1_14)
                  ? (int) (position & 0xFFF)
                  : (int) ((position >> 26) & 0xFFF);
        }
        assertTrue(platformY == -1 || platformY == y, "one flat platform");
        platformY = y;
      } finally {
        body.release();
      }
    }
    return platformY;
  }

  private int readEcho(EmbeddedChannel channel, ProtocolVersion version) {
    PacketRegistry play = StateRegistry.get(ProtocolState.PLAY, PacketDirection.CLIENTBOUND);
    if (version.isAtLeast(ProtocolVersion.MINECRAFT_1_17)) {
      ByteBuf body = nextRaw(channel, play.packetId(version, PlayPing.class));
      try {
        return body.readInt();
      } finally {
        body.release();
      }
    }
    ByteBuf body = nextRaw(channel, play.packetId(version, WindowConfirmation.class));
    try {
      body.readByte();
      return body.readShort();
    } finally {
      body.release();
    }
  }

  /** Confirms the teleport and reports the first position, as every client does at once. */
  private void confirmAndStand(EmbeddedChannel channel, ProtocolVersion version, World world) {
    if (version.isAtLeast(ProtocolVersion.MINECRAFT_1_9)) {
      send(channel, new TeleportConfirm(world.teleportId), version);
    }
    send(
        channel,
        new ClientMovement.PositionRotation(world.x, world.y, world.z, 0, 0, false),
        version);
    assertTrue(channel.isActive(), "the first position is right");
  }

  /** Confirms, stands, echoes the ping, then falls one tick at a time until the platform. */
  private void fallAsVanilla(EmbeddedChannel channel, ProtocolVersion version, World world) {
    confirmAndStand(channel, version, world);
    if (version.isAtLeast(ProtocolVersion.MINECRAFT_1_17)) {
      send(channel, new PlayPong(world.echo), version);
    } else {
      send(channel, new WindowConfirmation(0, (short) world.echo, true), version);
    }
    double y = world.y;
    double v = 0;
    for (int tick = 0; tick < 64; tick++) {
      if (version.isAtLeast(ProtocolVersion.MINECRAFT_1_21_2)) {
        send(channel, new TickEnd(), version);
      }
      v = (v - 0.08) * 0.98f;
      double next = y + v;
      if (next <= world.platformTop()) {
        send(
            channel,
            new ClientMovement.Position(world.x, world.platformTop(), world.z, true),
            version);
        return;
      }
      y = next;
      send(channel, new ClientMovement.Position(world.x, y, world.z, false), version);
      assertTrue(channel.isActive(), "still connected at tick " + tick);
    }
    throw new AssertionError("never landed");
  }

  private void assertDisconnected(EmbeddedChannel channel, ProtocolVersion version) {
    channel.runPendingTasks();
    boolean disconnect = false;
    for (ByteBuf frame = channel.readOutbound(); frame != null; frame = channel.readOutbound()) {
      try {
        VarInt.read(frame);
        int id = VarInt.read(frame);
        if (id
            == StateRegistry.get(ProtocolState.PLAY, PacketDirection.CLIENTBOUND)
                .packetId(version, PlayDisconnect.class)) {
          disconnect = true;
        }
      } finally {
        frame.release();
      }
    }
    assertTrue(disconnect, "a disconnect was sent");
    channel.runPendingTasks();
    assertFalse(channel.isActive(), "closed");
  }

  private boolean sawChat(EmbeddedChannel channel, ProtocolVersion version) {
    PacketRegistry play = StateRegistry.get(ProtocolState.PLAY, PacketDirection.CLIENTBOUND);
    int chat = play.packetId(version, SystemChatMessage.class);
    boolean seen = false;
    for (ByteBuf frame = channel.readOutbound(); frame != null; frame = channel.readOutbound()) {
      try {
        VarInt.read(frame);
        if (VarInt.read(frame) == chat) {
          seen = true;
        }
      } finally {
        frame.release();
      }
    }
    return seen;
  }

  // ---------------------------------------------------------------------------
  // Helpers: frames
  // ---------------------------------------------------------------------------

  private void send(EmbeddedChannel channel, Packet packet, ProtocolVersion version) {
    PacketRegistry world = StateRegistry.world(ProtocolState.PLAY);
    channel.writeInbound(frame(body(world, packet, version)));
    channel.runPendingTasks();
  }

  private <T extends Packet> void send(
      EmbeddedChannel channel, ProtocolState state, T packet, ProtocolVersion version) {
    PacketRegistry registry =
        state == ProtocolState.CONFIGURATION && packet instanceof KnownPacks
            ? StateRegistry.world(state)
            : StateRegistry.get(state, PacketDirection.SERVERBOUND);
    channel.writeInbound(frame(body(registry, packet, version)));
    channel.runPendingTasks();
  }

  @SuppressWarnings("unchecked")
  private static <T extends Packet> ByteBuf body(
      PacketRegistry registry, T packet, ProtocolVersion version) {
    PacketRegistry.Encoding encoding = registry.encoding(version, packet.getClass());
    ByteBuf body = Unpooled.buffer();
    VarInt.write(body, encoding.packetId());
    ((PacketCodec<T>) encoding.codec()).encode(packet, body, version);
    return body;
  }

  /** The next frame's body, which must carry {@code packetId}. */
  private static ByteBuf nextRaw(EmbeddedChannel channel, int packetId) {
    channel.runPendingTasks();
    ByteBuf frame = channel.readOutbound();
    assertNotNull(frame, "expected packet 0x" + Integer.toHexString(packetId));
    VarInt.read(frame);
    int id = VarInt.read(frame);
    assertEquals(packetId, id, "packet id (got 0x" + Integer.toHexString(id) + ")");
    return frame;
  }

  @SuppressWarnings("unchecked")
  private <T extends Packet> T nextPacket(
      EmbeddedChannel channel, ProtocolState state, Class<T> type, ProtocolVersion version) {
    PacketRegistry registry = StateRegistry.get(state, PacketDirection.CLIENTBOUND);
    ByteBuf frame = nextRaw(channel, registry.packetId(version, type));
    try {
      return ((PacketCodec<T>) registry.encoding(version, type).codec()).decode(frame, version);
    } finally {
      frame.release();
    }
  }

  private static ConfigPluginMessage brand() {
    ByteBuf buf = Unpooled.buffer();
    try {
      McString.write(buf, "vanilla");
      return new ConfigPluginMessage(ConfigPluginMessage.BRAND_CHANNEL, ByteBufUtil.getBytes(buf));
    } finally {
      buf.release();
    }
  }

  private ByteBuf handshake(ProtocolVersion version) {
    ByteBuf body = Unpooled.buffer();
    VarInt.write(body, 0x00);
    VarInt.write(body, version.protocol());
    McString.write(body, HOST);
    body.writeShort(PORT);
    VarInt.write(body, HandshakeIntent.LOGIN.id());
    return frame(body);
  }

  private ByteBuf loginStart(String name, ProtocolVersion version) {
    ByteBuf body = Unpooled.buffer();
    VarInt.write(body, 0x00);
    McString.write(body, name);
    if (version.isBetween(ProtocolVersion.MINECRAFT_1_19, ProtocolVersion.MINECRAFT_1_19_2)) {
      body.writeBoolean(false); // no profile public key
    }
    if (version.isAtLeast(ProtocolVersion.MINECRAFT_1_19_1)) {
      UUID uuid = UUID.nameUUIDFromBytes(name.getBytes(StandardCharsets.UTF_8));
      if (version.isOlderThan(ProtocolVersion.MINECRAFT_1_20_2)) {
        body.writeBoolean(true);
      }
      McUuid.write(body, uuid);
    }
    return frame(body);
  }

  private static ByteBuf frame(ByteBuf body) {
    ByteBuf frame = Unpooled.buffer();
    VarInt.write(frame, body.readableBytes());
    frame.writeBytes(body);
    body.release();
    return frame;
  }

  private static boolean waitFor(java.util.function.BooleanSupplier condition)
      throws InterruptedException {
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

  @SuppressWarnings("unused")
  private List<String> unused() {
    return new ArrayList<>();
  }
}
