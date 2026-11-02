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
package dev.warp.proxy.connection;

import dev.warp.api.messaging.ChannelIdentifier;
import dev.warp.api.player.AudienceFeature;
import dev.warp.api.player.ConnectionResult;
import dev.warp.api.player.GameProfile;
import dev.warp.api.player.Player;
import dev.warp.api.player.TransferResult;
import dev.warp.api.server.RegisteredServer;
import dev.warp.api.server.ServerConnection;
import dev.warp.protocol.ProtocolVersion;
import dev.warp.proxy.server.BackendServer;

import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.chat.ChatType;
import net.kyori.adventure.chat.SignedMessage;
import net.kyori.adventure.dialog.DialogLike;
import net.kyori.adventure.identity.Identity;
import net.kyori.adventure.inventory.Book;
import net.kyori.adventure.pointer.Pointers;
import net.kyori.adventure.resource.ResourcePackInfo;
import net.kyori.adventure.resource.ResourcePackRequest;
import net.kyori.adventure.sound.Sound;
import net.kyori.adventure.sound.SoundStop;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.title.Title;
import net.kyori.adventure.title.TitlePart;

/** A player as plugins see it: a view of the proxy's {@link ConnectedPlayer}. */
public final class WarpPlayer implements Player {

  private final ConnectedPlayer player;

  WarpPlayer(ConnectedPlayer player) {
    this.player = player;
  }

  @Override
  public UUID uniqueId() {
    return player.uuid();
  }

  @Override
  public String username() {
    return player.username();
  }

  @Override
  public GameProfile profile() {
    return player.apiProfile();
  }

  @Override
  public UUID sessionId() {
    return player.sessionId();
  }

  @Override
  public boolean isOnlineMode() {
    return player.isOnlineMode();
  }

  @Override
  public InetSocketAddress remoteAddress() {
    return player.remoteAddress();
  }

  @Override
  public Optional<String> virtualHost() {
    return Optional.ofNullable(player.clientConnection().virtualHost());
  }

  @Override
  public int protocolVersion() {
    return player.protocolVersion().protocol();
  }

  @Override
  public boolean isActive() {
    return player.clientConnection().channel().isActive();
  }

  @Override
  public boolean isTransferred() {
    return player.isTransferred();
  }

  @Override
  public Optional<ServerConnection> currentServer() {
    BackendServer server = player.currentServer();
    return server == null
        ? Optional.empty()
        : Optional.of(new WarpServerConnection(player, server));
  }

  @Override
  public Optional<RegisteredServer> pendingServer() {
    return Optional.ofNullable(player.pendingServer());
  }

  @Override
  public long ping() {
    return player.measuredLatencyMs();
  }

  @Override
  public CompletableFuture<ConnectionResult> connect(RegisteredServer server) {
    if (!(server instanceof BackendServer backend)) {
      throw new IllegalArgumentException("Not a server of this proxy: " + server);
    }
    return player.requestConnection(backend, false);
  }

  // ---------------------------------------------------------------------------
  // Audience
  // ---------------------------------------------------------------------------

  @Override
  public Identity identity() {
    return Identity.identity(player.uuid());
  }

  @Override
  public Pointers pointers() {
    return Pointers.builder()
        .withStatic(Identity.UUID, player.uuid())
        .withStatic(Identity.NAME, player.username())
        .build();
  }

  @Override
  public boolean supportsTransfers() {
    return player.supportsTransfers();
  }

  @Override
  public CompletableFuture<TransferResult> transfer(String host, int port) {
    return player.requestTransfer(host, port);
  }

  @Override
  public void sendMessage(Component message) {
    player.sendMessage(message);
  }

  @Override
  public void sendMessage(Component message, ChatType.Bound boundChatType) {
    // The proxy cannot write player chat for a backend's chat types: shown as a system message.
    player.sendMessage(message);
  }

  @Override
  public void sendMessage(SignedMessage signedMessage, ChatType.Bound boundChatType) {
    if (!signedMessage.isSystem()) {
      throw new UnsupportedOperationException(
          "Warp cannot write a player's signed chat message: the client verifies each against the"
              + " chain of its sender's messages, which only the backend holds. Relay it from the"
              + " backend, or send a system message");
    }
    Component content = signedMessage.unsignedContent();
    player.sendMessage(content != null ? content : Component.text(signedMessage.message()));
  }

  @Override
  public void deleteMessage(SignedMessage.Signature signature) {
    player.deleteMessage(signature.bytes());
  }

  @Override
  public void sendActionBar(Component message) {
    player.sendActionBar(message);
  }

  @Override
  public void showTitle(Title title) {
    player.showTitle(title);
  }

  @Override
  public <T> void sendTitlePart(TitlePart<T> part, T value) {
    player.sendTitlePart(part, value);
  }

  @Override
  public void clearTitle() {
    player.clearTitle();
  }

  @Override
  public void resetTitle() {
    player.resetTitle();
  }

  @Override
  public void sendPlayerListHeaderAndFooter(Component header, Component footer) {
    player.sendPlayerListHeaderAndFooter(header, footer);
  }

  @Override
  public void sendPlayerListHeader(Component header) {
    player.sendPlayerListHeaderAndFooter(header, player.playerListFooter());
  }

  @Override
  public void sendPlayerListFooter(Component footer) {
    player.sendPlayerListHeaderAndFooter(player.playerListHeader(), footer);
  }

  @Override
  public boolean supports(AudienceFeature feature) {
    return supports(feature, player.protocolVersion());
  }

  /**
   * Returns whether a client of {@code version} has a feature.
   *
   * @param feature the feature
   * @param version the client's protocol version
   * @return {@code true} if it has it
   */
  static boolean supports(AudienceFeature feature, ProtocolVersion version) {
    return switch (feature) {
      case ACTION_BAR, TITLE, PLAYER_LIST_HEADER_AND_FOOTER, RESOURCE_PACK_STATUS ->
          version.isAtLeast(ProtocolVersion.MINECRAFT_1_8);
      case POSITIONED_SOUND, RESOURCE_PACK -> true;
      case EMITTED_SOUND -> version.isAtLeast(ProtocolVersion.MINECRAFT_1_14);
      case STOP_SOUND -> version.isAtLeast(ProtocolVersion.MINECRAFT_1_9_4);
      case BOSS_BAR -> version.isAtLeast(ProtocolVersion.MINECRAFT_1_9);
      case MULTIPLE_RESOURCE_PACKS -> version.isAtLeast(ProtocolVersion.MINECRAFT_1_20_3);
      case DELETE_MESSAGE -> version.isAtLeast(ProtocolVersion.MINECRAFT_1_19_1);
      case CLOSE_DIALOG -> version.isAtLeast(ProtocolVersion.MINECRAFT_1_21_6);
    };
  }

  @Override
  public void playSound(Sound sound) {
    player.playSound(sound, (ConnectedPlayer) null);
  }

  @Override
  public void playSound(Sound sound, double x, double y, double z) {
    player.playSound(sound, x, y, z);
  }

  @Override
  public void playSound(Sound sound, Sound.Emitter emitter) {
    if (Sound.Emitter.self().equals(emitter)) {
      player.playSound(sound, (ConnectedPlayer) null);
    } else if (emitter instanceof WarpPlayer other) {
      player.playSound(sound, other.connectedPlayer());
    } else {
      throw new IllegalArgumentException(
          "A sound emitter on a proxy is Sound.Emitter.self() or a player, not " + emitter);
    }
  }

  @Override
  public void stopSound(SoundStop stop) {
    player.stopSound(stop);
  }

  @Override
  public void openBook(Book book) {
    throw new UnsupportedOperationException(
        "Warp cannot open a book: a client opens the book in the player's hand, and the proxy"
            + " neither reads the inventory nor can give the item back. Open it from the backend");
  }

  @Override
  public void showBossBar(BossBar bar) {
    player.showBossBar(bar);
  }

  @Override
  public void hideBossBar(BossBar bar) {
    player.hideBossBar(bar);
  }

  @Override
  public Iterable<? extends BossBar> activeBossBars() {
    return player.activeBossBars();
  }

  @Override
  public void sendResourcePacks(ResourcePackRequest request) {
    player.sendResourcePacks(request);
  }

  // Adventure 5.2's default sends a single pack's removal twice.
  @Override
  public void removeResourcePacks(ResourcePackRequest request) {
    player.removeResourcePacks(request.packs().stream().map(ResourcePackInfo::id).toList());
  }

  @Override
  public void removeResourcePacks(UUID id, UUID... others) {
    List<UUID> ids = new ArrayList<>(others.length + 1);
    ids.add(id);
    ids.addAll(Arrays.asList(others));
    player.removeResourcePacks(ids);
  }

  @Override
  public void clearResourcePacks() {
    player.clearResourcePacks();
  }

  @Override
  public void showDialog(DialogLike dialog) {
    throw new UnsupportedOperationException(
        "Warp cannot show a dialog: Adventure defines no dialog, only DialogLike, which server"
            + " platforms implement with their own model. Show it from the backend");
  }

  @Override
  public void closeDialog() {
    player.closeDialog();
  }

  @Override
  public boolean hasPermission(String permission) {
    return player.loginContext().permissions().check(this, permission);
  }

  @Override
  public boolean sendPluginMessage(ChannelIdentifier channel, byte[] data) {
    return player.sendPluginMessage(channel.id(), data);
  }

  @Override
  public void disconnect(Component reason) {
    player.disconnectByPlugin(reason);
  }

  /**
   * Returns the proxy's player behind this view.
   *
   * @return the player
   */
  public ConnectedPlayer connectedPlayer() {
    return player;
  }

  @Override
  public String toString() {
    return "Player[" + player.username() + "]";
  }
}
