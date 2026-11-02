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
import dev.warp.api.player.ConnectionResult;
import dev.warp.api.player.GameProfile;
import dev.warp.api.player.Player;
import dev.warp.api.server.RegisteredServer;
import dev.warp.api.server.ServerConnection;
import dev.warp.proxy.server.BackendServer;

import java.net.InetSocketAddress;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import net.kyori.adventure.audience.MessageType;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.chat.ChatType;
import net.kyori.adventure.chat.SignedMessage;
import net.kyori.adventure.dialog.DialogLike;
import net.kyori.adventure.identity.Identified;
import net.kyori.adventure.identity.Identity;
import net.kyori.adventure.inventory.Book;
import net.kyori.adventure.pointer.Pointers;
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
  public void sendMessage(Component message) {
    player.sendMessage(message);
  }

  @Override
  public void sendMessage(Component message, ChatType.Bound boundChatType) {
    // The proxy cannot write player chat for a backend's chat types: shown as a system message.
    player.sendMessage(message);
  }

  @Override
  @SuppressWarnings("deprecation") // Adventure's identity overloads, still part of Audience
  public void sendMessage(Identified source, Component message, MessageType type) {
    player.sendMessage(message);
  }

  @Override
  @SuppressWarnings("deprecation") // Adventure's identity overloads, still part of Audience
  public void sendMessage(Identity source, Component message, MessageType type) {
    player.sendMessage(message);
  }

  @Override
  public void sendMessage(SignedMessage signedMessage, ChatType.Bound boundChatType) {
    throw unsupported("signed messages (only a backend can write them)");
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

  // Not supported yet: refused explicitly rather than silently ignored.

  @Override
  public void deleteMessage(SignedMessage.Signature signature) {
    throw unsupported("deleting signed messages");
  }

  @Override
  public void playSound(Sound sound) {
    throw unsupported("sounds");
  }

  @Override
  public void playSound(Sound sound, double x, double y, double z) {
    throw unsupported("sounds");
  }

  @Override
  public void playSound(Sound sound, Sound.Emitter emitter) {
    throw unsupported("sounds");
  }

  @Override
  public void stopSound(SoundStop stop) {
    throw unsupported("sounds");
  }

  @Override
  public void openBook(Book book) {
    throw unsupported("books");
  }

  @Override
  public void showBossBar(BossBar bar) {
    throw unsupported("boss bars");
  }

  @Override
  public void hideBossBar(BossBar bar) {
    throw unsupported("boss bars");
  }

  @Override
  public void sendResourcePacks(ResourcePackRequest request) {
    throw unsupported("resource packs");
  }

  @Override
  public void removeResourcePacks(ResourcePackRequest request) {
    throw unsupported("resource packs");
  }

  @Override
  public void removeResourcePacks(Iterable<UUID> ids) {
    throw unsupported("resource packs");
  }

  @Override
  public void clearResourcePacks() {
    throw unsupported("resource packs");
  }

  @Override
  public void showDialog(DialogLike dialog) {
    throw unsupported("dialogs");
  }

  @Override
  public void closeDialog() {
    throw unsupported("dialogs");
  }

  private static UnsupportedOperationException unsupported(String what) {
    return new UnsupportedOperationException("Warp does not send " + what + " to players yet");
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
