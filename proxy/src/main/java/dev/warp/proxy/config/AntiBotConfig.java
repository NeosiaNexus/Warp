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
 */
public record AntiBotConfig(
    boolean enabled,
    CidrRanges exempt,
    Verified verified,
    Attack attack,
    Blocklist blocklist,
    Duration checkTimeout) {

  /** The defaults: everything on (decision D18 of the anti-bot research). */
  public static final AntiBotConfig DEFAULTS =
      new AntiBotConfig(
          true,
          CidrRanges.none(),
          Verified.DEFAULTS,
          Attack.DEFAULTS,
          Blocklist.DEFAULTS,
          Duration.ofSeconds(2));

  /** The anti-bot off: the detector, the store and lockdown do not run. */
  public static final AntiBotConfig DISABLED =
      new AntiBotConfig(
          false,
          CidrRanges.none(),
          Verified.DEFAULTS,
          Attack.DEFAULTS,
          new Blocklist(0, Duration.ofMinutes(10)),
          Duration.ofSeconds(2));

  /** The environment variable that holds the secret, ahead of {@code secret-file}. */
  public static final String ENV_SECRET = "WARP_ANTIBOT_SECRET";

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
    try {
      boolean enabled = node.node("enabled").getBoolean(d.enabled());
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
              verifiedNode.node("persist").getBoolean(dv.persist()),
              verifiedNode.node("secret-file").getString(dv.secretFile()),
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
      return new AntiBotConfig(enabled, exempt, verified, attack, blocklist, checkTimeout);
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
        + "; exempt "
        + exempt;
  }
}
