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
package dev.warp.protocol.netty;

import io.netty.handler.codec.DecoderException;

/**
 * Thrown by {@link FrameDecoder} for a frame the {@link FrameLimits} of the client's state refuse,
 * from its header alone. No vanilla client sends such a frame, so the proxy counts it as a protocol
 * violation rather than logging it.
 *
 * <p>Instances are shared and carry no stack trace: throwing one costs no allocation, whatever rate
 * a flood sends them at.
 */
public final class FrameLimitException extends DecoderException {

  private static final long serialVersionUID = 1L;

  /** What is wrong with the frame. */
  public enum Reason {
    /** Longer than any packet a client may send in the state. */
    TOO_LONG,
    /** A packet identifier the state does not allow. */
    PACKET_NOT_ALLOWED,
    /** A length outside the bounds of the packet it announces. */
    WRONG_LENGTH
  }

  private final Reason reason;

  /**
   * Creates an exception without a stack trace.
   *
   * @param reason what is wrong with the frame
   * @param message the message
   */
  FrameLimitException(Reason reason, String message) {
    super(message);
    this.reason = reason;
  }

  /**
   * Returns what is wrong with the frame.
   *
   * @return the reason
   */
  public Reason reason() {
    return reason;
  }

  @Override
  public synchronized Throwable fillInStackTrace() {
    return this;
  }
}
