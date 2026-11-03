/**
 * Commands the proxy runs itself, whatever server the player is on.
 *
 * <p>Commands are Brigadier trees, the library the vanilla client and server use: proxy commands
 * appear in the player's command list with their arguments and suggestions, merged into the tree of
 * the server it plays on, and the client completes them as it completes any vanilla command. Simple
 * commands that take a list of words can implement {@link dev.warp.api.command.SimpleCommand}
 * instead.
 *
 * <p>Commands run on virtual threads, never on network threads: blocking (a database query) is
 * allowed. A command a player types that the proxy does not own goes to its server untouched,
 * signature included.
 */
@NullMarked
package dev.warp.api.command;

import org.jspecify.annotations.NullMarked;
