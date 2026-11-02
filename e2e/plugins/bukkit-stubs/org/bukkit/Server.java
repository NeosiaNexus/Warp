package org.bukkit;

import org.bukkit.entity.Player;
import org.bukkit.plugin.messaging.Messenger;

/** Compile-time stub of Bukkit's Server: the members the probe plugin uses. */
public interface Server {

  Player getPlayerExact(String name);

  Messenger getMessenger();
}
