/**
 * Plugin messages: custom payloads exchanged with clients and backend servers on named channels.
 *
 * <p>A channel nobody registered passes through the proxy untouched, at no cost. A plugin that
 * registers a channel owns it: messages on it fire {@link
 * dev.warp.api.messaging.PluginMessageEvent} and are not relayed unless a handler says so, and
 * messages from a side the plugin did not accept are dropped, so a client cannot speak on a channel
 * meant for backends.
 */
@NullMarked
package dev.warp.api.messaging;

import org.jspecify.annotations.NullMarked;
