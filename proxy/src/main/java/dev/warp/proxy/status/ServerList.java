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
package dev.warp.proxy.status;

import dev.warp.api.server.Favicon;
import dev.warp.api.server.ServerPing;
import dev.warp.protocol.ProtocolVersion;
import dev.warp.protocol.legacy.LegacyDisconnect;
import dev.warp.protocol.legacy.LegacyPing;
import dev.warp.proxy.connection.ConnectedPlayer;
import dev.warp.proxy.player.PlayerRegistry;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReferenceArray;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The proxy's server list entry: the one it proposes to each ping, built from the {@code status}
 * settings and the players online, and the answers it writes, in the form each client reads.
 *
 * <p>Answering costs little. The description is written once per text format, at startup; the
 * sample of players is drawn at most every {@value #SAMPLE_REFRESH_SECONDS} seconds, as vanilla
 * does, or when a server smaller than the sample changes; and the proxy's own answer is kept per
 * protocol until the player count or the sample changes, so that a ping (a server list refresh, a
 * monitoring probe, a flood of them) usually builds nothing. Entries plugins return are written
 * afresh, never cached: an answer is never staler than its handlers made it.
 *
 * <p>Thread-safe: pings arrive on every event loop. The caches are immutable snapshots replaced
 * whole; two threads racing to refresh one only do the work twice.
 */
public final class ServerList {

  private static final Logger logger = LoggerFactory.getLogger(ServerList.class);

  /** How long a sample of players is shown before another is drawn, as vanilla does. */
  static final long SAMPLE_REFRESH_SECONDS = 5;

  private static final long SAMPLE_REFRESH_NANOS = TimeUnit.SECONDS.toNanos(SAMPLE_REFRESH_SECONDS);

  /** The longest name a player can have, for the size check of the configured entry. */
  private static final int LONGEST_NAME = 16;

  private static final Sample NO_SAMPLE = new Sample(Long.MAX_VALUE, 0, List.of());

  private final StatusSettings settings;
  private final PlayerRegistry players;

  /** The configured description, written in each text format. */
  private final String[] motdJson;

  /** The configured description as legacy text, for legacy pings. */
  private final String motdLegacy;

  /**
   * The last answer to the proxy's own entry, per protocol a client can be advertised: indexed by
   * protocol number for supported versions, with one more slot, last, for unsupported ones.
   */
  private final AtomicReferenceArray<@Nullable Memo> answers;

  private final int unsupportedSlot;

  private volatile @Nullable Sample sample;

  /** Whether an entry too long for clients was reported at WARN already; later ones go to DEBUG. */
  private final AtomicBoolean reportedOversized = new AtomicBoolean();

  /**
   * Creates the server list entry.
   *
   * @param settings the {@code status} settings
   * @param players the connected players, counted and sampled
   */
  public ServerList(StatusSettings settings, PlayerRegistry players) {
    this.settings = settings;
    this.players = players;
    this.motdJson = new String[DescriptionJson.formats()];
    for (int format = 0; format < motdJson.length; format++) {
      motdJson[format] = DescriptionJson.write(settings.motd(), format);
    }
    this.motdLegacy = LegacyComponentSerializer.legacySection().serialize(settings.motd());
    int maxProtocol =
        ProtocolVersion.values().stream().mapToInt(ProtocolVersion::protocol).max().orElseThrow();
    this.unsupportedSlot = maxProtocol + 1;
    this.answers = new AtomicReferenceArray<>(unsupportedSlot + 1);
  }

  /**
   * Returns the settings the entry is built from.
   *
   * @return the settings
   */
  public StatusSettings settings() {
    return settings;
  }

  // ---------------------------------------------------------------------------
  // Proposals
  // ---------------------------------------------------------------------------

  /**
   * Returns the entry the proxy proposes to a client.
   *
   * @param clientProtocol the protocol of the client's handshake, supported or not
   * @return the entry: the configured one, with the live counts and the protocol to advertise
   */
  public ServerPing proposal(int clientProtocol) {
    return build(advertisedProtocol(clientProtocol), players.count(), currentSample());
  }

  /**
   * Returns the entry the proxy proposes to a legacy ping: it advertises {@link
   * LegacyPing#MODERN_SERVER_PROTOCOL}, as vanilla does, so that legacy clients list the proxy as
   * incompatible and show its version name.
   *
   * @return the entry
   */
  public ServerPing legacyProposal() {
    return build(LegacyPing.MODERN_SERVER_PROTOCOL, players.count(), currentSample());
  }

  /**
   * Returns the protocol advertised to a client: its own when the proxy supports it, so that the
   * client lists the proxy as compatible, and the latest supported one otherwise, as Velocity and
   * BungeeCord do.
   *
   * @param clientProtocol the protocol of the client's handshake
   * @return the protocol to advertise
   */
  static int advertisedProtocol(int clientProtocol) {
    return ProtocolVersion.byProtocolId(clientProtocol) != null
        ? clientProtocol
        : ProtocolVersion.latest().protocol();
  }

  /** Builds the configured entry, advertising {@code protocol}, with these counts. */
  private ServerPing build(int protocol, int online, Sample sample) {
    ServerPing.Players counts = null;
    if (settings.showPlayerCount()) {
      counts = new ServerPing.Players(online, settings.shownMax(online), sample.players());
    }
    return new ServerPing(
        new ServerPing.Version(settings.versionName(), protocol),
        counts,
        settings.motd(),
        settings.favicon());
  }

  // ---------------------------------------------------------------------------
  // Answers
  // ---------------------------------------------------------------------------

  /**
   * Returns the answer to a client when no plugin changes the entry: the proposal's JSON, reused
   * until the player count or the sample changes.
   *
   * @param clientProtocol the protocol of the client's handshake, supported or not
   * @return the JSON status response
   */
  @SuppressWarnings("ReferenceEquality") // the same sample, not an equal one: no list comparison
  public String json(int clientProtocol) {
    int format = DescriptionJson.format(clientProtocol);
    int slot =
        ProtocolVersion.byProtocolId(clientProtocol) != null ? clientProtocol : unsupportedSlot;
    Sample current = currentSample();
    int online = settings.showPlayerCount() ? players.count() : 0;
    Memo memo = answers.get(slot);
    if (memo != null
        && memo.format() == format
        && memo.online() == online
        && memo.sample() == current) {
      return memo.json();
    }
    String json = json(build(advertisedProtocol(clientProtocol), online, current), clientProtocol);
    answers.set(slot, new Memo(format, online, current, json));
    return json;
  }

  /**
   * Returns the answer to a client with an entry a plugin may have changed.
   *
   * @param ping the entry
   * @param clientProtocol the protocol of the client's handshake, supported or not
   * @return the JSON status response, at most {@value StatusJson#MAX_LENGTH} characters: an entry
   *     longer than a client reads is answered without its icon, then without its sample
   */
  @SuppressWarnings("ReferenceEquality") // the configured description itself: written already
  public String json(ServerPing ping, int clientProtocol) {
    int format = DescriptionJson.format(clientProtocol);
    String description =
        ping.description() == settings.motd()
            ? motdJson[format]
            : DescriptionJson.write(ping.description(), format);
    StatusJson.Fitted fitted = StatusJson.fit(ping, description);
    String dropped = fitted.dropped();
    if (dropped != null) {
      reportOversized(dropped);
    }
    return fitted.json();
  }

  /**
   * Returns the answer to a legacy ping.
   *
   * @param ping the entry
   * @param format the form of the ping
   * @return the legacy kick packet carrying the entry: the first line of its description, its
   *     counts (0 and 0 when hidden, as Velocity answers) and its version
   */
  @SuppressWarnings("ReferenceEquality") // the configured description itself: written already
  public LegacyDisconnect legacyAnswer(ServerPing ping, LegacyPing.Format format) {
    String description =
        ping.description() == settings.motd()
            ? motdLegacy
            : LegacyComponentSerializer.legacySection().serialize(ping.description());
    ServerPing.Players counts = ping.players();
    return LegacyDisconnect.ofStatus(
        format,
        ping.version().protocol(),
        ping.version().name(),
        description,
        counts == null ? 0 : counts.online(),
        counts == null ? 0 : counts.max());
  }

  private void reportOversized(String dropped) {
    String message =
        "A plugin's server list entry is longer than the "
            + StatusJson.MAX_LENGTH
            + " characters a client reads: answered without {}";
    if (reportedOversized.compareAndSet(false, true)) {
      logger.warn(message + " (reported once; later ones are logged at DEBUG)", dropped);
    } else {
      logger.debug(message, dropped);
    }
  }

  // ---------------------------------------------------------------------------
  // Sample
  // ---------------------------------------------------------------------------

  /**
   * Returns the players listed when hovering the counts: up to {@link StatusSettings#sampleSize()}
   * of them at random, those who turned server listings off shown as {@link
   * ServerPing.SamplePlayer#ANONYMOUS}. Drawn again once {@value #SAMPLE_REFRESH_SECONDS} seconds
   * old, or as soon as the count changes while it lists every player.
   */
  private Sample currentSample() {
    int size = settings.sampleSize();
    if (size == 0 || !settings.showPlayerCount()) {
      return NO_SAMPLE;
    }
    Sample current = this.sample;
    long now = System.nanoTime();
    if (current != null && !current.isStale(now, players.count(), size)) {
      return current;
    }
    List<ServerPing.SamplePlayer> drawn = new ArrayList<>(size);
    int online = players.count();
    for (ConnectedPlayer player : players.randomSample(size, ThreadLocalRandom.current())) {
      drawn.add(
          player.allowsServerListing()
              ? new ServerPing.SamplePlayer(player.username(), player.uuid())
              : ServerPing.SamplePlayer.ANONYMOUS);
    }
    Sample fresh = new Sample(now + SAMPLE_REFRESH_NANOS, online, List.copyOf(drawn));
    this.sample = fresh;
    return fresh;
  }

  /**
   * A sample of players.
   *
   * @param expiresAt when another is drawn, in {@link System#nanoTime()} time
   * @param online the player count when it was drawn
   * @param players the players listed
   */
  private record Sample(long expiresAt, int online, List<ServerPing.SamplePlayer> players) {

    /** Whether another must be drawn: too old, or listing everyone while the count changed. */
    boolean isStale(long now, int count, int size) {
      return now - expiresAt >= 0 || (players.size() < size && count != online);
    }
  }

  /**
   * An answer kept for reuse.
   *
   * @param format the text format it was written in
   * @param online the player count it shows
   * @param sample the sample it lists, compared by identity
   * @param json the answer
   */
  private record Memo(int format, int online, Sample sample, String json) {}

  // ---------------------------------------------------------------------------
  // Startup check
  // ---------------------------------------------------------------------------

  /**
   * Checks that the configured entry always fits in what a client reads: with the longest counts, a
   * full sample of the longest names, and the description in its longest format.
   *
   * @param settings the settings
   * @return the length of the longest answer
   * @throws IllegalArgumentException if it does not fit; the message says how much each part takes
   */
  public static int checkFits(StatusSettings settings) {
    List<ServerPing.SamplePlayer> sample = new ArrayList<>(settings.sampleSize());
    for (int i = 0; i < settings.sampleSize(); i++) {
      sample.add(new ServerPing.SamplePlayer("x".repeat(LONGEST_NAME), new UUID(0, 0)));
    }
    ServerPing longest =
        new ServerPing(
            new ServerPing.Version(settings.versionName(), Integer.MAX_VALUE),
            settings.showPlayerCount()
                ? new ServerPing.Players(Integer.MAX_VALUE, Integer.MAX_VALUE, sample)
                : null,
            settings.motd(),
            settings.favicon());
    int longestDescription = 0;
    int longestAnswer = 0;
    for (int format = 0; format < DescriptionJson.formats(); format++) {
      String description = DescriptionJson.write(settings.motd(), format);
      longestDescription = Math.max(longestDescription, description.length());
      longestAnswer = Math.max(longestAnswer, StatusJson.write(longest, description).length());
    }
    if (longestAnswer > StatusJson.MAX_LENGTH) {
      Favicon icon = settings.favicon();
      int favicon = icon == null ? 0 : icon.uri().length();
      throw new IllegalArgumentException(
          "the server list answer would take up to "
              + longestAnswer
              + " characters, more than the "
              + StatusJson.MAX_LENGTH
              + " a client reads (it would list the proxy as incompatible): the favicon takes "
              + favicon
              + ", the motd "
              + longestDescription
              + " and a full sample of "
              + settings.sampleSize()
              + " players about "
              + settings.sampleSize() * (LONGEST_NAME + 50)
              + ". Shrink the favicon (a 64x64 PNG rarely needs more than 10 KB), shorten the motd"
              + " or lower sample-size");
    }
    return longestAnswer;
  }

  /**
   * Returns the description written in a client's format, for tests.
   *
   * @param description the description
   * @param clientProtocol the client's protocol
   * @return the JSON text
   */
  static String descriptionJson(Component description, int clientProtocol) {
    return DescriptionJson.write(description, DescriptionJson.format(clientProtocol));
  }
}
