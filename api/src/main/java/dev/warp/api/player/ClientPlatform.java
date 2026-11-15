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
package dev.warp.api.player;

import java.net.InetAddress;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * What kind of game client a connection is, as far as the proxy can tell: vanilla Java, a Forge
 * client of one of its three protocol eras, a NeoForge client, or a Bedrock player behind Geyser.
 *
 * <p>The platform is decided at the handshake from the markers a client appends to the host name it
 * connects with, and refined later when a platform leaves no mark there. It drives what the proxy
 * forwards to a backend on the player's behalf (the marker, login queries) and is never a trust
 * signal by itself: a marker is a string anyone can send. The one exception is {@link Bedrock},
 * which the proxy only sets after verifying Floodgate's encrypted data with the shared key.
 */
public sealed interface ClientPlatform {

  /** The vanilla client, or any client that leaves no mark the proxy recognizes. */
  ClientPlatform JAVA = new Java();

  /** A NeoForge client, which negotiates its mods in the configuration phase. */
  ClientPlatform NEOFORGE = new NeoForge();

  /**
   * Returns whether the client declared a mod loader, Forge or NeoForge, which negotiates its mods
   * with the server: such a client needs a modded backend, or a vanilla one it can fall back to.
   *
   * @return {@code true} for {@link Forge} and {@link NeoForge}
   */
  default boolean isModded() {
    return this instanceof Forge || this instanceof NeoForge;
  }

  /** The vanilla Java Edition client, or one the proxy cannot tell from it (Fabric, Quilt). */
  record Java() implements ClientPlatform {}

  /**
   * A Forge client.
   *
   * @param era the protocol era its handshake marker declared
   * @param netVersion the network version the marker carried, when it carries one: the number after
   *     {@code FORGE} in the modern marker, {@code 2} or {@code 3} for {@code FML2} and {@code
   *     FML3}, {@code 0} for the legacy {@code FML} marker, which has none
   */
  record Forge(ForgeEra era, int netVersion) implements ClientPlatform {}

  /**
   * A NeoForge client. NeoForge appends no marker to the host name, so the proxy learns of it from
   * the client's first configuration payload; until then the connection counts as {@link Java}.
   */
  record NeoForge() implements ClientPlatform {}

  /**
   * A Bedrock Edition player, connected through Geyser. The proxy sets this variant once it has
   * decrypted and verified Floodgate's data in the handshake with the shared Floodgate key, or when
   * Geyser opens the session in process: unlike the other platforms, it is a proof, not a claim,
   * since nobody without the key can produce it.
   *
   * <p>The player's Java identity is derived as Floodgate derives it: UUID {@code
   * 00000000-0000-0000-0009-} followed by the XUID in hexadecimal, name {@code <prefix><gamertag>}
   * with spaces replaced by underscores and cut to 16 characters, unless the account is linked to a
   * Java account, whose UUID and name are used instead.
   *
   * @param xuid the Xbox user id, the stable identity of the player
   * @param bedrockName the gamertag, as Bedrock shows it (spaces included, no prefix)
   * @param deviceOs the operating system of the device
   * @param inputMode how the player controls the game
   * @param uiProfile the user interface profile the player chose
   * @param language the language of the client, such as {@code en_US}
   * @param linkedJava the Java account this Bedrock account is linked to, when Floodgate said so
   * @param address the player's own address, the one Geyser accepted the Bedrock connection from,
   *     as opposed to the address of the Geyser host
   */
  record Bedrock(
      long xuid,
      String bedrockName,
      DeviceOs deviceOs,
      InputMode inputMode,
      UiProfile uiProfile,
      String language,
      Optional<LinkedAccount> linkedJava,
      InetAddress address)
      implements ClientPlatform {

    /**
     * Creates the platform of a verified Bedrock player.
     *
     * @param xuid the Xbox user id
     * @param bedrockName the gamertag, as Bedrock shows it
     * @param deviceOs the operating system of the device
     * @param inputMode how the player controls the game
     * @param uiProfile the user interface profile
     * @param language the language of the client
     * @param linkedJava the linked Java account, if any
     * @param address the player's own address
     */
    public Bedrock {
      Objects.requireNonNull(bedrockName, "bedrockName");
      Objects.requireNonNull(deviceOs, "deviceOs");
      Objects.requireNonNull(inputMode, "inputMode");
      Objects.requireNonNull(uiProfile, "uiProfile");
      Objects.requireNonNull(language, "language");
      Objects.requireNonNull(linkedJava, "linkedJava");
      Objects.requireNonNull(address, "address");
    }

    /**
     * Returns the Java UUID Floodgate derives from the XUID, which is the player's UUID on the
     * proxy and the backends unless the account is linked.
     *
     * @return {@code 00000000-0000-0000-0009-} followed by the XUID in hexadecimal
     */
    public UUID floodgateId() {
      return new UUID(0, xuid);
    }
  }

  /**
   * A Java account a Bedrock account is linked to, through Floodgate's global or local linking.
   *
   * @param id the Java account's UUID, the player's UUID while linked
   * @param name the Java account's name, the player's name while linked
   */
  record LinkedAccount(UUID id, String name) {
    /**
     * Creates a linked account.
     *
     * @param id the Java account's UUID
     * @param name the Java account's name
     */
    public LinkedAccount {
      Objects.requireNonNull(id, "id");
      Objects.requireNonNull(name, "name");
    }
  }

  /** The operating systems a Bedrock client reports, in the order of Floodgate's identifiers. */
  enum DeviceOs {
    /** An identifier the proxy does not know. */
    UNKNOWN("Unknown"),
    /** Android. */
    ANDROID("Android"),
    /** iOS. */
    IOS("iOS"),
    /** macOS. */
    MACOS("macOS"),
    /** Amazon Fire. */
    AMAZON("Amazon"),
    /** Gear VR. */
    GEAR_VR("Gear VR"),
    /** HoloLens, no longer supported by the game. */
    HOLOLENS("Hololens"),
    /** Windows, the Microsoft Store edition. */
    WINDOWS("Windows"),
    /** Windows, the 32-bit edition. */
    WINDOWS_X86("Windows x86"),
    /** A dedicated server. */
    DEDICATED("Dedicated"),
    /** Apple TV, no longer supported by the game. */
    TVOS("Apple TV"),
    /** Any PlayStation. */
    PLAYSTATION("PlayStation"),
    /** Nintendo Switch. */
    SWITCH("Switch"),
    /** Any Xbox. */
    XBOX("Xbox"),
    /** Windows Phone, no longer supported by the game. */
    WINDOWS_PHONE("Windows Phone"),
    /** Linux. */
    LINUX("Linux");

    private static final DeviceOs[] VALUES = values();

    private final String displayName;

    DeviceOs(String displayName) {
      this.displayName = displayName;
    }

    /**
     * Returns the operating system of a Floodgate identifier.
     *
     * @param id the identifier, the ordinal Floodgate and Geyser use
     * @return the operating system, {@link #UNKNOWN} for an identifier the proxy does not know
     */
    public static DeviceOs fromId(int id) {
      return id >= 0 && id < VALUES.length ? VALUES[id] : UNKNOWN;
    }

    /**
     * Returns the name to show players and staff, such as {@code Android}.
     *
     * @return the display name
     */
    public String displayName() {
      return displayName;
    }
  }

  /** How a Bedrock player controls the game, in the order of Floodgate's identifiers. */
  enum InputMode {
    /** An identifier the proxy does not know. */
    UNKNOWN,
    /** A keyboard and a mouse. */
    KEYBOARD_MOUSE,
    /** A touch screen. */
    TOUCH,
    /** A controller. */
    CONTROLLER,
    /** A virtual reality headset. */
    VR;

    private static final InputMode[] VALUES = values();

    /**
     * Returns the input mode of a Floodgate identifier.
     *
     * @param id the identifier, the ordinal Floodgate and Geyser use
     * @return the input mode, {@link #UNKNOWN} for an identifier the proxy does not know
     */
    public static InputMode fromId(int id) {
      return id >= 0 && id < VALUES.length ? VALUES[id] : UNKNOWN;
    }
  }

  /** The user interface profile of a Bedrock client, in the order of Floodgate's identifiers. */
  enum UiProfile {
    /** The classic interface. */
    CLASSIC,
    /** The pocket interface of phones. */
    POCKET;

    private static final UiProfile[] VALUES = values();

    /**
     * Returns the profile of a Floodgate identifier.
     *
     * @param id the identifier, the ordinal Floodgate and Geyser use
     * @return the profile, {@link #CLASSIC} for an identifier the proxy does not know, as Floodgate
     *     itself falls back
     */
    public static UiProfile fromId(int id) {
      return id >= 0 && id < VALUES.length ? VALUES[id] : CLASSIC;
    }
  }

  /** The three protocols Forge has used to negotiate mods with a server. */
  enum ForgeEra {
    /**
     * Minecraft 1.7 to 1.12: marker {@code \0FML\0}, negotiation on the {@code FML|HS} plugin
     * channel once in the play state.
     */
    LEGACY,
    /**
     * Minecraft 1.13 to 1.20.1: marker {@code \0FML2\0} ({@code \0FML3\0} from 1.19), negotiation
     * with login plugin messages on {@code fml:loginwrapper} before Login Success.
     */
    FML2,
    /**
     * Minecraft 1.20.2 and newer: marker {@code \0FORGE} followed by an optional network version,
     * negotiation in the configuration phase.
     */
    MODERN
  }
}
