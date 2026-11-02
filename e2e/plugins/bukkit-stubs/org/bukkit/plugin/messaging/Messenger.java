package org.bukkit.plugin.messaging;

import org.bukkit.plugin.Plugin;

/** Compile-time stub of Bukkit's Messenger: the members the probe plugin uses. */
public interface Messenger {

  void registerOutgoingPluginChannel(Plugin plugin, String channel);

  PluginMessageListenerRegistration registerIncomingPluginChannel(
      Plugin plugin, String channel, PluginMessageListener listener);
}
