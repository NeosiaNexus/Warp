/**
 * Declaring, loading and inspecting plugins.
 *
 * <p>A plugin is a jar in the proxy's {@code plugins} directory whose main class is annotated with
 * {@link dev.warp.api.plugin.Plugin}. The annotation processor shipped with the API turns that
 * annotation into the {@code warp-plugin.json} descriptor the proxy reads, and rejects invalid
 * declarations at compile time.
 *
 * <p>Each plugin gets its own class loader. It sees the Warp API and the libraries the API exposes
 * (Adventure, SLF4J, JSpecify), the classes of the plugins it declares as dependencies, and its own
 * jar; it never sees the proxy's internals or the libraries the proxy uses internally, so a plugin
 * can ship any version of Gson, Guava or Netty without clashing with the proxy or other plugins.
 *
 * <p>The main class has a single public constructor. Its parameters are injected by type: see
 * {@link dev.warp.api.plugin.PluginContext} for what can be injected. Lifecycle callbacks come from
 * {@link dev.warp.api.plugin.WarpPlugin}, which the main class may implement.
 */
@NullMarked
package dev.warp.api.plugin;

import org.jspecify.annotations.NullMarked;
