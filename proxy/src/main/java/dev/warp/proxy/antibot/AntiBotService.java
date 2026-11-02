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
package dev.warp.proxy.antibot;

import dev.warp.api.antibot.AntiBot;
import dev.warp.api.antibot.AttackLevel;
import dev.warp.api.antibot.AttackReport;
import dev.warp.api.antibot.Identity;
import dev.warp.api.antibot.LoginAttempt;
import dev.warp.api.antibot.Signal;
import dev.warp.api.antibot.Verdict;
import dev.warp.api.antibot.Verification;
import dev.warp.api.antibot.VerificationCheck;
import dev.warp.api.antibot.VerificationMethod;
import dev.warp.api.antibot.VerifiedIdentities;
import dev.warp.api.antibot.VerifiedKey;
import dev.warp.api.antibot.VerifiedStore;
import dev.warp.api.event.Subscription;
import dev.warp.api.event.antibot.AttackEndedEvent;
import dev.warp.api.event.antibot.AttackStartedEvent;
import dev.warp.api.event.antibot.LockdownChangedEvent;
import dev.warp.proxy.config.AntiBotConfig;
import dev.warp.proxy.event.WarpEventBus;
import dev.warp.proxy.limit.Blocklist;
import dev.warp.proxy.limit.ClientLimits;
import dev.warp.proxy.limit.Refusal;
import dev.warp.proxy.network.CidrRanges;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;
import java.util.function.UnaryOperator;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The built-in anti-bot (phase 1 of the anti-bot research): the attack detector, the store of
 * trusted identities, lockdown, plugins' checks and shared stores, and what the login flow asks of
 * them. It implements the {@link AntiBot} plugins see.
 *
 * <p>What it does per login, all on the login's event loop and without blocking:
 *
 * <ol>
 *   <li>{@link #recognize}: computes the name and network key and looks it up (a plugin's shared
 *       store is asked, with a deadline, only on a miss). An unknown identity counts towards the
 *       detector's unknown logins.
 *   <li>{@link #check}: plugins' checks, for unknown identities only, with a deadline.
 *   <li>During a lockdown, an unknown identity is refused: at its Login Start in offline mode,
 *       after authentication in online mode (an account trusted from another network still gets
 *       in).
 *   <li>{@link #joined}: a player that logged in refreshes what is known of it; an unknown one is
 *       learned, unless the proxy is under attack, in lockdown, or a check escalated it.
 * </ol>
 *
 * <p>Logging follows decision D11: one WARN line when an attack starts, one INFO summary per
 * interval while it lasts, one INFO line when it ends; nothing per connection.
 */
public final class AntiBotService implements AntiBot, AutoCloseable {

  private static final Logger logger = LoggerFactory.getLogger(AntiBotService.class);

  /** The directory of the store's files, under the proxy's. */
  static final String DIRECTORY = "anti-bot";

  private final AntiBotConfig config;
  private final boolean onlineMode;
  private final ClientLimits limits;
  private final WarpEventBus events;
  private final LongSupplier millisClock;
  private final IdentityKeys keys;
  private final @Nullable IdentityStore store;
  private final AttackDetector detector;
  private final List<VerificationCheck> checks = new CopyOnWriteArrayList<>();
  private volatile boolean lockdown;

  /** Refusals at the start of the attack and at its last summary, for the summaries' deltas. */
  private EnumMap<Refusal, Long> refusalsAtStart = new EnumMap<>(Refusal.class);

  private EnumMap<Refusal, Long> refusalsAtSummary = new EnumMap<>(Refusal.class);

  /**
   * What the anti-bot knows of a login, from its name and address.
   *
   * @param name the name the client claims
   * @param key the key of its name and network
   * @param trusted whether that key is trusted
   * @param exempt whether its address is in {@code anti-bot.exempt}
   * @param address the client's address
   */
  public record Recognition(
      String name, VerifiedKey key, boolean trusted, boolean exempt, InetAddress address) {

    /**
     * Returns whether the login is let through as known: trusted, or exempt.
     *
     * @return {@code true} if known
     */
    public boolean known() {
      return trusted || exempt;
    }
  }

  // ---------------------------------------------------------------------------
  // Lifecycle
  // ---------------------------------------------------------------------------

  /**
   * Creates the anti-bot of a proxy, loading its store.
   *
   * @param config the {@code anti-bot} section
   * @param onlineMode whether the proxy authenticates players
   * @param limits the proxy's limits, whose refusals and connections the detector counts
   * @param events the event bus
   * @param directory the proxy's working directory
   * @param env reads an environment variable, {@code null} when unset
   * @param millisClock the wall clock
   * @throws IOException if the store's secret or files cannot be read or written
   */
  public AntiBotService(
      AntiBotConfig config,
      boolean onlineMode,
      ClientLimits limits,
      WarpEventBus events,
      Path directory,
      UnaryOperator<@Nullable String> env,
      LongSupplier millisClock)
      throws IOException {
    this.config = config;
    this.onlineMode = onlineMode;
    this.limits = limits;
    this.events = events;
    this.millisClock = millisClock;
    AntiBotConfig.Verified verified = config.verified();
    this.detector =
        new AttackDetector(
            config.attack(),
            limits::pendingConnections,
            limits.maxPendingConnections(),
            verified.ipv4Prefix(),
            verified.ipv6Prefix(),
            millisClock,
            new AttackLog());
    if (!config.enabled()) {
      this.keys = new IdentityKeys(randomSecret(), verified.ipv4Prefix(), verified.ipv6Prefix());
      this.store = null;
      return;
    }
    byte[] secret = secret(verified, directory, env);
    this.keys = new IdentityKeys(secret, verified.ipv4Prefix(), verified.ipv6Prefix());
    this.store =
        new IdentityStore(
            verified,
            keys,
            verified.persist() ? directory.resolve(DIRECTORY) : null,
            millisClock,
            event -> {
              var _ = events.fire(event);
            },
            IdentityStore.newThread());
    limits.attackMode(() -> detector.level() == AttackLevel.ATTACK);
    limits.listen(
        new ClientLimits.RefusalListener() {
          @Override
          public void refused(Refusal reason, @Nullable InetAddress address) {
            detector.record(Signal.REFUSALS, address);
            if (reason == Refusal.AUTHENTICATION_FAILED) {
              detector.record(Signal.AUTHENTICATION_FAILURES, address);
            }
          }

          @Override
          public void accepted() {
            detector.record(Signal.CONNECTIONS);
          }
        });
  }

  /**
   * Returns an anti-bot that does not run, for proxies and tests that turn it off: the limits still
   * apply, no file is touched.
   *
   * @param limits the proxy's limits
   * @param events the event bus
   * @return the anti-bot
   */
  public static AntiBotService disabled(ClientLimits limits, WarpEventBus events) {
    try {
      return new AntiBotService(
          AntiBotConfig.DISABLED,
          false,
          limits,
          events,
          Path.of("."),
          _ -> null,
          System::currentTimeMillis);
    } catch (IOException e) {
      throw new AssertionError("a disabled anti-bot touches no file", e);
    }
  }

  /** Closes the current second of the detector; called once a second. */
  public void tick() {
    try {
      detector.tick();
    } catch (RuntimeException e) {
      logger.error("Anti-bot: the attack detector failed", e);
    }
  }

  /** Saves the store and stops its thread. */
  @Override
  public void close() {
    IdentityStore open = store;
    if (open != null) {
      open.close();
    }
  }

  // ---------------------------------------------------------------------------
  // Login flow
  // ---------------------------------------------------------------------------

  /**
   * Recognizes a login from its name and address in this proxy's store: lock-free, at once.
   *
   * @param name the name the client claims
   * @param address its address
   * @return what the anti-bot knows of it
   */
  public Recognition recognize(String name, InetSocketAddress address) {
    InetAddress ip = address.getAddress();
    VerifiedKey key = keys.nameAt(name, ip);
    IdentityStore open = store;
    boolean trusted = open != null && open.knows(key);
    return new Recognition(name, key, trusted, isExempt(ip), ip);
  }

  /**
   * Recognizes a login as {@link #recognize} does, then asks the plugin's shared store, if any, on
   * a miss, waiting at most its deadline; and counts an unknown one towards the detector.
   *
   * @param name the name the client claims
   * @param address its address
   * @return completed with what the anti-bot knows of it; never exceptionally
   */
  public CompletableFuture<Recognition> recognizeEverywhere(
      String name, InetSocketAddress address) {
    Recognition local = recognize(name, address);
    IdentityStore open = store;
    if (local.known() || open == null) {
      return CompletableFuture.completedFuture(counted(local));
    }
    return open.findEverywhere(local.key())
        .thenApply(
            found ->
                counted(
                    found.isPresent()
                        ? new Recognition(
                            local.name(), local.key(), true, local.exempt(), local.address())
                        : local));
  }

  private Recognition counted(Recognition recognition) {
    if (!recognition.known() && config.enabled()) {
      detector.record(Signal.UNKNOWN_LOGINS, recognition.address());
    }
    return recognition;
  }

  /**
   * Returns whether lockdown refuses a login from what is known before authentication: an unknown
   * name and network. In online mode the login may still be let in by its account ({@link
   * #lockdownRefuses(Recognition, UUID)}).
   *
   * @param recognition what is known of the login
   * @return {@code true} to refuse it
   */
  public boolean lockdownRefuses(Recognition recognition) {
    return lockdown && !recognition.known();
  }

  /**
   * Returns whether lockdown refuses a login once authenticated: neither its name and network nor
   * its account is trusted.
   *
   * @param recognition what was known before authentication
   * @param account the authenticated account
   * @return {@code true} to refuse it
   */
  public boolean lockdownRefuses(Recognition recognition, UUID account) {
    IdentityStore open = store;
    return lockdownRefuses(recognition) && (open == null || !open.knows(keys.account(account)));
  }

  /**
   * Returns whether plugins registered checks.
   *
   * @return {@code true} if {@link #check} has anything to run
   */
  public boolean hasChecks() {
    return !checks.isEmpty();
  }

  /**
   * Runs plugins' checks of an unknown login, all at once, each bounded by {@code
   * anti-bot.checks.timeout}: a late or failed one abstains.
   *
   * @param attempt the login
   * @return completed with the combined verdict (a failure wins, then an escalation); never
   *     exceptionally
   */
  public CompletableFuture<Verdict> check(LoginAttempt attempt) {
    List<CompletableFuture<Verdict>> verdicts = new ArrayList<>();
    long timeout = config.checkTimeout().toMillis();
    for (VerificationCheck check : checks) {
      CompletableFuture<Verdict> verdict;
      try {
        verdict = check.check(attempt);
      } catch (RuntimeException e) {
        logger.warn("Anti-bot: a plugin's check failed for {}", attempt.name(), e);
        continue;
      }
      verdicts.add(
          verdict
              .completeOnTimeout(new Verdict.Abstain(), timeout, TimeUnit.MILLISECONDS)
              .exceptionally(failure -> new Verdict.Abstain()));
    }
    return CompletableFuture.allOf(verdicts.toArray(CompletableFuture[]::new))
        .thenApply(
            _ -> {
              Verdict combined = new Verdict.Abstain();
              for (CompletableFuture<Verdict> verdict : verdicts) {
                Verdict one = verdict.join();
                if (one instanceof Verdict.Fail) {
                  return one;
                }
                if (one instanceof Verdict.Escalate
                    || (one instanceof Verdict.Pass && combined instanceof Verdict.Abstain)) {
                  combined = one;
                }
              }
              return combined;
            });
  }

  /**
   * Records that a player logged in: what is known of it is refreshed, and an unknown identity is
   * learned ({@link VerificationMethod#JOINED}) unless the proxy is under attack, in lockdown, or a
   * check escalated the login. In online mode both the account and the name and network are stored,
   * so that the player is recognized before authentication next time.
   *
   * @param recognition what was known before authentication
   * @param account the account in online mode, or {@code null} in offline mode
   * @param escalated whether a plugin's check escalated the login
   */
  public void joined(Recognition recognition, @Nullable UUID account, boolean escalated) {
    IdentityStore open = store;
    if (open == null) {
      return;
    }
    VerifiedKey accountKey = account == null ? null : keys.account(account);
    boolean accountTrusted = accountKey != null && open.knows(accountKey);
    boolean known = recognition.trusted() || accountTrusted;
    if (!known && !learns(escalated)) {
      return;
    }
    VerificationMethod method = VerificationMethod.JOINED;
    if (accountKey != null && account != null) {
      if (accountTrusted) {
        open.seen(accountKey);
      } else {
        var _ = open.trust(accountKey, method, new Identity.Account(account));
      }
    }
    if (recognition.trusted()) {
      open.seen(recognition.key());
    } else {
      // Offline, the name and network is the identity, announced by the event; online, it is how
      // the account is recognized before it authenticates, and the event names the account.
      Identity announced =
          account == null
              ? new Identity.NameAtAddress(recognition.name(), recognition.address())
              : null;
      var _ = open.trust(recognition.key(), method, announced);
    }
  }

  private boolean learns(boolean escalated) {
    return !escalated && !lockdown && detector.level() == AttackLevel.NORMAL;
  }

  private boolean isExempt(InetAddress address) {
    CidrRanges exempt = config.exempt();
    return !exempt.isEmpty() && exempt.contains(address);
  }

  // ---------------------------------------------------------------------------
  // Commands and management
  // ---------------------------------------------------------------------------

  @Override
  public boolean lockdown(boolean enabled) {
    return lockdown(enabled, "plugin");
  }

  /**
   * Turns lockdown on or off.
   *
   * @param enabled {@code true} to turn it on
   * @param source who does, for the log and the event
   * @return {@code true} if it changed
   * @throws IllegalStateException if the anti-bot does not run
   */
  public boolean lockdown(boolean enabled, String source) {
    if (!config.enabled()) {
      throw new IllegalStateException("the anti-bot is off (anti-bot.enabled = false)");
    }
    if (lockdown == enabled) {
      return false;
    }
    lockdown = enabled;
    if (enabled) {
      logger.warn("Lockdown on ({}): only players who have played here before may log in", source);
    } else {
      logger.info("Lockdown off ({})", source);
    }
    var _ = events.fire(new LockdownChangedEvent(enabled, source));
    return true;
  }

  /**
   * Returns the key of an identity, for staff commands.
   *
   * @param identity the identity
   * @return its key
   */
  public VerifiedKey key(Identity identity) {
    return keys.key(identity);
  }

  /**
   * Trusts an identity on staff's word.
   *
   * @param identity the identity
   * @return completed once stored
   * @throws IllegalStateException if the anti-bot does not run
   */
  public CompletableFuture<Verification> trustByCommand(Identity identity) {
    return requireStore().trust(keys.key(identity), VerificationMethod.COMMAND, identity);
  }

  /**
   * Returns a snapshot of where the anti-bot stands.
   *
   * @return the status
   */
  public AntiBotStatus status() {
    IdentityStore open = store;
    Blocklist blocklist = limits.blocklist();
    EnumMap<Refusal, Long> refusals = new EnumMap<>(Refusal.class);
    for (Refusal reason : Refusal.values()) {
      refusals.put(reason, limits.refusals(reason));
    }
    var lane = limits.lane();
    return new AntiBotStatus(
        config.enabled(),
        detector.level(),
        lockdown,
        detector.currentAttack().orElse(null),
        detector.latest(),
        limits.pendingConnections(),
        limits.maxPendingConnections(),
        lane.inFlight(),
        lane.max(),
        lane.waiting(),
        lane.grantedFromLine(),
        lane.waitedSeconds(),
        open == null ? 0 : open.size(),
        open == null ? 0 : open.capacity(),
        open == null ? 0 : open.droppedChanges(),
        blocklist == null ? -1 : blocklist.size(),
        refusals,
        checks.size(),
        open != null && open.isShared(),
        Instant.ofEpochMilli(millisClock.getAsLong()));
  }

  /**
   * Returns the blocklisted networks.
   *
   * @return CIDR lines, empty when the blocklist is off
   */
  public List<String> blocklist() {
    Blocklist blocklist = limits.blocklist();
    return blocklist == null ? List.of() : blocklist.cidrs();
  }

  /**
   * Returns whether the proxy authenticates players.
   *
   * @return {@code true} in online mode
   */
  public boolean onlineMode() {
    return onlineMode;
  }

  /**
   * Returns the network an address counts as, for messages.
   *
   * @param address the address
   * @return its CIDR
   */
  public String network(InetAddress address) {
    return keys.network(address);
  }

  private IdentityStore requireStore() {
    IdentityStore open = store;
    if (open == null) {
      throw new IllegalStateException("the anti-bot is off (anti-bot.enabled = false)");
    }
    return open;
  }

  // ---------------------------------------------------------------------------
  // AntiBot, for plugins
  // ---------------------------------------------------------------------------

  @Override
  public boolean isEnabled() {
    return config.enabled();
  }

  @Override
  public AttackLevel level() {
    return detector.level();
  }

  @Override
  public Optional<AttackReport> currentAttack() {
    return detector.currentAttack();
  }

  @Override
  public boolean isLockdown() {
    return lockdown;
  }

  @Override
  public VerifiedIdentities verified() {
    IdentityStore open = store;
    return open != null ? open : new DisabledIdentities();
  }

  @Override
  public Subscription shareVerifications(VerifiedStore shared) {
    return requireStore().share(shared);
  }

  @Override
  public Subscription addCheck(VerificationCheck check) {
    requireStore();
    checks.add(check);
    return new Subscription() {
      @Override
      public boolean isActive() {
        return checks.contains(check);
      }

      @Override
      public void unsubscribe() {
        checks.remove(check);
      }
    };
  }

  /** The store of a proxy whose anti-bot is off: trusts nobody, refuses changes. */
  private static final class DisabledIdentities implements VerifiedIdentities {

    @Override
    public Optional<Verification> check(Identity identity) {
      return Optional.empty();
    }

    @Override
    public CompletableFuture<Verification> add(Identity identity) {
      return CompletableFuture.failedFuture(
          new IllegalStateException("the anti-bot is off (anti-bot.enabled = false)"));
    }

    @Override
    public CompletableFuture<Boolean> remove(Identity identity) {
      return CompletableFuture.failedFuture(
          new IllegalStateException("the anti-bot is off (anti-bot.enabled = false)"));
    }

    @Override
    public int size() {
      return 0;
    }

    @Override
    public int capacity() {
      return 0;
    }
  }

  // ---------------------------------------------------------------------------
  // Attack log
  // ---------------------------------------------------------------------------

  /** Logs attacks in three kinds of lines, and fires their events. */
  private final class AttackLog implements AttackDetector.Listener {

    @Override
    public void started(AttackReport attack) {
      refusalsAtStart = refusalTotals();
      refusalsAtSummary = refusalsAtStart;
      logger.warn(
          "Bot attack detected: {} at {}{} (threshold {}). Unknown identities are no longer learned"
              + " from joins; one summary every {}. To let only known players in: /warp antibot"
              + " lockdown on",
          describe(attack.trigger()),
          attack.triggerRate(),
          attack.trigger() == Signal.PENDING_CONNECTIONS ? "" : "/s",
          attack.threshold(),
          format(config.attack().reportInterval()));
      var _ = events.fire(new AttackStartedEvent(attack));
    }

    @Override
    public void ongoing(AttackReport attack, AttackDetector.Snapshot now) {
      EnumMap<Refusal, Long> totals = refusalTotals();
      String refused = refusedSince(refusalsAtSummary, totals);
      refusalsAtSummary = totals;
      logger.info(
          "Attack in progress for {}: {} unknown logins/s (peak {}), {} failed authentications/s"
              + " (peak {}), {} connections/s, {} not logged in; refused since the last summary:"
              + " {}; top sources: {}",
          format(attack.duration(now(attack))),
          now.lastSecond().get(Signal.UNKNOWN_LOGINS),
          attack.peaks().get(Signal.UNKNOWN_LOGINS),
          now.lastSecond().get(Signal.AUTHENTICATION_FAILURES),
          attack.peaks().get(Signal.AUTHENTICATION_FAILURES),
          now.lastSecond().get(Signal.CONNECTIONS),
          now.lastSecond().get(Signal.PENDING_CONNECTIONS),
          refused,
          sources(attack));
    }

    @Override
    public void ended(AttackReport attack) {
      String refused = refusedSince(refusalsAtStart, refusalTotals());
      logger.info(
          "Attack over after {}: {} unknown logins (peak {}/s), {} failed authentications (peak"
              + " {}/s), {} connections (peak {}/s), at most {} not logged in; refused: {}; top"
              + " sources: {}",
          format(attack.duration(now(attack))),
          attack.totals().get(Signal.UNKNOWN_LOGINS),
          attack.peaks().get(Signal.UNKNOWN_LOGINS),
          attack.totals().get(Signal.AUTHENTICATION_FAILURES),
          attack.peaks().get(Signal.AUTHENTICATION_FAILURES),
          attack.totals().get(Signal.CONNECTIONS),
          attack.peaks().get(Signal.CONNECTIONS),
          attack.peaks().get(Signal.PENDING_CONNECTIONS),
          refused,
          sources(attack));
      var _ = events.fire(new AttackEndedEvent(attack));
    }

    private Instant now(AttackReport attack) {
      Instant ended = attack.endedAt();
      return ended != null ? ended : Instant.ofEpochMilli(millisClock.getAsLong());
    }
  }

  private EnumMap<Refusal, Long> refusalTotals() {
    EnumMap<Refusal, Long> totals = new EnumMap<>(Refusal.class);
    for (Refusal reason : Refusal.values()) {
      totals.put(reason, limits.refusals(reason));
    }
    return totals;
  }

  private static String refusedSince(Map<Refusal, Long> before, Map<Refusal, Long> after) {
    List<String> parts = new ArrayList<>();
    long all = 0;
    for (Refusal reason : Refusal.values()) {
      long delta = after.getOrDefault(reason, 0L) - before.getOrDefault(reason, 0L);
      if (delta > 0) {
        all += delta;
        parts.add(reason.description() + ": " + delta);
      }
    }
    return all == 0 ? "none" : all + " (" + String.join(", ", parts) + ")";
  }

  private static String sources(AttackReport attack) {
    if (attack.topSources().isEmpty()) {
      return "none";
    }
    List<String> parts = new ArrayList<>();
    for (AttackReport.Source source : attack.topSources()) {
      parts.add(source.network() + " (" + source.events() + ")");
    }
    return String.join(", ", parts);
  }

  private static String describe(Signal signal) {
    return switch (signal) {
      case UNKNOWN_LOGINS -> "logins of unknown identities";
      case AUTHENTICATION_FAILURES -> "failed authentications";
      case PENDING_CONNECTIONS -> "connections not logged in";
      case CONNECTIONS -> "connections";
      case REFUSALS -> "refusals";
    };
  }

  /** Formats a duration as {@code 1h 2m 3s}, leaving out the zero parts. */
  static String format(Duration duration) {
    long seconds = Math.max(0, duration.toSeconds());
    long hours = seconds / 3600;
    long minutes = seconds % 3600 / 60;
    long rest = seconds % 60;
    StringBuilder text = new StringBuilder();
    if (hours > 0) {
      text.append(hours).append("h ");
    }
    if (minutes > 0) {
      text.append(minutes).append("m ");
    }
    if (rest > 0 || text.isEmpty()) {
      text.append(rest).append('s');
    }
    return text.toString().strip();
  }

  // ---------------------------------------------------------------------------
  // Secret
  // ---------------------------------------------------------------------------

  /**
   * Returns the secret identities are hashed under: from {@link AntiBotConfig#ENV_SECRET} (any
   * text, hashed to 32 bytes); else, for a store kept on disk, from the secret file (Base64),
   * generated with 32 random bytes, readable by its owner only, when absent; else a random one, a
   * store in memory having nothing to find again after a restart.
   */
  static byte[] secret(
      AntiBotConfig.Verified verified, Path directory, UnaryOperator<@Nullable String> env)
      throws IOException {
    String fromEnv = env.apply(AntiBotConfig.ENV_SECRET);
    if (fromEnv != null && !fromEnv.isBlank()) {
      return sha256(fromEnv.strip());
    }
    if (!verified.persist()) {
      return randomSecret();
    }
    return loadSecret(directory.resolve(verified.secretFile()));
  }

  /** Reads the secret file, generating it when absent. */
  private static byte[] loadSecret(Path file) throws IOException {
    if (Files.exists(file)) {
      String text = Files.readString(file, StandardCharsets.US_ASCII).strip();
      try {
        byte[] secret = Base64.getDecoder().decode(text);
        if (secret.length >= 16) {
          return secret;
        }
      } catch (IllegalArgumentException e) {
        // reported below
      }
      throw new IOException(
          "The anti-bot secret "
              + file
              + " is not a Base64 secret of 16 bytes or more: delete it to generate a new one, or"
              + " set "
              + AntiBotConfig.ENV_SECRET);
    }
    byte[] secret = randomSecret();
    Path parent = file.toAbsolutePath().getParent();
    if (parent != null) {
      Files.createDirectories(parent);
    }
    Files.writeString(
        file,
        Base64.getEncoder().encodeToString(secret) + "\n",
        StandardCharsets.US_ASCII,
        StandardOpenOption.CREATE_NEW,
        StandardOpenOption.WRITE);
    try {
      Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"));
    } catch (UnsupportedOperationException e) {
      // Not a POSIX file system: the platform's defaults apply.
    }
    logger.info("Anti-bot: generated the secret identities are hashed under in {}", file);
    return secret;
  }

  private static byte[] randomSecret() {
    byte[] secret = new byte[IdentityKeys.SECRET_LENGTH];
    new SecureRandom().nextBytes(secret);
    return secret;
  }

  private static byte[] sha256(String text) {
    try {
      return MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
    } catch (NoSuchAlgorithmException e) {
      throw new AssertionError("SHA-256 is a mandatory JCA algorithm", e);
    }
  }

  @Override
  public String toString() {
    return "AntiBot["
        + (config.enabled() ? detector.level().name().toLowerCase(Locale.ROOT) : "off")
        + "]";
  }
}
