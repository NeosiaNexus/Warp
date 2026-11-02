/**
 * The Brigadier command tree of the commands packet: decoded, edited and re-encoded, for every
 * version from 1.13.
 *
 * <h2>Model</h2>
 *
 * <p>A {@link dev.warp.protocol.command.CommandTree} is the graph the packet carries: {@link
 * dev.warp.protocol.command.CommandNode} records (root, literals, arguments) referring to each
 * other by index, so that shared nodes, redirects ({@code /execute run} back to the root) and the
 * exact node order survive a decode and re-encode byte for byte. An argument's {@link
 * dev.warp.protocol.command.ArgumentParser} is a sealed type with a record per argument type that
 * has properties, and {@link dev.warp.protocol.command.ArgumentTypes} holds the argument types and
 * registry ids of each version, checked against Mojang's own data.
 *
 * <h2>Opaque trees</h2>
 *
 * <p>{@link dev.warp.protocol.command.CommandTreeCodec#decode} returns a {@link
 * dev.warp.protocol.command.CommandTreeDecoding}: either the tree, or {@link
 * dev.warp.protocol.command.CommandTreeDecoding.Opaque} when the packet holds something Warp cannot
 * represent exactly, typically a modded server's own argument type, whose properties no length
 * delimits. An opaque packet is forwarded untouched and the player keeps the backend's commands;
 * only proxy commands are missing from that tree. Proxies that cannot decode such a tree disconnect
 * the player instead (PaperMC/Velocity#1912, #1714, #1737, minekube/gate#322). A backend running
 * the CrossStitch mod wraps those types so that a proxy can read past them: Warp reads the wrapper
 * ({@link dev.warp.protocol.command.ArgumentParser.WrappedParser}) and unwraps it for the client,
 * but does not need it. A tree from such a backend must be decoded and encoded again, though:
 * forwarded raw, its wrapper reaches a client that cannot read it.
 *
 * <h2>Intended integration</h2>
 *
 * <p>The packet is large (some 1500 nodes and 20 KiB for a vanilla operator) and comes on every
 * join, every server switch, and every permission change. The proxy reads it only when it has a
 * reason to:
 *
 * <ol>
 *   <li>{@link dev.warp.protocol.packet.play.DeclareCommands} is registered encode-only: while the
 *       proxy has no command of its own and no listener of the packet, every copy is forwarded
 *       untouched, compressed bytes included, like any unregistered packet.
 *   <li>Once it has some, it registers the packet as decoded, which only copies the payload: the
 *       tree is parsed when {@link dev.warp.protocol.packet.play.DeclareCommands#tree} is called,
 *       and never if nothing needs it. Keeping the last payload of each backend connection lets the
 *       proxy rebuild a player's tree when its own commands or permissions change, without waiting
 *       for the backend to resend it (PaperMC/Velocity#352).
 *   <li>For a decoded tree: {@link dev.warp.protocol.command.CommandTree#merge} adds the proxy's
 *       commands (built with {@link dev.warp.protocol.command.CommandTree.Builder}, already
 *       filtered for the player), {@link dev.warp.protocol.command.CommandTree#withoutTopLevel} and
 *       {@link dev.warp.protocol.command.CommandTree#filter} hide backend commands, and {@link
 *       dev.warp.protocol.packet.play.DeclareCommands#of} encodes the result.
 *   <li>For an opaque one: forward the original payload, and log {@link
 *       dev.warp.protocol.command.CommandTreeDecoding.Opaque#message()} once per backend. A {@link
 *       io.netty.handler.codec.DecoderException} (a tree the client would refuse too) is best
 *       forwarded as well, for the client to judge as it would without a proxy.
 * </ol>
 *
 * <p>Converting Brigadier {@code CommandNode}s is left to the proxy: the protocol module depends on
 * no Brigadier, which its rules keep out. The conversion walks the Brigadier graph with an identity
 * map (it is a graph, and two equal nodes are still two nodes), adds each node to a builder once,
 * and checks {@link dev.warp.protocol.command.ArgumentTypes#isAvailable} for the player's version,
 * falling back to {@code brigadier:string} for an argument type the client does not know.
 */
@NullMarked
package dev.warp.protocol.command;

import org.jspecify.annotations.NullMarked;
