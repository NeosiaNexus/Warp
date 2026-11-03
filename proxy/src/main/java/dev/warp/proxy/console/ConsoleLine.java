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

import org.apache.logging.log4j.Level;
import org.jspecify.annotations.Nullable;

/**
 * A formatted record on its way to the console.
 *
 * @param level the record's level, which decides whether a full queue may drop it
 * @param text the text, possibly coloured, possibly several lines, without the final line feed
 * @param repeatKey the text without its time, to recognise a line repeating the previous one;
 *     {@code null} for lines never collapsed (stack traces, blocks, command replies)
 */
record ConsoleLine(Level level, String text, @Nullable String repeatKey) {}
