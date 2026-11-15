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
package dev.warp.proxy.antibot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.warp.api.player.ClientPlatform;
import dev.warp.protocol.ProtocolVersion;
import dev.warp.proxy.config.AntiBotConfig;
import dev.warp.proxy.event.WarpEventBus;
import dev.warp.proxy.limit.ClientLimits;

import java.net.InetAddress;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("AntiBotService and the client's platform")
class AntiBotPlatformTest {

  private static final ClientPlatform BEDROCK =
      new ClientPlatform.Bedrock(
          2535428912345678L,
          "Steve",
          ClientPlatform.DeviceOs.XBOX,
          ClientPlatform.InputMode.CONTROLLER,
          ClientPlatform.UiProfile.CLASSIC,
          "en_US",
          Optional.empty(),
          InetAddress.getLoopbackAddress());

  private final AntiBotService antiBot =
      AntiBotService.disabled(ClientLimits.unlimited(), new WarpEventBus());

  @Test
  @DisplayName("should exempt a verified Bedrock player, and nobody else, from the world check")
  void worldCheck() {
    assertTrue(antiBot.skipsWorldCheck(BEDROCK));
    assertFalse(antiBot.skipsWorldCheck(ClientPlatform.JAVA));
    assertFalse(antiBot.skipsWorldCheck(ClientPlatform.NEOFORGE));
    assertFalse(antiBot.skipsWorldCheck(new ClientPlatform.Forge(ClientPlatform.ForgeEra.FML2, 2)));
  }

  @Test
  @DisplayName("should give a Bedrock player's probe three times the deadline")
  void deadline() {
    long base = AntiBotConfig.DISABLED.verification().probeTimeout().toNanos();

    Probe java =
        antiBot.newProbe(
            Probe.Kind.PRE_JOIN, ProtocolVersion.MINECRAFT_1_16_4, ClientPlatform.JAVA);
    Probe bedrock =
        antiBot.newProbe(Probe.Kind.PRE_JOIN, ProtocolVersion.MINECRAFT_1_16_4, BEDROCK);

    long now = antiBot.nanoClock().getAsLong();
    assertEquals(base, java.deadlineNanos() - now, base / 10);
    assertEquals(
        AntiBotService.BEDROCK_DEADLINE_FACTOR * base, bedrock.deadlineNanos() - now, base / 10);
  }
}
