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
package dev.warp.via;

import dev.warp.api.command.CommandSource;
import dev.warp.api.command.SimpleCommand;
import dev.warp.api.player.Player;

import java.util.List;
import java.util.UUID;

import com.viaversion.viaversion.api.command.ViaCommandSender;
import com.viaversion.viaversion.api.command.ViaSubCommand;
import com.viaversion.viaversion.commands.ViaCommandHandler;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;

/**
 * ViaVersion's {@code /viaversion} command, as a Warp command: ViaVersion's own subcommands ({@code
 * list}, {@code pps}, {@code debug}, {@code dump}, {@code player}...) plus {@code probe}, which
 * asks every {@code auto} server for its version again.
 *
 * <p>Like every proxy command, it is merged into the command tree each player receives, in the
 * player's version, translated or not.
 */
final class ViaCommand extends ViaCommandHandler implements SimpleCommand {

  /** The permission to see and run the command; subcommands check their own. */
  static final String PERMISSION = "viaversion.command";

  private static final LegacyComponentSerializer LEGACY =
      LegacyComponentSerializer.builder().character('§').extractUrls().build();

  /**
   * Creates the command.
   *
   * @param versions the backend versions, probed by {@code /viaversion probe}
   */
  ViaCommand(BackendVersions versions) {
    // No update subcommand: updates come with this plugin, not from ViaVersion's site.
    super(false);
    registerSubCommand(new Probe(versions));
  }

  @Override
  public void execute(Invocation invocation) {
    onCommand(new Sender(invocation.source()), invocation.arguments().toArray(String[]::new));
  }

  @Override
  public List<String> suggest(Invocation invocation) {
    return onTabComplete(
        new Sender(invocation.source()), invocation.arguments().toArray(String[]::new));
  }

  @Override
  public boolean hasPermission(CommandSource source) {
    return source.hasPermission(PERMISSION);
  }

  /**
   * A Warp command source as ViaVersion sees it.
   *
   * @param source the source
   */
  record Sender(CommandSource source) implements ViaCommandSender {

    @Override
    public boolean hasPermission(String permission) {
      return source.hasPermission(permission);
    }

    @Override
    public void sendMessage(String msg) {
      source.sendMessage(LEGACY.deserialize(msg));
    }

    @Override
    public UUID getUUID() {
      return source instanceof Player player ? player.uniqueId() : new UUID(0, 0);
    }

    @Override
    public String getName() {
      return source instanceof Player player ? player.username() : "CONSOLE";
    }
  }

  /** {@code /viaversion probe}: asks every {@code auto} server for its version again. */
  private record Probe(BackendVersions versions) implements ViaSubCommand {

    @Override
    public String name() {
      return "probe";
    }

    @Override
    public String description() {
      return "Asks every server set to auto in servers.yml for its version";
    }

    @Override
    public boolean execute(ViaCommandSender sender, String[] args) {
      versions.probeAllServers();
      sendMessage(sender, "&6Asking the servers for their versions; the answers are logged.");
      return true;
    }
  }
}
