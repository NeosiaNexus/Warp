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
package dev.warp.proxy.bedrock;

import java.util.UUID;

import org.jspecify.annotations.Nullable;

/**
 * The plaintext Geyser puts in a Floodgate blob: twelve NUL-separated fields, in the order of
 * Floodgate's {@code BedrockData} (GeyserMC/Geyser, {@code
 * common/src/main/java/org/geysermc/floodgate/util/BedrockData.java}).
 *
 * <pre>
 * version \0 username \0 xuid \0 deviceOs \0 language \0 uiProfile \0 inputMode \0 ip \0
 * linkedPlayer \0 fromProxy \0 subscribeId \0 verifyCode
 * </pre>
 *
 * <p>{@code linkedPlayer} is {@code javaUsername;javaUuid;bedrockUuid} or the text {@code null};
 * {@code fromProxy} is {@code 1} or {@code 0}; {@code subscribeId} is {@code -1} and {@code
 * verifyCode} the text {@code null} when Geyser's skin uploader is off. Geyser's game version
 * string, such as {@code 1.21.50}, is the version field. Fields the proxy does not act on are kept
 * as text, so that re-emitting the data to a backend gives Floodgate exactly what Geyser wrote.
 *
 * @param version Geyser's game version text
 * @param username the Bedrock gamertag, spaces included
 * @param xuid the Xbox user id as text, a positive decimal number
 * @param deviceOs the device OS identifier
 * @param language the client's language, such as {@code en_US}
 * @param uiProfile the UI profile identifier
 * @param inputMode the input mode identifier
 * @param ip the Bedrock player's address, as Geyser saw it, without an IPv6 scope
 * @param linkedPlayer the linked Java account, or {@code null}
 * @param fromProxy whether a proxy, not Geyser, wrote the data
 * @param subscribeId the skin uploader's subscription id, {@code -1} without one
 * @param verifyCode the skin uploader's code, the text {@code null} without one
 */
public record FloodgateData(
    String version,
    String username,
    String xuid,
    int deviceOs,
    String language,
    int uiProfile,
    int inputMode,
    String ip,
    @Nullable LinkedPlayer linkedPlayer,
    boolean fromProxy,
    int subscribeId,
    String verifyCode) {

  /** How many fields the plaintext has. */
  public static final int FIELD_COUNT = 12;

  /** The text Floodgate writes for an absent linked player. */
  static final String NO_LINK = "null";

  private static final String SEPARATOR = "\0";

  /**
   * Parses the plaintext of a blob.
   *
   * @param plaintext the decrypted text
   * @return the data
   * @throws IllegalArgumentException if the field count or a numeric field is wrong
   */
  public static FloodgateData parse(String plaintext) {
    String[] fields = plaintext.split(SEPARATOR, -1);
    if (fields.length != FIELD_COUNT) {
      throw new IllegalArgumentException(
          "Floodgate data has " + fields.length + " fields, not " + FIELD_COUNT);
    }
    return new FloodgateData(
        fields[0],
        fields[1],
        fields[2],
        number(fields[3], "device OS"),
        fields[4],
        number(fields[5], "UI profile"),
        number(fields[6], "input mode"),
        fields[7],
        LinkedPlayer.parse(fields[8]),
        "1".equals(fields[9]),
        number(fields[10], "subscribe id"),
        fields[11]);
  }

  private static int number(String text, String what) {
    try {
      return Integer.parseInt(text);
    } catch (NumberFormatException e) {
      throw new IllegalArgumentException("the " + what + " '" + text + "' is not a number", e);
    }
  }

  /**
   * Writes the data as Floodgate's {@code BedrockData.toString()} does.
   *
   * @return the plaintext to encrypt
   */
  public String encode() {
    return String.join(
        SEPARATOR,
        version,
        username,
        xuid,
        Integer.toString(deviceOs),
        language,
        Integer.toString(uiProfile),
        Integer.toString(inputMode),
        ip,
        linkedPlayer == null ? NO_LINK : linkedPlayer.encode(),
        fromProxy ? "1" : "0",
        Integer.toString(subscribeId),
        verifyCode);
  }

  /**
   * Returns the XUID as a number.
   *
   * @return the XUID
   * @throws IllegalArgumentException if the field is not a positive number
   */
  public long xuidValue() {
    try {
      long value = Long.parseLong(xuid);
      if (value <= 0) {
        throw new IllegalArgumentException("the XUID " + xuid + " is not positive");
      }
      return value;
    } catch (NumberFormatException e) {
      throw new IllegalArgumentException("the XUID '" + xuid + "' is not a number", e);
    }
  }

  /**
   * Returns a copy whose {@code fromProxy} flag is set, as Floodgate on a proxy marks the data it
   * passes on to a backend.
   *
   * @return the data as a proxy forwards it
   */
  public FloodgateData fromProxy(boolean value) {
    return new FloodgateData(
        version,
        username,
        xuid,
        deviceOs,
        language,
        uiProfile,
        inputMode,
        ip,
        linkedPlayer,
        value,
        subscribeId,
        verifyCode);
  }

  /**
   * A Java account the Bedrock account is linked to, as Floodgate's {@code LinkedPlayer} writes it:
   * {@code javaUsername;javaUuid;bedrockUuid}.
   *
   * @param javaUsername the Java account's name
   * @param javaId the Java account's UUID
   * @param bedrockId the Bedrock player's Floodgate UUID
   */
  public record LinkedPlayer(String javaUsername, UUID javaId, UUID bedrockId) {

    /**
     * Parses the linked player field.
     *
     * @param field the field's text
     * @return the linked player, or {@code null} for {@code null}, an empty field, or one that is
     *     not three parts (Floodgate itself reads such a field as no link)
     * @throws IllegalArgumentException if a UUID part is not a UUID
     */
    public static @Nullable LinkedPlayer parse(String field) {
      if (field.isEmpty() || NO_LINK.equals(field)) {
        return null;
      }
      String[] parts = field.split(";", -1);
      if (parts.length != 3) {
        return null;
      }
      try {
        return new LinkedPlayer(parts[0], UUID.fromString(parts[1]), UUID.fromString(parts[2]));
      } catch (IllegalArgumentException e) {
        throw new IllegalArgumentException("the linked player '" + field + "' has a bad UUID", e);
      }
    }

    /**
     * Writes the field as Floodgate does.
     *
     * @return {@code javaUsername;javaUuid;bedrockUuid}
     */
    public String encode() {
      return javaUsername + ';' + javaId + ';' + bedrockId;
    }
  }
}
