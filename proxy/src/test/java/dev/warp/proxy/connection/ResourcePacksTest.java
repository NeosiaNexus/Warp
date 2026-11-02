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

import static dev.warp.proxy.connection.SwitchHarness.LOBBY;
import static dev.warp.proxy.connection.SwitchHarness.SURVIVAL;
import static dev.warp.proxy.connection.SwitchHarness.frame;
import static dev.warp.proxy.connection.SwitchHarness.types;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.warp.api.event.Subscription;
import dev.warp.api.event.player.PlayerResourcePackStatusEvent;
import dev.warp.protocol.ProtocolState;
import dev.warp.protocol.ProtocolVersion;
import dev.warp.protocol.packet.Packet;
import dev.warp.protocol.packet.play.PlayPluginMessage;
import dev.warp.protocol.packet.play.ResourcePackPop;
import dev.warp.protocol.packet.play.ResourcePackPush;
import dev.warp.protocol.packet.play.ResourcePackResponse;
import dev.warp.proxy.event.PluginEventBus;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import net.kyori.adventure.resource.ResourcePackCallback;
import net.kyori.adventure.resource.ResourcePackInfo;
import net.kyori.adventure.resource.ResourcePackRequest;
import net.kyori.adventure.resource.ResourcePackStatus;
import net.kyori.adventure.text.Component;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("Resource packs")
class ResourcePacksTest {

  private static final ProtocolVersion MODERN = ProtocolVersion.MINECRAFT_1_21_4;
  private static final ProtocolVersion SINGLE = ProtocolVersion.MINECRAFT_1_12_2;
  private static final String HASH = "0123456789abcdef0123456789abcdef01234567";

  private static final ResourcePackInfo NETWORK =
      ResourcePackInfo.resourcePackInfo(
          UUID.fromString("5c39a8cb-1a3a-4c86-9a47-0f0aaf0e3a01"),
          URI.create("https://warp.dev/network.zip"),
          HASH);
  private static final ResourcePackInfo EVENT =
      ResourcePackInfo.resourcePackInfo(
          UUID.fromString("0f3e1b7c-7b5d-4b55-8e0a-2c3d4e5f6a7b"),
          URI.create("https://warp.dev/event.zip"),
          "");

  private final List<SwitchHarness> harnesses = new ArrayList<>();

  /** What the request callbacks received, in order. */
  private final BlockingQueue<Status> statuses = new LinkedBlockingQueue<>();

  private final ResourcePackCallback callback =
      (id, status, audience) -> statuses.add(new Status(id, status));

  @AfterEach
  void tearDown() {
    harnesses.forEach(SwitchHarness::close);
  }

  private record Status(UUID id, ResourcePackStatus status) {}

  private Status nextStatus() throws InterruptedException {
    Status status = statuses.poll(5, TimeUnit.SECONDS);
    assertNotNull(status, "no status reported");
    return status;
  }

  private void assertNoStatus() throws InterruptedException {
    assertNull(statuses.poll(200, TimeUnit.MILLISECONDS));
  }

  private ResourcePackRequest request(ResourcePackInfo... packs) {
    return ResourcePackRequest.resourcePackRequest()
        .packs(List.of(packs))
        .required(true)
        .prompt(Component.text("Please"))
        .callback(callback)
        .build();
  }

  /** A player of {@code version} in a game on the lobby, whose backend is returned. */
  private SwitchHarness inGame(ProtocolVersion version) {
    SwitchHarness setup = new SwitchHarness(version, List.of(), ProtocolState.PLAY);
    harnesses.add(setup);
    MinecraftConnection lobby = setup.backend(ProtocolState.PLAY);
    setup.player.setBackendConnection(new BackendConnection(lobby, LOBBY));
    lobby.setSessionHandler(new BackendPlaySessionHandler(setup.player, lobby));
    setup.client.setSessionHandler(new ClientPlaySessionHandler(setup.player));
    setup.player.joinedGame();
    setup.sentToClient();
    return setup;
  }

  private static List<ResourcePackPush> pushes(List<SwitchHarness.Sent> sent) {
    return sent.stream()
        .filter(packet -> packet.type() == ResourcePackPush.class)
        .map(packet -> packet.as(ResourcePackPush.class))
        .toList();
  }

  private static ResourcePackResponse answer(UUID id, int result) {
    return new ResourcePackResponse(id, "", result);
  }

  private static void loaded(SwitchHarness setup, UUID id) {
    for (int result :
        new int[] {
          ResourcePackResponse.ACCEPTED,
          ResourcePackResponse.DOWNLOADED,
          ResourcePackResponse.SUCCESSFULLY_LOADED
        }) {
      setup.player.resourcePacks().clientAnswered(answer(id, result));
    }
  }

  private static ResourcePackPush pushOf(ResourcePackInfo info, ProtocolVersion version) {
    return ResourcePackPush.of(info.id(), info.uri().toString(), info.hash(), false, null, version);
  }

  private static List<Integer> results(List<Packet> packets) {
    return packets.stream()
        .filter(ResourcePackResponse.class::isInstance)
        .map(packet -> ((ResourcePackResponse) packet).result())
        .toList();
  }

  // ---------------------------------------------------------------------------
  // 1.20.3+
  // ---------------------------------------------------------------------------

  @Nested
  @DisplayName("from 1.20.3")
  class Multiple {

    @Test
    @DisplayName("should send every pack, keep the answers from the server, and report them")
    void sendAndReport() throws InterruptedException {
      SwitchHarness setup = inGame(MODERN);
      MinecraftConnection lobby = setup.current();

      setup.player.api().sendResourcePacks(request(NETWORK, EVENT));
      List<ResourcePackPush> sent = pushes(setup.sentToClient());
      loaded(setup, NETWORK.id());

      assertEquals(
          List.of(NETWORK.id(), EVENT.id()), sent.stream().map(ResourcePackPush::uuid).toList());
      assertEquals(true, sent.getFirst().required());
      assertNotNull(sent.getFirst().prompt());
      assertEquals(new Status(NETWORK.id(), ResourcePackStatus.ACCEPTED), nextStatus());
      assertEquals(new Status(NETWORK.id(), ResourcePackStatus.DOWNLOADED), nextStatus());
      assertEquals(new Status(NETWORK.id(), ResourcePackStatus.SUCCESSFULLY_LOADED), nextStatus());
      assertEquals(List.of(), setup.packetsSentToBackend(lobby), "the server never sees them");
    }

    @Test
    @DisplayName("should not send a pack the client loaded again, and report it loaded at once")
    void alreadyLoaded() throws InterruptedException {
      SwitchHarness setup = inGame(MODERN);
      setup.player.api().sendResourcePacks(request(NETWORK));
      setup.sentToClient();
      loaded(setup, NETWORK.id());
      for (int answers = 0; answers < 3; answers++) {
        nextStatus();
      }

      setup.player.api().sendResourcePacks(request(NETWORK));

      assertEquals(List.of(), pushes(setup.sentToClient()));
      assertEquals(new Status(NETWORK.id(), ResourcePackStatus.SUCCESSFULLY_LOADED), nextStatus());
    }

    @Test
    @DisplayName("should answer a server sending a pack the client loaded, as the client would")
    void answerForTheClient() {
      SwitchHarness setup = inGame(MODERN);
      MinecraftConnection lobby = setup.current();
      setup.player.api().sendResourcePacks(request(NETWORK));
      setup.sentToClient();
      loaded(setup, NETWORK.id());

      setup.receive(lobby, frame(MODERN, pushOf(NETWORK, MODERN)));

      assertEquals(List.of(), pushes(setup.sentToClient()), "no reload on the client");
      assertEquals(
          List.of(
              ResourcePackResponse.ACCEPTED,
              ResourcePackResponse.DOWNLOADED,
              ResourcePackResponse.SUCCESSFULLY_LOADED),
          results(setup.packetsSentToBackend(lobby)));
    }

    @Test
    @DisplayName("should pass a server's pack to the client and its answers back, firing the event")
    void serverPack() throws InterruptedException {
      SwitchHarness setup = inGame(MODERN);
      MinecraftConnection lobby = setup.current();
      BlockingQueue<PlayerResourcePackStatusEvent> events = new LinkedBlockingQueue<>();
      Subscription subscription =
          new PluginEventBus(TestLoginContexts.EVENTS, "test", null, unused -> {})
              .subscribe(
                  PlayerResourcePackStatusEvent.class,
                  event -> {
                    if (event.player() == setup.player.api()) {
                      events.add(event);
                    }
                  });
      try {
        setup.receive(lobby, frame(MODERN, pushOf(EVENT, MODERN)));
        List<ResourcePackPush> forwarded = pushes(setup.sentToClient());
        setup
            .player
            .resourcePacks()
            .clientAnswered(answer(EVENT.id(), ResourcePackResponse.DECLINED));
        lobby.flush(); // the client handler flushes once it read the client's packets

        assertEquals(List.of(EVENT.id()), forwarded.stream().map(ResourcePackPush::uuid).toList());
        assertEquals(
            List.of(ResourcePackResponse.DECLINED), results(setup.packetsSentToBackend(lobby)));
        PlayerResourcePackStatusEvent event = events.poll(5, TimeUnit.SECONDS);
        assertNotNull(event);
        assertEquals(ResourcePackStatus.DECLINED, event.status());
        assertEquals(false, event.sentByProxy());
      } finally {
        subscription.unsubscribe();
      }
    }

    @Test
    @DisplayName("should send the server an answer for a pack the proxy did not see sent")
    void unknownPack() {
      SwitchHarness setup = inGame(MODERN);
      MinecraftConnection lobby = setup.current();

      setup
          .player
          .resourcePacks()
          .clientAnswered(answer(UUID.randomUUID(), ResourcePackResponse.ACCEPTED));
      lobby.flush();

      assertEquals(
          List.of(ResourcePackResponse.ACCEPTED), results(setup.packetsSentToBackend(lobby)));
    }

    @Test
    @DisplayName(
        "should turn a server's removal of every pack into the removal of the servers' packs")
    void serverRemovesEverything() {
      SwitchHarness setup = inGame(MODERN);
      MinecraftConnection lobby = setup.current();
      setup.player.api().sendResourcePacks(request(NETWORK));
      setup.receive(lobby, frame(MODERN, pushOf(EVENT, MODERN)));
      setup.sentToClient();

      setup.receive(lobby, frame(MODERN, new ResourcePackPop(null)));
      setup.receive(lobby, frame(MODERN, new ResourcePackPop(NETWORK.id())));

      List<SwitchHarness.Sent> sent = setup.sentToClient();
      assertEquals(List.of(ResourcePackPop.class), types(sent));
      assertEquals(EVENT.id(), sent.getFirst().as(ResourcePackPop.class).uuid());
      assertEquals(List.of(NETWORK.id()), setup.player.resourcePacks().held());
    }

    @Test
    @DisplayName("should remove packs by UUID, and clear only the proxy's")
    void removeAndClear() {
      SwitchHarness setup = inGame(MODERN);
      MinecraftConnection lobby = setup.current();
      setup.player.api().sendResourcePacks(request(NETWORK));
      setup.receive(lobby, frame(MODERN, pushOf(EVENT, MODERN)));
      setup.sentToClient();

      setup.player.api().clearResourcePacks();
      List<SwitchHarness.Sent> cleared = setup.sentToClient();
      setup.player.api().removeResourcePacks(EVENT.id());

      assertEquals(NETWORK.id(), cleared.getFirst().as(ResourcePackPop.class).uuid());
      assertEquals(1, cleared.size());
      assertEquals(EVENT.id(), setup.sentToClient().getFirst().as(ResourcePackPop.class).uuid());
    }

    @Test
    @DisplayName("should send packs in the configuration phase when the client is there")
    void duringConfiguration() {
      SwitchHarness setup = inGame(MODERN);
      setup.player.startConfiguration();
      setup.sentToClient();

      setup.player.api().sendResourcePacks(request(NETWORK));

      SwitchHarness.Sent sent = setup.sentToClient().getFirst();
      assertEquals(ProtocolState.CONFIGURATION, sent.state());
      assertEquals(NETWORK.id(), sent.as(ResourcePackPush.class).uuid());
    }

    @Test
    @DisplayName("should keep the proxy's packs across a switch, and remove the previous server's")
    void acrossSwitch() {
      SwitchHarness setup = inGame(MODERN);
      MinecraftConnection lobby = setup.current();
      setup.player.resourcePacks().serverChanged(lobby);
      setup.player.api().sendResourcePacks(request(NETWORK));
      setup.receive(lobby, frame(MODERN, pushOf(EVENT, MODERN)));
      setup.sentToClient();
      loaded(setup, NETWORK.id());
      loaded(setup, EVENT.id());

      MinecraftConnection survival = setup.backend(ProtocolState.CONFIGURATION);
      setup.player.setBackendConnection(new BackendConnection(survival, SURVIVAL));
      setup.player.startConfiguration();
      setup.player.resourcePacks().configurationStarted(survival);
      setup.player.resourcePacks().serverSent(pushOf(NETWORK, MODERN), survival);
      setup.player.resourcePacks().removeStale();

      List<SwitchHarness.Sent> sent = setup.sentToClient();
      assertEquals(EVENT.id(), sent.getLast().as(ResourcePackPop.class).uuid());
      assertEquals(List.of(), pushes(sent), "the network pack is not reloaded");
      assertEquals(List.of(NETWORK.id()), setup.player.resourcePacks().held());
    }

    @Test
    @DisplayName("should give a pack's discard to the request it replaced under the same UUID")
    void replacedUnderSameUuid() throws InterruptedException {
      SwitchHarness setup = inGame(MODERN);
      BlockingQueue<ResourcePackStatus> first = new LinkedBlockingQueue<>();
      setup
          .player
          .api()
          .sendResourcePacks(
              ResourcePackRequest.resourcePackRequest()
                  .packs(NETWORK)
                  .callback((id, status, audience) -> first.add(status))
                  .build());
      setup.player.api().sendResourcePacks(request(NETWORK));

      setup
          .player
          .resourcePacks()
          .clientAnswered(answer(NETWORK.id(), ResourcePackResponse.DISCARDED));
      setup
          .player
          .resourcePacks()
          .clientAnswered(answer(NETWORK.id(), ResourcePackResponse.ACCEPTED));

      assertEquals(ResourcePackStatus.DISCARDED, first.poll(5, TimeUnit.SECONDS));
      assertEquals(new Status(NETWORK.id(), ResourcePackStatus.ACCEPTED), nextStatus());
      assertNoStatus();
    }
  }

  // ---------------------------------------------------------------------------
  // Before 1.20.3
  // ---------------------------------------------------------------------------

  @Nested
  @DisplayName("before 1.20.3")
  class Single {

    @Test
    @DisplayName("should send the first pack, and discard the others: the client holds one")
    void firstPackOnly() throws InterruptedException {
      SwitchHarness setup = inGame(SINGLE);

      setup.player.api().sendResourcePacks(request(NETWORK, EVENT));

      List<ResourcePackPush> sent = pushes(setup.sentToClient());
      assertEquals(1, sent.size());
      assertEquals("https://warp.dev/network.zip", sent.getFirst().url());
      assertEquals(new Status(EVENT.id(), ResourcePackStatus.DISCARDED), nextStatus());
    }

    @Test
    @DisplayName("should send one pack at a time, and give each answer to the pack in flight")
    void oneAtATime() throws InterruptedException {
      SwitchHarness setup = inGame(SINGLE);
      MinecraftConnection lobby = setup.current();
      setup.player.api().sendResourcePacks(request(NETWORK));
      setup.sentToClient();

      setup.receive(lobby, frame(SINGLE, pushOf(EVENT, SINGLE)));
      List<ResourcePackPush> whileInFlight = pushes(setup.sentToClient());
      setup
          .player
          .resourcePacks()
          .clientAnswered(answer(new UUID(0, 0), ResourcePackResponse.ACCEPTED));
      setup
          .player
          .resourcePacks()
          .clientAnswered(answer(new UUID(0, 0), ResourcePackResponse.SUCCESSFULLY_LOADED));
      List<ResourcePackPush> next = pushes(setup.sentToClient());
      setup
          .player
          .resourcePacks()
          .clientAnswered(answer(new UUID(0, 0), ResourcePackResponse.DECLINED));
      lobby.flush();

      assertEquals(List.of(), whileInFlight);
      assertEquals(new Status(NETWORK.id(), ResourcePackStatus.ACCEPTED), nextStatus());
      assertEquals(new Status(NETWORK.id(), ResourcePackStatus.SUCCESSFULLY_LOADED), nextStatus());
      assertEquals(
          List.of("https://warp.dev/event.zip"), next.stream().map(ResourcePackPush::url).toList());
      assertEquals(
          List.of(ResourcePackResponse.DECLINED), results(setup.packetsSentToBackend(lobby)));
      assertNoStatus();
    }

    @Test
    @DisplayName("should answer a server sending the pack the client loaded last")
    void answerForTheClient() {
      SwitchHarness setup = inGame(SINGLE);
      MinecraftConnection lobby = setup.current();
      setup.player.api().sendResourcePacks(request(NETWORK));
      setup.sentToClient();
      setup
          .player
          .resourcePacks()
          .clientAnswered(answer(new UUID(0, 0), ResourcePackResponse.SUCCESSFULLY_LOADED));

      setup.receive(lobby, frame(SINGLE, pushOf(NETWORK, SINGLE)));

      assertEquals(List.of(), pushes(setup.sentToClient()));
      assertEquals(
          List.of(ResourcePackResponse.ACCEPTED, ResourcePackResponse.SUCCESSFULLY_LOADED),
          results(setup.packetsSentToBackend(lobby)));
    }

    @Test
    @DisplayName("should not let a pack the client never answered hold back the next server's")
    void unansweredPackOfAServerLeft() {
      SwitchHarness setup = inGame(SINGLE);
      MinecraftConnection lobby = setup.current();
      setup.player.resourcePacks().serverChanged(lobby);
      setup.receive(lobby, frame(SINGLE, pushOf(NETWORK, SINGLE)));
      setup.sentToClient();

      MinecraftConnection survival = setup.backend(ProtocolState.PLAY);
      setup.player.resourcePacks().serverChanged(survival);
      setup.player.resourcePacks().serverSent(pushOf(EVENT, SINGLE), survival);

      List<ResourcePackPush> sent = pushes(setup.sentToClient());
      assertEquals(
          List.of("https://warp.dev/event.zip"), sent.stream().map(ResourcePackPush::url).toList());
    }

    @Test
    @DisplayName("should not remove packs, which no packet before 1.20.3 can do")
    void noRemoval() {
      SwitchHarness setup = inGame(SINGLE);
      setup.player.api().sendResourcePacks(request(NETWORK));
      setup.sentToClient();

      setup.player.api().removeResourcePacks(NETWORK.id());
      setup.player.api().clearResourcePacks();

      assertEquals(List.of(), setup.sentToClient());
    }

    @Test
    @DisplayName("should keep a pack sent before the first Join Game until it")
    void beforeJoin() {
      SwitchHarness setup = new SwitchHarness(SINGLE, List.of(), ProtocolState.PLAY);
      harnesses.add(setup);

      setup.player.api().sendResourcePacks(request(NETWORK));
      List<SwitchHarness.Sent> beforeJoin = setup.sentToClient();
      setup.player.joinedGame();

      assertEquals(List.of(), beforeJoin);
      assertEquals(1, pushes(setup.sentToClient()).size());
    }

    @Test
    @DisplayName("should apply the proxy's pack again after a 1.20.2 configuration phase")
    void againAfter1202Configuration() {
      SwitchHarness setup = inGame(ProtocolVersion.MINECRAFT_1_20_2);
      setup.player.api().sendResourcePacks(request(NETWORK));
      setup.sentToClient();
      setup
          .player
          .resourcePacks()
          .clientAnswered(answer(new UUID(0, 0), ResourcePackResponse.SUCCESSFULLY_LOADED));

      MinecraftConnection survival = setup.backend(ProtocolState.CONFIGURATION);
      setup.player.startConfiguration();
      setup.player.resourcePacks().configurationStarted(survival);
      setup.player.resourcePacks().configurationFinishing();

      List<SwitchHarness.Sent> sent = setup.sentToClient();
      SwitchHarness.Sent again = sent.getLast();
      assertEquals(ProtocolState.CONFIGURATION, again.state());
      assertEquals("https://warp.dev/network.zip", again.as(ResourcePackPush.class).url());
    }

    @Test
    @DisplayName("should leave the slot to a server that sent its own pack during configuration")
    void serverPackDuringConfiguration() {
      SwitchHarness setup = inGame(ProtocolVersion.MINECRAFT_1_20_2);
      setup.player.api().sendResourcePacks(request(NETWORK));
      setup.sentToClient();
      setup
          .player
          .resourcePacks()
          .clientAnswered(answer(new UUID(0, 0), ResourcePackResponse.SUCCESSFULLY_LOADED));

      MinecraftConnection survival = setup.backend(ProtocolState.CONFIGURATION);
      setup.player.setBackendConnection(new BackendConnection(survival, SURVIVAL));
      setup.player.startConfiguration();
      setup.player.resourcePacks().configurationStarted(survival);
      setup
          .player
          .resourcePacks()
          .serverSent(pushOf(EVENT, ProtocolVersion.MINECRAFT_1_20_2), survival);
      setup.player.resourcePacks().configurationFinishing();

      List<ResourcePackPush> sent = pushes(setup.sentToClient());
      assertEquals(
          List.of("https://warp.dev/event.zip"), sent.stream().map(ResourcePackPush::url).toList());
    }

    @Test
    @DisplayName("should send a 1.7 client the URL in the MC|RPack message")
    void legacy() {
      SwitchHarness setup = inGame(ProtocolVersion.MINECRAFT_1_7_6);

      setup.player.api().sendResourcePacks(request(NETWORK));

      PlayPluginMessage message = setup.sentToClient().getFirst().as(PlayPluginMessage.class);
      assertEquals("MC|RPack", message.channel());
    }
  }

  @Test
  @DisplayName("should hear an answer that comes between two servers, while the client waits")
  void answerWhileWaitingForTheNextServer() throws InterruptedException {
    SwitchHarness setup = inGame(MODERN);
    setup.player.api().sendResourcePacks(request(NETWORK));
    setup.player.startConfiguration();
    setup.client.setSessionHandler(new SwitchWaitSessionHandler(setup.player));

    SwitchHarness.handlerOf(setup.client)
        .handle(answer(NETWORK.id(), ResourcePackResponse.DECLINED));

    assertEquals(new Status(NETWORK.id(), ResourcePackStatus.DECLINED), nextStatus());
  }

  @Test
  @DisplayName("should refuse a hash no client reads, before anything is sent")
  void refuseLongHash() {
    SwitchHarness setup = inGame(MODERN);
    ResourcePackInfo bad =
        ResourcePackInfo.resourcePackInfo(
            UUID.randomUUID(), URI.create("https://warp.dev/x.zip"), HASH + "00");

    assertThrows(
        IllegalArgumentException.class, () -> setup.player.api().sendResourcePacks(request(bad)));
    assertEquals(List.of(), setup.sentToClient());
  }

  @Test
  @DisplayName("should map every result code to its status, and none to an unknown one")
  void statuses() {
    assertEquals(ResourcePackStatus.SUCCESSFULLY_LOADED, ResourcePacks.status(0));
    assertEquals(ResourcePackStatus.DECLINED, ResourcePacks.status(1));
    assertEquals(ResourcePackStatus.FAILED_DOWNLOAD, ResourcePacks.status(2));
    assertEquals(ResourcePackStatus.ACCEPTED, ResourcePacks.status(3));
    assertEquals(ResourcePackStatus.DOWNLOADED, ResourcePacks.status(4));
    assertEquals(ResourcePackStatus.INVALID_URL, ResourcePacks.status(5));
    assertEquals(ResourcePackStatus.FAILED_RELOAD, ResourcePacks.status(6));
    assertEquals(ResourcePackStatus.DISCARDED, ResourcePacks.status(7));
    @Nullable ResourcePackStatus unknown = ResourcePacks.status(8);
    assertNull(unknown);
  }
}
