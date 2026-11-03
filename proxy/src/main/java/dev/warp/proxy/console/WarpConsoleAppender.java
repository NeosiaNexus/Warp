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

import java.util.concurrent.TimeUnit;

import org.apache.logging.log4j.core.Appender;
import org.apache.logging.log4j.core.Core;
import org.apache.logging.log4j.core.Filter;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Property;
import org.apache.logging.log4j.core.config.plugins.Plugin;
import org.apache.logging.log4j.core.config.plugins.PluginAttribute;
import org.apache.logging.log4j.core.config.plugins.PluginElement;
import org.apache.logging.log4j.core.config.plugins.PluginFactory;
import org.jspecify.annotations.Nullable;

/**
 * The text console, as a Log4j appender: {@code <WarpConsole name="Console"/>} in {@code
 * log4j2.xml}. Formats each record on the logging thread and hands it to the {@link ConsoleOutput},
 * which writes it from a thread of its own: appending never waits for the terminal.
 */
@Plugin(
    name = "WarpConsole",
    category = Core.CATEGORY_NAME,
    elementType = Appender.ELEMENT_TYPE,
    printObject = true)
public final class WarpConsoleAppender extends AbstractAppender {

  private @Nullable ConsoleOutput output;

  private WarpConsoleAppender(String name, @Nullable Filter filter) {
    super(name, filter, null, true, Property.EMPTY_ARRAY);
  }

  /**
   * Creates the appender; called by Log4j.
   *
   * @param name the appender's name
   * @param filter its filter, or {@code null}
   * @return the appender
   */
  @PluginFactory
  public static WarpConsoleAppender create(
      @PluginAttribute("name") @Nullable String name,
      @PluginElement("Filter") @Nullable Filter filter) {
    return new WarpConsoleAppender(name == null ? "Console" : name, filter);
  }

  @Override
  public void start() {
    output = ConsoleOutput.acquire();
    super.start();
  }

  @Override
  public void append(LogEvent event) {
    ConsoleOutput current = output;
    if (current != null) {
      current.submit(event);
    }
  }

  @Override
  public boolean stop(long timeout, TimeUnit timeUnit) {
    setStopping();
    boolean stopped = super.stop(timeout, timeUnit, false);
    if (output != null) {
      output = null;
      ConsoleOutput.release();
    }
    setStopped();
    return stopped;
  }
}
