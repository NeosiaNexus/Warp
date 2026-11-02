package org.bukkit.plugin.messaging;

import org.bukkit.entity.Player;

/** Compile-time stub of Bukkit's PluginMessageListener. */
public interface PluginMessageListener {

  void onPluginMessageReceived(String channel, Player player, byte[] message);
}
