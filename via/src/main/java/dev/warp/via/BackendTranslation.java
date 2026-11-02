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
package dev.warp.via;

import dev.warp.api.network.BackendPipeline;
import dev.warp.api.network.PipelineInitializer;
import dev.warp.api.network.PipelinePosition;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

import com.viaversion.viaversion.api.connection.StorableObject;
import com.viaversion.viaversion.api.connection.UserConnection;
import com.viaversion.viaversion.api.protocol.ProtocolManager;
import com.viaversion.viaversion.api.protocol.ProtocolPathEntry;
import com.viaversion.viaversion.api.protocol.version.ProtocolVersion;
import com.viaversion.viaversion.connection.UserConnectionImpl;
import com.viaversion.viaversion.platform.ViaDecodeHandler;
import com.viaversion.viaversion.platform.ViaEncodeHandler;
import com.viaversion.viaversion.protocol.ProtocolPipelineImpl;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;

/**
 * Decides, for each connection the proxy opens to a backend, whether it needs translation, and
 * installs ViaVersion on the ones that do.
 *
 * <p>The proxy speaks the player's version on the backend connection too. When the backend runs
 * another one, ViaVersion's handlers go between the proxy's packet codec and the compression layer
 * ({@link PipelinePosition#PACKET}): the handshake the proxy writes first leaves with the backend's
 * version, and every packet is translated both ways. The proxy's session logic, server switches,
 * plugin messages and command tree all keep working in the player's version.
 *
 * <p>When the two versions match, when the backend's version is unknown, or when no translation
 * path exists, nothing is installed: the connection keeps blind forwarding and compression
 * passthrough, with no Via code on its path at all (ViaVersion#4649, #4610).
 */
final class BackendTranslation implements PipelineInitializer<BackendPipeline> {

  /** The name of ViaVersion's decoder in this plugin's handlers. */
  static final String DECODER = "decoder";

  /** The name of ViaVersion's encoder in this plugin's handlers. */
  static final String ENCODER = "encoder";

  private final BackendVersions versions;
  private final Supplier<ProtocolManager> protocols;
  private final Logger logger;

  /** The version pairs already reported, to report each once: translated, or impossible. */
  private final Set<String> reported = ConcurrentHashMap.newKeySet();

  /**
   * Creates the initializer.
   *
   * @param versions the backend versions
   * @param protocols ViaVersion's protocols, which know the translation paths (ViaVersion creates
   *     them after its injector, hence the supplier)
   * @param logger where impossible translations are reported
   */
  BackendTranslation(BackendVersions versions, Supplier<ProtocolManager> protocols, Logger logger) {
    this.versions = versions;
    this.protocols = protocols;
    this.logger = logger;
  }

  @Override
  public void initialize(BackendPipeline pipeline) {
    ProtocolVersion client = ProtocolVersion.getProtocol(pipeline.protocolVersion());
    String server = pipeline.server().name();
    ProtocolVersion backend = versions.serverProtocolVersion(server);
    Decision decision = decide(client, backend);
    if (decision == Decision.TRANSLATE) {
      install(pipeline, backend);
      if (reported.add(client.getName() + ">" + server)) {
        logger.info(
            "Translating players of {} for {} ({}); players of {} stay untouched",
            client.getName(),
            server,
            backend.getName(),
            backend.getName());
      }
      logger.debug(
          "Translating {} ({}) on its way to {} ({})",
          pipeline.player().username(),
          client.getName(),
          server,
          backend.getName());
    } else if (decision == Decision.IMPOSSIBLE
        && reported.add(client.getName() + ">" + backend.getName())) {
      logger.warn(
          "No translation from {} to {} ({}): such players join it untranslated, and it decides",
          client.getName(),
          backend.getName(),
          server);
    }
  }

  /** What a connection gets. */
  enum Decision {
    /** Nothing: the versions match, or the backend's version is unknown. */
    NONE,
    /** ViaVersion's handlers. */
    TRANSLATE,
    /** Nothing: ViaVersion has no path between the two versions. */
    IMPOSSIBLE
  }

  /**
   * Decides what a connection gets.
   *
   * @param client the player's version
   * @param backend the backend's version, possibly unknown
   * @return the decision
   */
  Decision decide(ProtocolVersion client, ProtocolVersion backend) {
    if (!client.isKnown() || !backend.isKnown() || client.equalTo(backend)) {
      return Decision.NONE;
    }
    List<ProtocolPathEntry> path = protocols.get().getProtocolPath(client, backend);
    return path == null || path.isEmpty() ? Decision.IMPOSSIBLE : Decision.TRANSLATE;
  }

  /**
   * Puts ViaVersion on a connection: a user connection of its own (ViaBungee#5: never one shared by
   * the player's connections), and the two codec handlers.
   */
  private static void install(BackendPipeline pipeline, ProtocolVersion backend) {
    UserConnection user = new UserConnectionImpl(pipeline.channel(), true);
    var _ = new ProtocolPipelineImpl(user);
    user.put(new Target(backend));
    pipeline.addHandler(PipelinePosition.PACKET, DECODER, new ViaDecodeHandler(user));
    pipeline.addHandler(PipelinePosition.PACKET, ENCODER, new ViaEncodeHandler(user));
  }

  /**
   * The backend version decided for a connection, read by {@link WarpVersionProvider} when the
   * handshake goes through: the version used is the one decided here, whatever a probe found in
   * between.
   *
   * @param version the backend's version
   */
  record Target(ProtocolVersion version) implements StorableObject {}

  /**
   * Returns the backend version decided for a user connection.
   *
   * @param user the connection
   * @return the version, or {@code null} if this plugin did not set the connection up
   */
  static @Nullable ProtocolVersion targetOf(UserConnection user) {
    Target target = user.get(Target.class);
    return target != null ? target.version() : null;
  }
}
