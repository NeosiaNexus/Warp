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

import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;

import org.jspecify.annotations.Nullable;

/**
 * The banner, and the summaries printed once the proxy is ready and once it stopped: what a person
 * starting Warp wants to see at a glance, in place of a dozen scattered lines.
 */
public final class Summaries {

  /** The width of the rules around a summary. */
  private static final int RULE_WIDTH = 64;

  /** The width of the labels of a summary. */
  private static final int LABEL_WIDTH = 13;

  private Summaries() {}

  /**
   * A backend server as the startup checked it.
   *
   * @param name its name
   * @param address its address, as configured
   * @param latencyMillis how long its status ping took, or -1 when it did not answer
   * @param version the version it reports, or {@code null}
   */
  public record Server(String name, String address, long latencyMillis, @Nullable String version) {

    /** Returns whether it answered. */
    boolean up() {
      return latencyMillis >= 0;
    }
  }

  /**
   * What the startup summary shows.
   *
   * @param java the Java version and vendor
   * @param cores the processors available
   * @param maxHeapBytes the maximum heap
   * @param transport the network transport: epoll, kqueue or NIO
   * @param listener where players connect
   * @param tcpFastOpen whether TCP Fast Open is on
   * @param compression the compression implementation
   * @param nativeCompression whether it is the native one
   * @param onlineMode whether players are authenticated with Mojang
   * @param forwarding how player information reaches the backends
   * @param proxyProtocol whether connections come through the PROXY protocol
   * @param servers the backend servers
   * @param plugins the plugins enabled, as {@code id version}
   * @param failedPlugins how many plugins failed to load or enable
   * @param antiBot the anti-bot's state: off, or its level
   * @param management where the management endpoint listens, or {@code null} when it is off
   */
  public record Startup(
      String java,
      int cores,
      long maxHeapBytes,
      String transport,
      String listener,
      boolean tcpFastOpen,
      String compression,
      boolean nativeCompression,
      boolean onlineMode,
      String forwarding,
      boolean proxyProtocol,
      List<Server> servers,
      List<String> plugins,
      int failedPlugins,
      String antiBot,
      @Nullable String management) {}

  /**
   * What the shutdown summary shows.
   *
   * @param cause why the proxy stopped
   * @param uptime how long it ran
   * @param disconnected how many players it disconnected
   * @param drain how a drain went, such as {@code 40 players moved in 32 s}, or {@code null}
   */
  public record Shutdown(String cause, Duration uptime, int disconnected, @Nullable String drain) {}

  // ---------------------------------------------------------------------------
  // Blocks
  // ---------------------------------------------------------------------------

  /**
   * The banner: a small W, the version and the build.
   *
   * @param version Warp's version
   * @param commit the commit it was built from
   * @param branch the branch it was built from
   * @return the block, whose plain text is {@code Starting Warp <version> (commit: <commit>,
   *     branch: <branch>)}
   */
  public static ConsoleBlock banner(String version, String commit, String branch) {
    return ConsoleBlock.builder(
            "Starting Warp " + version + " (commit: " + commit + ", branch: " + branch + ")")
        .end()
        .text("  ")
        .glyph(Style.ACCENT, g -> g.logoTop().substring(0, 3))
        .glyph(Style.ACCENT_2, g -> g.logoTop().substring(3))
        .text("   ")
        .text(Style.STRONG, "Warp")
        .text(" ")
        .text(Style.ACCENT, version)
        .end()
        .text("  ")
        .glyph(Style.ACCENT, g -> g.logoBottom().substring(0, 3))
        .glyph(Style.ACCENT_2, g -> g.logoBottom().substring(3))
        .text("   ")
        .text(Style.MUTED, commit + " ")
        .glyph(Style.MUTED, Glyphs::dot)
        .text(Style.MUTED, " " + branch)
        .end()
        .build();
  }

  /**
   * The startup summary.
   *
   * @param facts what it shows
   * @return the block
   */
  public static ConsoleBlock startup(Startup facts) {
    ConsoleBlock.Builder block = ConsoleBlock.builder("Startup summary:");
    rule(block);
    row(block, "java")
        .text(facts.java())
        .text(" ")
        .glyph(Style.MUTED, Glyphs::dot)
        .text(" ")
        .text(Style.NUMBER, Integer.toString(facts.cores()))
        .text(facts.cores() == 1 ? " core " : " cores ")
        .glyph(Style.MUTED, Glyphs::dot)
        .text(" ")
        .text(Style.NUMBER, mebibytes(facts.maxHeapBytes()))
        .text(" heap")
        .end();
    row(block, "listening")
        .text(Style.STRONG, facts.listener())
        .text(" ")
        .glyph(Style.MUTED, Glyphs::dot)
        .text(" " + facts.transport() + (facts.tcpFastOpen() ? ", TCP Fast Open" : ""))
        .text(facts.proxyProtocol() ? ", PROXY protocol" : "")
        .end();
    row(block, "players")
        .text(facts.onlineMode() ? "online mode" : "offline mode")
        .text(" ")
        .glyph(Style.MUTED, Glyphs::dot)
        .text(" forwarding " + facts.forwarding())
        .end();
    row(block, "compression")
        .text(facts.nativeCompression() ? Style.GOOD : Style.CAUTION, facts.compression())
        .end();
    servers(block, facts.servers());
    plugins(block, facts.plugins(), facts.failedPlugins());
    row(block, "anti-bot")
        .text(facts.antiBot().startsWith("off") ? Style.MUTED : null, facts.antiBot())
        .end();
    String management = facts.management();
    if (management == null) {
      row(block, "management").text(Style.MUTED, "off").end();
    } else {
      // A wildcard address listens everywhere, but a browser opens it as this machine.
      String local = management.replace("0.0.0.0:", "127.0.0.1:").replace("[::]:", "[::1]:");
      row(block, "management").link(null, management, "http://" + local + "/readyz").end();
    }
    rule(block);
    return block.build();
  }

  /**
   * The shutdown summary.
   *
   * @param facts what it shows
   * @return the block
   */
  public static ConsoleBlock shutdown(Shutdown facts) {
    ConsoleBlock.Builder block = ConsoleBlock.builder("Shutdown summary:");
    rule(block);
    row(block, "cause").text(facts.cause()).end();
    row(block, "uptime").text(Style.NUMBER, duration(facts.uptime())).end();
    row(block, "players")
        .text(Style.NUMBER, Integer.toString(facts.disconnected()))
        .text(" disconnected")
        .end();
    String drain = facts.drain();
    if (drain != null) {
      row(block, "drain").text(drain).end();
    }
    rule(block);
    return block.build();
  }

  private static void servers(ConsoleBlock.Builder block, List<Server> servers) {
    if (servers.isEmpty()) {
      row(block, "servers").text(Style.CAUTION, "none configured").end();
      return;
    }
    long up = servers.stream().filter(Server::up).count();
    row(block, "servers")
        .text(up == servers.size() ? Style.GOOD : up == 0 ? Style.BAD : Style.CAUTION, up + "")
        .text(" of " + servers.size() + " reachable")
        .end();
    int nameWidth = 0;
    int addressWidth = 0;
    for (Server server : servers) {
      nameWidth = Math.max(nameWidth, server.name().length());
      addressWidth = Math.max(addressWidth, server.address().length());
    }
    for (Server server : servers) {
      block.text(" ".repeat(2 + LABEL_WIDTH));
      block.glyph(server.up() ? Style.GOOD : Style.BAD, server.up() ? Glyphs::up : Glyphs::down);
      block.text(" ").text(Style.SERVER, pad(server.name(), nameWidth + 2));
      block.text(Style.MUTED, pad(server.address(), addressWidth + 2));
      if (server.up()) {
        block.text(Style.NUMBER, server.latencyMillis() + " ms");
        String version = server.version();
        if (version != null && !version.isBlank()) {
          block.text("  ").text(Style.MUTED, version);
        }
      } else {
        block.text(Style.BAD, "unreachable");
      }
      block.end();
    }
  }

  private static void plugins(ConsoleBlock.Builder block, List<String> plugins, int failed) {
    ConsoleBlock.Builder row = row(block, "plugins");
    if (plugins.isEmpty()) {
      row.text(Style.MUTED, "none");
    } else {
      row.text(String.join(", ", plugins));
    }
    if (failed > 0) {
      row.text(" ").text(Style.BAD, "(" + failed + " failed)");
    }
    row.end();
  }

  private static ConsoleBlock.Builder row(ConsoleBlock.Builder block, String label) {
    return block.text("  ").text(Style.LABEL, pad(label, LABEL_WIDTH));
  }

  private static void rule(ConsoleBlock.Builder block) {
    Function<Glyphs, String> rule = g -> g.rule().repeat(RULE_WIDTH);
    block.text("  ").glyph(Style.MUTED, rule).end();
  }

  private static String pad(String text, int width) {
    return text.length() >= width ? text + " " : text + " ".repeat(width - text.length());
  }

  /**
   * Writes a size in mebibytes: {@code 512 MiB}.
   *
   * @param bytes the size
   * @return the text
   */
  static String mebibytes(long bytes) {
    return (bytes >> 20) + " MiB";
  }

  /**
   * Writes a duration for people: {@code 42 s}, {@code 3 min 12 s}, {@code 2 h 5 min}, {@code 3 d 4
   * h}.
   *
   * @param duration the duration
   * @return the text
   */
  static String duration(Duration duration) {
    long seconds = Math.max(0, duration.toSeconds());
    if (seconds < 60) {
      return seconds + " s";
    }
    if (seconds < 3600) {
      return String.format(Locale.ROOT, "%d min %d s", seconds / 60, seconds % 60);
    }
    if (seconds < 86_400) {
      return String.format(Locale.ROOT, "%d h %d min", seconds / 3600, seconds % 3600 / 60);
    }
    return String.format(Locale.ROOT, "%d d %d h", seconds / 86_400, seconds % 86_400 / 3600);
  }
}
