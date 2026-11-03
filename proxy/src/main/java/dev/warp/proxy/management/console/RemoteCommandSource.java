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
package dev.warp.proxy.management.console;

import dev.warp.protocol.ProtocolVersion;
import dev.warp.proxy.command.OperatorSource;
import dev.warp.proxy.console.ComponentMessage;
import dev.warp.proxy.i18n.PlayerMessages;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

import net.kyori.adventure.chat.ChatType;
import net.kyori.adventure.chat.SignedMessage;
import net.kyori.adventure.text.Component;
import org.apache.logging.log4j.CloseableThreadContext;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Someone at the remote console, running one command: every permission, as at the console (its
 * token may run commands, or it would not get this far), and what it is sent is both logged (the
 * log file, the local console and the other remote clients see it) and collected for the command's
 * answer.
 *
 * <p>The lines collected are logged with the session they belong to, so that the client that ran
 * the command, which shows them from the answer, does not show them twice from its stream. Lines
 * sent once the answer is written (an asynchronous command replying late) are logged without it:
 * that client then sees them in its stream.
 */
final class RemoteCommandSource implements OperatorSource {

  /** The same logger as the console's: what a command answers reads the same, local or remote. */
  private static final Logger logger = LogManager.getLogger("dev.warp.proxy.console.Console");

  /** The most lines one answer carries; the rest still reach the log. */
  static final int MAX_LINES = 1000;

  private final String user;
  private final String session;
  private final Supplier<PlayerMessages> messages;
  private final List<Component> output = new ArrayList<>();
  private boolean collecting = true;
  private volatile long lastMessage = System.nanoTime();

  /**
   * Creates the source of one command.
   *
   * @param user the name of the token, for the log
   * @param session the client's session, or an empty string
   * @param messages the proxy's messages, which render what is sent in the default language
   */
  RemoteCommandSource(String user, String session, Supplier<PlayerMessages> messages) {
    this.user = user;
    this.session = session;
    this.messages = messages;
  }

  @Override
  public boolean hasPermission(String permission) {
    return true;
  }

  @Override
  public void sendMessage(Component message) {
    PlayerMessages texts = messages.get();
    Component rendered = texts.render(message, texts.defaultLocale(), ProtocolVersion.oldest());
    lastMessage = System.nanoTime();
    synchronized (this) {
      boolean collected = collecting && output.size() < MAX_LINES;
      if (collected) {
        output.add(rendered);
      }
      try (var _ =
          collected
              ? CloseableThreadContext.put(ConsoleRecord.USER_KEY, user)
                  .put(ConsoleRecord.SESSION_KEY, session)
              : CloseableThreadContext.put(ConsoleRecord.USER_KEY, user)) {
        logger.info(new ComponentMessage(rendered));
      }
    }
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

  /**
   * Returns when the last line was sent.
   *
   * @return its {@link System#nanoTime()}
   */
  long lastMessage() {
    return lastMessage;
  }

  /**
   * Stops collecting and returns the lines collected; later lines go to the log only.
   *
   * @return the lines, in the order they were sent
   */
  synchronized List<Component> finish() {
    collecting = false;
    return List.copyOf(output);
  }

  @Override
  public String toString() {
    return "the remote console (" + user + ")";
  }
}
