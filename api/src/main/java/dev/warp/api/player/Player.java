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

import dev.warp.api.command.CommandSource;
import dev.warp.api.messaging.ChannelIdentifier;
import dev.warp.api.server.RegisteredServer;
import dev.warp.api.server.ServerConnection;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import net.kyori.adventure.chat.ChatType;
import net.kyori.adventure.chat.SignedMessage;
import net.kyori.adventure.dialog.DialogLike;
import net.kyori.adventure.identity.Identified;
import net.kyori.adventure.inventory.Book;
import net.kyori.adventure.resource.ResourcePackRequest;
import net.kyori.adventure.sound.Sound;
import net.kyori.adventure.text.Component;

/**
 * A player connected to the proxy, from the moment its login was allowed until it disconnects.
 *
 * <p>Every method is safe to call from any thread. Methods that send something to the client take
 * effect on its connection's thread, in the order they were called.
 *
 * <h2>As an Adventure audience</h2>
 *
 * <p>A player receives everything an Adventure {@link net.kyori.adventure.audience.Audience} sends
 * that a proxy can deliver, encoded for its client's version and translated for its language:
 * messages, action bars, titles, tab list header and footer, sounds, boss bars, resource packs, the
 * deletion of signed messages and the closing of dialogs.
 *
 * <ul>
 *   <li><b>Across server switches.</b> Chat messages and titles sent while the player switches
 *       servers are kept until it is in a game again; action bars and sounds are dropped then. Boss
 *       bars stay on screen across switches: a client from 1.20.2 clears them when it enters the
 *       configuration phase, and the proxy shows them again once the player is in the next game.
 *       Updates to a bar reach the client live, never while it does not show the bar. Resource
 *       packs the proxy sent stay applied across switches and are never sent again; a server's own
 *       packs are its own (see {@link #sendResourcePacks(ResourcePackRequest)}).
 *   <li><b>What a client lacks does nothing.</b> A method whose {@link AudienceFeature} the
 *       player's client lacks, by its version, does nothing for that player, as Adventure has every
 *       audience do with what it cannot show: an audience of several players reaches those that can
 *       show it and never fails because one client is old. {@link #supports} tells which.
 *   <li><b>What no proxy can do throws.</b> {@link #openBook(Book)}, {@link
 *       #showDialog(DialogLike)} and {@link #sendMessage(SignedMessage, ChatType.Bound)} of a
 *       player's message throw {@link UnsupportedOperationException} for every player, whatever its
 *       version: they need state only the backend holds (see each method). They fail at once, never
 *       silently.
 *   <li><b>Arguments a client could never read</b> (a component too large for the client, a sound
 *       key longer than 256 characters, a message signature that is not 256 bytes, a sound emitter
 *       that is not a player) throw {@link IllegalArgumentException} before anything is sent.
 * </ul>
 *
 * <p>Implemented by the proxy; plugins must not implement it.
 */
public interface Player extends InboundConnection, CommandSource, Identified, Sound.Emitter {

  /**
   * Returns the player's UUID: Mojang's in online mode, derived from the name in offline mode, or
   * whatever a plugin set in {@link dev.warp.api.event.player.GameProfileRequestEvent}.
   *
   * @return the UUID
   */
  UUID uniqueId();

  /**
   * Returns the player's name.
   *
   * @return the name
   */
  String username();

  /**
   * Returns the player's profile, as forwarded to backend servers.
   *
   * @return the profile
   */
  GameProfile profile();

  /**
   * Returns an identifier of this session, distinct for every login of the same account, so that
   * data keyed by session never mixes two logins of one player.
   *
   * @return the session identifier
   */
  UUID sessionId();

  /**
   * Returns whether the player was authenticated with Mojang.
   *
   * @return {@code true} in online mode
   */
  boolean isOnlineMode();

  /**
   * Returns the server the player plays on. It stays the same during a switch until the new server
   * took over; see {@link #pendingServer()} for the one being switched to.
   *
   * @return the connection to the current server, or empty before the player reached its first
   *     server
   */
  Optional<ServerConnection> currentServer();

  /**
   * Returns the server the player is being moved to.
   *
   * @return the server, or empty when no switch is in progress
   */
  Optional<RegisteredServer> pendingServer();

  /**
   * Returns the round trip time between the proxy and the client, measured with keep-alives.
   *
   * @return the latency in milliseconds, or {@code -1} before the first measure
   */
  long ping();

  /**
   * Moves the player to a server. The player keeps playing on its current server until the new one
   * accepted it; if the new server cannot be reached or refuses it, the player stays.
   *
   * @param server the server to move to
   * @return completed with the outcome once known; never completes exceptionally
   */
  CompletableFuture<ConnectionResult> connect(RegisteredServer server);

  /**
   * Sends a plugin message to the player's client.
   *
   * @param channel the channel
   * @param data the payload, copied
   * @return {@code true} if it was sent; {@code false} if the player is gone or between two states
   *     where the client cannot receive one
   */
  boolean sendPluginMessage(ChannelIdentifier channel, byte[] data);

  /**
   * Disconnects the player with a reason shown on its screen.
   *
   * @param reason the reason
   */
  void disconnect(Component reason);

  // ---------------------------------------------------------------------------
  // Audience
  // ---------------------------------------------------------------------------

  /**
   * Returns whether the player's client can show something an Audience sends, by its version. A
   * method whose feature the client lacks does nothing for this player.
   *
   * @param feature the feature
   * @return {@code true} if the client has it
   */
  boolean supports(AudienceFeature feature);

  /**
   * Applies resource packs on the player's client.
   *
   * <p>From 1.20.3 the client holds every pack, each by its UUID. Before, it holds a single server
   * pack, which the next one replaces: such a client receives the first pack of the request, and
   * the others are reported {@link net.kyori.adventure.resource.ResourcePackStatus#DISCARDED} at
   * once. 1.7 receives the URL alone, through the {@code MC|RPack} plugin message, and reports
   * nothing; before 1.17 the client ignores {@code required} and the prompt.
   *
   * <p>The client's answers reach the request's callback and fire {@link
   * dev.warp.api.event.player.PlayerResourcePackStatusEvent}, off the network thread, in the order
   * the client sent them; the server never sees them. A pack the client already loaded from the
   * proxy (same UUID, URL and hash) is not sent again: it is reported loaded at once, so that a
   * plugin may send its packs on every server switch without the client reloading them.
   *
   * <p>The packs stay applied across server switches, and a server cannot remove them. {@code
   * replace} replaces the packs the proxy sent, never a server's. Sent while the client is in the
   * configuration phase (from 1.20.2, at login or during a switch), the packs are applied there.
   *
   * @param request the packs, and what to do with the client's answers
   */
  @Override
  void sendResourcePacks(ResourcePackRequest request);

  /**
   * Refuses to open a book: always throws.
   *
   * <p>A client opens a book from the item in the player's hand: the server puts a written book
   * there for a moment, opens it, then puts the item back. The proxy forwards inventories without
   * reading them, so it can neither know what the hand holds nor put it back, and a book it opened
   * would replace the player's item on its screen until the server sent the slot again.
   *
   * @param book the book
   * @throws UnsupportedOperationException always: open books from the backend server
   */
  @Override
  void openBook(Book book);

  /**
   * Refuses to show a dialog: always throws.
   *
   * <p>Adventure defines no dialog: {@link DialogLike} is a marker that server platforms such as
   * Paper implement with their own dialog model, which the proxy cannot encode. {@link
   * #closeDialog()} works from 1.21.6.
   *
   * @param dialog the dialog
   * @throws UnsupportedOperationException always: show dialogs from the backend server
   */
  @Override
  void showDialog(DialogLike dialog);

  /**
   * Shows a signed message: a system message ({@link SignedMessage#isSystem()}) as a chat message,
   * like {@link #sendMessage(Component, ChatType.Bound)}; a player's message is refused.
   *
   * <p>A player's chat message is part of a chain the client verifies: each message carries the
   * index of the previous one from the same sender and the server's last-seen messages. The proxy
   * forwards chat without reading it, so it cannot place a message in the chain, and a client that
   * receives one out of the chain disconnects.
   *
   * @param signedMessage the message
   * @param boundChatType the chat type, which only the backend knows how to show: unused
   * @throws UnsupportedOperationException if the message is a player's: relay it from the backend
   */
  @Override
  void sendMessage(SignedMessage signedMessage, ChatType.Bound boundChatType);
}
