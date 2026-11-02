/**
 * Log sinks: a plugin receives the proxy's log records, its own and every other plugin's, to
 * forward them elsewhere (Sentry, Loki, a webhook).
 *
 * <p>Plugins log through SLF4J and cannot add appenders to the proxy's logging backend, which their
 * class loader does not see. A {@link dev.warp.api.logging.LogSink} registered through {@link
 * dev.warp.api.plugin.PluginContext#logSinks()} gets the same records, structured, on a thread of
 * its own, without ever slowing down the code that logs.
 */
@NullMarked
package dev.warp.api.logging;

import org.jspecify.annotations.NullMarked;
