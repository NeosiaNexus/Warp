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

import java.io.IOException;
import java.io.PrintStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.time.Duration;
import java.util.Iterator;
import java.util.List;
import java.util.function.UnaryOperator;

import org.jspecify.annotations.Nullable;

/**
 * The management server's command-line clients, for containers and probes: {@code health} asks a
 * probe endpoint and exits with its answer, {@code drain} starts the drain. Both run in a fresh JVM
 * without a shell or curl in the image: a Docker {@code HEALTHCHECK}, a Kubernetes {@code preStop}
 * hook, a {@code docker exec} before a restart.
 *
 * <ul>
 *   <li>{@code health [--url URL]}: GET the URL ({@code http://127.0.0.1:9901/readyz} by default),
 *       print the answer and exit 0 on 2xx, 1 otherwise.
 *   <li>{@code drain [--url URL] [--wait] [--token-file FILE]}: POST {@code /drain} of the
 *       management server (the base URL, {@code http://127.0.0.1:9901} by default) with the token
 *       of {@code --token-file} or {@code WARP_MANAGEMENT_TOKEN}; with {@code --wait}, return once
 *       every player left or the drain timed out.
 * </ul>
 */
public final class ManagementCli {

  /** The variable that holds the drain token when no {@code --token-file} is given. */
  static final String ENV_TOKEN = "WARP_MANAGEMENT_TOKEN";

  static final int OK = 0;
  static final int FAILED = 1;
  static final int USAGE = 2;
  static final int UNREACHABLE = 3;

  static final URI DEFAULT_BASE = URI.create("http://127.0.0.1:9901");
  static final String DEFAULT_HEALTH_PATH = "/readyz";

  private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
  private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(10);

  /** A drain can last up to {@code drain.timeout}: half an hour by default, with a margin. */
  private static final Duration WAIT_TIMEOUT = Duration.ofHours(24);

  static final String USAGE_TEXT =
      """
      Usage:
        java -jar warp.jar health [--url URL] [--ca-file FILE] [--timeout SECONDS]
        java -jar warp.jar drain [--url URL] [--wait] [--token-file FILE] [--ca-file FILE]

      health asks a probe of the management server (management.enabled = true in warp.conf) and
      exits 0 when it answers 2xx, 1 otherwise: a Docker HEALTHCHECK, or a check from a script.
      drain starts the drain (POST /drain), so that players are transferred and the proxy stops
      once empty: a Kubernetes preStop hook, or `docker exec` before a restart.

      Options:
        --url URL           health: the probe, default http://127.0.0.1:9901/readyz (also /livez,
                            /startupz); drain: the management server, default http://127.0.0.1:9901
        --wait              drain: return only once every player left or the drain timed out
        --token-file FILE   drain: the file holding the drain token (default: the
                            WARP_MANAGEMENT_TOKEN variable)
        --ca-file FILE      PEM certificates to trust for https (a self-signed certificate)
        --timeout SECONDS   health: give up after this long (default 10)

      Exit codes: 0 ok, 1 the proxy answered no (not ready, drain refused), 2 usage, 3 unreachable.
      """;

  private ManagementCli() {}

  // ---------------------------------------------------------------------------
  // Entry point
  // ---------------------------------------------------------------------------

  /**
   * Runs {@code health ...} or {@code drain ...}; called by the proxy's {@code main} before its
   * logging starts.
   *
   * @param command {@code health} or {@code drain}
   * @param args the arguments after it
   * @return the exit code: 0, 1 when the proxy answered no, 2 for a usage mistake, 3 when it cannot
   *     be reached
   */
  public static int run(String command, String[] args) {
    PrintStream out = System.out;
    PrintStream err = System.err;
    List<String> list = List.of(args);
    if (list.contains("--help") || list.contains("-h")) {
      out.print(USAGE_TEXT);
      return OK;
    }
    Options options;
    try {
      options = parse(command, list, System::getenv, Path.of("."));
    } catch (UsageException e) {
      err.println("warp " + command + ": " + e.getMessage());
      err.print(USAGE_TEXT);
      return USAGE;
    }
    return execute(options, out, err);
  }

  static int execute(Options options, PrintStream out, PrintStream err) {
    String name = options.drain() ? "drain" : "health";
    HttpClient client;
    try {
      client =
          HttpClient.newBuilder()
              .version(HttpClient.Version.HTTP_1_1)
              .connectTimeout(CONNECT_TIMEOUT)
              .followRedirects(HttpClient.Redirect.NEVER)
              .sslContext(ConsoleHttp.ssl(options.caFile()))
              .build();
    } catch (IOException | GeneralSecurityException e) {
      err.println("warp " + name + ": cannot read " + options.caFile() + ": " + e.getMessage());
      return USAGE;
    }
    HttpRequest.Builder request =
        HttpRequest.newBuilder(options.uri()).timeout(options.timeout()).header("Accept", "*/*");
    if (options.drain()) {
      request
          .POST(HttpRequest.BodyPublishers.noBody())
          .header("Authorization", "Bearer " + options.token());
    } else {
      request.GET();
    }
    HttpResponse<String> response;
    try (client) {
      response = client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    } catch (IOException e) {
      err.println(
          "warp "
              + name
              + ": "
              + ConsoleHttp.describe(options.uri())
              + " cannot be reached: "
              + ConsoleHttp.explain(e));
      return UNREACHABLE;
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return UNREACHABLE;
    }
    String body = response.body().strip();
    boolean ok = response.statusCode() / 100 == 2;
    (ok ? out : err)
        .println(
            options.uri().getPath()
                + ": "
                + response.statusCode()
                + (body.isEmpty() ? "" : " " + body));
    if (!ok && (response.statusCode() == 401 || response.statusCode() == 403)) {
      err.println(
          "warp drain: the token was refused; set management.token-file in warp.conf and give the"
              + " same token with --token-file or "
              + ENV_TOKEN);
    }
    return ok ? OK : FAILED;
  }

  // ---------------------------------------------------------------------------
  // Arguments
  // ---------------------------------------------------------------------------

  /** A usage mistake, reported with the usage text. */
  static final class UsageException extends Exception {
    private static final long serialVersionUID = 1L;

    UsageException(String message) {
      super(message);
    }
  }

  /**
   * What a run does.
   *
   * @param drain {@code true} for {@code drain}, {@code false} for {@code health}
   * @param uri the URL requested
   * @param token the drain token, empty for {@code health}
   * @param caFile the PEM file to trust, or {@code null} for the JDK's authorities
   * @param timeout how long to wait for the answer
   */
  record Options(boolean drain, URI uri, String token, @Nullable Path caFile, Duration timeout) {}

  /**
   * Parses the arguments of {@code health} or {@code drain}.
   *
   * @param command {@code health} or {@code drain}
   * @param args the arguments after it
   * @param env the environment
   * @param directory where relative files are resolved
   * @return the options
   * @throws UsageException if the arguments are invalid
   */
  static Options parse(
      String command, List<String> args, UnaryOperator<@Nullable String> env, Path directory)
      throws UsageException {
    boolean drain =
        switch (command) {
          case "drain" -> true;
          case "health" -> false;
          default -> throw new UsageException("unknown command " + command);
        };
    @Nullable String url = null;
    @Nullable Path tokenFile = null;
    @Nullable Path caFile = null;
    boolean wait = false;
    Duration timeout = DEFAULT_TIMEOUT;
    Iterator<String> remaining = args.iterator();
    while (remaining.hasNext()) {
      String name = remaining.next();
      if (name.equals("--wait")) {
        if (!drain) {
          throw new UsageException("--wait is for drain");
        }
        wait = true;
        continue;
      }
      if (name.equals("--token")) {
        throw new UsageException(
            "--token would show the token to ps and the shell's history: write it in a file for"
                + " --token-file, or set "
                + ENV_TOKEN);
      }
      if (!name.startsWith("--")) {
        throw new UsageException("unexpected argument " + name);
      }
      if (!remaining.hasNext()) {
        throw new UsageException(name + " needs a value");
      }
      String value = remaining.next();
      switch (name) {
        case "--url" -> url = value;
        case "--token-file" -> tokenFile = directory.resolve(value);
        case "--ca-file" -> caFile = directory.resolve(value);
        case "--timeout" -> timeout = seconds(value);
        default -> throw new UsageException("unknown option " + name);
      }
    }
    URI uri = uri(url, drain, wait);
    String token = "";
    if (drain) {
      token = token(tokenFile, env);
    } else if (tokenFile != null) {
      throw new UsageException("--token-file is for drain");
    }
    return new Options(drain, uri, token, caFile, drain && wait ? WAIT_TIMEOUT : timeout);
  }

  private static URI uri(@Nullable String url, boolean drain, boolean wait) throws UsageException {
    URI base;
    try {
      base = url == null ? DEFAULT_BASE : new URI(url);
    } catch (URISyntaxException e) {
      throw new UsageException("--url is not a URL: " + url);
    }
    String scheme = base.getScheme();
    if (scheme == null || !(scheme.equals("http") || scheme.equals("https"))) {
      throw new UsageException("--url must start with http:// or https://: " + url);
    }
    if (base.getHost() == null) {
      throw new UsageException("--url has no host: " + url);
    }
    if (!drain) {
      // health: the path given, or the readiness probe.
      if (base.getRawPath() == null
          || base.getRawPath().isEmpty()
          || base.getRawPath().equals("/")) {
        return base.resolve(DEFAULT_HEALTH_PATH);
      }
      return base;
    }
    // drain: the base of the management server, or /drain itself.
    String path = base.getRawPath() == null ? "" : base.getRawPath();
    if (path.endsWith("/")) {
      path = path.substring(0, path.length() - 1);
    }
    if (!path.endsWith("/drain")) {
      path = path + "/drain";
    }
    try {
      return new URI(
          base.getScheme(), base.getRawAuthority(), path, wait ? "wait=true" : null, null);
    } catch (URISyntaxException e) {
      throw new UsageException("--url is not a URL: " + url);
    }
  }

  private static String token(@Nullable Path tokenFile, UnaryOperator<@Nullable String> env)
      throws UsageException {
    if (tokenFile != null) {
      try {
        String token = Files.readString(tokenFile, StandardCharsets.UTF_8).strip();
        if (token.isEmpty()) {
          throw new UsageException(tokenFile + " is empty");
        }
        return token;
      } catch (IOException e) {
        throw new UsageException("cannot read " + tokenFile + ": " + e.getMessage());
      }
    }
    @Nullable String token = env.apply(ENV_TOKEN);
    if (token == null || token.isBlank()) {
      throw new UsageException(
          "give the drain token with --token-file, or set " + ENV_TOKEN + " in the environment");
    }
    return token.strip();
  }

  private static Duration seconds(String value) throws UsageException {
    try {
      long seconds = Long.parseLong(value);
      if (seconds <= 0) {
        throw new UsageException("--timeout must be positive: " + value);
      }
      return Duration.ofSeconds(seconds);
    } catch (NumberFormatException e) {
      throw new UsageException("--timeout takes a number of seconds: " + value);
    }
  }
}
