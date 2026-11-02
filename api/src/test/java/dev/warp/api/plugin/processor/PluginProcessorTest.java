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
package dev.warp.api.plugin.processor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@DisplayName("PluginProcessor")
class PluginProcessorTest {

  @TempDir private Path directory;

  @Nested
  @DisplayName("descriptor")
  class Descriptor {

    @Test
    @DisplayName("should write every declared field, with defaults for the rest")
    void writesDescriptor() throws IOException {
      Compilation compilation =
          compile(
              Map.of(
                  "demo/Router.java",
                  """
                  package demo;
                  import dev.warp.api.plugin.*;
                  @Plugin(id = "router", version = "1.2.0", authors = {"Steve", "Al\\"ex"},
                      description = "Routes \\u00e9l\\u00e8ves",
                      dependencies = {@Dependency(id = "db"), @Dependency(id = "perms", optional = true)})
                  public final class Router {
                    public Router() {}
                  }
                  """));

      assertTrue(compilation.errors().isEmpty(), compilation.errors().toString());
      String descriptor = compilation.descriptor();
      assertEquals(
          """
          {
            "id": "router",
            "name": "router",
            "version": "1.2.0",
            "description": "Routes \\u00e9l\\u00e8ves",
            "url": "",
            "authors": ["Steve", "Al\\"ex"],
            "dependencies": [
              {"id": "db", "optional": false},
              {"id": "perms", "optional": true}
            ],
            "main": "demo.Router"
          }
          """,
          descriptor);
    }

    @Test
    @DisplayName("should name a static nested main class by its binary name")
    void nestedClass() throws IOException {
      Compilation compilation =
          compile(
              Map.of(
                  "demo/Outer.java",
                  """
                  package demo;
                  public final class Outer {
                    @dev.warp.api.plugin.Plugin(id = "nested")
                    public static final class Main { public Main() {} }
                  }
                  """));

      assertTrue(compilation.errors().isEmpty(), compilation.errors().toString());
      assertTrue(compilation.descriptor().contains("\"main\": \"demo.Outer$Main\""));
    }
  }

  @Nested
  @DisplayName("errors")
  class Errors {

    @Test
    @DisplayName("should reject an invalid identifier")
    void invalidId() throws IOException {
      assertError(
          plugin("@Plugin(id = \"Not Valid\")", "public final class Main { public Main() {} }"),
          "Invalid plugin id");
    }

    @Test
    @DisplayName("should reject a main class without exactly one public constructor")
    void constructors() throws IOException {
      assertError(
          plugin(
              "@Plugin(id = \"two\")",
              "public final class Main { public Main() {} public Main(int x) {} }"),
          "exactly one public constructor");
    }

    @Test
    @DisplayName("should reject a main class that is not public")
    void notPublic() throws IOException {
      assertError(
          plugin("@Plugin(id = \"hidden\")", "final class Main { public Main() {} }"), "public");
    }

    @Test
    @DisplayName("should reject an abstract main class")
    void abstractClass() throws IOException {
      assertError(
          plugin("@Plugin(id = \"abs\")", "public abstract class Main { public Main() {} }"),
          "abstract");
    }

    @Test
    @DisplayName("should reject a dependency on itself and a duplicate dependency")
    void dependencies() throws IOException {
      assertError(
          plugin(
              "@Plugin(id = \"self\", dependencies = @Dependency(id = \"self\"))",
              "public final class Main { public Main() {} }"),
          "cannot depend on itself");
      assertError(
          plugin(
              "@Plugin(id = \"dup\", dependencies = {@Dependency(id = \"db\"), @Dependency(id = \"db\")})",
              "public final class Main { public Main() {} }"),
          "declared twice");
    }

    @Test
    @DisplayName("should reject a second annotated class")
    void twoPlugins() throws IOException {
      Compilation compilation =
          compile(
              Map.of(
                  "demo/A.java",
                  "package demo; @dev.warp.api.plugin.Plugin(id = \"a\") public final class A { public A() {} }",
                  "demo/B.java",
                  "package demo; @dev.warp.api.plugin.Plugin(id = \"b\") public final class B { public B() {} }"));

      assertTrue(
          compilation.errors().stream().anyMatch(e -> e.contains("exactly one @Plugin class")),
          compilation.errors().toString());
    }

    private Compilation plugin(String annotation, String declaration) throws IOException {
      return compile(
          Map.of(
              "demo/Main.java",
              "package demo;\nimport dev.warp.api.plugin.*;\n" + annotation + "\n" + declaration));
    }

    private void assertError(Compilation compilation, String fragment) {
      assertFalse(compilation.errors().isEmpty(), "expected an error containing: " + fragment);
      assertTrue(
          compilation.errors().stream().anyMatch(e -> e.contains(fragment)),
          compilation.errors().toString());
    }
  }

  /** The outcome of compiling sources with the processor. */
  private record Compilation(List<String> errors, Path output) {

    String descriptor() throws IOException {
      Path file = output.resolve(PluginProcessor.DESCRIPTOR);
      assertTrue(Files.exists(file), "no descriptor written");
      return Files.readString(file, StandardCharsets.US_ASCII);
    }
  }

  private Compilation compile(Map<String, String> sources) throws IOException {
    Path sourceDir = Files.createDirectories(directory.resolve("src"));
    Path output = Files.createDirectories(directory.resolve("out"));
    List<Path> files = new ArrayList<>();
    for (var entry : sources.entrySet()) {
      Path file = sourceDir.resolve(entry.getKey());
      Files.createDirectories(file.getParent());
      Files.writeString(file, entry.getValue(), StandardCharsets.UTF_8);
      files.add(file);
    }
    JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
    assertNotNull(compiler, "tests need a JDK");
    DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
    try (StandardJavaFileManager fileManager =
        compiler.getStandardFileManager(diagnostics, Locale.ROOT, StandardCharsets.UTF_8)) {
      compiler
          .getTask(
              null,
              fileManager,
              diagnostics,
              List.of(
                  "-classpath",
                  System.getProperty("java.class.path"),
                  "-d",
                  output.toString(),
                  "-processor",
                  PluginProcessor.class.getName(),
                  "-encoding",
                  "UTF-8"),
              null,
              fileManager.getJavaFileObjectsFromPaths(files))
          .call();
    }
    List<String> errors =
        diagnostics.getDiagnostics().stream()
            .filter(d -> d.getKind() == Diagnostic.Kind.ERROR)
            .map(d -> d.getMessage(Locale.ROOT))
            .collect(Collectors.toList());
    return new Compilation(errors, output);
  }
}
