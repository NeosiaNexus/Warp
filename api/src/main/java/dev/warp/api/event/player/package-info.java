/**
 * Events about players: logging in, choosing and switching servers, losing a server, disconnecting.
 *
 * <h2>Order</h2>
 *
 * For one player, events come in this order, each fully handled before the next starts:
 *
 * <ol>
 *   <li>{@link dev.warp.api.event.player.PreLoginEvent}: name and address known, nothing
 *       authenticated yet.
 *   <li>{@link dev.warp.api.event.player.GameProfileRequestEvent}: authenticated (or not, in
 *       offline mode); the profile can still change.
 *   <li>{@link dev.warp.api.event.player.LoginEvent}: the {@link dev.warp.api.player.Player}
 *       exists; the login can still be denied.
 *   <li>{@link dev.warp.api.event.player.PostLoginEvent}: the session started.
 *   <li>{@link dev.warp.api.event.player.PlayerChooseInitialServerEvent}, then for every server the
 *       player joins: {@link dev.warp.api.event.player.ServerPreConnectEvent}, {@link
 *       dev.warp.api.event.player.ServerConnectedEvent}, {@link
 *       dev.warp.api.event.player.ServerPostConnectEvent}; {@link
 *       dev.warp.api.event.player.KickedFromServerEvent} whenever a server is lost.
 *   <li>{@link dev.warp.api.event.player.DisconnectEvent}: the session ended. Exactly one per
 *       {@code PostLoginEvent}, never for a login that was denied.
 * </ol>
 *
 * <p>The events of an account are ordered across its sessions too: when an account logs in again
 * while still connected, the old session's {@code DisconnectEvent} is fully handled before the new
 * session's {@code LoginEvent} starts.
 */
@NullMarked
package dev.warp.api.event.player;

import org.jspecify.annotations.NullMarked;
