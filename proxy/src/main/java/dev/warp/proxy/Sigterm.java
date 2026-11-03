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
package dev.warp.proxy;

import dev.warp.proxy.logging.LogCode;

import java.lang.reflect.Proxy;
import java.util.concurrent.atomic.AtomicBoolean;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Catches SIGTERM so that it starts a drain rather than the JVM's shutdown.
 *
 * <p>A drain can last half an hour. Run from a shutdown hook, it would run while the JVM refuses
 * new shutdown hooks, deadlocks any {@code System.exit} (from a plugin, say) and exits with 143
 * whatever happened. Caught instead, SIGTERM only starts the drain: the proxy keeps running
 * normally until it stops itself, with an exit code that tells how the drain went. A second SIGTERM
 * stops it at once. SIGINT (Ctrl+C) and SIGQUIT (thread dump) keep the JVM's behaviour.
 *
 * <p>Uses {@code sun.misc.Signal}, the JDK's supported way to handle signals (module {@code
 * jdk.unsupported}), through reflection so that the build stays free of proprietary API warnings.
 */
final class Sigterm {

  private static final Logger logger = LoggerFactory.getLogger(Sigterm.class);

  private Sigterm() {}

  /**
   * Installs the SIGTERM handler. Both callbacks run on the JVM's signal thread and must return at
   * once.
   *
   * @param first runs on the first SIGTERM
   * @param again runs on every later one
   * @return whether the handler is installed; {@code false} on a JVM that keeps SIGTERM to itself
   *     (with {@code -Xrs}, or without {@code jdk.unsupported}), where SIGTERM still runs the
   *     shutdown hooks
   */
  @SuppressWarnings("ReferenceEquality") // A proxy equals only itself: identity, not equality.
  static boolean install(Runnable first, Runnable again) {
    try {
      Class<?> signalClass = Class.forName("sun.misc.Signal");
      Class<?> handlerClass = Class.forName("sun.misc.SignalHandler");
      Object signal = signalClass.getConstructor(String.class).newInstance("TERM");
      AtomicBoolean received = new AtomicBoolean();
      Object handler =
          Proxy.newProxyInstance(
              Sigterm.class.getClassLoader(),
              new Class<?>[] {handlerClass},
              (proxy, method, args) ->
                  switch (method.getName()) {
                    case "handle" -> {
                      run(received.compareAndSet(false, true) ? first : again);
                      yield null;
                    }
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> args != null && args.length == 1 && proxy == args[0];
                    default -> "Warp's SIGTERM handler";
                  });
      var _ =
          signalClass.getMethod("handle", signalClass, handlerClass).invoke(null, signal, handler);
      return true;
    } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
      logger.debug("Cannot handle SIGTERM", e);
      return false;
    }
  }

  private static void run(Runnable callback) {
    try {
      callback.run();
    } catch (RuntimeException e) {
      logger.error(LogCode.SIGTERM_HANDLER_FAILED.marker(), "Handling SIGTERM failed", e);
    }
  }
}
