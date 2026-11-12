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
import dev.warp.api.antibot.VerificationFailure;
import dev.warp.api.antibot.VerificationMethod;
import dev.warp.api.antibot.VerifiedIdentities;
import dev.warp.api.antibot.VerifiedKey;
import dev.warp.api.antibot.VerifiedStore;
import dev.warp.api.event.Subscription;
import dev.warp.api.event.antibot.AttackEndedEvent;
import dev.warp.api.event.antibot.AttackStartedEvent;
import dev.warp.api.event.antibot.LockdownChangedEvent;
import dev.warp.api.event.antibot.VerificationFailedEvent;
import dev.warp.api.player.ClientPlatform;
import dev.warp.protocol.ProtocolVersion;
import dev.warp.proxy.config.AntiBotConfig;
import dev.warp.proxy.config.LoggingConfig;
import dev.warp.proxy.config.SecretFiles;
import dev.warp.proxy.event.WarpEventBus;
import dev.warp.proxy.limit.Blocklist;
import dev.warp.proxy.limit.ClientLimits;
import dev.warp.proxy.limit.Refusal;
import dev.warp.proxy.logging.LogCode;
import dev.warp.proxy.network.CidrRanges;
import dev.warp.proxy.world.ProxyWorld;
import dev.warp.proxy.world.WorldLayouts;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
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
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.LongSupplier;
import java.util.function.UnaryOperator;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The built-in anti-bot (phases 1 and 2 of the anti-bot research): the attack detector, the store
 * of trusted identities, lockdown, plugins' checks and shared stores, the verification of unknown
 * identities (the {@linkplain Probe probe}, its {@linkplain VerificationQueue fair queue} and the
 * {@linkplain VerificationTokens tokens} of verifications before authentication), and what the
 * login flow asks of them. It implements the {@link AntiBot} plugins see.
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
 *   <li>{@link #plan}: whether, and when, an unknown identity is verified (section 9.6 of the
 *       research): after Login Success in offline mode, after authentication in online mode, and
 *       before it during an attack, when a client that passes is transferred back with a {@link
 *       #issueToken token} or asked to reconnect. The {@link #queue} bounds how many verify at
 *       once; {@link #newProbe} starts one; {@link #verificationPassed} and {@link
 *       #verificationFailed} take its outcome.
 *   <li>{@link #joined}: a player that logged in refreshes what is known of it; an unknown one is
 *       learned, unless the proxy is under attack, in lockdown, or a check escalated it. A player
 *       that passed a verification is trusted whatever the attack.
 * </ol>
 *
 * <p>Logging follows decision D11: one WARN line when an attack starts, one INFO summary per
 * interval while it lasts, one INFO line when it ends; nothing per connection.
 */
public final class AntiBotService implements AntiBot, AutoCloseable {

  private static final Logger logger = LoggerFactory.getLogger(AntiBotService.class);

  /** Generates the secret identities are hashed under, when none is configured. */
  private static final SecureRandom RANDOM = new SecureRandom();

  /** The directory of the store's files, under the proxy's. */
  static final String DIRECTORY = "anti-bot";

  private final AntiBotConfig config;
  private final boolean onlineMode;
  private final ClientLimits limits;
  private final WarpEventBus events;
  private final LoggingConfig logging;
  private final LongSupplier millisClock;
  private final IdentityKeys keys;
  private final @Nullable IdentityStore store;
  private final AttackDetector detector;
  private final List<VerificationCheck> checks = new CopyOnWriteArrayList<>();
  private final LongSupplier nanoClock;
  private final VerificationQueue queue;
  private final ProxyWorld world;
  private final @Nullable VerificationTokens tokens;
  private volatile boolean lockdown;

  /** When a token was last issued: transfers back are expected for a token's lifetime after. */
  private final LongAdder verificationsPassed = new LongAdder();

  private final LongAdder verificationsEscalated = new LongAdder();
  private final EnumMap<VerificationFailure, LongAdder> verificationsFailed =
      new EnumMap<>(VerificationFailure.class);

  /** Refusals at the start of the attack and at its last summary, for the summaries' deltas. */
  private EnumMap<Refusal, Long> refusalsAtStart = new EnumMap<>(Refusal.class);

  private EnumMap<Refusal, Long> refusalsAtSummary = new EnumMap<>(Refusal.class);

  /** The routine lines not written during attacks, by kind. */
  private final EnumMap<RoutineLine, LongAdder> unlogged = new EnumMap<>(RoutineLine.class);

  /** Routine lines not written at the start of the attack and at its last summary. */
  private EnumMap<RoutineLine, Long> unloggedAtStart = new EnumMap<>(RoutineLine.class);

  private EnumMap<RoutineLine, Long> unloggedAtSummary = new EnumMap<>(RoutineLine.class);

  /**
   * A kind of line the {@code logging} section writes one of per event, which an attack would turn
   * into a flood of lines.
   */
  public enum RoutineLine {
    /** A server list ping ({@code logging.pings}). */
    PING("server list pings"),
    /** A player logging in, moving between servers or leaving ({@code logging.connections}). */
    CONNECTION("player connection events");

    private final String description;

    RoutineLine(String description) {
      this.description = description;
    }

    /**
     * Returns how the summaries name these lines.
     *
     * @return e.g. {@code server list pings}
     */
    public String description() {
      return description;
    }
  }

  /**
   * Whether, and when, a login is verified (section 9.6 of the research).
   *
   * <p>In offline mode, where identities are free, an unknown identity is verified after Login
   * Success. In online mode the account is the identity: an unknown one is verified after it
   * authenticated, which costs the player nothing visible; during an attack, when fake
   * authentications are the weapon, it is verified before, and never admitted from that path: the
   * client is transferred back with a token (1.20.5+) or asked to reconnect, and its next login
   * authenticates as a known identity.
   */
  public enum Plan {
    /** Known, exempt, or not verified in this mode: straight through. */
    NONE,
    /** Verified after Login Success (offline) or after authentication (online, calm). */
    AFTER_LOGIN,
    /** Verified before authentication (online, under attack), then transferred or reconnected. */
    BEFORE_AUTHENTICATION
  }

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
   * @param logging the {@code logging} section, whose routine lines attacks hold back
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
      LoggingConfig logging,
      Path directory,
      UnaryOperator<@Nullable String> env,
      LongSupplier millisClock)
      throws IOException {
    this(
        config, onlineMode, limits, events, logging, directory, env, millisClock, System::nanoTime);
  }

  /**
   * Creates the anti-bot of a proxy on a monotonic clock of the caller's, for tests of deadlines.
   *
   * @param config the {@code anti-bot} section
   * @param onlineMode whether the proxy authenticates players
   * @param limits the proxy's limits, whose refusals and connections the detector counts
   * @param events the event bus
   * @param logging the {@code logging} section, whose routine lines attacks hold back
   * @param directory the proxy's working directory
   * @param env reads an environment variable, {@code null} when unset
   * @param millisClock the wall clock
   * @param nanoClock the monotonic clock of the probes' deadlines and the queue's waits
   * @throws IOException if the store's secret or files cannot be read or written
   */
  public AntiBotService(
      AntiBotConfig config,
      boolean onlineMode,
      ClientLimits limits,
      WarpEventBus events,
      LoggingConfig logging,
      Path directory,
      UnaryOperator<@Nullable String> env,
      LongSupplier millisClock,
      LongSupplier nanoClock)
      throws IOException {
    this.config = config;
    this.nanoClock = nanoClock;
    for (VerificationFailure reason : VerificationFailure.values()) {
      verificationsFailed.put(reason, new LongAdder());
    }
    AntiBotConfig.Verification verification = config.verification();
    this.queue =
        new VerificationQueue(
            verification.maxConcurrent(),
            verification.queueSize(),
            verification.queueMaxWait(),
            nanoClock);
    this.world = new ProxyWorld(config.world(), new WorldLayouts(), nanoClock);
    this.onlineMode = onlineMode;
    this.limits = limits;
    this.events = events;
    this.logging = logging;
    this.millisClock = millisClock;
    for (RoutineLine line : RoutineLine.values()) {
      unlogged.put(line, new LongAdder());
    }
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
      this.tokens = null;
      return;
    }
    byte[] secret = secret(verified, directory, env);
    this.keys = new IdentityKeys(secret, verified.ipv4Prefix(), verified.ipv6Prefix());
    this.tokens =
        new VerificationTokens(
            verification.tokenLifetime(),
            verification.tokenRotateAfter(),
            verified.persist() ? directory.resolve(verification.tokenSecretFile()) : null,
            env.apply(AntiBotConfig.ENV_TOKEN_SECRET),
            millisClock);
    if (tokens.generated()) {
      logger.info(
          "Anti-bot: generated the key that signs verification tokens, in {}",
          directory.resolve(verification.tokenSecretFile()));
    }
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
          LoggingConfig.DEFAULTS,
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
      VerificationTokens signing = tokens;
      if (signing != null) {
        signing.tick();
      }
    } catch (RuntimeException e) {
      logger.error(
          LogCode.ANTIBOT_DETECTOR_FAILED.marker(), "Anti-bot: the attack detector failed", e);
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
        logger.warn(
            LogCode.ANTIBOT_PLUGIN_CHECK_FAILED.marker(),
            "Anti-bot: a plugin's check failed for {}",
            attempt.name(),
            e);
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
    joined(recognition, account, escalated, null);
  }

  /**
   * Records that a player logged in, as {@link #joined(Recognition, UUID, boolean)} does, after a
   * verification: an identity that passed one is trusted under its method whatever the attack
   * level, and a token-verified account is stored so that it never pays the transfer hop again.
   *
   * @param recognition what was known before authentication
   * @param account the account in online mode, or {@code null} in offline mode
   * @param escalated whether a plugin's check escalated the login
   * @param verified how the login was verified, or {@code null} if it was not
   */
  public void joined(
      Recognition recognition,
      @Nullable UUID account,
      boolean escalated,
      @Nullable VerificationMethod verified) {
    IdentityStore open = store;
    if (open == null) {
      return;
    }
    VerifiedKey accountKey = account == null ? null : keys.account(account);
    boolean accountTrusted = accountKey != null && open.knows(accountKey);
    boolean known = recognition.trusted() || accountTrusted;
    if (!known && verified == null && !learns(escalated)) {
      return;
    }
    VerificationMethod method = verified != null ? verified : VerificationMethod.JOINED;
    if (accountKey != null) {
      if (accountTrusted) {
        open.seen(accountKey);
      } else {
        // An account key exists only for an account.
        var _ =
            open.trust(accountKey, method, new Identity.Account(Objects.requireNonNull(account)));
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
  // Verification
  // ---------------------------------------------------------------------------

  /**
   * Decides whether, and when, a login is verified.
   *
   * @param recognition what is known of the login
   * @param onlineMode whether this login authenticates
   * @param version the client's version: 1.7 clients are never probed (their keep-alive answer is
   *     not reliable before Join Game, as Sonar found), and no version is verified before
   *     authentication without a way back (every version has one: transfer or reconnect)
   * @return the plan. The client's platform does not enter it: a verified Bedrock player
   *     (Floodgate's data decrypted with the key) is planned like any other login, Geyser answers
   *     the probe as a client does, and the budgets and limits apply to the player's own address.
   *     What its proof exempts is the world check alone, see {@link #skipsWorldCheck}; a
   *     self-declared platform (a Forge marker) exempts nothing
   */
  public Plan plan(Recognition recognition, boolean onlineMode, ProtocolVersion version) {
    if (!config.enabled()
        || recognition.known()
        || version.isOlderThan(ProtocolVersion.MINECRAFT_1_8)) {
      return Plan.NONE;
    }
    AntiBotConfig.Verification verification = config.verification();
    AntiBotConfig.Mode mode = onlineMode ? verification.onlineMode() : verification.offlineMode();
    boolean attack = detector.level() == AttackLevel.ATTACK;
    if (mode == AntiBotConfig.Mode.NEVER || (mode == AntiBotConfig.Mode.ATTACK && !attack)) {
      return Plan.NONE;
    }
    return onlineMode && attack ? Plan.BEFORE_AUTHENTICATION : Plan.AFTER_LOGIN;
  }

  /**
   * Returns whether a verification may skip its world check, the physics stage: Geyser translates
   * Bedrock movement, which is not Java physics, so a verified Bedrock player (and only one with
   * proof, which the {@link ClientPlatform.Bedrock} variant is by construction) would fail it for
   * nothing. Every other stage still applies to it. The world check is a later phase of the
   * anti-bot; this is the input it takes from the platform.
   *
   * @param platform the client's platform
   * @return {@code true} for a verified Bedrock player
   */
  public boolean skipsWorldCheck(ClientPlatform platform) {
    return platform instanceof ClientPlatform.Bedrock;
  }

  /**
   * Returns whether an account that just authenticated, planned for verification after
   * authentication, still needs it: not if the account is trusted from another network.
   *
   * @param account the authenticated account
   * @return {@code true} to verify it
   */
  public boolean verifiesAccount(UUID account) {
    IdentityStore open = store;
    return open == null || !open.knows(keys.account(account));
  }

  /**
   * Returns whether a login that arrived by a transfer must present a verification token: when it
   * would otherwise be verified before authentication (the token is what spares it), and when the
   * proxy accepts no transfers but its own (the token is what proves the transfer is one of the
   * proxy's). A client older than 1.20.5 has no cookies and no transfers.
   *
   * @param recognition what is known of the login
   * @param onlineMode whether this login authenticates
   * @param version the client's version
   * @param acceptsTransfers the proxy's {@code accepts-transfers}
   * @return {@code true} to ask the client for its {@value VerificationTokens#COOKIE_KEY} cookie
   */
  public boolean checksToken(
      Recognition recognition,
      boolean onlineMode,
      ProtocolVersion version,
      boolean acceptsTransfers) {
    if (tokens == null || !version.isAtLeast(ProtocolVersion.MINECRAFT_1_20_5)) {
      return false;
    }
    return !acceptsTransfers
        || plan(recognition, onlineMode, version) == Plan.BEFORE_AUTHENTICATION;
  }

  /**
   * Checks the token a transferred client returned.
   *
   * @param token the cookie's payload
   * @param recognition the login that returned it
   * @return what the token says, or {@code null} if it is not a valid token for this login
   */
  public VerificationTokens.@Nullable Claim acceptToken(byte[] token, Recognition recognition) {
    VerificationTokens signing = tokens;
    return signing == null ? null : signing.verify(token, recognition.key());
  }

  /**
   * Issues the token of a login verified before authentication, to store in its client before the
   * transfer back.
   *
   * @param recognition the login verified
   * @param trusted whether the verification trusts it
   * @return the token
   * @throws IllegalStateException if the anti-bot does not run
   */
  public byte[] issueToken(Recognition recognition, boolean trusted) {
    VerificationTokens signing = tokens;
    if (signing == null) {
      throw new IllegalStateException("the anti-bot is off (anti-bot.enabled = false)");
    }
    return signing.issue(recognition.key(), trusted);
  }

  /**
   * Returns whether transfers back from a verification may arrive: whenever tokens are issued,
   * since the proxy that verified the client and the one its transfer lands on need not be the same
   * (the fleet shares the signing key, and a load balancer the address). The handshake handler
   * accepts a transfer intent then even when {@code accepts-transfers} is off; the login then has
   * to present a valid token, or it is refused as any other transfer.
   *
   * @return {@code true} while verified clients may be coming back, here or from another proxy
   */
  public boolean acceptsVerificationTransfers() {
    return tokens != null;
  }

  /**
   * Returns where a client verified before authentication is transferred back to: the configured
   * host and port, or the ones its handshake named.
   *
   * @param handshakeHost the host of the client's handshake, or {@code null}
   * @param handshakePort the port of the client's handshake
   * @return the host and port, or {@code null} if neither the configuration nor the handshake gives
   *     a usable host
   */
  public @Nullable InetSocketAddress transferTarget(
      @Nullable String handshakeHost, int handshakePort) {
    AntiBotConfig.Verification verification = config.verification();
    String host =
        verification.transferHost().isEmpty() ? handshakeHost : verification.transferHost();
    int port = verification.transferPort() == 0 ? handshakePort : verification.transferPort();
    if (host == null || host.isEmpty() || port <= 0 || port > 65_535) {
      return null;
    }
    return InetSocketAddress.createUnresolved(host, port);
  }

  /**
   * Returns the virtual world: the world check, the hold of players without a server, and the
   * plugin API over both.
   *
   * @return the world
   */
  public ProxyWorld world() {
    return world;
  }

  /**
   * Returns whether a client that passed the probe goes on to the world check: the check is on, the
   * version is at least 1.8 (1.7 is not probed either) and the client is neither modded nor Bedrock
   * (rules 6 and 7 of section 10 of the research).
   *
   * @param version the client's version
   * @param platform the client's platform
   * @return {@code true} to run the world check
   */
  public boolean worldChecks(ProtocolVersion version, ClientPlatform platform) {
    return config.enabled() && world.checks(version, platform);
  }

  /**
   * Returns the base deadline of each stage of the probe and of the world check.
   *
   * @return the configured probe timeout
   */
  public Duration stageTimeout() {
    return config.verification().probeTimeout();
  }

  /**
   * Returns the admission of logins to verification.
   *
   * @return the queue
   */
  public VerificationQueue queue() {
    return queue;
  }

  /**
   * Returns the monotonic clock the probes' deadlines run on: the one their session handlers must
   * schedule with.
   *
   * @return the clock, in nanoseconds
   */
  public LongSupplier nanoClock() {
    return nanoClock;
  }

  /**
   * Starts a probe of a client.
   *
   * @param kind what to ask
   * @param version the client's version
   * @param platform the client's platform: a verified Bedrock player gets a longer deadline
   * @return the probe, with its ids drawn
   */
  public Probe newProbe(Probe.Kind kind, ProtocolVersion version, ClientPlatform platform) {
    Duration timeout = config.verification().probeTimeout();
    if (platform instanceof ClientPlatform.Bedrock) {
      // Geyser answers for the Bedrock client, after its own translation and the Bedrock
      // client's round trip: Sonar gives Bedrock players three times the deadline (45 s for 15).
      timeout = timeout.multipliedBy(BEDROCK_DEADLINE_FACTOR);
    }
    return new Probe(kind, version, timeout, ThreadLocalRandom.current(), nanoClock);
  }

  /** How many times the probe's deadline a Bedrock player gets, after Sonar's 45 s for 15 s. */
  static final int BEDROCK_DEADLINE_FACTOR = 3;

  /**
   * Takes a passed verification: counted, and the identity trusted under {@link
   * VerificationMethod#PROBE} unless the pass raised two flags or more, in which case the player
   * gets in but is probed again next time. In online mode the account is trusted too, and the name
   * and network with it, so that the player is recognized before authentication next time.
   *
   * @param recognition the login verified
   * @param account the account, if it authenticated before the verification
   * @param passed the outcome
   */
  public void verificationPassed(
      Recognition recognition, @Nullable UUID account, Probe.Outcome.Passed passed) {
    verificationPassed(recognition, account, passed.trusted());
  }

  /**
   * Counts a verification that passed, and trusts the identity when the pass was clean.
   *
   * @param recognition what the login was recognized as
   * @param account the authenticated account, in online mode
   * @param trusted whether the pass had fewer than two flags across its probe and world check
   */
  public void verificationPassed(Recognition recognition, @Nullable UUID account, boolean trusted) {
    detector.record(Signal.VERIFICATIONS_PASSED);
    verificationsPassed.increment();
    if (!trusted) {
      verificationsEscalated.increment();
      return;
    }
    IdentityStore open = store;
    if (open == null) {
      return;
    }
    if (account != null) {
      var _ =
          open.trust(
              keys.account(account), VerificationMethod.PROBE, new Identity.Account(account));
    }
    if (!recognition.trusted()) {
      Identity announced =
          account == null
              ? new Identity.NameAtAddress(recognition.name(), recognition.address())
              : null;
      var _ = open.trust(recognition.key(), VerificationMethod.PROBE, announced);
    }
  }

  /**
   * Takes a failed verification: counted by reason, the detector told (a wave of failures is an
   * attack signal), and plugins told through {@link VerificationFailedEvent}.
   *
   * @param attempt the login that failed
   * @param reason why
   */
  public void verificationFailed(LoginAttempt attempt, VerificationFailure reason) {
    detector.record(Signal.VERIFICATIONS_FAILED, attempt.address().getAddress());
    LongAdder counter = verificationsFailed.get(reason);
    if (counter == null) {
      throw new AssertionError("every reason has a counter: " + reason);
    }
    counter.increment();
    var _ = events.fire(new VerificationFailedEvent(attempt, reason));
  }

  /**
   * Rotates the key verification tokens are signed under, on staff's word.
   *
   * @param source who asks
   * @throws IOException if the key file cannot be written
   * @throws IllegalStateException if the anti-bot does not run, or the key is the environment's
   */
  public void rotateTokenKey(String source) throws IOException {
    VerificationTokens signing = tokens;
    if (signing == null) {
      throw new IllegalStateException("the anti-bot is off (anti-bot.enabled = false)");
    }
    signing.rotate(source);
  }

  /**
   * Returns the hash of a login's network, for the queue's fairness.
   *
   * @param address the login's address
   * @return the hash
   */
  public int networkHash(InetAddress address) {
    return keys.networkHash(address);
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
      logger.warn(
          LogCode.ANTIBOT_LOCKDOWN.marker(),
          "Lockdown on ({}): only players who have played here before may log in",
          source);
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
    EnumMap<VerificationFailure, Long> failed = new EnumMap<>(VerificationFailure.class);
    for (VerificationFailure reason : VerificationFailure.values()) {
      LongAdder counter = verificationsFailed.get(reason);
      failed.put(reason, counter == null ? 0L : counter.sum());
    }
    VerificationTokens signing = tokens;
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
        new AntiBotStatus.Verifications(
            queue.inFlight(),
            queue.max(),
            queue.waiting(),
            queue.capacity(),
            queue.grantedFromLine(),
            queue.expired() + queue.refused(),
            queue.waitedSeconds(),
            verificationsPassed.sum(),
            verificationsEscalated.sum(),
            failed,
            signing == null ? 0 : signing.issued(),
            signing == null ? 0 : signing.accepted(),
            signing == null ? 0 : signing.rejected(),
            signing == null ? null : signing.keySince()),
        world.status(),
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

  /**
   * Decides whether a routine line that its {@code logging} switch lets through is written: always
   * out of an attack; during one never, so that a flood of pings or logins never becomes a flood of
   * log lines. The lines held back are counted in the attack's summaries instead. Thread-safe.
   *
   * @param line the kind of line, which the caller's switch already allowed
   * @return whether to write it
   */
  public boolean logs(RoutineLine line) {
    if (detector.level() != AttackLevel.ATTACK) {
      return true;
    }
    unloggedCounter(line).increment();
    return false;
  }

  /**
   * Returns how many routine lines of a kind attacks held back since the proxy started.
   *
   * @param line the kind of line
   * @return the count
   */
  public long unlogged(RoutineLine line) {
    return unloggedCounter(line).sum();
  }

  private LongAdder unloggedCounter(RoutineLine line) {
    LongAdder counter = unlogged.get(line);
    if (counter == null) {
      throw new AssertionError("every kind of line has a counter: " + line);
    }
    return counter;
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
      unloggedAtStart = unloggedTotals();
      unloggedAtSummary = unloggedAtStart;
      logger.warn(
          LogCode.ANTIBOT_ATTACK.marker(),
          "Bot attack detected: {} at {}{} (threshold {}). Unknown identities are no longer learned"
              + " from joins; one summary every {}. To let only known players in: /warp antibot"
              + " lockdown on",
          describe(attack.trigger()),
          attack.triggerRate(),
          attack.trigger() == Signal.PENDING_CONNECTIONS ? "" : "/s",
          attack.threshold(),
          format(config.attack().reportInterval()));
      String heldBack = heldBack();
      if (!heldBack.isEmpty()) {
        logger.info(
            "Until the attack is over, {} are not logged one by one: the summaries count them",
            heldBack);
      }
      var _ = events.fire(new AttackStartedEvent(attack));
    }

    @Override
    public void ongoing(AttackReport attack, AttackDetector.Snapshot now) {
      EnumMap<Refusal, Long> totals = refusalTotals();
      String refused = refusedSince(refusalsAtSummary, totals);
      refusalsAtSummary = totals;
      EnumMap<RoutineLine, Long> unloggedNow = unloggedTotals();
      String notLogged = unloggedSince(unloggedAtSummary, unloggedNow);
      unloggedAtSummary = unloggedNow;
      logger.info(
          "Attack in progress for {}: {} unknown logins/s (peak {}), {} failed authentications/s"
              + " (peak {}), {} connections/s, {} not logged in; refused since the last summary:"
              + " {}; top sources: {}{}",
          format(attack.duration(now(attack))),
          now.lastSecond().get(Signal.UNKNOWN_LOGINS),
          attack.peaks().get(Signal.UNKNOWN_LOGINS),
          now.lastSecond().get(Signal.AUTHENTICATION_FAILURES),
          attack.peaks().get(Signal.AUTHENTICATION_FAILURES),
          now.lastSecond().get(Signal.CONNECTIONS),
          now.lastSecond().get(Signal.PENDING_CONNECTIONS),
          refused,
          sources(attack),
          notLogged);
    }

    @Override
    public void ended(AttackReport attack) {
      String refused = refusedSince(refusalsAtStart, refusalTotals());
      String notLogged = unloggedSince(unloggedAtStart, unloggedTotals());
      logger.info(
          "Attack over after {}: {} unknown logins (peak {}/s), {} failed authentications (peak"
              + " {}/s), {} connections (peak {}/s), at most {} not logged in; refused: {}; top"
              + " sources: {}{}",
          format(attack.duration(now(attack))),
          attack.totals().get(Signal.UNKNOWN_LOGINS),
          attack.peaks().get(Signal.UNKNOWN_LOGINS),
          attack.totals().get(Signal.AUTHENTICATION_FAILURES),
          attack.peaks().get(Signal.AUTHENTICATION_FAILURES),
          attack.totals().get(Signal.CONNECTIONS),
          attack.peaks().get(Signal.CONNECTIONS),
          attack.peaks().get(Signal.PENDING_CONNECTIONS),
          refused,
          sources(attack),
          notLogged);
      var _ = events.fire(new AttackEndedEvent(attack));
    }

    private Instant now(AttackReport attack) {
      Instant ended = attack.endedAt();
      return ended != null ? ended : Instant.ofEpochMilli(millisClock.getAsLong());
    }
  }

  /** The routine lines the {@code logging} switches turn on, which attacks hold back. */
  private String heldBack() {
    List<String> kinds = new ArrayList<>();
    if (logging.pings()) {
      kinds.add(RoutineLine.PING.description());
    }
    if (logging.connections()) {
      kinds.add(RoutineLine.CONNECTION.description());
    }
    return String.join(" and ", kinds);
  }

  private EnumMap<RoutineLine, Long> unloggedTotals() {
    EnumMap<RoutineLine, Long> totals = new EnumMap<>(RoutineLine.class);
    for (RoutineLine line : RoutineLine.values()) {
      totals.put(line, unlogged(line));
    }
    return totals;
  }

  /**
   * Describes the routine lines held back between two counts, such as {@code "; not logged one by
   * one: 1200 server list pings"}, or nothing if none was.
   */
  private static String unloggedSince(Map<RoutineLine, Long> before, Map<RoutineLine, Long> after) {
    List<String> parts = new ArrayList<>();
    for (RoutineLine line : RoutineLine.values()) {
      long delta = after.getOrDefault(line, 0L) - before.getOrDefault(line, 0L);
      if (delta > 0) {
        parts.add(delta + " " + line.description());
      }
    }
    return parts.isEmpty() ? "" : "; not logged one by one: " + String.join(", ", parts);
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
      case VERIFICATIONS_PASSED -> "verifications passed";
      case VERIFICATIONS_FAILED -> "verifications failed";
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
    SecretFiles.create(file, Base64.getEncoder().encodeToString(secret) + "\n");
    logger.info("Anti-bot: generated the secret that hashes identities, in {}", file);
    return secret;
  }

  private static byte[] randomSecret() {
    byte[] secret = new byte[IdentityKeys.SECRET_LENGTH];
    RANDOM.nextBytes(secret);
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
