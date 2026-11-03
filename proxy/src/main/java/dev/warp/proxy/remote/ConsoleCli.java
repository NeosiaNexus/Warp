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
package dev.warp.proxy.remote;

import dev.warp.proxy.console.ConsoleMode;

import java.io.IOException;
import java.io.PrintStream;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.security.GeneralSecurityException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.UnaryOperator;

import javax.net.ssl.SSLContext;

import com.google.gson.JsonElement;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.ansi.ANSIComponentSerializer;
import net.kyori.adventure.text.serializer.gson.GsonComponentSerializer;
import org.jspecify.annotations.Nullable;

/**
 * {@code java -jar warp.jar console ...}: the client of the remote console, in the proxy's own jar.
 *
 * <ul>
 *   <li>{@code console attach}: the live log of one proxy or several, and the prompt of the one
 *       chosen for commands
 *   <li>{@code console run}: runs one command, prints its answer and exits, for scripts
 * </ul>
 *
 * <p>The token comes from a file or from {@code WARP_CONSOLE_TOKEN}, never from the command line,
 * where {@code ps} and the shell's history would show it.
 */
public final class ConsoleCli {

  /** The variable that holds the token when no {@code --token-file} is given. */
  public static final String ENV_TOKEN = "WARP_CONSOLE_TOKEN";

  /** The client's Log4j configuration: the console, without the proxy's log files. */
  static final String LOG_CONFIGURATION = "warp-console-client.xml";

  /** Exit codes. */
  static final int OK = 0;

  static final int COMMAND_FAILED = 1;
  static final int USAGE = 2;
  static final int REFUSED = 3;

  private static final String USAGE_TEXT =
      """
      Usage:
        java -jar warp.jar console attach --url URL [--url URL ...] [options]
        java -jar warp.jar console run --url URL [options] [--] COMMAND ...

      The remote console of Warp (management.console in warp.conf): the live log of one proxy or
      several, and commands, where no terminal is at hand (Kubernetes, panels, several proxies).

        --url [NAME=]URL          a proxy's management server, such as https://eu-1.example:9901;
                                  repeat it for several, NAME prefixing their lines
        --token-file [NAME=]FILE  the file holding the token (default: the WARP_CONSOLE_TOKEN
                                  variable); NAME= gives one proxy a token of its own
        --commands NAME           with several proxies, the one that runs what is typed (by
                                  default they are all read-only)
        --read-only               never run commands, even with one proxy
        --level LEVEL             the least level shown: trace, debug, info, warn or error
        --tags TAGS               tags to show, or to hide with a dash: proxy,plugin:*,-netty
        --grep TEXT               only the lines that contain TEXT
        --replay N                lines replayed when attaching (default 100)
        --ca-file FILE            trust the certificates of this PEM file (a private authority, a
                                  self-signed certificate)

      Ctrl+C or Ctrl+D leaves; the proxies keep running.
      """;

  private ConsoleCli() {}

  /** A mistake on the command line, its message telling what to type instead. */
  static final class UsageException extends Exception {

    private static final long serialVersionUID = 1L;

    UsageException(String message) {
      super(message);
    }
  }

  /**
   * What the command line asks for.
   *
   * @param subcommand {@code attach} or {@code run}
   * @param targets the proxies, with their tokens
   * @param commands the proxy that runs commands, or {@code null}
   * @param query the filter and the replay of the stream
   * @param caFile the trusted certificates, or {@code null}
   * @param command the command of {@code run}
   */
  record Options(
      String subcommand,
      List<ConsoleTarget> targets,
      @Nullable String commands,
      Map<String, String> query,
      @Nullable Path caFile,
      String command) {}

  // ---------------------------------------------------------------------------
  // Entry point
  // ---------------------------------------------------------------------------

  /**
   * Runs {@code console ...}; called by the proxy's {@code main} before its logging starts.
   *
   * @param args the arguments after {@code console}
   * @return the exit code: 0, 1 for a command that failed, 2 for a usage mistake, 3 when a proxy
   *     refused the token or cannot be reached
   */
  public static int run(String[] args) {
    if (System.getProperty("log4j2.configurationFile") == null) {
      System.setProperty("log4j2.configurationFile", LOG_CONFIGURATION);
    }
    PrintStream err = System.err;
    List<String> list = List.of(args);
    if (list.isEmpty() || list.contains("--help") || list.contains("-h")) {
      (list.isEmpty() ? err : System.out).print(USAGE_TEXT);
      return list.isEmpty() ? USAGE : OK;
    }
    Options options;
    try {
      options = parse(list, System::getenv, Path.of("."));
    } catch (UsageException e) {
      err.println("warp console: " + e.getMessage());
      err.println("Try java -jar warp.jar console --help");
      return USAGE;
    }
    SSLContext ssl;
    try {
      ssl = ConsoleHttp.ssl(options.caFile());
    } catch (IOException | GeneralSecurityException e) {
      err.println(
          "warp console: cannot read --ca-file " + options.caFile() + ": " + e.getMessage());
      return USAGE;
    }
    String session = HexFormat.of().toHexDigits(ThreadLocalRandom.current().nextLong());
    String operator = operator();
    List<ConsoleHttp> targets = new ArrayList<>();
    for (ConsoleTarget target : options.targets()) {
      targets.add(new ConsoleHttp(target, ssl, session, operator));
    }
    try {
      if (options.subcommand().equals("run")) {
        return runOnce(targets.getFirst(), options.command());
      }
      ConsoleHttp commandTarget = null;
      for (ConsoleHttp target : targets) {
        if (target.target().name().equals(options.commands())) {
          commandTarget = target;
        }
      }
      Path history = Path.of(System.getProperty("user.home"), ".warp", "console-history");
      return new AttachSession(targets, commandTarget, options.query(), session, history).run();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return OK;
    } finally {
      org.apache.logging.log4j.LogManager.shutdown();
    }
  }

  /** {@code console run}: one command, its answer on standard output. */
  private static int runOnce(ConsoleHttp target, String command) throws InterruptedException {
    ConsoleHttp.Answer answer;
    try {
      answer = target.command(command);
    } catch (IOException e) {
      System.err.println(
          "warp console: " + target.target() + " cannot be reached: " + e.getMessage());
      return REFUSED;
    }
    if (answer.status() != 200) {
      System.err.println("warp console: " + target.target().name() + ": " + answer.error());
      return answer.status() == 400 ? USAGE : REFUSED;
    }
    boolean colors = ConsoleMode.detect().colors().colored();
    JsonElement output = answer.body().get("output");
    if (output != null && output.isJsonArray()) {
      for (JsonElement line : output.getAsJsonArray()) {
        System.out.println(render(line, colors));
      }
    }
    JsonElement error = answer.body().get("error");
    if (error != null && error.isJsonPrimitive()) {
      System.err.println("warp console: " + error.getAsString());
      return COMMAND_FAILED;
    }
    JsonElement found = answer.body().get("found");
    return found != null && found.isJsonPrimitive() && !found.getAsBoolean() ? COMMAND_FAILED : OK;
  }

  private static String render(JsonElement line, boolean colors) {
    if (!line.isJsonObject()) {
      return "";
    }
    JsonElement component = line.getAsJsonObject().get("component");
    if (colors && component != null && component.isJsonObject()) {
      try {
        Component parsed = GsonComponentSerializer.gson().deserializeFromTree(component);
        return ANSIComponentSerializer.ansi().serialize(parsed);
      } catch (RuntimeException e) {
        // Its text, then.
      }
    }
    JsonElement text = line.getAsJsonObject().get("text");
    return text != null && text.isJsonPrimitive() ? text.getAsString() : "";
  }

  // ---------------------------------------------------------------------------
  // Arguments
  // ---------------------------------------------------------------------------

  /**
   * Reads the command line.
   *
   * @param args the arguments after {@code console}
   * @param env reads an environment variable
   * @param directory where relative files resolve
   * @return the options
   * @throws UsageException if the arguments are invalid
   */
  static Options parse(List<String> args, UnaryOperator<@Nullable String> env, Path directory)
      throws UsageException {
    String subcommand = args.getFirst().toLowerCase(Locale.ROOT);
    if (!subcommand.equals("attach") && !subcommand.equals("run")) {
      throw new UsageException("unknown subcommand " + args.getFirst() + "; use attach or run");
    }
    List<String> urls = new ArrayList<>();
    Map<String, String> tokenFiles = new HashMap<>();
    String defaultTokenFile = null;
    String commands = null;
    boolean readOnly = false;
    Map<String, String> query = new LinkedHashMap<>();
    Path caFile = null;
    List<String> words = new ArrayList<>();
    for (int i = 1; i < args.size(); i++) {
      String arg = args.get(i);
      if (arg.equals("--")) {
        words.addAll(args.subList(i + 1, args.size()));
        break;
      }
      if (!arg.startsWith("--")) {
        words.add(arg);
        continue;
      }
      String name = arg;
      String value = null;
      int equals = arg.indexOf('=');
      if (equals > 0) {
        name = arg.substring(0, equals);
        value = arg.substring(equals + 1);
      }
      if (name.equals("--read-only")) {
        readOnly = true;
        continue;
      }
      if (name.equals("--token")) {
        throw new UsageException(
            "--token would show the token to ps and the shell's history: write it in a file for"
                + " --token-file, or set "
                + ENV_TOKEN);
      }
      if (value == null) {
        if (i + 1 >= args.size()) {
          throw new UsageException(name + " needs a value");
        }
        value = args.get(++i);
      }
      switch (name) {
        case "--url" -> urls.add(value);
        case "--token-file" -> {
          int named = value.indexOf('=');
          if (named > 0) {
            tokenFiles.put(value.substring(0, named), value.substring(named + 1));
          } else {
            defaultTokenFile = value;
          }
        }
        case "--commands" -> commands = value;
        case "--level", "--tags", "--grep", "--replay" -> query.put(name.substring(2), value);
        case "--ca-file" -> caFile = directory.resolve(value);
        default -> throw new UsageException("unknown option " + name);
      }
    }
    if (urls.isEmpty()) {
      throw new UsageException(
          "give the proxy's address with --url, such as --url http://127.0.0.1:9901");
    }
    if (subcommand.equals("run")) {
      if (urls.size() > 1) {
        throw new UsageException("console run runs a command on one proxy: give one --url");
      }
      if (words.isEmpty()) {
        throw new UsageException(
            "console run needs the command to run, such as: run --url ... glist");
      }
    } else if (!words.isEmpty()) {
      throw new UsageException(
          "unexpected " + words.getFirst() + "; console attach takes options only");
    }
    String envToken = env.apply(ENV_TOKEN);
    List<ConsoleTarget> targets = new ArrayList<>();
    for (String url : urls) {
      ConsoleTarget target;
      try {
        target = ConsoleTarget.parse(url, "");
      } catch (IllegalArgumentException e) {
        throw new UsageException(String.valueOf(e.getMessage()));
      }
      target = target.named(unique(target.name(), targets));
      String file = tokenFiles.getOrDefault(target.name(), defaultTokenFile);
      String token;
      if (file != null) {
        token = readToken(directory.resolve(file));
      } else if (envToken != null && !envToken.isBlank()) {
        token = envToken.strip();
      } else {
        throw new UsageException(
            "no token for " + target.name() + ": give --token-file FILE, or set " + ENV_TOKEN);
      }
      targets.add(new ConsoleTarget(target.name(), target.base(), token));
    }
    for (String named : tokenFiles.keySet()) {
      if (targets.stream().noneMatch(target -> target.name().equals(named))) {
        throw new UsageException("--token-file " + named + "=... names no --url");
      }
    }
    if (commands != null) {
      String chosen = commands;
      if (targets.stream().noneMatch(target -> target.name().equals(chosen))) {
        throw new UsageException(
            "--commands " + chosen + " names no --url; the names are " + names(targets));
      }
      if (readOnly) {
        throw new UsageException("--commands and --read-only contradict each other");
      }
    } else if (targets.size() == 1 && !readOnly) {
      commands = targets.getFirst().name();
    }
    return new Options(subcommand, targets, commands, query, caFile, String.join(" ", words));
  }

  private static String unique(String name, List<ConsoleTarget> taken) {
    String candidate = name;
    for (int n = 2; names(taken).contains(candidate); n++) {
      candidate = name + "-" + n;
    }
    return candidate;
  }

  private static List<String> names(List<ConsoleTarget> targets) {
    return targets.stream().map(ConsoleTarget::name).toList();
  }

  private static String readToken(Path file) throws UsageException {
    String token;
    try {
      token = Files.readString(file, StandardCharsets.UTF_8).strip();
    } catch (IOException e) {
      throw new UsageException("cannot read the token file " + file + ": " + e.getMessage());
    }
    if (token.isEmpty()) {
      throw new UsageException("the token file " + file + " is empty");
    }
    try {
      Set<PosixFilePermission> permissions = Files.getPosixFilePermissions(file);
      if (permissions.contains(PosixFilePermission.OTHERS_READ)) {
        System.err.println(
            "warp console: warning: " + file + " is readable by every user; chmod 600 it");
      }
    } catch (IOException | UnsupportedOperationException e) {
      // Not a POSIX file system: nothing to check.
    }
    return token;
  }

  /** Who sits at this client, as the proxy's audit log shows it: user@host. */
  private static String operator() {
    String user = System.getProperty("user.name", "unknown");
    String host = System.getenv("HOSTNAME");
    if (host == null || host.isBlank()) {
      host = System.getenv("COMPUTERNAME");
    }
    if (host == null || host.isBlank()) {
      try {
        host = InetAddress.getLocalHost().getHostName();
      } catch (IOException e) {
        host = "unknown";
      }
    }
    return user + "@" + host;
  }
}
