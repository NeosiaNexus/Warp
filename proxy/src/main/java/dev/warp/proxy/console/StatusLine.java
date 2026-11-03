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
package dev.warp.proxy.console;

import org.jspecify.annotations.Nullable;

/**
 * The status line at the bottom of an interactive console, redrawn once a second:
 *
 * <pre>
 *  ● 12 players · 3/3 servers · 4 conn/s · calm · 212/512 MiB
 * </pre>
 *
 * <p>Only on a real terminal: never in a panel, {@code docker logs} or a file, where a line that
 * redraws itself would print once a second instead.
 */
public final class StatusLine {

  private StatusLine() {}

  /**
   * What the status line shows.
   *
   * @param players the players online
   * @param serversUp the backend servers that answered their last ping
   * @param servers the backend servers
   * @param connectionsPerSecond the connections accepted in the last second
   * @param attack whether the anti-bot sees an attack
   * @param lockdown whether the anti-bot's lockdown is on
   * @param draining what a drain in progress has left, such as {@code draining, 34 left}, or {@code
   *     null}
   * @param usedBytes the heap in use
   * @param maxBytes the maximum heap
   */
  public record Snapshot(
      int players,
      int serversUp,
      int servers,
      long connectionsPerSecond,
      boolean attack,
      boolean lockdown,
      @Nullable String draining,
      long usedBytes,
      long maxBytes) {}

  /**
   * Renders the status line.
   *
   * @param snapshot what it shows
   * @param mode the console's mode
   * @return the line, coloured for the console
   */
  public static String render(Snapshot snapshot, ConsoleMode mode) {
    Glyphs glyphs = Glyphs.of(mode.unicode());
    Styled out = new Styled(mode.palette(), glyphs);
    String dot = " " + glyphs.dot() + " ";
    out.append(' ').append(snapshot.attack() ? Style.BAD : Style.GOOD, glyphs.up()).append(' ');
    out.append(Style.NUMBER, Integer.toString(snapshot.players()))
        .append(snapshot.players() == 1 ? " player" : " players")
        .append(Style.MUTED, dot);
    Style servers =
        snapshot.serversUp() == snapshot.servers()
            ? Style.GOOD
            : snapshot.serversUp() == 0 ? Style.BAD : Style.CAUTION;
    out.append(servers, snapshot.serversUp() + "/" + snapshot.servers())
        .append(" servers")
        .append(Style.MUTED, dot);
    out.append(Style.NUMBER, Long.toString(snapshot.connectionsPerSecond()))
        .append(" conn/s")
        .append(Style.MUTED, dot);
    if (snapshot.attack()) {
      out.append(Style.BAD, "under attack");
    } else {
      out.append(Style.GOOD, "calm");
    }
    if (snapshot.lockdown()) {
      out.append(Style.MUTED, dot).append(Style.CAUTION, "lockdown");
    }
    String draining = snapshot.draining();
    if (draining != null) {
      out.append(Style.MUTED, dot).append(Style.CAUTION, draining);
    }
    out.append(Style.MUTED, dot)
        .append(Style.NUMBER, Long.toString(snapshot.usedBytes() >> 20))
        .append(Style.MUTED, "/" + (snapshot.maxBytes() >> 20) + " MiB");
    return out.toString();
  }
}
