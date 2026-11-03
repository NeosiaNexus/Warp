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
package dev.warp.proxy.console;

import dev.warp.api.command.CommandSource;
import dev.warp.protocol.ProtocolVersion;
import dev.warp.proxy.i18n.PlayerMessages;

import java.util.function.Supplier;

import net.kyori.adventure.chat.ChatType;
import net.kyori.adventure.chat.SignedMessage;
import net.kyori.adventure.text.Component;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * The console as a command source: it has every permission, and what is sent to it is logged, so
 * that it shows in colour on the console and as plain text in the log file. Action bars, titles,
 * sounds and the like mean nothing to a console and are ignored.
 *
 * <p>The proxy's own messages (the commands' replies among them) are rendered in the configured
 * default language, {@code messages.default-locale}, with the operator's overrides; vanilla
 * messages get Warp's text of them, since no game translates them here.
 */
public final class ConsoleCommandSource implements CommandSource {

  /** The logger of what the console is sent; its source tag is {@code console}. */
  private static final Logger logger = LogManager.getLogger("dev.warp.proxy.console.Console");

  private final Supplier<PlayerMessages> messages;

  /** Creates the console of a proxy without messages of its own: Warp's, in American English. */
  public ConsoleCommandSource() {
    this(PlayerMessages::bundled);
  }

  /**
   * Creates the console.
   *
   * @param messages the proxy's messages, read at each message: they are loaded with the
   *     configuration
   */
  public ConsoleCommandSource(Supplier<PlayerMessages> messages) {
    this.messages = messages;
  }

  @Override
  public boolean hasPermission(String permission) {
    return true;
  }

  @Override
  public void sendMessage(Component message) {
    PlayerMessages texts = messages.get();
    logger.info(
        new ComponentMessage(
            texts.render(message, texts.defaultLocale(), ProtocolVersion.oldest())));
  }

  @Override
  public void sendMessage(Component message, ChatType.Bound boundChatType) {
    sendMessage(message);
  }

  @Override
  public void sendMessage(SignedMessage signedMessage, ChatType.Bound boundChatType) {
    Component content = signedMessage.unsignedContent();
    sendMessage(content != null ? content : Component.text(signedMessage.message()));
  }

  @Override
  public String toString() {
    return "the console";
  }
}
