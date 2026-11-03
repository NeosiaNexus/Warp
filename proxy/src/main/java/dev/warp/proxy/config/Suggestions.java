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

import java.util.Collection;
import java.util.Locale;

import org.jspecify.annotations.Nullable;

/**
 * Finds what an operator probably meant when a name is not one Warp knows: the "did you mean" of
 * unknown settings and message keys.
 */
public final class Suggestions {

  private Suggestions() {}

  /**
   * Returns the known name closest to {@code typed}, if it is close enough to be a typo: at most
   * one edit for names under 8 characters, two under 16, three from there; a swap of two
   * neighbours, a missing, an extra or a wrong character are one edit each. Case is ignored, and
   * {@code _} reads as {@code -}.
   *
   * @param typed the name the operator wrote
   * @param known the names Warp knows
   * @return the closest one, or {@code null} if none is close enough
   */
  public static @Nullable String closest(String typed, Collection<String> known) {
    String target = typed.toLowerCase(Locale.ROOT).replace('_', '-');
    int allowed = target.length() >= 16 ? 3 : target.length() >= 8 ? 2 : 1;
    String best = null;
    int bestDistance = Integer.MAX_VALUE;
    for (String candidate : known) {
      int distance = distance(target, candidate.toLowerCase(Locale.ROOT).replace('_', '-'));
      if (distance < bestDistance) {
        best = candidate;
        bestDistance = distance;
      }
    }
    return bestDistance <= allowed ? best : null;
  }

  /** Optimal string alignment distance: Levenshtein with transpositions of neighbours. */
  static int distance(String a, String b) {
    int[][] d = new int[a.length() + 1][b.length() + 1];
    for (int i = 0; i <= a.length(); i++) {
      d[i][0] = i;
    }
    for (int j = 0; j <= b.length(); j++) {
      d[0][j] = j;
    }
    for (int i = 1; i <= a.length(); i++) {
      for (int j = 1; j <= b.length(); j++) {
        int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
        int best = Math.min(Math.min(d[i - 1][j] + 1, d[i][j - 1] + 1), d[i - 1][j - 1] + cost);
        if (i > 1
            && j > 1
            && a.charAt(i - 1) == b.charAt(j - 2)
            && a.charAt(i - 2) == b.charAt(j - 1)) {
          best = Math.min(best, d[i - 2][j - 2] + 1);
        }
        d[i][j] = best;
      }
    }
    return d[a.length()][b.length()];
  }
}
