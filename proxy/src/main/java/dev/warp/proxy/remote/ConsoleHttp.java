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

import dev.warp.proxy.WarpBuildInfo;
import dev.warp.proxy.management.console.RemoteConsole;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.Socket;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.Map;
import java.util.StringJoiner;
import java.util.concurrent.CompletableFuture;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509ExtendedTrustManager;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import org.jspecify.annotations.Nullable;

/**
 * The HTTP side of one proxy's remote console: the stream, commands, completions and the command
 * tree, each request with the token, the client's session and the operator's name. The token never
 * appears in an address, where proxies and logs would keep it.
 */
final class ConsoleHttp {

  private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
  private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(30);

  /**
   * An answer whose body is JSON.
   *
   * @param status the HTTP status
   * @param body the body, or an empty object
   */
  record Answer(int status, JsonObject body) {

    /**
     * Returns the server's error message, or a description of the status.
     *
     * @return the message
     */
    String error() {
      JsonElement error = body.get("error");
      return error != null && error.isJsonPrimitive() ? error.getAsString() : "HTTP " + status;
    }
  }

  private final ConsoleTarget target;
  private final HttpClient client;
  private final String session;
  private final String operator;

  /**
   * Creates the client of a proxy.
   *
   * @param target the proxy
   * @param ssl the TLS context, with the trusted certificates
   * @param session this client's session, which the server tags its own output with
   * @param operator who sits at this client, for the server's audit log
   */
  ConsoleHttp(ConsoleTarget target, SSLContext ssl, String session, String operator) {
    this.target = target;
    this.session = session;
    this.operator = operator;
    this.client =
        HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(CONNECT_TIMEOUT)
            .followRedirects(HttpClient.Redirect.NEVER)
            .sslContext(ssl)
            .build();
  }

  /**
   * Returns the proxy.
   *
   * @return the target
   */
  ConsoleTarget target() {
    return target;
  }

  /**
   * Opens the log stream.
   *
   * @param query the filter and replay, as query parameters
   * @param lastEventId the last event received, to resume after it, or {@code null}
   * @return the response, whose body the caller closes
   * @throws IOException if the proxy cannot be reached
   * @throws InterruptedException if interrupted
   */
  HttpResponse<InputStream> stream(Map<String, String> query, @Nullable String lastEventId)
      throws IOException, InterruptedException {
    HttpRequest.Builder request =
        request("/console/logs" + query(query)).header("Accept", "text/event-stream").GET();
    if (lastEventId != null) {
      request.header("Last-Event-ID", lastEventId);
    }
    return client.send(request.build(), HttpResponse.BodyHandlers.ofInputStream());
  }

  /**
   * Runs a command.
   *
   * @param line the command line
   * @return the answer
   * @throws IOException if the proxy cannot be reached
   * @throws InterruptedException if interrupted
   */
  Answer command(String line) throws IOException, InterruptedException {
    JsonObject body = new JsonObject();
    body.addProperty("command", line);
    return answer(
        client.send(post("/console/command", body), HttpResponse.BodyHandlers.ofString()));
  }

  /**
   * Completes a line.
   *
   * @param line the line up to the cursor
   * @return the answer, once it arrives
   */
  CompletableFuture<Answer> suggest(String line) {
    JsonObject body = new JsonObject();
    body.addProperty("line", line);
    return client
        .sendAsync(post("/console/suggest", body), HttpResponse.BodyHandlers.ofString())
        .thenApply(ConsoleHttp::answer);
  }

  /**
   * Reads the commands the token may run.
   *
   * @return the answer
   * @throws IOException if the proxy cannot be reached
   * @throws InterruptedException if interrupted
   */
  Answer commands() throws IOException, InterruptedException {
    return answer(
        client.send(
            request("/console/commands").GET().build(), HttpResponse.BodyHandlers.ofString()));
  }

  private HttpRequest post(String path, JsonObject body) {
    return request(path)
        .header("Content-Type", "application/json; charset=utf-8")
        .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8))
        .build();
  }

  private HttpRequest.Builder request(String path) {
    HttpRequest.Builder builder =
        HttpRequest.newBuilder(target.resolve(path))
            .header("Authorization", "Bearer " + target.token())
            .header("User-Agent", "warp-console/" + WarpBuildInfo.VERSION)
            .header(RemoteConsole.SESSION_HEADER, session)
            .header(RemoteConsole.OPERATOR_HEADER, operator);
    if (!path.startsWith("/console/logs")) {
      builder.timeout(REQUEST_TIMEOUT);
    }
    return builder;
  }

  private static Answer answer(HttpResponse<String> response) {
    JsonObject body = new JsonObject();
    try {
      JsonElement json = JsonParser.parseString(response.body());
      if (json.isJsonObject()) {
        body = json.getAsJsonObject();
      }
    } catch (JsonParseException e) {
      // Not JSON (a reverse proxy's page): the status says enough.
    }
    return new Answer(response.statusCode(), body);
  }

  private static String query(Map<String, String> parameters) {
    StringJoiner query = new StringJoiner("&", "?", "");
    query.setEmptyValue("");
    parameters.forEach(
        (name, value) -> query.add(name + "=" + URLEncoder.encode(value, StandardCharsets.UTF_8)));
    return query.toString();
  }

  /**
   * Creates the TLS context: the JDK's trusted authorities, or only those of a PEM file (a private
   * authority, or a self-signed certificate). The certificate must name the host connected to,
   * except over loopback (see {@link #loopback(String)}).
   *
   * @param caFile the PEM file, or {@code null}
   * @return the context
   * @throws IOException if the file cannot be read
   * @throws GeneralSecurityException if it holds no valid certificate
   */
  static SSLContext ssl(@Nullable Path caFile) throws IOException, GeneralSecurityException {
    KeyStore trusted = null;
    if (caFile != null) {
      trusted = KeyStore.getInstance(KeyStore.getDefaultType());
      trusted.load(null, null);
      try (InputStream in = Files.newInputStream(caFile)) {
        int index = 0;
        for (Certificate certificate :
            CertificateFactory.getInstance("X.509").generateCertificates(in)) {
          trusted.setCertificateEntry("ca-" + index++, certificate);
        }
        if (index == 0) {
          throw new GeneralSecurityException("no certificate in " + caFile);
        }
      }
    }
    TrustManagerFactory trust =
        TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
    trust.init(trusted);
    X509ExtendedTrustManager manager = null;
    for (TrustManager candidate : trust.getTrustManagers()) {
      if (candidate instanceof X509ExtendedTrustManager extended) {
        manager = extended;
        break;
      }
    }
    if (manager == null) {
      throw new GeneralSecurityException("no X.509 trust manager");
    }
    SSLContext context = SSLContext.getInstance("TLS");
    context.init(null, new TrustManager[] {new LoopbackTrustManager(manager)}, null);
    return context;
  }

  /**
   * Tells whether a host is {@code localhost} or a literal loopback address (127.0.0.0/8 or ::1,
   * with or without brackets). Names are not resolved: a name that would resolve to loopback is not
   * trusted as such.
   *
   * @param host the host, as a URI or an {@link SSLEngine} gives it
   * @return true for a loopback host
   */
  static boolean loopback(@Nullable String host) {
    if (host == null) {
      return false;
    }
    if (host.equalsIgnoreCase("localhost")) {
      return true;
    }
    if (host.startsWith("[") && host.endsWith("]")) {
      host = host.substring(1, host.length() - 1);
    }
    try {
      return InetAddress.ofLiteral(host).isLoopbackAddress();
    } catch (IllegalArgumentException e) {
      return false;
    }
  }

  /**
   * The JDK's trust manager, with its HTTPS endpoint identification (the certificate must name the
   * host) except toward a loopback address, where only the chain is verified.
   *
   * <p>Loopback traffic never leaves the host (in Kubernetes, the Pod), so the peer is the local
   * proxy, whose certificate is issued for its Service's name and not for 127.0.0.1: a preStop
   * hook's {@code warp drain --url https://127.0.0.1:9901} and a port-forwarded console would
   * otherwise fail on the name.
   */
  private static final class LoopbackTrustManager extends X509ExtendedTrustManager {

    private final X509ExtendedTrustManager delegate;

    LoopbackTrustManager(X509ExtendedTrustManager delegate) {
      this.delegate = delegate;
    }

    @Override
    public void checkServerTrusted(X509Certificate[] chain, String authType, SSLEngine engine)
        throws CertificateException {
      if (loopback(engine.getPeerHost())) {
        delegate.checkServerTrusted(chain, authType);
      } else {
        delegate.checkServerTrusted(chain, authType, engine);
      }
    }

    @Override
    public void checkServerTrusted(X509Certificate[] chain, String authType, Socket socket)
        throws CertificateException {
      InetAddress address = socket.getInetAddress();
      if (address != null && address.isLoopbackAddress()) {
        delegate.checkServerTrusted(chain, authType);
      } else {
        delegate.checkServerTrusted(chain, authType, socket);
      }
    }

    @Override
    public void checkServerTrusted(X509Certificate[] chain, String authType)
        throws CertificateException {
      delegate.checkServerTrusted(chain, authType);
    }

    @Override
    public void checkClientTrusted(X509Certificate[] chain, String authType, SSLEngine engine)
        throws CertificateException {
      delegate.checkClientTrusted(chain, authType, engine);
    }

    @Override
    public void checkClientTrusted(X509Certificate[] chain, String authType, Socket socket)
        throws CertificateException {
      delegate.checkClientTrusted(chain, authType, socket);
    }

    @Override
    public void checkClientTrusted(X509Certificate[] chain, String authType)
        throws CertificateException {
      delegate.checkClientTrusted(chain, authType);
    }

    @Override
    public X509Certificate[] getAcceptedIssuers() {
      return delegate.getAcceptedIssuers();
    }
  }

  /**
   * Describes why a proxy cannot be reached, with what to do about an untrusted certificate.
   *
   * @param e the failure
   * @return its description
   */
  static String explain(IOException e) {
    String message = e.getMessage();
    if (message == null || message.isBlank()) {
      message = e.getClass().getSimpleName();
    }
    if (message.contains("PKIX") || message.contains("certificate_unknown")) {
      return "its certificate is not trusted; give its authority (or the certificate itself) with"
          + " --ca-file";
    }
    if (message.contains("No subject alternative") || message.contains("No name matching")) {
      return "its certificate does not name this address: " + message;
    }
    return message;
  }

  /**
   * Returns the base of a target's addresses, for messages.
   *
   * @param uri the address
   * @return its scheme, host and port
   */
  static String describe(URI uri) {
    return uri.getScheme() + "://" + uri.getAuthority();
  }
}
