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

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.apache.logging.log4j.message.Message;
import org.jspecify.annotations.Nullable;

/**
 * A log message that is an Adventure component: what a command or a plugin sends to the console.
 * The log file and the JSON format get its plain text; the console renders it in colour.
 */
public final class ComponentMessage implements Message {

  private static final long serialVersionUID = 1L;

  private static final Object[] NO_PARAMETERS = {};

  private final transient Component component;
  private final String plain;

  /**
   * Creates a message.
   *
   * @param component the component
   */
  public ComponentMessage(Component component) {
    this.component = component;
    this.plain = PlainTextComponentSerializer.plainText().serialize(component);
  }

  /**
   * Returns the component.
   *
   * @return the component
   */
  public Component component() {
    return component;
  }

  @Override
  public String getFormattedMessage() {
    return plain;
  }

  @Override
  @SuppressWarnings("deprecation") // Still part of Message; deprecated for Log4j 3.
  public String getFormat() {
    return plain;
  }

  @Override
  public Object[] getParameters() {
    return NO_PARAMETERS;
  }

  @Override
  public @Nullable Throwable getThrowable() {
    return null;
  }
}
