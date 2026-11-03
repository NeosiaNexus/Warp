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
package dev.warp.e2e;

import dev.warp.api.command.SimpleCommand;
import dev.warp.api.permission.Tristate;
import dev.warp.api.player.Player;
import dev.warp.api.plugin.Plugin;
import dev.warp.api.plugin.PluginContext;
import dev.warp.api.plugin.WarpPlugin;

import java.net.URI;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.resource.ResourcePackInfo;
import net.kyori.adventure.resource.ResourcePackRequest;
import net.kyori.adventure.sound.Sound;
import net.kyori.adventure.text.Component;

/**
 * Shows what a Warp player receives as an Adventure audience, for the end-to-end harness's {@code
 * audience} scenario: {@code /audience} shows a boss bar, plays a sound at a position and one that
 * follows the player, and sends the proxy's resource pack, whose statuses come back as chat lines
 * ({@code audience pack <STATUS>}).
 *
 * <p>The bar is animated for as long as the proxy runs, ten updates a second, so that a bot sees
 * updates arrive across its server switches: one sent while its client has no bar (in the
 * configuration phase from 1.20.2) fails the run. The pack comes from the system properties {@code
 * warp.e2e.pack.url}, {@code warp.e2e.pack.sha1} and {@code warp.e2e.pack.id}.
 *
 * <p>It is also the run's permission provider: players whose name starts with {@value
 * #ADMIN_PREFIX} hold every {@code warp.command.*} permission, for the {@code admin-commands}
 * scenario, and nobody else holds any.
 */
@Plugin(
    id = "e2e-audience",
    version = "1",
    description = "Boss bar, sounds and resource pack for the end-to-end harness")
public final class AudienceProbe implements WarpPlugin {

  /** The sound both plays use, in every version's sound list since 1.9 (a name before). */
  private static final Sound SOUND =
      Sound.sound(Key.key("entity.experience_orb.pickup"), Sound.Source.PLAYER, 1, 1);

  /** Players whose name starts with this hold the proxy's command permissions. */
  static final String ADMIN_PREFIX = "e2e_admin";

  private final PluginContext context;

  /** The bar every player who ran the command sees. */
  private final BossBar bar =
      BossBar.bossBar(
          Component.text("Warp e2e"), 0, BossBar.Color.PINK, BossBar.Overlay.NOTCHED_10);

  private final ScheduledExecutorService animation =
      Executors.newSingleThreadScheduledExecutor(
          Thread.ofPlatform().name("e2e-audience").daemon().factory());

  /**
   * Creates the plugin.
   *
   * @param context the plugin's context
   */
  public AudienceProbe(PluginContext context) {
    this.context = context;
  }

  @Override
  public void enable() {
    context.commands().register("audience", this::show);
    var _ =
        context
            .permissions()
            .register(
                0,
                (player, permission) ->
                    player.username().startsWith(ADMIN_PREFIX)
                            && permission.startsWith("warp.command.")
                        ? Tristate.TRUE
                        : Tristate.UNDEFINED);
    var _ = animation.scheduleAtFixedRate(this::animate, 100, 100, TimeUnit.MILLISECONDS);
  }

  @Override
  public void disable() {
    animation.shutdownNow();
  }

  private void animate() {
    float next = bar.progress() + 0.05f;
    bar.progress(next > 1 ? 0 : next);
    if (next > 1) {
      bar.color(bar.color() == BossBar.Color.PINK ? BossBar.Color.BLUE : BossBar.Color.PINK);
    }
  }

  private void show(SimpleCommand.Invocation invocation) {
    if (!(invocation.source() instanceof Player player)) {
      invocation.source().sendMessage(Component.text("audience: players only"));
      return;
    }
    player.showBossBar(bar);
    player.playSound(SOUND, 0.5, 64, 0.5);
    player.playSound(SOUND);
    String url = System.getProperty("warp.e2e.pack.url");
    String sha1 = System.getProperty("warp.e2e.pack.sha1");
    String id = System.getProperty("warp.e2e.pack.id");
    if (url != null && sha1 != null && id != null) {
      player.sendResourcePacks(
          ResourcePackRequest.resourcePackRequest()
              .packs(ResourcePackInfo.resourcePackInfo(UUID.fromString(id), URI.create(url), sha1))
              .callback(
                  (pack, status, audience) ->
                      audience.sendMessage(Component.text("audience pack " + status.name())))
              .build());
    }
    player.sendMessage(Component.text("audience shown"));
  }
}
