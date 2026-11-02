/**
 * The network pipeline: Netty handlers that plugins add to player and backend connections, at
 * documented positions, instead of injecting into the proxy by reflection.
 *
 * <p>This is the low level of the API, for protocol translators (ViaVersion), packet inspectors and
 * traffic tools. A plugin uses it by declaring {@link
 * dev.warp.api.plugin.Capability#NETWORK_PIPELINE} in {@link
 * dev.warp.api.plugin.Plugin#capabilities()}: it then gets {@link
 * dev.warp.api.network.NetworkPipelines} from {@link dev.warp.api.plugin.PluginContext#network()},
 * and its class loader sees the proxy's Netty (it must not ship its own). The proxy names it in the
 * line that enables it, and names its handlers after it in every pipeline.
 *
 * <h2>The pipeline</h2>
 *
 * <p>Every connection of the proxy, to a player or to a backend, is a Netty pipeline. Read from the
 * socket (top) to the proxy's logic (bottom):
 *
 * <pre>
 *   socket
 *   decryption, encryption (players in online mode only)
 *   framing                        splits the stream into frames, and frames what is sent
 *     FRAME handlers               {@link dev.warp.api.network.PipelinePosition#FRAME}
 *   decompression                  only on connections with PACKET handlers
 *     PACKET handlers              {@link dev.warp.api.network.PipelinePosition#PACKET}
 *   Warp's packet codec            decodes the few packets the proxy reads, encodes what it sends
 *   Warp's session logic
 * </pre>
 *
 * <p>The positions do not move when the connection switches on encryption or compression, so a
 * handler never has to put itself back in place. (ViaVersion on Velocity has to: it listens for a
 * "compression enabled" event and moves its handlers after Velocity's.)
 *
 * <h2>Cost</h2>
 *
 * <p>Warp forwards most packets without decoding them, and forwards compressed packets in their
 * compressed form, without inflating or deflating them. A {@code FRAME} handler keeps all of that.
 * A {@code PACKET} handler gives it up <em>for that connection only</em>: from the first one, the
 * connection inflates every frame it receives, and every packet forwarded to it is compressed
 * again. Install packet handlers only where they are needed: a translator installs them on the
 * connections whose two sides speak different versions, and nothing on the others.
 *
 * <h2>Threading and ordering</h2>
 *
 * <ul>
 *   <li>Initializers run on the connection's event loop: the client's, on which the proxy also runs
 *       every backend connection of that player. They must not block.
 *   <li>Initializers run in the order of their plugins (dependencies first), then in registration
 *       order. Handlers at one position nest in the same order: the first one added is the
 *       outermost, nearest the socket. It sees received messages first and sent messages last.
 *   <li>Handlers run on the connection's event loop, as Netty guarantees; a player's client and
 *       backend connections share that thread, so a handler of one can call into the other without
 *       locking.
 *   <li>An initializer that throws closes the connection: a connection set up halfway would corrupt
 *       the stream. The proxy logs the error with the plugin's identifier.
 * </ul>
 *
 * <h2>What a handler may do</h2>
 *
 * <p>A handler reads, rewrites, drops or adds messages in the format of its position, and may use
 * the channel for its event loop, its attributes and closing it. It must not remove, replace or
 * move the proxy's own handlers, nor another plugin's: their names and order are not part of the
 * API. The proxy enforces it where it costs nothing on the packet path: after each initializer, it
 * checks that the handlers the initializer found are all still there, in their order, and closes
 * the connection otherwise, naming the plugin and the handler (once per plugin).
 */
@NullMarked
package dev.warp.api.network;

import org.jspecify.annotations.NullMarked;
