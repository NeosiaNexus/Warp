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
package dev.warp.proxy.management.console;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.jspecify.annotations.Nullable;

/**
 * Keeps secrets out of the streamed log: the proxy's own secrets (the tokens, the forwarding
 * secret) wherever they appear, and whatever follows {@code Bearer} or a {@code token=}, {@code
 * password=} or {@code secret=}. What a log line holds is never trusted to be clean: a plugin may
 * log a header, an exception may quote a configuration value.
 */
final class Redactor {

  /** What replaces a secret. */
  static final String MASK = "[redacted]";

  /** Secrets shorter than this are not searched for: they would mask ordinary words. */
  private static final int MIN_SECRET_LENGTH = 8;

  private static final Pattern CREDENTIALS =
      Pattern.compile(
          "(?i)(\\bbearer\\s+|\\b(?:token|password|passwd|secret|api[-_]?key)\\s*[=:]\\s*)"
              + "([^\\s,;\"'&]+)");

  private final List<String> secrets;

  /**
   * Creates a redactor.
   *
   * @param secrets the values to mask; the short and the blank ones are ignored
   */
  Redactor(Collection<String> secrets) {
    List<String> kept = new ArrayList<>();
    for (String secret : secrets) {
      if (secret.length() >= MIN_SECRET_LENGTH && !kept.contains(secret)) {
        kept.add(secret);
      }
    }
    // The longest first: a secret that contains another is masked whole.
    kept.sort(Comparator.comparingInt(String::length).reversed());
    this.secrets = List.copyOf(kept);
  }

  /**
   * Masks the secrets of a text.
   *
   * @param text the text, or {@code null}
   * @return the text without secrets, or {@code null}
   */
  @Nullable String redact(@Nullable String text) {
    if (text == null || text.isEmpty()) {
      return text;
    }
    String result = text;
    for (String secret : secrets) {
      if (result.contains(secret)) {
        result = result.replace(secret, MASK);
      }
    }
    Matcher matcher = CREDENTIALS.matcher(result);
    if (!matcher.find()) {
      return result;
    }
    StringBuilder out = new StringBuilder(result.length());
    do {
      String value = matcher.group(2);
      matcher.appendReplacement(
          out, Matcher.quoteReplacement(matcher.group(1) + (value.equals(MASK) ? value : MASK)));
    } while (matcher.find());
    matcher.appendTail(out);
    return out.toString();
  }

  /**
   * Masks the secrets of a text that is never {@code null}.
   *
   * @param text the text
   * @return the text without secrets
   */
  String clean(String text) {
    String result = redact(text);
    return result == null ? "" : result;
  }
}
