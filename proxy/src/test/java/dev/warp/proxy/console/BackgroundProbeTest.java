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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

@DisplayName("BackgroundProbe")
class BackgroundProbeTest {

  @ParameterizedTest(name = "{0} is light: {1}")
  @CsvSource({
    "rgb:ffff/ffff/ffff, true",
    "rgb:fdfd/f6f6/e3e3, true",
    "rgb:0000/0000/0000, false",
    "rgb:1e1e/1e1e/2e2e, false",
    "rgb:ff/ff/ff, true",
    "rgb:2/2/3, false",
    "rgba:eeee/eeee/eeee/ffff, true"
  })
  @DisplayName("should read the background of an OSC 11 answer, whatever its digits")
  void shouldReadTheBackground(String color, boolean light) {
    // given
    String reply = "\u001B]11;" + color + "\u001B\\\u001B[?62;22c";

    // when
    Boolean found = BackgroundProbe.parse(reply);

    // then
    assertEquals(light, found);
  }

  @Test
  @DisplayName("should know nothing from a terminal that answered the attributes request only")
  void shouldKnowNothingWithoutOsc11() {
    // given
    String reply = "\u001B[?1;2c";

    // when
    Boolean found = BackgroundProbe.parse(reply);

    // then
    assertNull(found);
  }

  @Test
  @DisplayName("should read an answer ended by BEL as well as by ST")
  void shouldReadBelTerminatedAnswers() {
    // given
    String reply = "\u001B]11;rgb:ffff/ffff/ffff\u0007";

    // when
    Boolean found = BackgroundProbe.parse(reply);

    // then
    assertEquals(Boolean.TRUE, found);
  }
}
