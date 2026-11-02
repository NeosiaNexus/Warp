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
package dev.warp.jni;

/**
 * Whether a native library can be used, and the words an operator needs either way.
 *
 * @param available whether the library loaded and passed its start-up self-test
 * @param detail what was loaded and from where, or why nothing could be: for example {@code
 *     libdeflate 1.26 (linux-x86_64) from /home/warp/.cache/warp/natives/libdeflate-...so}
 * @param advice what an operator should do, as a sentence without its final period: about an
 *     unavailable library, or about a working one the JVM warns about (native access not enabled);
 *     empty when there is nothing to do
 */
public record NativeStatus(boolean available, String detail, String advice) {}
