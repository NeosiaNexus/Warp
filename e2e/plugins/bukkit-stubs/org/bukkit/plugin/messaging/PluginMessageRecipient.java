package org.bukkit.plugin.messaging;

import org.bukkit.plugin.Plugin;

/** Compile-time stub of Bukkit's PluginMessageRecipient. */
public interface PluginMessageRecipient {

  void sendPluginMessage(Plugin source, String channel, byte[] message);
}
