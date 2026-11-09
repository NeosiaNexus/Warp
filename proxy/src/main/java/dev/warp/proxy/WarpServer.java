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
package dev.warp.proxy;

import dev.warp.api.Warp;
import dev.warp.api.WarpProvider;
import dev.warp.api.antibot.AntiBot;
import dev.warp.api.antibot.AttackLevel;
import dev.warp.api.antibot.Signal;
import dev.warp.api.event.proxy.ProxyReadyEvent;
import dev.warp.api.event.proxy.ProxyShutdownEvent;
import dev.warp.api.lifecycle.DrainOutcome;
import dev.warp.api.lifecycle.DrainPhase;
import dev.warp.api.lifecycle.DrainStatus;
import dev.warp.api.lifecycle.DrainTrigger;
import dev.warp.api.player.Player;
import dev.warp.api.plugin.PluginContainer;
import dev.warp.api.plugin.PluginManager;
import dev.warp.api.plugin.PluginState;
import dev.warp.jni.NativeStatus;
import dev.warp.jni.Natives;
import dev.warp.proxy.antibot.AntiBotService;
import dev.warp.proxy.auth.MojangSessionService;
import dev.warp.proxy.auth.ProfileKeys;
import dev.warp.proxy.command.ProxyCommands;
import dev.warp.proxy.command.ProxyControl;
import dev.warp.proxy.command.WarpCommandManager;
import dev.warp.proxy.compression.Compression;
import dev.warp.proxy.config.AntiBotConfig;
import dev.warp.proxy.config.DrainConfig;
import dev.warp.proxy.config.WarpConfig;
import dev.warp.proxy.connection.BackendPinger;
import dev.warp.proxy.connection.ConnectedPlayer;
import dev.warp.proxy.connection.PlaySession;
import dev.warp.proxy.connection.ServerChannelInitializer;
import dev.warp.proxy.connection.ServerLoginContext;
import dev.warp.proxy.console.ConsoleCommandSource;
import dev.warp.proxy.console.ConsoleOutput;
import dev.warp.proxy.console.NameDirectory;
import dev.warp.proxy.console.ServerChecks;
import dev.warp.proxy.console.StartupTimings;
import dev.warp.proxy.console.StatusLine;
import dev.warp.proxy.console.Summaries;
import dev.warp.proxy.console.WarpConsole;
import dev.warp.proxy.drain.ExecutorDrainScheduler;
import dev.warp.proxy.drain.ProxyDrain;
import dev.warp.proxy.event.WarpEventBus;
import dev.warp.proxy.limit.Blocklist;
import dev.warp.proxy.limit.ClientLimits;
import dev.warp.proxy.logging.LogCode;
import dev.warp.proxy.logging.LogLevels;
import dev.warp.proxy.logging.LogSinkRegistry;
import dev.warp.proxy.logging.Plural;
import dev.warp.proxy.logging.SinkAppender;
import dev.warp.proxy.management.ManagementServer;
import dev.warp.proxy.management.ProxyHealth;
import dev.warp.proxy.management.ReadinessHolds;
import dev.warp.proxy.management.console.RemoteConsole;
import dev.warp.proxy.messaging.BungeeCordChannel;
import dev.warp.proxy.messaging.ChannelRegistry;
import dev.warp.proxy.network.CidrRanges;
import dev.warp.proxy.permission.PermissionRegistry;
import dev.warp.proxy.pipeline.PipelineRegistry;
import dev.warp.proxy.player.PlayerRegistry;
import dev.warp.proxy.plugin.WarpPluginManager;
import dev.warp.proxy.server.ServerRegistry;
import dev.warp.proxy.status.ServerList;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.spec.InvalidKeySpecException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.IntConsumer;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.PooledByteBufAllocator;
import io.netty.channel.Channel;
import io.netty.channel.ChannelOption;
import io.netty.channel.IoHandlerFactory;
import io.netty.channel.MultiThreadIoEventLoopGroup;
import io.netty.channel.WriteBufferWaterMark;
import io.netty.channel.nio.NioIoHandler;
import io.netty.channel.socket.ServerSocketChannel;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.util.concurrent.Future;
import net.kyori.adventure.text.Component;
import org.apache.logging.log4j.LogManager;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Concrete proxy implementation that owns the full server lifecycle.
 *
 * <p>Implements the public {@link Warp} API so that {@link WarpProvider#get()} returns this
 * instance once the server has started.
 */
public final class WarpServer implements Warp {

  private static final Logger logger = LoggerFactory.getLogger(WarpServer.class);

  /**
   * Low water mark (bytes) for the write buffer. When the buffered amount drops below this, the
   * channel becomes writable again.
   */
  private static final int WRITE_BUFFER_LOW_WATER_MARK = 512 * 1024;

  /**
   * High water mark (bytes) for the write buffer. When the buffered amount exceeds this, the
   * channel becomes unwritable (back-pressure).
   */
  private static final int WRITE_BUFFER_HIGH_WATER_MARK = 2 * 1024 * 1024;

  /**
   * DSCP value for low-latency traffic. Sets the IP Type of Service field to prioritise interactive
   * game packets over bulk transfers.
   */
  private static final int IP_TOS_LOW_LATENCY = 0x18;

  /**
   * How many TCP Fast Open connections may wait for their handshake to complete at once. Beyond it
   * the kernel falls back to a normal handshake, so the value only bounds how many data-carrying
   * SYNs the listener holds during a SYN flood; it never refuses a player.
   */
  private static final int TCP_FAST_OPEN_QUEUE = 256;

  /**
   * RSA key size for the Minecraft encryption handshake: 1024 bits, as specified by the protocol
   * and generated by the vanilla server. The key pair lives in memory for one run of the proxy and
   * only carries each connection's 16-byte shared secret during login.
   */
  private static final int RSA_KEY_SIZE = 1024;

  /** Logs the console's blocks (banner, summaries), which SLF4J cannot carry. */
  private static final org.apache.logging.log4j.Logger BLOCKS =
      LogManager.getLogger(WarpServer.class);

  /** Why the proxy stops when nothing says otherwise: a call to {@link #stop()}. */
  private static final String DEFAULT_STOP_CAUSE = "stop requested";

  /** How long the startup's status pings of the backend servers may take, at most. */
  private static final Duration STARTUP_PING_TIMEOUT = Duration.ofSeconds(2);

  /** How often the backend servers are pinged while the console's status line shows them. */
  private static final long STATUS_PING_SECONDS = 10;

  /** How long shutdown waits for the handlers of {@link ProxyShutdownEvent}. */
  private static final long SHUTDOWN_EVENT_TIMEOUT_SECONDS = 30;

  /** How often the BungeeCord forwards that waited too long for a player are dropped. */
  private static final long HELD_FORWARDS_SWEEP_SECONDS = 1;

  /** How long shutdown waits for the listener to close. */
  private static final long LISTENER_CLOSE_TIMEOUT_SECONDS = 5;

  /** Netty's default quiet period of an event loop being shut down. */
  private static final Duration SHUTDOWN_QUIET_PERIOD = Duration.ofSeconds(2);

  /** Netty's default limit to an event loop's shutdown, quiet period included. */
  private static final long SHUTDOWN_TIMEOUT_MILLIS = 15_000;

  /**
   * How long a second shutdown request (the JVM's hook during a drain's shutdown) waits for the one
   * in progress before the JVM goes on exiting.
   */
  private static final long STOP_JOIN_TIMEOUT_SECONDS = 60;

  /** Exit code once a drain emptied the proxy. */
  static final int EXIT_DRAINED = 0;

  /** Exit code when the drain's time-out expired and the players left were disconnected. */
  static final int EXIT_DRAIN_TIMED_OUT = 2;

  /** Exit code when a second SIGTERM stops the proxy in the middle of a drain: 128 + 15. */
  static final int EXIT_SIGTERM = 143;

  // ---------------------------------------------------------------------------
  // Instance fields
  // ---------------------------------------------------------------------------

  private final WarpConfig config;

  /** The players connected to the proxy. */
  private final PlayerRegistry playerRegistry = new PlayerRegistry();

  /** The backend servers, static and registered at runtime. */
  private final ServerRegistry serverRegistry;

  /** The event bus plugins subscribe to. */
  private final WarpEventBus events = new WarpEventBus();

  /** The plugin message channels plugins own. */
  private final ChannelRegistry channels;

  /** The proxy's commands, its own and the plugins'. */
  private final WarpCommandManager commands = new WarpCommandManager();

  /** The console as a command source, rendering the proxy's messages in the default language. */
  private final ConsoleCommandSource consoleSource;

  /** Where the startup's time went: /warp startup, and the line under Ready in. */
  private final StartupTimings startup = new StartupTimings();

  /** The levels of log sources: logging.levels, and /log level. */
  private final LogLevels logLevels = new LogLevels();

  /** Pings the backend servers for the startup summary and the status line. */
  private final ServerChecks serverChecks = new ServerChecks();

  private @Nullable WarpConsole console;

  /** Why the proxy stops, for the shutdown summary. */
  private volatile String stopCause = DEFAULT_STOP_CAUSE;

  /** How a drain ended, for the shutdown summary; {@code null} without one. */
  private volatile @Nullable String drainSummary;

  /** When the proxy became ready, for the uptime of the shutdown summary. */
  private volatile long readyNanos;

  /** The permission providers plugins registered. */
  private final PermissionRegistry permissions = new PermissionRegistry();

  /** The pipeline initializers plugins registered. */
  private final PipelineRegistry pipelines = new PipelineRegistry();

  private final LogSinkRegistry logSinks = new LogSinkRegistry();
  private final SinkAppender sinkAppender = new SinkAppender(logSinks);

  /** The plugins of the proxy's {@code plugins} directory. */
  private final WarpPluginManager pluginManager;

  /** The holds plugins keep on the proxy's readiness. */
  private final ReadinessHolds readinessHolds = new ReadinessHolds();

  /** Runs the drain's steps, on a thread of their own. */
  private final ExecutorDrainScheduler drainScheduler = new ExecutorDrainScheduler();

  /** Empties the proxy before it stops. */
  private final ProxyDrain drain;

  /** Ends the process once a drain stopped the proxy: {@link System#exit} outside tests. */
  private final IntConsumer exit;

  /**
   * How long an event loop must go without a new task before it terminates, when stopping: Netty's
   * default, {@link #SHUTDOWN_QUIET_PERIOD}. A test without players takes none.
   */
  private final Duration shutdownQuietPeriod;

  /** The management server, when enabled. */
  private @Nullable ManagementServer management;

  /** The remote console on the management server, when enabled. */
  private final @Nullable RemoteConsole remoteConsole;

  /** Netty boss group — accepts incoming connections. */
  private @Nullable MultiThreadIoEventLoopGroup bossGroup;

  /** Netty worker group — handles I/O on accepted connections. */
  private @Nullable MultiThreadIoEventLoopGroup workerGroup;

  /** The bound server channel, used for graceful shutdown. */
  private @Nullable Channel serverChannel;

  /** The detected transport info, needed for outbound connections. */
  private @Nullable TransportInfo transportInfo;

  /** The Mojang session service, kept for lifecycle management. */
  private @Nullable MojangSessionService sessionService;

  /** The anti-bot, from {@link #start()} on. */
  private volatile @Nullable AntiBotService antiBot;

  /** The proxy's working directory. */
  private final Path directory;

  private volatile boolean running;

  /** Whether the proxy listens and its plugins are enabled. */
  private volatile boolean started;

  /** Set by the first shutdown request; later ones wait for {@link #stopped}. */
  private final AtomicBoolean stopping = new AtomicBoolean();

  /** Completed once the shutdown is over. */
  private final CompletableFuture<@Nullable Void> stopped = new CompletableFuture<>();

  /**
   * Creates a server with the given configuration.
   *
   * @param config the proxy configuration
   * @param directory the proxy's working directory, which holds the {@code plugins} directory
   */
  public WarpServer(WarpConfig config, Path directory) {
    this(config, directory, System::exit);
  }

  /**
   * Creates a server that ends the process with {@code exit} once a drain stopped it.
   *
   * @param config the proxy configuration
   * @param directory the proxy's working directory, which holds the {@code plugins} directory
   * @param exit ends the process with an exit code
   */
  WarpServer(WarpConfig config, Path directory, IntConsumer exit) {
    this(config, directory, exit, SHUTDOWN_QUIET_PERIOD);
  }

  /**
   * Creates a server that ends the process with {@code exit} once a drain stopped it, and whose
   * event loops wait {@code shutdownQuietPeriod} for tasks when stopping.
   *
   * @param config the proxy configuration
   * @param directory the proxy's working directory, which holds the {@code plugins} directory
   * @param exit ends the process with an exit code
   * @param shutdownQuietPeriod see {@link #shutdownQuietPeriod}
   */
  WarpServer(WarpConfig config, Path directory, IntConsumer exit, Duration shutdownQuietPeriod) {
    this.config = config;
    this.consoleSource = new ConsoleCommandSource(config::messages);
    this.remoteConsole = createRemoteConsole(config);
    this.directory = directory;
    this.exit = exit;
    this.shutdownQuietPeriod = shutdownQuietPeriod;
    this.serverRegistry =
        new ServerRegistry(config.servers(), config.defaultServer(), config.fallbackOrder());
    serverRegistry.setEventBus(events);
    serverRegistry.setPingTimeout(config.timeouts().ping());
    this.channels =
        new ChannelRegistry(
            new BungeeCordChannel(config.bungeeCord(), playerRegistry, serverRegistry));
    ProxyCommands.register(commands, serverRegistry);
    // The command lists players see follow the commands and the permissions that filter them.
    commands.onChange(
        () -> {
          playerRegistry.all().forEach(ConnectedPlayer::refreshCommands);
          RemoteConsole remote = remoteConsole;
          if (remote != null) {
            remote.commandsChanged();
          }
        });
    permissions.trackPlayers(this::players);
    this.pluginManager =
        new WarpPluginManager(
            this,
            events,
            channels,
            commands,
            permissions,
            readinessHolds,
            pipelines,
            logSinks,
            directory.resolve("plugins"));
    this.drain =
        new ProxyDrain(
            config.drain(), playerRegistry, events, drainScheduler, this::stopAfterDrain);
  }

  // ---------------------------------------------------------------------------
  // Lifecycle
  // ---------------------------------------------------------------------------

  /**
   * Initialises Netty, registers the shutdown hook, binds the server port, and publishes the API.
   */
  public void start() {
    if (running) {
      throw new IllegalStateException("Server is already running.");
    }

    ConsoleOutput output = ConsoleOutput.get();
    if (output != null) {
      output.names(names());
    }

    // What the operator can fix is checked before anything starts: one clear message each.
    long mark = System.nanoTime();
    StartupException.checkBindable(config.bind(), "bind");
    if (config.management().enabled()) {
      StartupException.checkBindable(config.management().bind(), "management.bind");
    }
    StartupException.checkReadableDirectory(directory.resolve("plugins"), "plugins");
    // Persisted levels first, so that the startup's own lines follow them.
    for (String unknown : logLevels.configure(config.logging().levels())) {
      logger.info("logging.levels: {} is no source Warp knows yet, set as a logger name", unknown);
    }
    mark = lap("checks", mark);

    TransportInfo transport = detectTransport();
    this.transportInfo = transport;
    // Loads and self-tests native compression before any connection, so the log tells the truth.
    Compression.logImplementation();
    mark = lap("natives", mark);
    // Behind the PROXY protocol, the trusted proxies are never limited: their own connections are
    // health checks, and every other one is limited under the client address of its header.
    AntiBotConfig antiBotConfig = config.antiBot();
    AntiBotConfig.Blocklist blocklist = antiBotConfig.blocklist();
    ClientLimits limits =
        new ClientLimits(
            config.limits(),
            config.proxyProtocol().enabled()
                ? config.proxyProtocol().trustedProxies()
                : CidrRanges.none(),
            antiBotConfig.enabled() && blocklist.enabled()
                ? new Blocklist(blocklist.strikes(), blocklist.duration(), System::nanoTime)
                : null);
    if (config.tcpFastOpen() && !transport.tcpFastOpen()) {
      logger.warn(
          LogCode.TCP_FAST_OPEN_UNSUPPORTED.marker(),
          "tcp-fast-open is on, but only the epoll transport (Linux) supports it: ignored");
    }
    // Before the event loops, so that a configuration error leaves nothing to shut down.
    AntiBotService bots = startAntiBot(limits);
    ServerLoginContext loginContext = createLoginContext(transport, limits, bots);
    mark = lap("anti-bot", mark);
    this.bossGroup = new MultiThreadIoEventLoopGroup(1, transport.ioFactory());
    this.workerGroup = new MultiThreadIoEventLoopGroup(transport.ioFactory());
    long reportMillis = limits.reportInterval().toMillis();
    var _ =
        workerGroup.scheduleAtFixedRate(
            limits::report, reportMillis, reportMillis, TimeUnit.MILLISECONDS);
    var _ = workerGroup.scheduleAtFixedRate(bots::tick, 1, 1, TimeUnit.SECONDS);
    ProxyCommands.registerAdmin(commands, this, new Control(), bots);
    ProxyCommands.registerConsole(commands, logLevels);
    if (channels.bungeeCord().enabled()) {
      var _ =
          workerGroup.scheduleAtFixedRate(
              channels.bungeeCord()::expireHeld,
              HELD_FORWARDS_SWEEP_SECONDS,
              HELD_FORWARDS_SWEEP_SECONDS,
              TimeUnit.SECONDS);
    }

    serverRegistry.setPinger(new BackendPinger(workerGroup, transport.clientChannelClass()));
    // Answered while plugins load; the summary waits for what is left once the port is bound.
    Duration pingTimeout =
        config.timeouts().ping().compareTo(STARTUP_PING_TIMEOUT) < 0
            ? config.timeouts().ping()
            : STARTUP_PING_TIMEOUT;
    CompletableFuture<List<Summaries.Server>> checks =
        serverChecks.check(serverRegistry.servers(), pingTimeout);

    mark = lap("event loops", mark);
    // Probes get an answer (503) while plugins load, and the agent check from the start.
    startManagement();
    mark = lap("management", mark);

    // Plugins find the API published, and load before the first player can connect. Their log
    // sinks receive the records logged from their registration on.
    registerProvider();
    sinkAppender.install();
    pluginManager.loadAll();
    startup.record("plugins", System.nanoTime() - mark, pluginManager.loadNanos());
    mark = System.nanoTime();

    ServerBootstrap bootstrap =
        new ServerBootstrap()
            .group(bossGroup, workerGroup)
            .channel(transport.serverChannelClass())
            .childHandler(new ServerChannelInitializer(loginContext, config.proxyProtocol()))
            // TCP_NODELAY: disable Nagle — critical for game traffic. BungeeCord misses this.
            .childOption(ChannelOption.TCP_NODELAY, true)
            // SO_KEEPALIVE: detect dead connections at the TCP level.
            .childOption(ChannelOption.SO_KEEPALIVE, true)
            // IP_TOS: DSCP low-latency marking for routers that honour it.
            .childOption(ChannelOption.IP_TOS, IP_TOS_LOW_LATENCY)
            // Write buffer water marks for back-pressure (512 KB / 2 MB).
            .childOption(
                ChannelOption.WRITE_BUFFER_WATER_MARK,
                new WriteBufferWaterMark(WRITE_BUFFER_LOW_WATER_MARK, WRITE_BUFFER_HIGH_WATER_MARK))
            // Pooled direct allocator — avoids unpooled heap copies.
            .childOption(ChannelOption.ALLOCATOR, PooledByteBufAllocator.DEFAULT)
            // SO_REUSEADDR: allow rebind immediately after restart.
            .option(ChannelOption.SO_REUSEADDR, true);
    if (loginContext.tcpFastOpen()) {
      bootstrap.option(ChannelOption.TCP_FASTOPEN, TCP_FAST_OPEN_QUEUE);
    }

    // No SO_REUSEPORT: with it, a second Warp started on the same port binds without error and the
    // kernel silently splits players between the two processes. A port conflict must fail loudly.

    try {
      this.serverChannel = bootstrap.bind(config.bind()).sync().channel();
      mark = lap("bind", mark);
    } catch (InterruptedException e) {
      shutdownEventLoops();
      stopManagement();
      Thread.currentThread().interrupt();
      throw new RuntimeException("Interrupted while binding server", e);
    } catch (Exception e) {
      shutdownEventLoops();
      stopManagement();
      throw StartupException.cannotBind(config.bind(), "bind", e);
    }

    Runtime.getRuntime().addShutdownHook(new Thread(this::onJvmShutdown, "warp-shutdown"));
    if (config.drain().onSigterm()) {
      catchSigterm();
    }

    this.running = true;
    this.started = true;
    List<Summaries.Server> servers = awaitChecks(checks, pingTimeout);
    var _ = lap("servers", mark);
    BLOCKS.info(Summaries.startup(startupFacts(transport, servers)));
    this.readyNanos = System.nanoTime();
    long uptimeMillis = ManagementFactory.getRuntimeMXBean().getUptime();
    logger.info("Ready in {} s", String.format(Locale.ROOT, "%.2f", uptimeMillis / 1000.0));
    startup.finish(TimeUnit.MILLISECONDS.toNanos(uptimeMillis));
    // Where the time went, in one dimmed line; /warp startup gives the details.
    BLOCKS.info(startup.block());
    var _ = events.fire(new ProxyReadyEvent(serverChannel.localAddress()));
  }

  /**
   * Starts reading commands at the console, and shows the status line on an interactive terminal.
   * Called by {@link WarpBootstrap} once the proxy started: an embedded proxy (tests, a plugin test
   * harness) leaves standard input alone.
   */
  public void startConsole() {
    WarpConsole reader =
        new WarpConsole(
            commands,
            consoleSource,
            directory.resolve(".console_history"),
            () -> {
              // Ctrl+C or Ctrl+D: the graceful path of /shutdown (players told, the drain, the
              // same summary). The prompt is gone: a second Ctrl+C is a SIGINT, which stops at
              // once. Without /shutdown (a plugin unregistered it), the proxy stops at once.
              logger.info("Shutting down as /shutdown does: Ctrl+C again stops at once");
              var _ =
                  commands
                      .execute(consoleSource, "shutdown")
                      .thenAccept(
                          ran -> {
                            if (!ran) {
                              stopCause = "stopped at the console";
                              var _ =
                                  Thread.ofPlatform().name("warp-stop").start(() -> exit.accept(0));
                            }
                          });
            });
    this.console = reader;
    reader.start();
    ConsoleOutput output = ConsoleOutput.get();
    MultiThreadIoEventLoopGroup workers = workerGroup;
    if (output != null && output.mode().interactive() && workers != null) {
      var _ =
          workers.scheduleAtFixedRate(
              () -> {
                var _ = serverChecks.check(serverRegistry.servers(), STARTUP_PING_TIMEOUT);
              },
              STATUS_PING_SECONDS,
              STATUS_PING_SECONDS,
              TimeUnit.SECONDS);
      output.status(() -> StatusLine.render(statusSnapshot(), output.mode()));
    }
  }

  @Override
  public ConsoleCommandSource console() {
    return consoleSource;
  }

  /**
   * Gracefully shuts down the server channel and Netty event loops. A call while a shutdown is in
   * progress waits for it, for at most a minute.
   */
  public void stop() {
    var _ = stop(null);
  }

  /**
   * Shuts down: closes the listener, disconnects the players, lets plugins finish, waits for the
   * connections that carry plugin handlers to close, disables the plugins, then stops the event
   * bus, the drain, the event loops and, last, the management server. A drain ends the same way.
   *
   * @param reason shown to the players disconnected, or {@code null} for the default
   * @return whether this call shut the proxy down, rather than a call before it
   */
  private boolean stop(@Nullable Component reason) {
    if (!stopping.compareAndSet(false, true)) {
      awaitStopped();
      return false;
    }
    try {
      if (!running) {
        return false;
      }
      running = false;
      long stopStart = System.nanoTime();
      WarpConsole reader = console;
      if (reader != null) {
        reader.close();
      }
      ConsoleOutput output = ConsoleOutput.get();
      if (output != null) {
        output.status(null);
        output.endInteractive();
      }
      logger.info("Shutting down: {}", stopCause);

      if (serverChannel != null) {
        serverChannel
            .close()
            .awaitUninterruptibly(LISTENER_CLOSE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
      }
      int disconnected = disconnectPlayers(reason);
      awaitShutdownHandlers();
      // Before the plugins: a disconnect is asynchronous, and a backend still decoding through a
      // plugin's handlers once its class loader closed fails with a NoClassDefFoundError.
      awaitConnectionsClosed();
      pluginManager.disableAll();
      logSinks.close();
      sinkAppender.uninstall();
      events.close();
      commands.close();
      drainScheduler.shutdown();
      shutdownEventLoops();

      if (sessionService != null) {
        sessionService.close();
      }
      AntiBotService bots = antiBot;
      if (bots != null) {
        bots.close(); // saves the trusted identities
      }
      // Last: the agent check and the liveness probe answer until the very end.
      stopManagement();

      long readyAt = readyNanos;
      Duration uptime =
          readyAt == 0 ? Duration.ZERO : Duration.ofNanos(System.nanoTime() - readyAt);
      BLOCKS.info(
          Summaries.shutdown(
              new Summaries.Shutdown(stopCause, uptime, disconnected, drainSummary)));
      logger.info(
          "Stopped in {} s",
          String.format(Locale.ROOT, "%.2f", (System.nanoTime() - stopStart) / 1e9));
      return true;
    } finally {
      stopped.complete(null);
    }
  }

  /**
   * Returns where the startup's time goes, for {@link WarpBootstrap} to record what happens before
   * {@link #start()}: the JVM, logging, the configuration.
   *
   * @return the timings
   */
  public StartupTimings startupTimings() {
    return startup;
  }

  /** Records a phase of the startup that began at {@code since}; returns its end. */
  private long lap(String phase, long since) {
    long now = System.nanoTime();
    startup.record(phase, now - since);
    return now;
  }

  /** Waits a bounded time for the shutdown in progress. */
  private void awaitStopped() {
    try {
      stopped.get(STOP_JOIN_TIMEOUT_SECONDS, TimeUnit.SECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    } catch (ExecutionException | TimeoutException e) {
      logger.warn(
          LogCode.SHUTDOWN_STUCK.marker(),
          "The shutdown in progress did not finish within {} s",
          STOP_JOIN_TIMEOUT_SECONDS);
    }
  }

  /**
   * The JVM's shutdown hook (SIGINT, SIGTERM unless it drains, the end of the process): stops the
   * proxy or waits for the shutdown in progress, then stops the logging, whose own hook is off so
   * that the proxy can log its shutdown.
   */
  private void onJvmShutdown() {
    if (!stopping.get() && stopCause.equals(DEFAULT_STOP_CAUSE)) {
      stopCause = "JVM shutdown (SIGINT, SIGTERM or System.exit)";
    }
    stop();
    org.apache.logging.log4j.LogManager.shutdown();
  }

  /** Phase 3 of a drain: stops the proxy, then ends the process with the drain's outcome. */
  private void stopAfterDrain(DrainStatus status) {
    stopCause = "drain finished";
    drainSummary =
        String.format(
            Locale.ROOT,
            "%s after %s: %d moved, %d kept, %d disconnected",
            status.outcome() == DrainOutcome.TIMED_OUT ? "timed out" : "completed",
            status.elapsed().toSeconds() + " s",
            status.transferred(),
            status.kept(),
            status.kicked());
    if (stop(drain.message())) {
      int code = status.outcome() == DrainOutcome.TIMED_OUT ? EXIT_DRAIN_TIMED_OUT : EXIT_DRAINED;
      logger.info("Exiting with code {}", code);
      exit.accept(code);
    }
  }

  /** Disconnects every player, telling them the proxy is shutting down; returns how many. */
  private int disconnectPlayers(@Nullable Component reason) {
    List<ConnectedPlayer> players = playerRegistry.all();
    if (players.isEmpty()) {
      return 0;
    }
    logger.info(
        "Disconnecting {} " + Plural.of(players.size(), "player", "players"), players.size());
    for (ConnectedPlayer player : players) {
      if (reason == null) {
        player.disconnectShutdown();
      } else {
        player.disconnectShutdown(reason);
      }
    }
    return players.size();
  }

  /** Starts the management server, if enabled. */
  private void startManagement() {
    if (!config.management().enabled()) {
      return;
    }
    RemoteConsole remote = remoteConsole;
    if (remote != null) {
      remote.start();
    }
    ManagementServer server = new ManagementServer(config.management(), new Health(), remote);
    try {
      server.start();
    } catch (IllegalStateException e) {
      if (remote != null) {
        remote.stop();
      }
      shutdownEventLoops();
      throw e;
    }
    this.management = server;
  }

  private void stopManagement() {
    RemoteConsole remote = remoteConsole;
    if (remote != null) {
      remote.stop();
    }
    ManagementServer server = management;
    if (server != null) {
      server.stop();
    }
  }

  /**
   * Creates the remote console, if enabled: it keeps the proxy's secrets out of what it streams.
   */
  private @Nullable RemoteConsole createRemoteConsole(WarpConfig config) {
    if (!config.management().enabled() || !config.management().console().enabled()) {
      return null;
    }
    List<String> secrets = new ArrayList<>();
    secrets.add(new String(config.forwardingSecret(), StandardCharsets.UTF_8));
    String token = config.management().token();
    if (token != null) {
      secrets.add(token);
    }
    return new RemoteConsole(
        config.management().console(), commands, config::messages, WarpBuildInfo.VERSION, secrets);
  }

  /** Makes SIGTERM start a drain, and a second SIGTERM stop at once. */
  private void catchSigterm() {
    boolean caught =
        Sigterm.install(
            () -> {
              if (stopping.get()) {
                return;
              }
              logger.info("SIGTERM received: draining (another SIGTERM stops at once)");
              var _ = startDrain(DrainTrigger.SIGNAL);
            },
            () -> {
              logger.warn(
                  LogCode.DRAIN_SIGTERM_AGAIN.marker(),
                  "SIGTERM received again: stopping now, without finishing the drain");
              var _ = Thread.ofPlatform().name("warp-stop").start(() -> exit.accept(EXIT_SIGTERM));
            });
    if (caught) {
      logger.info("SIGTERM drains the proxy (drain.on-sigterm)");
    } else {
      logger.warn(
          LogCode.DRAIN_SIGTERM_UNSUPPORTED.marker(),
          "drain.on-sigterm is on, but this JVM does not let Warp handle SIGTERM: SIGTERM stops"
              + " the proxy at once, without draining");
    }
  }

  /** Fires {@link ProxyShutdownEvent} and waits a bounded time for its handlers. */
  private void awaitShutdownHandlers() {
    try {
      events.fire(new ProxyShutdownEvent()).get(SHUTDOWN_EVENT_TIMEOUT_SECONDS, TimeUnit.SECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    } catch (ExecutionException | TimeoutException e) {
      logger.warn(
          LogCode.SHUTDOWN_HANDLERS_SLOW.marker(),
          "Shutdown handlers did not finish within {} s",
          SHUTDOWN_EVENT_TIMEOUT_SECONDS);
    }
  }

  /**
   * Closes the connections that carry plugin handlers, the clients' and the backends', and waits
   * for them, {@value #SHUTDOWN_TIMEOUT_MILLIS} ms at most: a plugin's handler must not run once
   * the plugin is disabled and its class loader closed.
   */
  private void awaitConnectionsClosed() {
    if (!pipelines.closeConnections(Duration.ofMillis(SHUTDOWN_TIMEOUT_MILLIS))) {
      logger.warn(
          LogCode.SHUTDOWN_STUCK.marker(),
          "{} connections with plugin handlers did not close within {} ms",
          pipelines.connectionCount(),
          SHUTDOWN_TIMEOUT_MILLIS);
    }
  }

  /**
   * Shuts down boss and worker event loops, both at once: each waits out its quiet period before it
   * terminates, so shutting them down one after the other made every stop, a drain's exit included,
   * wait twice as long. Safe to call multiple times.
   */
  private void shutdownEventLoops() {
    MultiThreadIoEventLoopGroup bosses = bossGroup;
    MultiThreadIoEventLoopGroup workers = workerGroup;
    long quiet = shutdownQuietPeriod.toMillis();
    Future<?> boss =
        bosses != null
            ? bosses.shutdownGracefully(quiet, SHUTDOWN_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)
            : null;
    Future<?> worker =
        workers != null
            ? workers.shutdownGracefully(quiet, SHUTDOWN_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)
            : null;
    if (boss != null) {
      boss.awaitUninterruptibly();
    }
    if (worker != null) {
      worker.awaitUninterruptibly();
    }
  }

  // ---------------------------------------------------------------------------
  // Warp API
  // ---------------------------------------------------------------------------

  @Override
  public String version() {
    return WarpBuildInfo.VERSION;
  }

  @Override
  public PluginManager plugins() {
    return pluginManager;
  }

  @Override
  public Collection<Player> players() {
    return playerRegistry.all().stream().<Player>map(ConnectedPlayer::api).toList();
  }

  @Override
  public Optional<Player> player(UUID uniqueId) {
    return Optional.ofNullable(playerRegistry.get(uniqueId)).map(ConnectedPlayer::api);
  }

  @Override
  public Optional<Player> player(String username) {
    return Optional.ofNullable(playerRegistry.get(username)).map(ConnectedPlayer::api);
  }

  @Override
  public int playerCount() {
    return playerRegistry.count();
  }

  @Override
  public dev.warp.api.server.ServerRegistry servers() {
    return serverRegistry;
  }

  /**
   * Returns the holds plugins keep on the proxy's readiness (tests take one as a plugin would).
   *
   * @return the readiness holds
   */
  ReadinessHolds readinessHolds() {
    return readinessHolds;
  }

  /**
   * {@inheritDoc}
   *
   * @throws IllegalStateException before {@link #start()}, which loads the anti-bot
   */
  @Override
  public AntiBot antiBot() {
    AntiBotService bots = antiBot;
    if (bots == null) {
      throw new IllegalStateException("The anti-bot is loaded when the proxy starts");
    }
    return bots;
  }

  /**
   * Loads the anti-bot: its secret and its store of trusted identities.
   *
   * @throws IllegalStateException if they cannot be read or written
   */
  private AntiBotService startAntiBot(ClientLimits limits) {
    try {
      AntiBotService bots =
          new AntiBotService(
              config.antiBot(),
              config.onlineMode(),
              limits,
              events,
              config.logging(),
              directory,
              System::getenv,
              System::currentTimeMillis);
      this.antiBot = bots;
      return bots;
    } catch (IOException e) {
      throw new IllegalStateException(
          "Cannot load the anti-bot's trusted identities from "
              + directory.resolve("anti-bot")
              + ": "
              + e.getMessage(),
          e);
    }
  }

  @Override
  public CompletableFuture<DrainStatus> drain() {
    return startDrain(DrainTrigger.PLUGIN);
  }

  @Override
  public boolean isDraining() {
    return drain.isDraining();
  }

  @Override
  public DrainStatus drainStatus() {
    return drain.status();
  }

  /**
   * Starts the drain, or returns the one in progress.
   *
   * @throws IllegalStateException if the proxy is not running
   */
  private CompletableFuture<DrainStatus> startDrain(DrainTrigger trigger) {
    if (!started || stopping.get()) {
      throw new IllegalStateException("The proxy is not running: it cannot drain");
    }
    return drain.start(trigger);
  }

  /** What the administration commands need beyond the API. */
  private final class Control implements ProxyControl {

    @Override
    public StartupTimings startup() {
      return startup;
    }

    @Override
    public Duration uptime() {
      return Duration.ofMillis(ManagementFactory.getRuntimeMXBean().getUptime());
    }

    @Override
    public String compression() {
      return Compression.describe();
    }

    @Override
    public DrainConfig drain() {
      return config.drain();
    }

    @Override
    public CompletableFuture<DrainStatus> shutdown(@Nullable Component reason) {
      if (!started || stopping.get()) {
        throw new IllegalStateException("The proxy is not running");
      }
      return drain.start(DrainTrigger.COMMAND, reason);
    }
  }

  /** The proxy as the management server reads it. */
  private final class Health implements ProxyHealth {

    @Override
    public boolean isStarted() {
      return started;
    }

    @Override
    public boolean isStopping() {
      return stopping.get();
    }

    @Override
    public int serverCount() {
      return serverRegistry.servers().size();
    }

    @Override
    public List<String> readinessHolds() {
      return readinessHolds.reasons();
    }

    @Override
    public boolean isDraining() {
      return drain.isDraining();
    }

    @Override
    public DrainStatus drainStatus() {
      return drain.status();
    }

    @Override
    public CompletableFuture<DrainStatus> drain(DrainTrigger trigger) {
      return startDrain(trigger);
    }

    @Override
    public @Nullable AntiBotService antiBot() {
      return antiBot;
    }
  }

  // ---------------------------------------------------------------------------
  // Internals
  // ---------------------------------------------------------------------------

  /**
   * Returns the worker event loop group for creating outbound connections (backend).
   *
   * @return the worker group
   * @throws IllegalStateException if the server has not started
   */
  public MultiThreadIoEventLoopGroup workerGroup() {
    if (workerGroup == null) {
      throw new IllegalStateException("Server has not started");
    }
    return workerGroup;
  }

  /**
   * Returns the client socket channel class matching the detected transport.
   *
   * @return the channel class for outbound connections
   * @throws IllegalStateException if the server has not started
   */
  public Class<? extends SocketChannel> clientChannelClass() {
    if (transportInfo == null) {
      throw new IllegalStateException("Server has not started");
    }
    return transportInfo.clientChannelClass();
  }

  /**
   * Creates the server-wide login context with RSA keypair and configuration values.
   *
   * @param transport the detected transport for channel class resolution
   * @param limits the limits on what one client address may do
   */
  private ServerLoginContext createLoginContext(
      TransportInfo transport, ClientLimits limits, AntiBotService bots) {
    ProfileKeys profileKeys = loadProfileKeys();

    KeyPairGenerator gen;
    try {
      gen = KeyPairGenerator.getInstance("RSA");
    } catch (NoSuchAlgorithmException e) {
      throw new AssertionError("RSA not available", e);
    }
    gen.initialize(RSA_KEY_SIZE);
    KeyPair rsaKeyPair = gen.generateKeyPair();

    MojangSessionService service = new MojangSessionService();
    this.sessionService = service;
    String sessionServer = System.getProperty(MojangSessionService.SESSION_SERVER_PROPERTY);
    if (sessionServer != null) {
      logger.warn(
          LogCode.SESSION_SERVER_CUSTOM.marker(),
          "Players are authenticated against {} instead of Mojang ({} system property)",
          sessionServer,
          MojangSessionService.SESSION_SERVER_PROPERTY);
    }

    return new ServerLoginContext(
        rsaKeyPair,
        config.onlineMode(),
        config.compressionThreshold(),
        config.compressionLevel(),
        config.compressionPassthrough(),
        service,
        profileKeys,
        new PlaySession(),
        serverRegistry,
        playerRegistry,
        new ServerList(config.status(), playerRegistry),
        config.duplicateLoginPolicy(),
        events,
        channels,
        commands,
        permissions,
        config.forwardingMode(),
        config.forwardingSecret(),
        transport.clientChannelClass(),
        config.acceptsTransfers(),
        limits,
        config.timeouts(),
        config.logging(),
        config.tcpFastOpen() && transport.tcpFastOpen(),
        bots,
        pipelines,
        config.messages());
  }

  /**
   * Loads the checker of 1.19 to 1.19.2 profile keys: it trusts Mojang's key, or the one named by
   * {@value ProfileKeys#SIGNER_PROPERTY}.
   *
   * @throws IllegalStateException if the property names a file that holds no RSA public key
   */
  private static ProfileKeys loadProfileKeys() {
    String signer = System.getProperty(ProfileKeys.SIGNER_PROPERTY);
    ProfileKeys profileKeys;
    try {
      profileKeys = ProfileKeys.fromSystemProperty();
    } catch (IOException | InvalidKeySpecException e) {
      throw new IllegalStateException(
          "Cannot read an RSA public key from " + signer + " (" + ProfileKeys.SIGNER_PROPERTY + ")",
          e);
    }
    if (signer != null) {
      logger.warn(
          LogCode.PROFILE_KEY_CUSTOM.marker(),
          "In online mode, the profile keys of 1.19 to 1.19.2 players are checked against {}"
              + " instead of Mojang's key ({} system property)",
          signer,
          ProfileKeys.SIGNER_PROPERTY);
    }
    return profileKeys;
  }

  /**
   * Detects the best available Netty I/O transport for the current platform.
   *
   * <p>Tries epoll (Linux), then kqueue (macOS), then falls back to NIO. Detection uses reflection
   * so there is no hard compile-time dependency on platform-specific modules.
   */
  @SuppressWarnings("unchecked")
  private static TransportInfo detectTransport() {
    try {
      Class<?> epoll = Class.forName("io.netty.channel.epoll.Epoll");
      if ((boolean) epoll.getMethod("isAvailable").invoke(null)) {
        logger.debug("Using epoll native transport");
        IoHandlerFactory factory =
            (IoHandlerFactory)
                Class.forName("io.netty.channel.epoll.EpollIoHandler")
                    .getMethod("newFactory")
                    .invoke(null);
        Class<? extends ServerSocketChannel> serverClass =
            (Class<? extends ServerSocketChannel>)
                Class.forName("io.netty.channel.epoll.EpollServerSocketChannel");
        Class<? extends SocketChannel> clientClass =
            (Class<? extends SocketChannel>)
                Class.forName("io.netty.channel.epoll.EpollSocketChannel");
        return new TransportInfo("epoll", factory, serverClass, clientClass, true);
      }
    } catch (ReflectiveOperationException | UnsatisfiedLinkError e) {
      logger.trace("Epoll not available", e);
    }

    try {
      Class<?> kqueue = Class.forName("io.netty.channel.kqueue.KQueue");
      if ((boolean) kqueue.getMethod("isAvailable").invoke(null)) {
        logger.debug("Using kqueue native transport");
        IoHandlerFactory factory =
            (IoHandlerFactory)
                Class.forName("io.netty.channel.kqueue.KQueueIoHandler")
                    .getMethod("newFactory")
                    .invoke(null);
        Class<? extends ServerSocketChannel> serverClass =
            (Class<? extends ServerSocketChannel>)
                Class.forName("io.netty.channel.kqueue.KQueueServerSocketChannel");
        Class<? extends SocketChannel> clientClass =
            (Class<? extends SocketChannel>)
                Class.forName("io.netty.channel.kqueue.KQueueSocketChannel");
        return new TransportInfo("kqueue", factory, serverClass, clientClass, false);
      }
    } catch (ReflectiveOperationException | UnsatisfiedLinkError e) {
      logger.trace("KQueue not available", e);
    }

    logger.debug("Using NIO transport (native transport unavailable)");
    return new TransportInfo(
        "NIO",
        NioIoHandler.newFactory(),
        NioServerSocketChannel.class,
        NioSocketChannel.class,
        false);
  }

  // ---------------------------------------------------------------------------
  // Console
  // ---------------------------------------------------------------------------

  /** Player and server names, for the console's highlighting; lookups in concurrent maps. */
  private NameDirectory names() {
    return new NameDirectory() {
      @Override
      public boolean isPlayer(String name) {
        return playerRegistry.get(name) != null;
      }

      @Override
      public boolean isServer(String name) {
        return serverRegistry.getServer(name) != null;
      }
    };
  }

  /** The results of the startup's pings, those not over in time as unreachable. */
  private List<Summaries.Server> awaitChecks(
      CompletableFuture<List<Summaries.Server>> checks, Duration timeout) {
    try {
      return checks.get(timeout.toMillis() + 500, TimeUnit.MILLISECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    } catch (ExecutionException | TimeoutException e) {
      logger.debug("The startup's pings of the backend servers did not finish", e);
    }
    List<Summaries.Server> unknown = new ArrayList<>();
    for (var server : serverRegistry.servers()) {
      unknown.add(
          new Summaries.Server(server.name(), server.info().address().toString(), -1, null));
    }
    return unknown;
  }

  private Summaries.Startup startupFacts(TransportInfo transport, List<Summaries.Server> servers) {
    Runtime runtime = Runtime.getRuntime();
    NativeStatus compression = Natives.compression();
    List<String> plugins = new ArrayList<>();
    int failed = 0;
    for (PluginContainer plugin : pluginManager.plugins()) {
      if (plugin.state() == PluginState.ENABLED) {
        plugins.add(plugin.description().id() + " " + plugin.description().version());
      } else if (plugin.failure().isPresent()) {
        failed++;
      }
    }
    AntiBotService bots = antiBot;
    String antiBotState =
        bots == null || !bots.isEnabled()
            ? "off"
            : (bots.level() == AttackLevel.ATTACK ? "on, under attack" : "on, calm")
                + (bots.isLockdown() ? ", lockdown" : "");
    String managementAddress =
        config.management().enabled() ? address(config.management().bind()) : null;
    return new Summaries.Startup(
        System.getProperty("java.version") + " (" + System.getProperty("java.vendor") + ")",
        runtime.availableProcessors(),
        runtime.maxMemory(),
        transport.name(),
        address(config.bind()),
        config.tcpFastOpen() && transport.tcpFastOpen(),
        compression.available()
            ? compression.detail().split(" from ", 2)[0] + ", native"
            : "java.util.zip (native libdeflate unavailable)",
        compression.available(),
        config.onlineMode(),
        config.forwardingMode().name().toLowerCase(Locale.ROOT),
        config.proxyProtocol().enabled(),
        List.copyOf(servers),
        List.copyOf(plugins),
        failed,
        antiBotState,
        managementAddress);
  }

  private StatusLine.Snapshot statusSnapshot() {
    Runtime runtime = Runtime.getRuntime();
    AntiBotService bots = antiBot;
    long connections = 0;
    boolean attack = false;
    boolean lockdown = false;
    if (bots != null) {
      connections = bots.status().signals().lastSecond().getOrDefault(Signal.CONNECTIONS, 0L);
      attack = bots.level() == AttackLevel.ATTACK;
      lockdown = bots.isLockdown();
    }
    DrainStatus drainState = drain.status();
    String draining =
        drainState.phase() == DrainPhase.NONE
            ? null
            : "draining, " + drainState.players() + " left";
    var servers = serverRegistry.servers();
    return new StatusLine.Snapshot(
        playerRegistry.count(),
        serverChecks.countUp(servers),
        servers.size(),
        connections,
        attack,
        lockdown,
        draining,
        runtime.totalMemory() - runtime.freeMemory(),
        runtime.maxMemory());
  }

  private static String address(InetSocketAddress address) {
    String host = address.getHostString();
    return (host.indexOf(':') >= 0 ? "[" + host + "]" : host) + ":" + address.getPort();
  }

  /** Registers this server as the {@link WarpProvider} singleton. */
  private void registerProvider() {
    WarpProvider.set(this);
  }

  /**
   * Holds the detected I/O transport factory and corresponding channel classes.
   *
   * @param ioFactory the Netty I/O handler factory for event loops
   * @param serverChannelClass the server socket channel class matching the transport
   * @param clientChannelClass the client socket channel class matching the transport
   * @param tcpFastOpen whether the transport supports TCP Fast Open on both the listener and
   *     outgoing connections (epoll only)
   */
  private record TransportInfo(
      String name,
      IoHandlerFactory ioFactory,
      Class<? extends ServerSocketChannel> serverChannelClass,
      Class<? extends SocketChannel> clientChannelClass,
      boolean tcpFastOpen) {}
}
