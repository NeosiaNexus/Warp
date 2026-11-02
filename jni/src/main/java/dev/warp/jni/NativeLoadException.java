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

import java.io.Serial;

/**
 * A native library could not be found, installed, loaded or bound. The message says why, the advice
 * what an operator can do about it.
 */
final class NativeLoadException extends Exception {

  @Serial private static final long serialVersionUID = 1L;

  /** The advice when nothing but a supported platform would help. */
  static final String UNSUPPORTED_ADVICE =
      "Warp bundles libdeflate for Linux (glibc and musl) and macOS on x86_64 and aarch64, and for"
          + " Windows on x86_64";

  private final String advice;

  /**
   * Creates the exception.
   *
   * @param message why the library is unavailable, readable by an operator
   * @param advice what an operator can do about it, as a sentence without its final period
   */
  NativeLoadException(String message, String advice) {
    super(message);
    this.advice = advice;
  }

  /**
   * Creates the exception with its cause.
   *
   * @param message why the library is unavailable, readable by an operator
   * @param advice what an operator can do about it, as a sentence without its final period
   * @param cause the underlying failure
   */
  NativeLoadException(String message, String advice, Throwable cause) {
    super(message, cause);
    this.advice = advice;
  }

  /**
   * Returns what an operator can do about the failure.
   *
   * @return a sentence without its final period
   */
  String advice() {
    return advice;
  }
}
