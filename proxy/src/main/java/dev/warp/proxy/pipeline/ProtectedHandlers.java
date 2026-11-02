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
package dev.warp.proxy.pipeline;

import static java.util.Objects.requireNonNull;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelPipeline;
import org.jspecify.annotations.Nullable;

/**
 * The handlers a plugin's initializer finds on a connection and must leave where they are: the
 * proxy's own, and those of the plugins initialized before it.
 *
 * <p>Initializers get the raw channel (a translator needs it for its own bookkeeping), so nothing
 * stops one from reaching into the pipeline. The proxy checks, after each initializer, that none of
 * these handlers was removed, replaced or moved; adding handlers is allowed. The check runs only at
 * initialization, a few list operations per initializer: nothing on the packet path.
 */
final class ProtectedHandlers {

  /** No handler: the views of a pipeline outside a registry run. */
  static final ProtectedHandlers NONE = new ProtectedHandlers(List.of(), List.of());

  /** The handlers' names, in pipeline order. */
  private final List<String> names;

  /** The handlers, at the same indexes: replacing one under the same name is a change too. */
  private final List<ChannelHandler> handlers;

  private ProtectedHandlers(List<String> names, List<ChannelHandler> handlers) {
    this.names = names;
    this.handlers = handlers;
  }

  /**
   * Takes every handler of a pipeline, in order.
   *
   * @param pipeline the pipeline
   * @return its handlers
   */
  static ProtectedHandlers of(ChannelPipeline pipeline) {
    return of(pipeline, name -> true);
  }

  private static ProtectedHandlers of(ChannelPipeline pipeline, Predicate<String> kept) {
    List<String> names = new ArrayList<>();
    List<ChannelHandler> handlers = new ArrayList<>();
    for (String name : pipeline.names()) {
      ChannelHandler handler = pipeline.get(name);
      if (handler != null && kept.test(name)) {
        names.add(name);
        handlers.add(handler);
      }
    }
    return new ProtectedHandlers(List.copyOf(names), List.copyOf(handlers));
  }

  /**
   * Adds the handlers the proxy itself put in while it added a plugin handler (the packet
   * decompressor, when a connection switches to packet level). Call it only on a pipeline that just
   * passed {@link #check}, so that nothing a plugin changed is taken in.
   *
   * @param pipeline the pipeline, right after the proxy added the plugin handler
   * @param before the names the pipeline had right before
   * @param pluginHandler the plugin handler's name, which is not protected from its own plugin
   * @return these handlers and the proxy's new ones
   */
  ProtectedHandlers withAddedByProxy(
      ChannelPipeline pipeline, Set<String> before, String pluginHandler) {
    Set<String> kept = new HashSet<>(names);
    for (String name : pipeline.names()) {
      if (!before.contains(name) && !name.equals(pluginHandler)) {
        kept.add(name);
      }
    }
    return of(pipeline, kept::contains);
  }

  /**
   * Checks that every handler is still in the pipeline, under its name and in its order.
   *
   * @param pipeline the pipeline
   * @return the first change found, or {@code null} if there is none
   */
  @SuppressWarnings("ReferenceEquality") // the same instance, not an equal one: see #handlers
  @Nullable Violation check(ChannelPipeline pipeline) {
    List<String> current = pipeline.names();
    int previous = -1;
    for (int i = 0; i < names.size(); i++) {
      String name = names.get(i);
      ChannelHandler handler = pipeline.get(name);
      if (handler == null) {
        return new Violation(Change.REMOVED, name, null);
      }
      if (handler != handlers.get(i)) {
        return new Violation(Change.REPLACED, name, null);
      }
      int index = current.indexOf(name);
      if (index < previous) {
        return new Violation(Change.MOVED, name, names.get(i - 1));
      }
      previous = index;
    }
    return null;
  }

  /** What an initializer did to a protected handler. */
  enum Change {
    /** It is no longer in the pipeline under its name. */
    REMOVED,
    /** Another handler has its name. */
    REPLACED,
    /** It is no longer after the handler that preceded it. */
    MOVED
  }

  /**
   * A change to a protected handler.
   *
   * @param change what happened to it
   * @param handler its name
   * @param preceding for a reordering, the protected handler it now comes before although it
   *     followed it
   */
  record Violation(Change change, String handler, @Nullable String preceding) {

    /**
     * Describes the change, naming the handler and its owner: "removed the proxy's handler
     * 'frame-decoder'".
     *
     * @return the description
     */
    String describe() {
      return switch (change) {
        case REMOVED -> "removed " + named(handler);
        case REPLACED -> "replaced " + named(handler) + " with another handler";
        case MOVED ->
            "reordered the handlers: "
                + named(handler)
                + " now comes before "
                + named(requireNonNull(preceding));
      };
    }

    private static String named(String handler) {
      int colon = handler.indexOf(':');
      String owner =
          colon > 0
              ? "plugin " + handler.substring(0, colon) + "'s handler"
              : "the proxy's handler";
      return owner + " '" + handler + "'";
    }
  }
}
