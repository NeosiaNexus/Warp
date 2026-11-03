package dev.warp.e2e;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.plugin.messaging.PluginMessageListener;

/**
 * A backend plugin of the end-to-end tests that speaks the BungeeCord channel the way thousands of
 * Paper and Spigot plugins do: {@code Player#sendPluginMessage} on {@code BungeeCord}, which Bukkit
 * renames {@code bungeecord:main} from 1.13.
 *
 * <p>The harness drives it from the console: {@code bungeeprobe <player> <SubChannel> [args...]}
 * sends the sub-channel and its arguments, as strings, through that player's connection. Every
 * reply the proxy sends back is logged as {@code reply <SubChannel> <field>|<field>...}.
 *
 * <p>Compiled for Java 8 against stubs of the few Bukkit types it uses, so that one jar loads on
 * every Paper the matrix runs, from 1.8.8 on.
 */
public final class BungeeProbe extends JavaPlugin implements PluginMessageListener {

  private static final String CHANNEL = "BungeeCord";

  @Override
  public void onEnable() {
    getServer().getMessenger().registerOutgoingPluginChannel(this, CHANNEL);
    getServer().getMessenger().registerIncomingPluginChannel(this, CHANNEL, this);
  }

  @Override
  public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
    if (args.length < 2) {
      sender.sendMessage("usage: bungeeprobe <player> <SubChannel> [args...]");
      return true;
    }
    Player player = getServer().getPlayerExact(args[0]);
    if (player == null) {
      getLogger().info("no player " + args[0]);
      return true;
    }
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    DataOutputStream out = new DataOutputStream(bytes);
    try {
      for (int i = 1; i < args.length; i++) {
        out.writeUTF(args[i]);
      }
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
    player.sendPluginMessage(this, CHANNEL, bytes.toByteArray());
    getLogger().info("sent " + args[1] + " through " + player.getName());
    return true;
  }

  @Override
  public void onPluginMessageReceived(String channel, Player player, byte[] message) {
    DataInputStream in = new DataInputStream(new ByteArrayInputStream(message));
    List<String> fields = new ArrayList<String>();
    String subchannel;
    try {
      subchannel = in.readUTF();
      if (subchannel.equals("PlayerCount") || subchannel.equals("IP")) {
        fields.add(in.readUTF());
        fields.add(String.valueOf(in.readInt()));
      } else if (subchannel.equals("PlayerList") || subchannel.equals("GetPlayerServer")) {
        fields.add(in.readUTF());
        fields.add(in.readUTF());
      } else {
        while (in.available() > 0) {
          fields.add(in.readUTF());
        }
      }
    } catch (IOException e) {
      getLogger().info("unreadable reply on " + channel + ": " + e);
      return;
    }
    StringBuilder joined = new StringBuilder();
    for (String field : fields) {
      if (joined.length() > 0) {
        joined.append('|');
      }
      joined.append(field);
    }
    getLogger().info("reply " + subchannel + " " + joined + " on " + channel);
  }
}
