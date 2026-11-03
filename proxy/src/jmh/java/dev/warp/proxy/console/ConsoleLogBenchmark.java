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
package dev.warp.proxy.console;

import java.io.OutputStream;
import java.io.PrintStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.ZoneId;
import java.util.concurrent.TimeUnit;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Configuration;
import org.apache.logging.log4j.core.config.Configurator;
import org.apache.logging.log4j.core.config.Property;
import org.apache.logging.log4j.core.config.builder.api.ConfigurationBuilder;
import org.apache.logging.log4j.core.config.builder.api.ConfigurationBuilderFactory;
import org.apache.logging.log4j.core.config.builder.impl.BuiltConfiguration;
import org.apache.logging.log4j.core.impl.Log4jLogEvent;
import org.apache.logging.log4j.message.ParameterizedMessage;
import org.jspecify.annotations.Nullable;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;

/**
 * What a log line costs with the console.
 *
 * <ul>
 *   <li>{@code logCall}: the thread that logs (a Netty event loop) through an asynchronous logger
 *       whose only appender is the console, writing to nowhere, as fast as it can: Log4j's thread
 *       cannot keep up, its queue fills, and the call pays for the queue being full.
 *   <li>{@code logCallPaced} minus {@code pacingOnly}: the same call when Log4j's thread keeps up,
 *       as it does in a running proxy. What the event loop pays.
 *   <li>{@code formatColoured}, {@code formatPlain}: formatting one line, on Log4j's thread. How
 *       many lines a second the console can take before it starts dropping DEBUG lines.
 * </ul>
 *
 * <pre>
 * ./gradlew :proxy:jmh -Pjmh.includes=ConsoleLogBenchmark
 * </pre>
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 3, time = 2)
@Measurement(iterations = 5, time = 2)
@Fork(
    value = 1,
    jvmArgsAppend = {"-Xms1g", "-Xmx1g", "--enable-native-access=ALL-UNNAMED"})
@State(Scope.Benchmark)
public class ConsoleLogBenchmark {

  /** About two microseconds of work between two log calls. */
  private static final long PACING_TOKENS = 1000;

  private static final NameDirectory NAMES =
      new NameDirectory() {
        @Override
        public boolean isPlayer(String name) {
          return name.equals("Steve");
        }

        @Override
        public boolean isServer(String name) {
          return name.equals("survival");
        }
      };

  private LineFormatter coloured;
  private LineFormatter plain;
  private LogEvent event;
  private ConsoleOutput output;
  private LoggerContext context;
  private Logger logger;
  private InetSocketAddress address;

  /** Builds the formatters, and a logging setup whose console writes to nowhere. */
  @Setup
  public void setUp() {
    coloured =
        new LineFormatter(
            new ConsoleMode(ColorDepth.TRUE_COLOR, false, false, null, true, null),
            ZoneId.systemDefault());
    plain = new LineFormatter(ConsoleMode.PLAIN, ZoneId.systemDefault());
    address = new InetSocketAddress(InetAddress.getLoopbackAddress(), 52_311);
    event =
        Log4jLogEvent.newBuilder()
            .setLoggerName("dev.warp.proxy.connection.ConnectedPlayer")
            .setLevel(Level.INFO)
            .setMessage(
                new ParameterizedMessage(
                    "{} switched to {} from {} in {} ms", "Steve", "survival", address, 42))
            .setTimeMillis(System.currentTimeMillis())
            .build();

    output =
        new ConsoleOutput(
            ConsoleMode.PLAIN,
            null,
            new PrintStream(OutputStream.nullOutputStream(), false, StandardCharsets.UTF_8),
            new ConsoleBuffer(ConsoleOutput.CAPACITY, ConsoleOutput.RESERVE));
    output.start();
    ConfigurationBuilder<BuiltConfiguration> builder =
        ConfigurationBuilderFactory.newConfigurationBuilder();
    builder.add(builder.newAsyncLogger("bench", Level.INFO).addAttribute("additivity", false));
    builder.add(builder.newRootLogger(Level.WARN));
    context = Configurator.initialize(builder.build());
    Configuration configuration = context.getConfiguration();
    AbstractAppender console =
        new AbstractAppender("console", null, null, true, Property.EMPTY_ARRAY) {
          @Override
          public void append(LogEvent logEvent) {
            output.submit(logEvent);
          }
        };
    console.start();
    configuration.addAppender(console);
    configuration.getLoggerConfig("bench").addAppender(console, null, null);
    context.updateLoggers();
    logger = context.getLogger("bench");
  }

  /** Stops the logging setup. */
  @TearDown
  public void tearDown() {
    Configurator.shutdown(context);
    output.close();
  }

  /** One log call, as a Netty event loop makes it. */
  @Benchmark
  public void logCall() {
    logger.info("{} switched to {} from {} in {} ms", "Steve", "survival", address, 42);
  }

  /**
   * One log call followed by some work, so that Log4j's thread keeps up as it does in a running
   * proxy (which logs far less than once every few microseconds): minus {@link #pacingOnly}, what a
   * log call costs the event loop when nothing is saturated.
   */
  @Benchmark
  public void logCallPaced() {
    logger.info("{} switched to {} from {} in {} ms", "Steve", "survival", address, 42);
    Blackhole.consumeCPU(PACING_TOKENS);
  }

  /** The work of {@link #logCallPaced} alone. */
  @Benchmark
  public void pacingOnly() {
    Blackhole.consumeCPU(PACING_TOKENS);
  }

  /**
   * Formats one line in 24-bit colour, values highlighted.
   *
   * @return the line
   */
  @Benchmark
  public @Nullable Object formatColoured() {
    return coloured.format(event, NAMES);
  }

  /**
   * Formats one line in plain text.
   *
   * @return the line
   */
  @Benchmark
  public @Nullable Object formatPlain() {
    return plain.format(event, NAMES);
  }
}
