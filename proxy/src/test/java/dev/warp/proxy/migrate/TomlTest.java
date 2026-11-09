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

import static dev.warp.proxy.migrate.MigrationSupport.resource;
import static dev.warp.proxy.migrate.MigrationSupport.table;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("Toml")
class TomlTest {

  @Nested
  @DisplayName("documents")
  class Documents {

    @Test
    @DisplayName("should parse Velocity's default file")
    void velocityDefault() throws Exception {
      Map<String, Object> root = Toml.parse(resource("velocity.toml"));

      assertEquals("2.9", root.get("config-version"));
      assertEquals("0.0.0.0:25565", root.get("bind"));
      assertEquals(500L, root.get("show-max-players"));
      assertEquals(Boolean.TRUE, root.get("online-mode"));
      Map<?, ?> servers = table(root, "servers");
      assertEquals("127.0.0.1:30066", servers.get("lobby"));
      assertEquals(List.of("lobby"), servers.get("try"));
      Map<?, ?> forced = table(root, "forced-hosts");
      assertEquals(List.of("factions"), forced.get("factions.example.com"));
      Map<?, ?> advanced = table(root, "advanced");
      assertEquals(-1L, advanced.get("compression-level"));
      assertEquals(3000L, advanced.get("login-ratelimit"));
      Map<?, ?> query = table(root, "query");
      assertEquals("Velocity", query.get("map"));
    }

    @Test
    @DisplayName("should keep the order of keys and tables")
    void order() throws Exception {
      Map<String, Object> root = Toml.parse("b = 1\na = 2\n[z]\nx = 1\n[y]\nx = 2\n");

      assertEquals(List.of("b", "a", "z", "y"), List.copyOf(root.keySet()));
    }

    @Test
    @DisplayName("should read dotted keys, dotted headers and inline tables")
    void nesting() throws Exception {
      Map<String, Object> root =
          Toml.parse("a.b.c = 1\n[x.y]\nz = { p = true, q = [1, 2,] }\n\"dotted.name\" = 'lit'\n");

      Map<?, ?> a = table(root, "a");
      assertEquals(1L, (table(a, "b")).get("c"));
      Map<?, ?> y = table(table(root, "x"), "y");
      Map<?, ?> z = table(y, "z");
      assertEquals(Boolean.TRUE, z.get("p"));
      assertEquals(List.of(1L, 2L), z.get("q"));
      assertEquals("lit", y.get("dotted.name"));
    }
  }

  @Nested
  @DisplayName("values")
  class Values {

    @Test
    @DisplayName("should unescape basic strings and keep literal strings")
    void strings() throws Exception {
      Map<String, Object> root =
          Toml.parse(
              "a = \"tab\\there \\\"quoted\\\" \\u00e9\"\nb = 'C:\\path \\n'\n"
                  + "c = \"\"\"\nfirst\nsecond\"\"\"\nd = '''\nraw \\ text'''\n");

      assertEquals("tab\there \"quoted\" \u00e9", root.get("a"));
      assertEquals("C:\\path \\n", root.get("b"));
      assertEquals("first\nsecond", root.get("c"));
      assertEquals("raw \\ text", root.get("d"));
    }

    @Test
    @DisplayName("should read integers, floats and booleans, with underscores and signs")
    void scalars() throws Exception {
      Map<String, Object> root =
          Toml.parse("a = 1_000\nb = -7\nc = +3\nd = 2.5\ne = 1e3\nf = 0xff\ng = false # c\n");

      assertEquals(1000L, root.get("a"));
      assertEquals(-7L, root.get("b"));
      assertEquals(3L, root.get("c"));
      assertEquals(2.5, root.get("d"));
      assertEquals(1000.0, root.get("e"));
      assertEquals(255L, root.get("f"));
      assertEquals(Boolean.FALSE, root.get("g"));
    }

    @Test
    @DisplayName("should read multi-line arrays with comments inside")
    void arrays() throws Exception {
      Map<String, Object> root = Toml.parse("try = [\n  # first\n  \"lobby\",\n  \"hub\"\n]\n");

      assertEquals(List.of("lobby", "hub"), root.get("try"));
    }
  }

  @Nested
  @DisplayName("errors")
  class Errors {

    @Test
    @DisplayName("should name the line of a syntax error")
    void line() {
      Toml.TomlException e =
          assertThrows(Toml.TomlException.class, () -> Toml.parse("a = 1\nb = \n"));

      assertEquals(2, e.line());
      assertTrue(
          String.valueOf(e.getMessage()).startsWith("line 2: "), String.valueOf(e.getMessage()));
    }

    @Test
    @DisplayName("should refuse a key defined twice")
    void duplicate() {
      Toml.TomlException e =
          assertThrows(Toml.TomlException.class, () -> Toml.parse("a = 1\na = 2\n"));

      assertTrue(
          String.valueOf(e.getMessage()).contains("defined twice"), String.valueOf(e.getMessage()));
    }

    @Test
    @DisplayName("should refuse constructs outside the subset Velocity uses")
    void unsupported() {
      assertThrows(Toml.TomlException.class, () -> Toml.parse("[[servers]]\nname = 1\n"));
      assertThrows(Toml.TomlException.class, () -> Toml.parse("d = 1979-05-27\n"));
      assertThrows(Toml.TomlException.class, () -> Toml.parse("a = \"open\n"));
      assertThrows(Toml.TomlException.class, () -> Toml.parse("a = 1 b = 2\n"));
      assertThrows(Toml.TomlException.class, () -> Toml.parse("a = [1, 2\n"));
      assertThrows(Toml.TomlException.class, () -> Toml.parse("a = nope\n"));
    }
  }
}
