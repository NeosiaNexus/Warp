/**
 * The event bus: how plugins observe and shape what the proxy does.
 *
 * <h2>Guarantees</h2>
 *
 * <ul>
 *   <li><b>Deterministic order.</b> The handlers of an event run one after another, highest {@link
 *       dev.warp.api.event.Subscribe#priority() priority} first; handlers of equal priority run in
 *       the order they were registered. A handler sees every change the handlers before it made.
 *   <li><b>Asynchronous handlers hold the event.</b> A handler returning a {@link
 *       java.util.concurrent.CompletionStage} holds the event until the stage completes, so the
 *       next handlers, and the proxy, see its outcome. A handler that takes longer than its {@link
 *       dev.warp.api.event.Subscribe#timeoutSeconds() time-out} is reported and skipped: one
 *       forgotten future can never freeze logins.
 *   <li><b>Per-player order.</b> Events about one account are delivered in the order they happened,
 *       each only once every handler is done with the previous one. In particular, the disconnect
 *       event of a session is fully handled before the login event of the next session of the same
 *       account starts, so a plugin never unloads data it just loaded.
 *   <li><b>Never on network threads.</b> Handlers run on virtual threads. Blocking (a database
 *       query) is allowed; it holds that event, and later events of that player, not the proxy.
 *   <li><b>Failures stay contained.</b> A handler that throws is logged with its plugin, method and
 *       event; the other handlers still run.
 *   <li><b>Plugin scope.</b> Every subscription is owned by the plugin that made it and removed
 *       when the plugin is disabled or fails. Handlers run with their plugin's class loader as the
 *       context class loader.
 * </ul>
 */
@NullMarked
package dev.warp.api.event;

import org.jspecify.annotations.NullMarked;
