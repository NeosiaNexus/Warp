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
package dev.warp.proxy.launcher;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.DataInputStream;
import java.io.InputStream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Launcher on a Java too old")
class WarpLauncherTest {

  @Test
  @DisplayName("should read the version of Java 8 and of later Javas")
  void featureVersion() {
    assertEquals(8, WarpLauncher.featureVersion("1.8"));
    assertEquals(21, WarpLauncher.featureVersion("21"));
    assertEquals(25, WarpLauncher.featureVersion("25-ea"));
    assertEquals(0, WarpLauncher.featureVersion("unknown"));
  }

  @Test
  @DisplayName("should tell a Java too old which Java to install and how to start Warp with it")
  void tooOld() {
    String text = WarpLauncher.tooOld(21, "/usr/lib/jvm/java-21");

    assertTrue(text.contains("needs Java 25 or newer"), text);
    assertTrue(text.contains("started with Java 21 (/usr/lib/jvm/java-21)"), text);
    assertTrue(text.contains("-jar warp.jar"), text);
  }

  @Test
  @DisplayName("should be compiled for Java 8, so that an old Java can load it")
  void classFileVersion() throws Exception {
    try (InputStream in = WarpLauncherTest.class.getResourceAsStream("WarpLauncher.class")) {
      assertNotNull(in);
      DataInputStream data = new DataInputStream(in);
      assertEquals(0xCAFEBABE, data.readInt());
      int minor = data.readUnsignedShort();
      assertEquals(0, minor);
      assertEquals(52, data.readUnsignedShort(), "the class file version of Java 8");
    }
  }
}
