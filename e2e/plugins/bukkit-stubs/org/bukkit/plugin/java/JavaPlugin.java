package org.bukkit.plugin.java;

import java.util.logging.Logger;

import org.bukkit.Server;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.Plugin;

/**
 * Compile-time stub of Bukkit's JavaPlugin: only the members the probe plugin uses, with Bukkit's
 * exact signatures. Never packaged: the server provides the real class.
 */
public abstract class JavaPlugin implements Plugin {

  public final Server getServer() {
    throw new UnsupportedOperationException("stub");
  }

  public Logger getLogger() {
    throw new UnsupportedOperationException("stub");
  }

  public void onEnable() {}

  public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
    return false;
  }
}
