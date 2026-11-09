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
package dev.warp.proxy.migrate;

import java.util.ArrayList;
import java.util.List;

/**
 * What a migration did with each setting of the source: migrated to a Warp key, dropped with the
 * reason, or worth a warning. Every key of the source ends up in exactly one of the first two
 * lists, so an operator can check nothing was lost silently.
 */
final class Report {

  /** A source setting written to {@code warp.conf}. */
  record Migrated(String source, String value, String target) {}

  /** A source setting Warp has no use for, and why. */
  record Dropped(String source, String reason) {}

  /** Reasons shared by several dropped settings. */
  static final String ALWAYS = "Warp always does this";

  static final String NOT_APPLICABLE = "not applicable to Warp";

  private final List<Migrated> migrated = new ArrayList<>();
  private final List<Dropped> dropped = new ArrayList<>();
  private final List<String> warnings = new ArrayList<>();
  private final List<String> nextSteps = new ArrayList<>();

  /**
   * Records a setting written to the Warp configuration.
   *
   * @param source the source key, as {@code advanced.compression-threshold}
   * @param value the source value, as the report shows it
   * @param target the Warp key, as {@code compression.threshold}
   */
  void keep(String source, Object value, String target) {
    migrated.add(new Migrated(source, Source.describe(value), target));
  }

  /**
   * Records a setting Warp has no use for.
   *
   * @param source the source key
   * @param reason why it was dropped, for the operator
   */
  void drop(String source, String reason) {
    dropped.add(new Dropped(source, reason));
  }

  /**
   * Records something the operator should know before starting Warp.
   *
   * @param text the warning, one sentence or two
   */
  void warn(String text) {
    warnings.add(text);
  }

  /**
   * Records something to do after the migration, such as reconfiguring the backends.
   *
   * @param text the step
   */
  void step(String text) {
    if (!nextSteps.contains(text)) {
      nextSteps.add(text);
    }
  }

  /**
   * Returns the settings written to the Warp configuration.
   *
   * @return the settings, in the order they were migrated
   */
  List<Migrated> migrated() {
    return List.copyOf(migrated);
  }

  /**
   * Returns the settings dropped.
   *
   * @return the settings, in the order they were dropped
   */
  List<Dropped> dropped() {
    return List.copyOf(dropped);
  }

  /**
   * Returns the warnings.
   *
   * @return the warnings, in order
   */
  List<String> warnings() {
    return List.copyOf(warnings);
  }

  /**
   * Returns the steps to take after the migration.
   *
   * @return the steps, in order
   */
  List<String> nextSteps() {
    return List.copyOf(nextSteps);
  }

  /**
   * Returns whether a source key was accounted for, migrated or dropped.
   *
   * @param source the source key
   * @return whether the report mentions it
   */
  boolean accountsFor(String source) {
    return migrated.stream().anyMatch(m -> m.source().equals(source))
        || dropped.stream().anyMatch(d -> d.source().equals(source));
  }
}
