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
package dev.warp.proxy.auth;

import dev.warp.protocol.packet.login.LoginSuccess;
import dev.warp.proxy.connection.GameProfile;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.jspecify.annotations.Nullable;

/**
 * Client for the Mojang session server's {@code hasJoined} endpoint.
 *
 * <p>Used during online-mode login to verify that the client authenticated with Mojang's servers.
 * The proxy sends the player's username and the server hash; Mojang responds with the player's UUID
 * and profile properties (skin, cape).
 *
 * <p>Uses {@link HttpClient} (Java 11+) — no external HTTP library needed. The client is
 * thread-safe and reused across all authentication requests.
 */
public final class MojangSessionService implements AutoCloseable {

  private static final String HAS_JOINED_URL =
      "https://sessionserver.mojang.com/session/minecraft/hasJoined";

  private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(10);

  private final HttpClient httpClient;

  /** Creates a new session service with a default HTTP client. */
  public MojangSessionService() {
    this.httpClient =
        HttpClient.newBuilder()
            .connectTimeout(REQUEST_TIMEOUT)
            // NEVER follow redirects — prevents SSRF if Mojang's URL is ever compromised.
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();
  }

  /**
   * Verifies that a player has authenticated with Mojang and retrieves their profile.
   *
   * <p><b>Warning:</b> This method performs a blocking HTTP request. It must be called from a
   * virtual thread or an off-event-loop context — never from a Netty event loop thread.
   *
   * @param username the player's username
   * @param serverHash the Minecraft-style server hash (from {@link ServerHash#compute})
   * @return the authenticated game profile
   * @throws AuthenticationException if authentication fails (network error, invalid response, or
   *     the player did not authenticate)
   */
  public GameProfile hasJoined(String username, String serverHash) throws AuthenticationException {
    String url =
        HAS_JOINED_URL
            + "?username="
            + URLEncoder.encode(username, StandardCharsets.UTF_8)
            + "&serverId="
            + URLEncoder.encode(serverHash, StandardCharsets.UTF_8);

    HttpRequest request =
        HttpRequest.newBuilder().uri(URI.create(url)).timeout(REQUEST_TIMEOUT).GET().build();

    HttpResponse<String> response;
    try {
      response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    } catch (IOException e) {
      throw new AuthenticationException("Failed to contact Mojang session server", e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new AuthenticationException("Authentication interrupted", e);
    }

    if (response.statusCode() == 204 || response.body().isEmpty()) {
      throw new AuthenticationException(
          "Player '" + username + "' did not authenticate with Mojang");
    }

    if (response.statusCode() != 200) {
      throw new AuthenticationException(
          "Mojang session server returned HTTP " + response.statusCode());
    }

    return parseProfile(response.body());
  }

  /**
   * Parses a Mojang session server response into a {@link GameProfile}.
   *
   * <p>Expected JSON format:
   *
   * <pre>{@code
   * {
   *   "id": "uuid_without_dashes",
   *   "name": "Username",
   *   "properties": [
   *     {
   *       "name": "textures",
   *       "value": "base64_data",
   *       "signature": "base64_signature"
   *     }
   *   ]
   * }
   * }</pre>
   */
  private static GameProfile parseProfile(String json) throws AuthenticationException {
    try {
      JsonObject root = JsonParser.parseString(json).getAsJsonObject();

      String rawUuid = root.get("id").getAsString();
      UUID uuid = parseUndashedUuid(rawUuid);
      String name = root.get("name").getAsString();

      List<LoginSuccess.Property> properties = new ArrayList<>();
      if (root.has("properties")) {
        JsonArray propsArray = root.getAsJsonArray("properties");
        for (JsonElement elem : propsArray) {
          JsonObject prop = elem.getAsJsonObject();
          String propName = prop.get("name").getAsString();
          String propValue = prop.get("value").getAsString();
          @Nullable String signature =
              prop.has("signature") ? prop.get("signature").getAsString() : null;
          properties.add(new LoginSuccess.Property(propName, propValue, signature));
        }
      }

      return new GameProfile(uuid, name, List.copyOf(properties));
    } catch (AuthenticationException e) {
      throw e;
    } catch (Exception e) {
      throw new AuthenticationException("Failed to parse Mojang session response", e);
    }
  }

  private static UUID parseUndashedUuid(String raw) throws AuthenticationException {
    if (raw.length() != 32) {
      throw new AuthenticationException("Invalid UUID format from Mojang: " + raw);
    }
    // Insert dashes: 8-4-4-4-12
    String dashed =
        raw.substring(0, 8)
            + "-"
            + raw.substring(8, 12)
            + "-"
            + raw.substring(12, 16)
            + "-"
            + raw.substring(16, 20)
            + "-"
            + raw.substring(20);
    try {
      return UUID.fromString(dashed);
    } catch (IllegalArgumentException e) {
      throw new AuthenticationException("Invalid UUID from Mojang: " + raw, e);
    }
  }

  // ---------------------------------------------------------------------------
  // Lifecycle
  // ---------------------------------------------------------------------------

  /** Closes the underlying HTTP client, releasing its thread pool. */
  @Override
  public void close() {
    httpClient.close();
  }
}
