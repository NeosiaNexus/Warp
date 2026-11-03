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

import static dev.warp.protocol.packet.play.ResourcePackResponse.ACCEPTED;
import static dev.warp.protocol.packet.play.ResourcePackResponse.DECLINED;
import static dev.warp.protocol.packet.play.ResourcePackResponse.DISCARDED;
import static dev.warp.protocol.packet.play.ResourcePackResponse.DOWNLOADED;
import static dev.warp.protocol.packet.play.ResourcePackResponse.SUCCESSFULLY_LOADED;

import dev.warp.api.event.player.PlayerResourcePackStatusEvent;
import dev.warp.protocol.ProtocolState;
import dev.warp.protocol.ProtocolVersion;
import dev.warp.protocol.packet.Packet;
import dev.warp.protocol.packet.play.ResourcePackPop;
import dev.warp.protocol.packet.play.ResourcePackPush;
import dev.warp.protocol.packet.play.ResourcePackResponse;
import dev.warp.proxy.server.BackendServer;

import java.net.URI;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import net.kyori.adventure.resource.ResourcePackCallback;
import net.kyori.adventure.resource.ResourcePackInfo;
import net.kyori.adventure.resource.ResourcePackRequest;
import net.kyori.adventure.resource.ResourcePackStatus;
import net.kyori.adventure.text.Component;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The resource packs a player's client holds, the proxy's and its servers', and the answers the
 * client gives about them.
 *
 * <p>The proxy decodes every pack a server sends and every answer the client gives (a few per
 * login), so that each answer reaches whoever sent the pack: the proxy's own fire {@link
 * PlayerResourcePackStatusEvent} and the request's callback, and never reach a server; a server's
 * go to that server, and fire the event too. Three client generations behave differently:
 *
 * <ul>
 *   <li><b>1.20.3+</b>: the client holds any number of packs, each named by a UUID it answers with,
 *       and keeps them across the configuration phase of a switch. Pushing a UUID it holds again
 *       makes it reload every pack, whatever the URL and hash: so a pack the client already loaded
 *       (same UUID, URL and hash) is never sent again. When a server sends one, the proxy answers
 *       in the client's place, accepted, downloaded then loaded, as the client would, and drops the
 *       push (BungeeCord #3235, Velocity #791). A server's packs belong to it: once the player is
 *       on another server, those the new server did not send again are removed, {@value
 *       #STALE_PACK_GRACE_MS} ms after its Join Game, which leaves a server that sends its packs
 *       once the player joined the time to send the same packs and keep them loaded. A server that
 *       removes every pack only removes its own; it cannot remove the proxy's.
 *   <li><b>1.8 to 1.20.2</b>: the client holds one server pack, which the next replaces, and
 *       answers for the last pack sent without naming it. It cannot download two packs at once
 *       (Velocity #1279): the proxy sends one pack at a time, the next once the client gave a final
 *       answer for the previous, so that every answer is for the pack in flight. A client that
 *       stays silent cannot hold the packs after it back forever: without a first answer {@value
 *       #ANSWER_DEADLINE_MS} ms after the pack reached it, or a final one {@value
 *       #LOAD_DEADLINE_MS} ms after it accepted it, the pack is reported {@code FAILED_DOWNLOAD} to
 *       whoever sent it and the next pack goes (see {@link #ANSWER_DEADLINE_MS}). A pack the client
 *       loaded last (same URL and hash) is not sent again. 1.20.2 unloads the server pack when it
 *       enters the configuration phase (Waterfall #827): the proxy sends its own pack again at the
 *       end of the next server's configuration, unless that server sent one of its own.
 *   <li><b>1.7</b>: the {@code MC|RPack} plugin message, the URL alone; no answer.
 * </ul>
 *
 * <p>The proxy never answers a pack the client has not loaded: a declined pack is sent to the
 * client again, which declines it again itself when the player disabled server packs, so that a
 * server always gets an answer (Velocity #1164, #1139).
 *
 * <p>Runs on the player's event loop, apart from {@link #send}, which builds the packets on the
 * caller's thread.
 */
final class ResourcePacks {

  private static final Logger logger = LoggerFactory.getLogger(ResourcePacks.class);

  /** How long a previous server's packs stay after the next server's Join Game. */
  static final long STALE_PACK_GRACE_MS = 10_000;

  /**
   * Before 1.20.3: how long the client has to give a first answer about the pack in flight, from
   * the moment the pack reached it. A vanilla client answers at once, accepted or declined (server
   * packs disabled), or once the player chose at the prompt. Once it accepted, it has {@link
   * #LOAD_DEADLINE_MS} to download and load the pack.
   *
   * <p>A client silent past its deadline is taken to have failed the download: the pack is reported
   * {@code FAILED_DOWNLOAD} to whoever sent it, the proxy's callback and event or the server, and
   * the next pack goes. Of the final answers of 1.8 to 1.20.2, it is the one that claims nothing
   * that did not happen: {@code DECLINED} says the player refused (a server that requires its pack
   * kicks for that), {@code SUCCESSFULLY_LOADED} that the pack is on screen, and a vanilla client
   * reports a pack it could not get, whatever the reason, as {@code FAILED_DOWNLOAD} (an invalid
   * URL too, without answering {@code ACCEPTED} first). {@code DISCARDED}, the 1.20.3 answer for a
   * pack the client dropped, does not exist before, and a server rejects an answer its version does
   * not know (Velocity #1280). The client keeps the pack it held, or not: the proxy assumes it
   * dropped it, so that the pack is sent again rather than answered for. Answers that still come
   * for that pack while no other is in flight are dropped: it was already reported.
   */
  static final long ANSWER_DEADLINE_MS = 60_000;

  /** Before 1.20.3: how long the client has to load a pack it accepted, or report it failed. */
  static final long LOAD_DEADLINE_MS = 600_000;

  /** The answers the client gives for a pack it loads, by generation. */
  private static final int[] LOADED = {ACCEPTED, DOWNLOADED, SUCCESSFULLY_LOADED};

  private static final int[] LEGACY_LOADED = {ACCEPTED, SUCCESSFULLY_LOADED};

  private final ConnectedPlayer player;
  private final ProtocolVersion version;

  /** 1.20.3+: any number of packs, by UUID. */
  private final boolean multiple;

  /** 1.7: no pack packet, no answer. */
  private final boolean legacy;

  /** 1.20.3+: every pack the client holds or processes, by UUID, in the order sent. */
  private final Map<UUID, Pack> packs = new LinkedHashMap<>();

  /** Before 1.20.3: the pack the client loaded last. */
  private @Nullable Pack current;

  /** Before 1.20.3: the pack sent whose final answer is awaited. */
  private @Nullable Pack inFlight;

  /** Before 1.20.3: packs waiting for the one in flight. */
  private final ArrayDeque<Pack> waiting = new ArrayDeque<>();

  /** Before 1.20.3: the proxy's last pack, applied again after a 1.20.2 configuration phase. */
  private @Nullable Pack proxyPack;

  /** Whether the server configuring the client sent a pack during this configuration phase. */
  private boolean serverSentDuringConfiguration;

  /** The last backend that configured the client, or took it before 1.20.2. */
  private @Nullable MinecraftConnection server;

  /** Packets kept until the client can read them: before its first Join Game, before 1.20.2. */
  private final ArrayDeque<Packet> held = new ArrayDeque<>();

  private @Nullable ScheduledFuture<?> staleRemoval;

  /**
   * Before 1.20.3: when the proxy gives up waiting for the client's answer about the pack in
   * flight.
   */
  private @Nullable ScheduledFuture<?> deadline;

  /** Before 1.20.3: the last pack given up on, whose late answers are dropped. */
  private @Nullable Pack overdue;

  ResourcePacks(ConnectedPlayer player) {
    this.player = player;
    this.version = player.protocolVersion();
    this.multiple = version.isAtLeast(ProtocolVersion.MINECRAFT_1_20_3);
    this.legacy = version.isOlderThan(ProtocolVersion.MINECRAFT_1_8);
  }

  // ---------------------------------------------------------------------------
  // The proxy's packs
  // ---------------------------------------------------------------------------

  /**
   * Sends a plugin's packs. The packets are built on the calling thread, so that a pack the client
   * could not read fails the call; they are sent from the event loop.
   *
   * @param request the packs
   * @throws IllegalArgumentException if a hash is longer than 40 characters or the prompt too large
   *     for the client
   */
  void send(ResourcePackRequest request) {
    Component prompt = request.prompt();
    Component rendered = prompt == null ? null : player.render(prompt);
    List<Pack> sent = new ArrayList<>(request.packs().size());
    for (ResourcePackInfo info : request.packs()) {
      ResourcePackPush push =
          ResourcePackPush.of(
              info.id(),
              info.uri().toString(),
              info.hash(),
              request.required(),
              rendered,
              legacy ? ProtocolVersion.MINECRAFT_1_8 : version);
      sent.add(new Pack(push, null, null, info, request.callback()));
    }
    boolean replace = request.replace();
    player.onEventLoop(() -> apply(sent, replace));
  }

  private void apply(List<Pack> sent, boolean replace) {
    if (player.isGone()) {
      return;
    }
    if (legacy) {
      if (!sent.isEmpty()) {
        write(sent.getFirst().push.legacyPluginMessage());
      }
      return;
    }
    if (!multiple) {
      applySingle(sent);
      return;
    }
    if (replace) {
      List<UUID> replaced = new ArrayList<>();
      for (Pack pack : packs.values()) {
        if (pack.proxy() && sent.stream().noneMatch(next -> loadedAs(pack, next.push))) {
          replaced.add(pack.id());
        }
      }
      remove(replaced);
    }
    for (Pack pack : sent) {
      Pack held = packs.get(pack.id());
      if (held != null && loadedAs(held, pack.push)) {
        // Already loaded: sending it again would make the client reload every pack.
        pack.loaded = true;
        pack.finished = true;
        packs.put(pack.id(), pack);
        report(pack, ResourcePackStatus.SUCCESSFULLY_LOADED);
        continue;
      }
      track(pack, held);
      write(pack.push);
    }
  }

  /** Before 1.20.3: the first pack replaces the client's; the others are discarded. */
  private void applySingle(List<Pack> sent) {
    if (sent.isEmpty()) {
      return;
    }
    Pack first = sent.getFirst();
    for (Pack other : sent.subList(1, sent.size())) {
      report(other, ResourcePackStatus.DISCARDED); // the client holds one server pack
    }
    proxyPack = first;
    Pack loaded = current;
    if (loaded != null && idle() && sameContent(loaded.push, first.push)) {
      first.loaded = true;
      first.finished = true;
      current = first;
      report(first, ResourcePackStatus.SUCCESSFULLY_LOADED);
      return;
    }
    enqueue(first);
  }

  /**
   * Removes packs from the client, from 1.20.3: the proxy's or a server's, by UUID. Nothing happens
   * before 1.20.3, which has no packet that unloads a pack.
   *
   * @param ids the packs
   */
  void remove(Collection<UUID> ids) {
    if (!multiple) {
      return;
    }
    for (UUID id : ids) {
      forget(id);
      write(new ResourcePackPop(id));
    }
  }

  /** Removes the proxy's packs from the client, from 1.20.3; never a server's. */
  void clear() {
    if (!multiple) {
      return;
    }
    remove(packs.values().stream().filter(Pack::proxy).map(Pack::id).toList());
  }

  // ---------------------------------------------------------------------------
  // A server's packs, and the client's answers
  // ---------------------------------------------------------------------------

  /**
   * Takes a pack a server sends: sent on to the client, unless the client already loaded it, in
   * which case the proxy answers for the client.
   *
   * @param push the pack
   * @param backend the server's connection
   */
  void serverSent(ResourcePackPush push, MinecraftConnection backend) {
    if (backend == server) {
      serverSentDuringConfiguration = true;
    }
    Pack pack = new Pack(push, backend, player.serverOf(backend), info(push), null);
    if (multiple) {
      Pack held = packs.get(push.uuid());
      if (held != null && loadedAs(held, push)) {
        pack.loaded = true;
        pack.finished = true;
        if (!held.proxy()) {
          packs.put(pack.id(), pack); // now the new server's, no longer stale
        }
        answerForClient(pack, backend);
        return;
      }
      track(pack, held);
      write(push);
      return;
    }
    Pack loaded = current;
    if (loaded != null && idle() && sameContent(loaded.push, push)) {
      pack.loaded = true;
      pack.finished = true;
      current = pack;
      answerForClient(pack, backend);
      return;
    }
    enqueue(pack);
  }

  /**
   * Takes a server's removal of a pack, from 1.20.3: the removal of every pack becomes that of the
   * servers' packs, and a server never removes the proxy's.
   *
   * @param pop the removal
   */
  void serverRemoved(ResourcePackPop pop) {
    UUID id = pop.uuid();
    if (id == null) {
      List<UUID> servers = packs.values().stream().filter(p -> !p.proxy()).map(Pack::id).toList();
      remove(servers);
      return;
    }
    Pack pack = packs.get(id);
    if (pack != null && pack.proxy()) {
      return;
    }
    remove(List.of(id));
  }

  /**
   * Takes the client's answer about a pack: kept for the proxy's packs, sent on to the server that
   * sent any other, or to the current server for a pack the proxy did not see sent.
   *
   * @param response the answer
   */
  void clientAnswered(ResourcePackResponse response) {
    int result = response.result();
    Pack pack;
    if (multiple) {
      pack = packs.get(response.uuid());
      Pack replaced = pack == null ? null : pack.replaced;
      if (pack != null && replaced != null && result == DISCARDED) {
        // The client discards the pack this one replaced, under the same UUID, first.
        pack.replaced = null;
        replaced.finished = true;
        deliver(replaced, response);
        return;
      }
    } else {
      pack = inFlight;
      Pack late = overdue;
      if (pack == null && late != null) {
        lateAnswer(late, result);
        return;
      }
    }
    if (pack == null) {
      forwardToServer(response, null);
      return;
    }
    deliver(pack, response);
    if (!ResourcePackResponse.isFinal(result)) {
      if (!multiple) {
        armDeadline(pack, LOAD_DEADLINE_MS); // accepted: it downloads the pack
      }
      return;
    }
    pack.finished = true;
    pack.loaded = result == SUCCESSFULLY_LOADED;
    if (multiple) {
      if (!pack.loaded || pack.removed) {
        packs.remove(pack.id(), pack);
      }
      return;
    }
    inFlight = null;
    cancelDeadline();
    if (pack.loaded) {
      current = pack;
    } else if (result != DECLINED) {
      current = null; // the client dropped its pack to download this one
    }
    sendNext();
  }

  /** Before 1.20.3: an answer for the pack given up on, while no other is in flight. */
  private void lateAnswer(Pack late, int result) {
    logger.debug("Dropped a late resource pack answer of {}", player.username());
    if (ResourcePackResponse.isFinal(result)) {
      overdue = null;
      if (result == SUCCESSFULLY_LOADED) {
        current = late; // the client holds it after all
      }
    }
  }

  /**
   * Before 1.20.3: the client said nothing in time about a pack in flight, which is reported {@code
   * FAILED_DOWNLOAD} (see {@link #ANSWER_DEADLINE_MS}) before the next pack goes.
   *
   * @param pack the pack the deadline was set for
   */
  private void deadlinePassed(Pack pack) {
    if (inFlight != pack || player.isGone()) {
      return; // answered, or dropped with its server, in the meantime
    }
    deadline = null;
    logger.debug(
        "{} gave no answer in time about resource pack {}: reported as failed",
        player.username(),
        pack.id());
    inFlight = null;
    overdue = pack;
    pack.finished = true;
    current = null; // whether the client still holds its previous pack is unknown
    deliver(
        pack,
        new ResourcePackResponse(
            pack.id(), pack.push.hash(), ResourcePackResponse.FAILED_DOWNLOAD));
    BackendConnection backend = player.backendConnection();
    if (!pack.proxy() && backend != null && backend.isActive()) {
      backend.connection().flush();
    }
    sendNext();
  }

  // ---------------------------------------------------------------------------
  // Server switches
  // ---------------------------------------------------------------------------

  /**
   * A backend starts configuring the client (1.20.2+): at login, after a switch, or for a
   * reconfiguration of its own.
   *
   * @param backend the backend
   */
  void configurationStarted(MinecraftConnection backend) {
    serverSentDuringConfiguration = false;
    flushHeld();
    if (backend != server) {
      serverChanged(backend);
    }
  }

  /**
   * The client moved to another backend: the previous servers' packs become stale (1.20.3+), and
   * their packs still waiting are dropped (before 1.20.3), the one in flight too: the server it
   * answers is gone, and a client that never answers it (a bot, a modded client) must not hold back
   * the next server's packs forever.
   *
   * @param backend the new backend
   */
  void serverChanged(MinecraftConnection backend) {
    server = backend;
    cancelStaleRemoval();
    if (multiple) {
      for (Pack pack : packs.values()) {
        if (!pack.proxy() && pack.backend != backend) {
          pack.stale = true;
        }
      }
      return;
    }
    waiting.removeIf(pack -> !pack.proxy() && pack.backend != backend);
    Pack flying = inFlight;
    if (flying != null && !flying.proxy() && flying.backend != backend) {
      inFlight = null;
      cancelDeadline();
      sendNext();
    }
  }

  /**
   * The client enters the configuration phase: 1.20.2 unloads its server pack there (Waterfall
   * #827, MC-265463).
   */
  void clientReconfiguring() {
    if (version.protocol() == ProtocolVersion.MINECRAFT_1_20_2.protocol()) {
      current = null;
    }
  }

  /**
   * A backend ends its configuration of the client: on 1.20.2 the proxy's pack, which the client
   * unloaded, is sent again, unless the server sent one of its own.
   */
  void configurationFinishing() {
    Pack mine = proxyPack;
    if (mine == null
        || multiple
        || version.protocol() != ProtocolVersion.MINECRAFT_1_20_2.protocol()
        || serverSentDuringConfiguration
        || current != null
        || inFlight == mine
        || waiting.contains(mine)) {
      return;
    }
    Pack again = new Pack(mine.push, null, null, mine.info, mine.callback);
    proxyPack = again;
    enqueue(again);
  }

  /** The client is in a game: what was kept for it goes out, and stale packs are timed out. */
  void joinedGame() {
    flushHeld();
    if (multiple && packs.values().stream().anyMatch(pack -> pack.stale)) {
      cancelStaleRemoval();
      staleRemoval =
          player
              .clientConnection()
              .channel()
              .eventLoop()
              .schedule(this::removeStale, STALE_PACK_GRACE_MS, TimeUnit.MILLISECONDS);
    }
  }

  /** Removes the packs of servers the player left that its server did not send again. */
  void removeStale() {
    staleRemoval = null;
    if (player.isGone()) {
      return;
    }
    remove(packs.values().stream().filter(pack -> pack.stale).map(Pack::id).toList());
  }

  /** The player is gone. */
  void close() {
    cancelStaleRemoval();
    cancelDeadline();
    held.clear();
  }

  // ---------------------------------------------------------------------------
  // Internals
  // ---------------------------------------------------------------------------

  /** Tracks a pack sent from 1.20.3, which replaces a pack with its UUID the client holds. */
  private void track(Pack pack, @Nullable Pack previous) {
    if (previous != null && !previous.finished) {
      pack.replaced = previous; // the client reports the previous one discarded
    }
    packs.put(pack.id(), pack);
  }

  /** Forgets a pack removed from the client; one awaiting answers stays until the final one. */
  private void forget(UUID id) {
    Pack pack = packs.get(id);
    if (pack == null) {
      return;
    }
    if (pack.finished) {
      packs.remove(id);
    } else {
      pack.removed = true;
    }
  }

  private boolean idle() {
    return inFlight == null && waiting.isEmpty();
  }

  private void enqueue(Pack pack) {
    if (inFlight == null) {
      sendNow(pack);
    } else {
      waiting.add(pack);
    }
  }

  private void sendNow(Pack pack) {
    inFlight = pack;
    overdue = null; // the client's answers are now for this pack
    write(pack.push);
    if (!held.contains(pack.push)) {
      armDeadline(pack, ANSWER_DEADLINE_MS); // a held pack's counts once it is sent (flushHeld)
    }
  }

  private void sendNext() {
    Pack next = waiting.poll();
    if (next != null) {
      sendNow(next);
    }
  }

  /** Before 1.20.3: gives the client {@code delayMs} from now to answer about {@code pack}. */
  private void armDeadline(Pack pack, long delayMs) {
    cancelDeadline();
    deadline =
        player
            .clientConnection()
            .channel()
            .eventLoop()
            .schedule(() -> deadlinePassed(pack), delayMs, TimeUnit.MILLISECONDS);
  }

  private void cancelDeadline() {
    ScheduledFuture<?> task = deadline;
    if (task != null) {
      task.cancel(false);
      deadline = null;
    }
  }

  /** Answers a pack the client already loaded in its place, as it would answer it. */
  private void answerForClient(Pack pack, MinecraftConnection backend) {
    for (int result : multiple ? LOADED : LEGACY_LOADED) {
      ResourcePackResponse response = new ResourcePackResponse(pack.id(), pack.push.hash(), result);
      deliver(pack, response);
    }
    if (backend.channel().isActive()) {
      backend.flush();
    }
  }

  /** Reports an answer: the event and callback, and the server that sent the pack. */
  private void deliver(Pack pack, ResourcePackResponse response) {
    ResourcePackStatus status = status(response.result());
    if (status != null) {
      report(pack, status);
    }
    if (!pack.proxy()) {
      forwardToServer(response, pack.backend);
    }
  }

  /**
   * Sends an answer to the server that sent the pack, if the player is still on it, or to the
   * current server for a pack the proxy did not see sent.
   */
  private void forwardToServer(ResourcePackResponse response, @Nullable MinecraftConnection to) {
    BackendConnection backend = player.backendConnection();
    if (backend == null || !backend.isActive()) {
      return;
    }
    MinecraftConnection connection = backend.connection();
    if (to == null || to == connection) {
      connection.write(response);
    } else {
      logger.debug("Dropped a resource pack answer of {} for a server it left", player.username());
    }
  }

  private void report(Pack pack, ResourcePackStatus status) {
    player.resourcePackStatus(
        new PlayerResourcePackStatusEvent(player.api(), pack.id(), status, pack.info, pack.server),
        pack.callback);
  }

  /** Writes to the client now if it reads packets, or once it is in a game. */
  private void write(Packet packet) {
    MinecraftConnection client = player.clientConnection();
    ProtocolState state = client.encoder().state();
    if (state == ProtocolState.CONFIGURATION
        || (state == ProtocolState.PLAY && player.isInGame())) {
      client.write(packet);
      client.flush();
    } else {
      held.add(packet);
    }
  }

  private void flushHeld() {
    if (held.isEmpty()) {
      return;
    }
    Pack flying = inFlight;
    boolean flyingHeld = flying != null && held.contains(flying.push);
    MinecraftConnection client = player.clientConnection();
    for (Packet packet = held.poll(); packet != null; packet = held.poll()) {
      client.write(packet);
    }
    client.flush();
    if (flying != null && flyingHeld) {
      // The pack in flight reaches the client now: its deadline counts from here.
      armDeadline(flying, ANSWER_DEADLINE_MS);
    }
  }

  private void cancelStaleRemoval() {
    ScheduledFuture<?> task = staleRemoval;
    if (task != null) {
      task.cancel(false);
      staleRemoval = null;
    }
  }

  /** Whether {@code pack} is loaded and {@code push} names it: the client would reload it. */
  private boolean loadedAs(Pack pack, ResourcePackPush push) {
    return pack.loaded && !pack.removed && pack.push.samePack(push);
  }

  /** Before 1.20.3, packs carry no UUID on the wire: the URL and hash name them. */
  private static boolean sameContent(ResourcePackPush a, ResourcePackPush b) {
    return a.url().equals(b.url()) && a.hash().equals(b.hash());
  }

  /**
   * Returns the Adventure status of a result code.
   *
   * @param result the code the client sent
   * @return the status, or {@code null} for a code no version defines
   */
  static @Nullable ResourcePackStatus status(int result) {
    return switch (result) {
      case SUCCESSFULLY_LOADED -> ResourcePackStatus.SUCCESSFULLY_LOADED;
      case DECLINED -> ResourcePackStatus.DECLINED;
      case ResourcePackResponse.FAILED_DOWNLOAD -> ResourcePackStatus.FAILED_DOWNLOAD;
      case ACCEPTED -> ResourcePackStatus.ACCEPTED;
      case DOWNLOADED -> ResourcePackStatus.DOWNLOADED;
      case ResourcePackResponse.INVALID_URL -> ResourcePackStatus.INVALID_URL;
      case ResourcePackResponse.FAILED_RELOAD -> ResourcePackStatus.FAILED_RELOAD;
      case DISCARDED -> ResourcePackStatus.DISCARDED;
      default -> null;
    };
  }

  /** A server's pack as the event shows it, or {@code null} if its URL is not a URI. */
  private static @Nullable ResourcePackInfo info(ResourcePackPush push) {
    try {
      return ResourcePackInfo.resourcePackInfo(push.uuid(), URI.create(push.url()), push.hash());
    } catch (IllegalArgumentException notUri) {
      return null;
    }
  }

  /**
   * Returns the packs the client holds from 1.20.3, or the one it loaded last before, for tests.
   *
   * @return their UUIDs
   */
  List<UUID> held() {
    if (multiple) {
      return packs.values().stream().filter(pack -> !pack.removed).map(Pack::id).toList();
    }
    Pack loaded = current;
    return loaded == null ? List.of() : List.of(loaded.id());
  }

  /** A pack the proxy or a server sent. */
  private static final class Pack {

    private final ResourcePackPush push;

    /** The server's connection that sent it, or {@code null} for the proxy's. */
    private final @Nullable MinecraftConnection backend;

    private final @Nullable BackendServer server;
    private final @Nullable ResourcePackInfo info;
    private final @Nullable ResourcePackCallback callback;

    /** Whether the client reported it loaded. */
    private boolean loaded;

    /** Whether the client gave its final answer. */
    private boolean finished;

    /** Whether it was removed before its final answer, which still comes. */
    private boolean removed;

    /** Whether it is a server's the player left, removed unless its server sends it again. */
    private boolean stale;

    /** The unfinished pack with the same UUID it replaced, whose discard comes first. */
    private @Nullable Pack replaced;

    Pack(
        ResourcePackPush push,
        @Nullable MinecraftConnection backend,
        @Nullable BackendServer server,
        @Nullable ResourcePackInfo info,
        @Nullable ResourcePackCallback callback) {
      this.push = push;
      this.backend = backend;
      this.server = server;
      this.info = info;
      this.callback = callback;
    }

    UUID id() {
      return push.uuid();
    }

    boolean proxy() {
      return backend == null;
    }
  }
}
