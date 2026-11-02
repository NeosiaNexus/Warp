/**
 * Players connected to the proxy.
 *
 * <p>A {@link dev.warp.api.player.Player} exists from the moment its login was allowed ({@link
 * dev.warp.api.event.player.LoginEvent}) until it disconnects. Its session starts with exactly one
 * {@link dev.warp.api.event.player.PostLoginEvent} and ends with exactly one {@link
 * dev.warp.api.event.player.DisconnectEvent}. Before that, a connection logging in is an {@link
 * dev.warp.api.player.InboundConnection}.
 */
@NullMarked
package dev.warp.api.player;

import org.jspecify.annotations.NullMarked;
