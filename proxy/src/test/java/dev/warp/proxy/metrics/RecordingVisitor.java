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
package dev.warp.proxy.metrics;

import dev.warp.api.metrics.MetricType;
import dev.warp.api.metrics.Metrics;
import dev.warp.api.metrics.SampleVisitor;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Records what a registry collects, for assertions. */
final class RecordingVisitor implements SampleVisitor {

  record Family(String name, MetricType type, String help) {}

  record Sample(String name, List<String> labelNames, List<String> labelValues, double value) {
    String key() {
      StringBuilder key = new StringBuilder(name);
      if (!labelNames.isEmpty()) {
        key.append('{');
        for (int i = 0; i < labelNames.size(); i++) {
          if (i > 0) {
            key.append(',');
          }
          key.append(labelNames.get(i)).append('=').append(labelValues.get(i));
        }
        key.append('}');
      }
      return key.toString();
    }
  }

  private final List<Family> families = new ArrayList<>();
  private final Map<String, Double> samples = new LinkedHashMap<>();
  private final List<Sample> allSamples = new ArrayList<>();

  static RecordingVisitor of(Metrics metrics) {
    RecordingVisitor visitor = new RecordingVisitor();
    metrics.collect(visitor);
    return visitor;
  }

  @Override
  public void family(String name, MetricType type, String help) {
    families.add(new Family(name, type, help));
  }

  @Override
  public void sample(String name, List<String> labelNames, List<String> labelValues, double value) {
    Sample sample = new Sample(name, List.copyOf(labelNames), List.copyOf(labelValues), value);
    allSamples.add(sample);
    samples.put(sample.key(), value);
  }

  List<Family> families() {
    return families;
  }

  Map<String, Double> samples() {
    return samples;
  }

  List<Sample> allSamples() {
    return allSamples;
  }

  double value(String key) {
    Double value = samples.get(key);
    if (value == null) {
      throw new AssertionError("no sample " + key + " in " + samples.keySet());
    }
    return value;
  }

  boolean hasFamily(String name) {
    return families.stream().anyMatch(family -> family.name().equals(name));
  }
}
