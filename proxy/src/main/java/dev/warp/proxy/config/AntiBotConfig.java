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
package dev.warp.proxy.config;

import dev.warp.proxy.network.CidrRanges;

import java.time.Duration;
import java.util.Locale;

import org.spongepowered.configurate.CommentedConfigurationNode;

/**
 * The {@code anti-bot} section of {@code warp.conf}: the attack detector, the store of trusted
 * identities, lockdown's exemptions, the blocklist and the deadline of plugins' checks. The limits
 * every connection goes through (rates, deadlines, frame limits, the cap on connections not logged
 * in) are in {@code limits} and apply whether the anti-bot runs or not.
 *
 * @param enabled whether the detector, the store and lockdown run
 * @param exempt addresses never refused by lockdown and never counted as unknown: a staff VPN, a
 *     Geyser host
 * @param verified the store of trusted identities
 * @param attack the attack detector
 * @param blocklist the addresses refused at accept after repeated protocol violations
 * @param checkTimeout how long the proxy waits for plugins' checks of an unknown login
 * @param verification the verification of unknown identities: the probe, its queue, the tokens
 * @param world the virtual world: the world check and the hold of players without a server
 */
public record AntiBotConfig(
    boolean enabled,
    CidrRanges exempt,
    Verified verified,
    Attack attack,
    Blocklist blocklist,
    Duration checkTimeout,
    Verification verification,
    World world) {

  /** The defaults: everything on (decision D18 of the anti-bot research). */
  public static final AntiBotConfig DEFAULTS =
      new AntiBotConfig(
          true,
          CidrRanges.none(),
          Verified.DEFAULTS,
          Attack.DEFAULTS,
          Blocklist.DEFAULTS,
          Duration.ofSeconds(2),
          Verification.DEFAULTS,
          World.DEFAULTS);

  /** The anti-bot off: the detector, the store and lockdown do not run. */
  public static final AntiBotConfig DISABLED =
      new AntiBotConfig(
          false,
          CidrRanges.none(),
          Verified.DEFAULTS,
          Attack.DEFAULTS,
          new Blocklist(0, Duration.ofMinutes(10)),
          Duration.ofSeconds(2),
          Verification.DEFAULTS,
          World.OFF);

  /** The environment variable that holds the secret, ahead of {@code secret-file}. */
  public static final String ENV_SECRET = "WARP_ANTIBOT_SECRET";

  /**
   * The environment variable that holds the secret verification tokens are signed under, ahead of
   * {@code verification.token.secret-file}: shared by the proxies of a fleet.
   */
  public static final String ENV_TOKEN_SECRET = "WARP_ANTIBOT_TOKEN_SECRET";

  /**
   * Checks the values.
   *
   * @throws IllegalArgumentException if the check timeout is not positive
   */
  public AntiBotConfig {
    if (checkTimeout.isNegative() || checkTimeout.isZero()) {
      throw new IllegalArgumentException("checkTimeout must be positive, got " + checkTimeout);
    }
  }

  /**
   * The store of trusted identities.
   *
   * @param expireAfterLastJoin how long an identity stays trusted after it last joined
   * @param capacity the most identities remembered; the least recently seen of a sample of 8 makes
   *     room for a new one
   * @param ipv4Prefix the network an offline identity's IPv4 address counts as, in bits
   * @param ipv6Prefix the network an offline identity's IPv6 address counts as, in bits
   * @param persist whether the store is kept on disk (a snapshot and a journal flushed every
   *     second) or in memory only
   * @param secretFile the file, relative to the proxy's directory, of the secret identities are
   *     hashed under; generated when absent
   * @param sharedStoreTimeout how long a login waits for a plugin's shared store to answer
   */
  public record Verified(
      Duration expireAfterLastJoin,
      int capacity,
      int ipv4Prefix,
      int ipv6Prefix,
      boolean persist,
      String secretFile,
      Duration sharedStoreTimeout) {

    /** 30 days after the last join, 250,000 identities, /24 and /48, on disk. */
    public static final Verified DEFAULTS =
        new Verified(
            Duration.ofDays(30),
            250_000,
            24,
            48,
            true,
            "anti-bot/secret.key",
            Duration.ofMillis(500));

    /**
     * Checks the values.
     *
     * @throws IllegalArgumentException if a value is out of range
     */
    public Verified {
      if (expireAfterLastJoin.compareTo(Duration.ofMinutes(1)) < 0) {
        throw new IllegalArgumentException(
            "expireAfterLastJoin must be at least 1 minute, got " + expireAfterLastJoin);
      }
      if (capacity < 1 || capacity > 50_000_000) {
        throw new IllegalArgumentException("capacity must be 1 to 50000000, got " + capacity);
      }
      if (ipv4Prefix < 8 || ipv4Prefix > 32) {
        throw new IllegalArgumentException("ipv4Prefix must be 8 to 32, got " + ipv4Prefix);
      }
      if (ipv6Prefix < 16 || ipv6Prefix > 128) {
        throw new IllegalArgumentException("ipv6Prefix must be 16 to 128, got " + ipv6Prefix);
      }
      if (secretFile.isBlank()) {
        throw new IllegalArgumentException("secretFile must not be blank");
      }
      if (sharedStoreTimeout.isNegative() || sharedStoreTimeout.isZero()) {
        throw new IllegalArgumentException(
            "sharedStoreTimeout must be positive, got " + sharedStoreTimeout);
      }
    }
  }

  /**
   * The attack detector. It counts each second the logins of unknown identities and the failed
   * authentications against a baseline learned outside attacks, and the connections not logged in
   * against their cap.
   *
   * @param unknownLoginFloor the lowest threshold of unknown logins, per second
   * @param authenticationFailureFloor the lowest threshold of failed authentications, per second
   * @param baselineFactor how many times its baseline a signal must reach, above its floor
   * @param pendingShare the share of {@code limits.max-pending-connections}, in percent, that
   *     connections not logged in must reach; 0 to ignore them
   * @param sustain how long a signal must stay over its threshold to start an attack
   * @param calmFor how long every signal must stay under half its threshold to end it
   * @param reportInterval how often an attack in progress is summed up in the log
   */
  public record Attack(
      int unknownLoginFloor,
      int authenticationFailureFloor,
      int baselineFactor,
      int pendingShare,
      Duration sustain,
      Duration calmFor,
      Duration reportInterval) {

    /** Floors of 10 and 5 per second, 4 times the baseline, half the cap, 3 s, 60 s, 10 s. */
    public static final Attack DEFAULTS =
        new Attack(
            10, 5, 4, 50, Duration.ofSeconds(3), Duration.ofSeconds(60), Duration.ofSeconds(10));

    /**
     * Checks the values.
     *
     * @throws IllegalArgumentException if a value is out of range
     */
    public Attack {
      if (unknownLoginFloor < 1 || authenticationFailureFloor < 1) {
        throw new IllegalArgumentException("floors must be at least 1");
      }
      if (baselineFactor < 1) {
        throw new IllegalArgumentException("baselineFactor must be at least 1");
      }
      if (pendingShare < 0 || pendingShare > 100) {
        throw new IllegalArgumentException("pendingShare must be 0 to 100, got " + pendingShare);
      }
      if (sustain.compareTo(Duration.ofSeconds(1)) < 0
          || calmFor.compareTo(Duration.ofSeconds(1)) < 0
          || reportInterval.compareTo(Duration.ofSeconds(1)) < 0) {
        throw new IllegalArgumentException(
            "sustain, calmFor and reportInterval must be at least 1 second");
      }
    }
  }

  /**
   * The blocklist: addresses (IPv4, or IPv6 /64) that sent what no client sends (frames over the
   * limits of their state, invalid handshakes, unexpected packets) several times in a short while.
   * Their new connections are reset at accept for a while, and the list is exported for nftables on
   * the management server.
   *
   * @param strikes how many violations put an address on the list; 0 turns the list off
   * @param duration how long an address stays listed after its last violation, and the window its
   *     violations are counted in
   */
  public record Blocklist(int strikes, Duration duration) {

    /** Three violations within 10 minutes, listed 10 minutes. */
    public static final Blocklist DEFAULTS = new Blocklist(3, Duration.ofMinutes(10));

    /**
     * Checks the values.
     *
     * @throws IllegalArgumentException if a value is out of range
     */
    public Blocklist {
      if (strikes < 0) {
        throw new IllegalArgumentException("strikes must not be negative, got " + strikes);
      }
      if (duration.compareTo(Duration.ofSeconds(1)) < 0) {
        throw new IllegalArgumentException("duration must be at least 1 second, got " + duration);
      }
    }

    /**
     * Returns whether the list runs.
     *
     * @return {@code false} when {@code strikes} is 0
     */
    public boolean enabled() {
      return strikes > 0;
    }
  }

  /**
   * When unknown identities are verified: never, during attacks only, or always.
   *
   * <p>Decision D18 of the anti-bot research: {@link #ALWAYS} in both modes. In offline mode bots
   * are free, so the first wave of an attack would get in before the detector saw it; in online
   * mode the verification runs after authentication, where it costs the player nothing visible.
   */
  public enum Mode {
    /** Every unknown identity is verified. */
    ALWAYS,
    /** Unknown identities are verified while the detector reports an attack. */
    ATTACK,
    /** Nobody is verified: the limits, the detector and lockdown still run. */
    NEVER;

    /**
     * Parses a mode as written in the configuration.
     *
     * @param value {@code always}, {@code attack} or {@code never}, in any case
     * @return the mode
     * @throws IllegalArgumentException if it is none of them
     */
    public static Mode parse(String value) {
      return switch (value.strip().toLowerCase(Locale.ROOT)) {
        case "always" -> ALWAYS;
        case "attack" -> ATTACK;
        case "never" -> NEVER;
        default ->
            throw new IllegalArgumentException(
                "expected always, attack or never, got \"" + value + "\"");
      };
    }
  }

  /**
   * The verification of unknown identities (phase 2 of the anti-bot research): the probe of the
   * login and configuration packets, how many run at once and how the others wait, the signed
   * tokens that carry a verification across the transfer of a player verified before it
   * authenticated, and where that transfer sends the client.
   *
   * @param offlineMode when unknown identities are verified in offline mode (after Login Success)
   * @param onlineMode when unknown identities are verified in online mode: after authentication in
   *     normal times, before it during an attack, so that fake authentications never reach the
   *     session server
   * @param maxConcurrent the most verifications running at once
   * @param queueSize the most logins waiting for a verification to end; beyond it, a newcomer is
   *     told the server is busy
   * @param queueMaxWait how long a login may wait in line before it is told the same
   * @param probeTimeout the base deadline of each stage of the probe; 4 times the measured round
   *     trip is added once the ping is answered
   * @param tokenLifetime how long a verification token stays valid: the time a client has to come
   *     back from its transfer
   * @param tokenRotateAfter how often the proxy replaces the key tokens are signed under, keeping
   *     the previous one for the tokens still out; zero never rotates, and a key set in {@link
   *     #ENV_TOKEN_SECRET} is never rotated by the proxy
   * @param tokenSecretFile the file, relative to the proxy's directory, of the current and previous
   *     keys; generated when absent
   * @param transferHost the host a client verified before authentication is transferred back to;
   *     empty for the one its handshake named
   * @param transferPort the port of that transfer; zero for the one its handshake named
   */
  public record Verification(
      Mode offlineMode,
      Mode onlineMode,
      int maxConcurrent,
      int queueSize,
      Duration queueMaxWait,
      Duration probeTimeout,
      Duration tokenLifetime,
      Duration tokenRotateAfter,
      String tokenSecretFile,
      String transferHost,
      int transferPort) {

    /** Always, 2,048 at once, 4,096 in line for 20 s, 5 s per stage, tokens of 10 minutes. */
    public static final Verification DEFAULTS =
        new Verification(
            Mode.ALWAYS,
            Mode.ALWAYS,
            2_048,
            4_096,
            Duration.ofSeconds(20),
            Duration.ofSeconds(5),
            Duration.ofMinutes(10),
            Duration.ofDays(7),
            "anti-bot/token.key",
            "",
            0);

    /**
     * Checks the values.
     *
     * @throws IllegalArgumentException if a value is out of range
     */
    public Verification {
      if (maxConcurrent < 1) {
        throw new IllegalArgumentException(
            "maxConcurrent must be at least 1, got " + maxConcurrent);
      }
      if (queueSize < 0) {
        throw new IllegalArgumentException("queueSize must not be negative, got " + queueSize);
      }
      if (queueMaxWait.compareTo(Duration.ofSeconds(1)) < 0) {
        throw new IllegalArgumentException(
            "queueMaxWait must be at least 1 second, got " + queueMaxWait);
      }
      if (probeTimeout.compareTo(Duration.ofSeconds(1)) < 0
          || probeTimeout.compareTo(Duration.ofSeconds(30)) > 0) {
        throw new IllegalArgumentException(
            "probeTimeout must be 1 to 30 seconds, got " + probeTimeout);
      }
      if (tokenLifetime.compareTo(Duration.ofSeconds(10)) < 0
          || tokenLifetime.compareTo(Duration.ofDays(1)) > 0) {
        throw new IllegalArgumentException(
            "tokenLifetime must be 10 seconds to 1 day, got " + tokenLifetime);
      }
      if (tokenRotateAfter.isNegative()
          || (!tokenRotateAfter.isZero() && tokenRotateAfter.compareTo(tokenLifetime) < 0)) {
        throw new IllegalArgumentException(
            "tokenRotateAfter must be zero or at least the token lifetime, got "
                + tokenRotateAfter);
      }
      if (tokenSecretFile.isBlank()) {
        throw new IllegalArgumentException("tokenSecretFile must not be blank");
      }
      if (transferPort < 0 || transferPort > 65_535) {
        throw new IllegalArgumentException("transferPort must be 0 to 65535, got " + transferPort);
      }
    }

    /**
     * Returns whether a mode verifies anything at all.
     *
     * @return {@code false} when both modes are {@link Mode#NEVER}
     */
    public boolean verifiesAnyone() {
      return offlineMode != Mode.NEVER || onlineMode != Mode.NEVER;
    }
  }

  /**
   * The virtual world (phase 3 of the anti-bot research): the world check unknown players pass
   * after the probe, and the hold of players whose server is down.
   *
   * @param check whether unknown players fall through the world after the probe; off for listeners
   *     whose backends are modded, whose clients refuse a vanilla world
   * @param hold whether players are kept in the world when no server can take them, instead of
   *     being disconnected
   * @param holdRetryEvery how often a held player's server is tried again
   * @param holdMaxWait how long a player is held before it is disconnected after all
   */
  public record World(boolean check, boolean hold, Duration holdRetryEvery, Duration holdMaxWait) {

    /** The check and the hold on, a retry every 5 s, a hold of 5 minutes at most. */
    public static final World DEFAULTS =
        new World(true, true, Duration.ofSeconds(5), Duration.ofMinutes(5));

    /** Neither the check nor the hold: players without a server are disconnected. */
    public static final World OFF =
        new World(false, false, Duration.ofSeconds(5), Duration.ofMinutes(5));

    /**
     * Checks the values.
     *
     * @throws IllegalArgumentException if a value is out of range
     */
    public World {
      if (holdRetryEvery.compareTo(Duration.ofSeconds(1)) < 0
          || holdRetryEvery.compareTo(Duration.ofMinutes(1)) > 0) {
        throw new IllegalArgumentException(
            "holdRetryEvery must be 1 second to 1 minute, got " + holdRetryEvery);
      }
      if (holdMaxWait.compareTo(Duration.ofSeconds(5)) < 0
          || holdMaxWait.compareTo(Duration.ofHours(1)) > 0) {
        throw new IllegalArgumentException(
            "holdMaxWait must be 5 seconds to 1 hour, got " + holdMaxWait);
      }
    }
  }

  // ---------------------------------------------------------------------------
  // Parsing
  // ---------------------------------------------------------------------------

  /**
   * Reads the section, each absent key taking its default.
   *
   * @param root the configuration's root node
   * @return the section's values
   * @throws ConfigurationException if a value is malformed or out of range
   */
  static AntiBotConfig parse(CommentedConfigurationNode root) throws ConfigurationException {
    AntiBotConfig d = DEFAULTS;
    CommentedConfigurationNode node = root.node("anti-bot");
    CommentedConfigurationNode verifiedNode = node.node("verified");
    CommentedConfigurationNode attackNode = node.node("attack");
    CommentedConfigurationNode blocklistNode = node.node("blocklist");
    CommentedConfigurationNode verificationNode = node.node("verification");
    CommentedConfigurationNode worldNode = node.node("world");
    try {
      boolean enabled = WarpConfig.booleanValue(node.node("enabled"), d.enabled());
      CommentedConfigurationNode exemptNode = node.node("exempt");
      CidrRanges exempt =
          exemptNode.virtual()
              ? d.exempt()
              : WarpConfig.parseCidrRanges(exemptNode, "anti-bot.exempt");

      Verified dv = d.verified();
      Verified verified =
          new Verified(
              WarpConfig.parseDuration(
                  verifiedNode.node("expire-after-last-join"),
                  "anti-bot.verified.expire-after-last-join",
                  dv.expireAfterLastJoin()),
              WarpConfig.parseCount(
                  verifiedNode.node("capacity"), "anti-bot.verified.capacity", dv.capacity()),
              WarpConfig.parseCount(
                  verifiedNode.node("prefix", "ipv4"),
                  "anti-bot.verified.prefix.ipv4",
                  dv.ipv4Prefix()),
              WarpConfig.parseCount(
                  verifiedNode.node("prefix", "ipv6"),
                  "anti-bot.verified.prefix.ipv6",
                  dv.ipv6Prefix()),
              WarpConfig.booleanValue(verifiedNode.node("persist"), dv.persist()),
              WarpConfig.stringValue(verifiedNode.node("secret-file"), dv.secretFile()),
              WarpConfig.parseDuration(
                  verifiedNode.node("shared-store-timeout"),
                  "anti-bot.verified.shared-store-timeout",
                  dv.sharedStoreTimeout()));

      Attack da = d.attack();
      Attack attack =
          new Attack(
              WarpConfig.parseCount(
                  attackNode.node("floors", "unknown-logins"),
                  "anti-bot.attack.floors.unknown-logins",
                  da.unknownLoginFloor()),
              WarpConfig.parseCount(
                  attackNode.node("floors", "authentication-failures"),
                  "anti-bot.attack.floors.authentication-failures",
                  da.authenticationFailureFloor()),
              WarpConfig.parseCount(
                  attackNode.node("baseline-factor"),
                  "anti-bot.attack.baseline-factor",
                  da.baselineFactor()),
              WarpConfig.parseCount(
                  attackNode.node("pending-share"),
                  "anti-bot.attack.pending-share",
                  da.pendingShare()),
              WarpConfig.parseDuration(
                  attackNode.node("sustain"), "anti-bot.attack.sustain", da.sustain()),
              WarpConfig.parseDuration(
                  attackNode.node("calm-for"), "anti-bot.attack.calm-for", da.calmFor()),
              WarpConfig.parseDuration(
                  attackNode.node("report-interval"),
                  "anti-bot.attack.report-interval",
                  da.reportInterval()));

      Blocklist db = d.blocklist();
      Blocklist blocklist =
          new Blocklist(
              WarpConfig.parseCount(
                  blocklistNode.node("strikes"), "anti-bot.blocklist.strikes", db.strikes()),
              WarpConfig.parseDuration(
                  blocklistNode.node("duration"), "anti-bot.blocklist.duration", db.duration()));

      Duration checkTimeout =
          WarpConfig.parseDuration(
              node.node("checks", "timeout"), "anti-bot.checks.timeout", d.checkTimeout());

      Verification dn = d.verification();
      Verification verification =
          new Verification(
              Mode.parse(
                  WarpConfig.stringValue(
                      verificationNode.node("offline-mode"), dn.offlineMode().name())),
              Mode.parse(
                  WarpConfig.stringValue(
                      verificationNode.node("online-mode"), dn.onlineMode().name())),
              WarpConfig.parseCount(
                  verificationNode.node("max-concurrent"),
                  "anti-bot.verification.max-concurrent",
                  dn.maxConcurrent()),
              WarpConfig.parseCount(
                  verificationNode.node("queue", "size"),
                  "anti-bot.verification.queue.size",
                  dn.queueSize()),
              WarpConfig.parseDuration(
                  verificationNode.node("queue", "max-wait"),
                  "anti-bot.verification.queue.max-wait",
                  dn.queueMaxWait()),
              WarpConfig.parseDuration(
                  verificationNode.node("probe-timeout"),
                  "anti-bot.verification.probe-timeout",
                  dn.probeTimeout()),
              WarpConfig.parseDuration(
                  verificationNode.node("token", "lifetime"),
                  "anti-bot.verification.token.lifetime",
                  dn.tokenLifetime()),
              WarpConfig.parseDuration(
                  verificationNode.node("token", "rotate-after"),
                  "anti-bot.verification.token.rotate-after",
                  dn.tokenRotateAfter()),
              WarpConfig.stringValue(
                  verificationNode.node("token", "secret-file"), dn.tokenSecretFile()),
              WarpConfig.stringValue(verificationNode.node("transfer", "host"), dn.transferHost()),
              WarpConfig.parseCount(
                  verificationNode.node("transfer", "port"),
                  "anti-bot.verification.transfer.port",
                  dn.transferPort()));
      World dw = d.world();
      World world =
          new World(
              WarpConfig.booleanValue(worldNode.node("check"), dw.check()),
              WarpConfig.booleanValue(worldNode.node("hold", "enabled"), dw.hold()),
              WarpConfig.parseDuration(
                  worldNode.node("hold", "retry-every"),
                  "anti-bot.world.hold.retry-every",
                  dw.holdRetryEvery()),
              WarpConfig.parseDuration(
                  worldNode.node("hold", "max-wait"),
                  "anti-bot.world.hold.max-wait",
                  dw.holdMaxWait()));
      return new AntiBotConfig(
          enabled, exempt, verified, attack, blocklist, checkTimeout, verification, world);
    } catch (IllegalArgumentException e) {
      throw new ConfigurationException("anti-bot: " + e.getMessage(), e);
    }
  }

  /** Describes the section for the startup log. */
  @Override
  public String toString() {
    if (!enabled) {
      return "off (limits still apply)";
    }
    return "on; trusted identities expire "
        + LimitsConfig.format(verified.expireAfterLastJoin())
        + " after their last join, at most "
        + verified.capacity()
        + ", /"
        + verified.ipv4Prefix()
        + " and /"
        + verified.ipv6Prefix()
        + (verified.persist() ? ", on disk" : ", in memory only")
        + "; attack over "
        + attack.unknownLoginFloor()
        + " unknown logins/s or "
        + attack.authenticationFailureFloor()
        + " failed authentications/s (or "
        + attack.baselineFactor()
        + "x baseline); blocklist "
        + (blocklist.enabled()
            ? "after "
                + blocklist.strikes()
                + " violations in "
                + LimitsConfig.format(blocklist.duration())
            : "off")
        + "; verification "
        + verification.offlineMode().name().toLowerCase(Locale.ROOT)
        + " offline, "
        + verification.onlineMode().name().toLowerCase(Locale.ROOT)
        + " online, "
        + verification.maxConcurrent()
        + " at once; world check "
        + (world.check() ? "on" : "off")
        + ", hold "
        + (world.hold() ? "up to " + LimitsConfig.format(world.holdMaxWait()) : "off")
        + "; exempt "
        + exempt;
  }
}
