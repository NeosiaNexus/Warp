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

import dev.warp.api.metrics.MetricNames;
import dev.warp.api.metrics.MetricType;
import dev.warp.api.metrics.Metrics;
import dev.warp.api.metrics.SampleVisitor;

import java.util.List;
import java.util.Locale;

import org.jspecify.annotations.Nullable;

/**
 * Writes a registry in the Prometheus text format, version 0.0.4, or in OpenMetrics 1.0. The two
 * differ in the content type, in {@code # EOF} at the end, and in a counter's family name, which
 * OpenMetrics gives without {@code _total}.
 */
public final class PrometheusText {

  /** The two formats {@code GET /metrics} answers in. */
  public enum Format {
    /** Prometheus text format 0.0.4, the default. */
    TEXT("text/plain; version=0.0.4; charset=utf-8"),

    /** OpenMetrics 1.0, when {@code Accept} asks for {@code application/openmetrics-text}. */
    OPENMETRICS("application/openmetrics-text; version=1.0.0; charset=utf-8");

    private final String contentType;

    Format(String contentType) {
      this.contentType = contentType;
    }

    /**
     * Returns the {@code Content-Type} of a response in this format.
     *
     * @return the content type
     */
    public String contentType() {
      return contentType;
    }
  }

  private static final String OPENMETRICS_TYPE = "application/openmetrics-text";

  private PrometheusText() {}

  /**
   * Picks the format from a request's {@code Accept} header: OpenMetrics when it is listed with a
   * quality above zero, the text format otherwise. Prometheus asks for both with a preference for
   * OpenMetrics, which it is given.
   *
   * @param accept the header, or {@code null} when absent
   * @return the format
   */
  public static Format negotiate(@Nullable String accept) {
    if (accept == null) {
      return Format.TEXT;
    }
    for (String option : accept.split(",")) {
      String[] parts = option.trim().split(";");
      if (!parts[0].trim().equalsIgnoreCase(OPENMETRICS_TYPE)) {
        continue;
      }
      for (int i = 1; i < parts.length; i++) {
        String parameter = parts[i].trim();
        if (parameter.startsWith("q=")) {
          try {
            if (Double.parseDouble(parameter.substring(2)) <= 0) {
              return Format.TEXT;
            }
          } catch (NumberFormatException e) {
            return Format.TEXT;
          }
        }
      }
      return Format.OPENMETRICS;
    }
    return Format.TEXT;
  }

  /**
   * Writes every family of a registry.
   *
   * @param metrics the registry
   * @param format the format
   * @param prefix a prefix families must have to be written, or {@code null} for all of them
   * @return the text
   */
  public static String write(Metrics metrics, Format format, @Nullable String prefix) {
    StringBuilder out = new StringBuilder(64 * 1024);
    metrics.collect(new Writer(out, format, prefix));
    if (format == Format.OPENMETRICS) {
      out.append("# EOF\n");
    }
    return out.toString();
  }

  /**
   * Formats a sample value or a bucket bound: whole numbers without a fraction, others as Java
   * prints a double, infinities as {@code +Inf} and {@code -Inf}.
   *
   * @param value the value
   * @return its text
   */
  public static String number(double value) {
    if (Double.isNaN(value)) {
      return "NaN";
    }
    if (Double.isInfinite(value)) {
      return value > 0 ? "+Inf" : "-Inf";
    }
    if (value == Math.rint(value) && Math.abs(value) < 1e15) {
      return Long.toString((long) value);
    }
    return Double.toString(value);
  }

  private static final class Writer implements SampleVisitor {
    private final StringBuilder out;
    private final Format format;
    private final @Nullable String prefix;
    private boolean skipping;

    Writer(StringBuilder out, Format format, @Nullable String prefix) {
      this.out = out;
      this.format = format;
      this.prefix = prefix;
    }

    @Override
    public void family(String name, MetricType type, String help) {
      skipping = prefix != null && !name.startsWith(prefix);
      if (skipping) {
        return;
      }
      String familyName = name;
      if (format == Format.OPENMETRICS
          && type == MetricType.COUNTER
          && name.endsWith(MetricNames.COUNTER_SUFFIX)) {
        familyName = name.substring(0, name.length() - MetricNames.COUNTER_SUFFIX.length());
      }
      out.append("# HELP ").append(familyName).append(' ');
      escapeHelp(help);
      out.append('\n');
      out.append("# TYPE ")
          .append(familyName)
          .append(' ')
          .append(type.name().toLowerCase(Locale.ROOT))
          .append('\n');
    }

    @Override
    public void sample(
        String name, List<String> labelNames, List<String> labelValues, double value) {
      if (skipping) {
        return;
      }
      out.append(name);
      if (!labelNames.isEmpty()) {
        out.append('{');
        for (int i = 0; i < labelNames.size(); i++) {
          if (i > 0) {
            out.append(',');
          }
          out.append(labelNames.get(i)).append("=\"");
          escapeLabel(labelValues.get(i));
          out.append('"');
        }
        out.append('}');
      }
      out.append(' ').append(number(value)).append('\n');
    }

    private void escapeHelp(String text) {
      for (int i = 0; i < text.length(); i++) {
        char c = text.charAt(i);
        switch (c) {
          case '\\' -> out.append("\\\\");
          case '\n' -> out.append("\\n");
          default -> out.append(c);
        }
      }
    }

    private void escapeLabel(String text) {
      for (int i = 0; i < text.length(); i++) {
        char c = text.charAt(i);
        switch (c) {
          case '\\' -> out.append("\\\\");
          case '"' -> out.append("\\\"");
          case '\n' -> out.append("\\n");
          default -> out.append(c);
        }
      }
    }
  }
}
