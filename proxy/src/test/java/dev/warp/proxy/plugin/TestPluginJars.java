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
package dev.warp.proxy.plugin;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.warp.api.plugin.processor.PluginProcessor;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.stream.Stream;

import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;

/**
 * Builds real plugin jars for tests: compiles sources with the API's annotation processor (which
 * writes the descriptor) against the test class path, and packs the output in a jar.
 */
final class TestPluginJars {

  private TestPluginJars() {}

  /**
   * Builds a plugin jar.
   *
   * @param pluginsDirectory where the jar goes
   * @param jarName the jar's file name
   * @param sources source files by path relative to the source root
   * @param extraClassPath directories compiled earlier that the sources need (other plugins)
   * @return the directory of the compiled classes, for plugins that depend on this one
   */
  static Path build(
      Path pluginsDirectory, String jarName, Map<String, String> sources, Path... extraClassPath)
      throws IOException {
    Path work = Files.createTempDirectory(pluginsDirectory.getParent(), "build-" + jarName);
    Path sourceRoot = Files.createDirectories(work.resolve("src"));
    Path classes = Files.createDirectories(work.resolve("classes"));
    List<Path> files = new ArrayList<>();
    for (var entry : sources.entrySet()) {
      Path file = sourceRoot.resolve(entry.getKey());
      Files.createDirectories(file.getParent());
      Files.writeString(file, entry.getValue(), StandardCharsets.UTF_8);
      files.add(file);
    }
    StringBuilder classPath = new StringBuilder(System.getProperty("java.class.path"));
    for (Path extra : extraClassPath) {
      classPath.append(java.io.File.pathSeparator).append(extra);
    }
    JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
    assertNotNull(compiler, "tests need a JDK");
    DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
    boolean compiled;
    try (StandardJavaFileManager fileManager =
        compiler.getStandardFileManager(diagnostics, Locale.ROOT, StandardCharsets.UTF_8)) {
      compiled =
          compiler
              .getTask(
                  null,
                  fileManager,
                  diagnostics,
                  List.of(
                      "-classpath",
                      classPath.toString(),
                      "-d",
                      classes.toString(),
                      "-processor",
                      PluginProcessor.class.getName(),
                      "-proc:full"),
                  null,
                  fileManager.getJavaFileObjectsFromPaths(files))
              .call();
    }
    assertTrue(
        compiled,
        () ->
            diagnostics.getDiagnostics().stream()
                .filter(d -> d.getKind() == Diagnostic.Kind.ERROR)
                .map(d -> d.getMessage(Locale.ROOT))
                .toList()
                .toString());

    Files.createDirectories(pluginsDirectory);
    Path jar = pluginsDirectory.resolve(jarName);
    try (OutputStream out = Files.newOutputStream(jar);
        JarOutputStream zip = new JarOutputStream(out);
        Stream<Path> walk = Files.walk(classes)) {
      for (Path file : walk.filter(Files::isRegularFile).sorted().toList()) {
        zip.putNextEntry(new JarEntry(classes.relativize(file).toString().replace('\\', '/')));
        Files.copy(file, zip);
        zip.closeEntry();
      }
    }
    return classes;
  }

  /**
   * Writes a jar holding the given entries as-is.
   *
   * @param jar the jar to write
   * @param entries entry contents by name
   */
  static void raw(Path jar, Map<String, String> entries) throws IOException {
    Files.createDirectories(jar.getParent());
    try (OutputStream out = Files.newOutputStream(jar);
        JarOutputStream zip = new JarOutputStream(out)) {
      for (var entry : entries.entrySet()) {
        zip.putNextEntry(new JarEntry(entry.getKey()));
        zip.write(entry.getValue().getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
      }
    }
  }
}
