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
package dev.warp.proxy.config;

/**
 * Thrown when the proxy configuration is invalid or cannot be loaded.
 *
 * <p>This is a checked exception because configuration errors must be handled at startup — the
 * proxy cannot run with an invalid configuration.
 */
public final class ConfigurationException extends Exception {

  ConfigurationException(String message) {
    super(message);
  }

  ConfigurationException(String message, Throwable cause) {
    super(message, cause);
  }
}
