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
package dev.warp.protocol.fuzz;

import static org.junit.jupiter.api.Assertions.fail;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import com.code_intelligence.jazzer.api.FuzzedDataProvider;

/**
 * The seed inputs of a fuzz test, defined in code and checked in as files.
 *
 * <p>Jazzer runs a fuzz test on every file of its inputs directory, {@code
 * src/test/resources/<package>/<TestClass>Inputs/<method>/}: each once in regression mode (the
 * {@code test} task), and all of them as the corpus fuzzing starts from. The seeds are the files
 * named {@code seed-*}, and {@link #verify()} keeps them identical to their definition here. Any
 * other file there is a past finding, kept as a regression test.
 *
 * <p>Fuzz tests read their choices with {@link FuzzedDataProvider}, which takes integral values
 * from the end of the input and bytes from its start: a seed is its data followed by its choices,
 * last one first. Each choice takes one byte, which holds for {@code consumeBoolean()}, {@code
 * pickValue(...)} and {@code consumeInt(min, max)} with at most 256 options.
 */
public final class FuzzSeeds {

  /**
   * Set to rewrite the seed files instead of checking them (Gradle: {@code -Pfuzz.updateSeeds}).
   */
  private static final String UPDATE_PROPERTY = "fuzz.updateSeeds";

  private static final String PREFIX = "seed-";

  private static final Pattern NAME = Pattern.compile("[a-z0-9][a-z0-9.-]*");

  private final Class<?> testClass;
  private final Path directory;
  private final Map<String, byte[]> seeds = new TreeMap<>();

  /**
   * Creates the seeds of a fuzz test.
   *
   * @param testClass the class declaring the fuzz test
   * @param method the name of the fuzz test method
   */
  public FuzzSeeds(Class<?> testClass, String method) {
    this.testClass = testClass;
    this.directory =
        Path.of("src/test/resources", testClass.getPackageName().replace('.', '/'))
            .resolve(testClass.getSimpleName() + "Inputs")
            .resolve(method);
  }

  /**
   * Adds a seed.
   *
   * @param name the name of the seed, unique within the test: lowercase letters, digits, dots and
   *     dashes; its file is {@code seed-<name>}
   * @param data the bytes the test consumes from the start of its input
   * @param choices the one-byte choices the test reads, in the order it reads them
   * @return these seeds
   */
  public FuzzSeeds add(String name, byte[] data, int... choices) {
    if (!NAME.matcher(name).matches()) {
      throw new IllegalArgumentException("Invalid seed name: " + name);
    }
    byte[] seed = Arrays.copyOf(data, data.length + choices.length);
    for (int i = 0; i < choices.length; i++) {
      if (choices[i] < 0 || choices[i] > 0xFF) {
        throw new IllegalArgumentException(
            "Choice " + choices[i] + " of " + name + " is not one byte");
      }
      seed[seed.length - 1 - i] = (byte) choices[i];
    }
    if (seeds.putIfAbsent(PREFIX + name, seed) != null) {
      throw new IllegalArgumentException("Duplicate seed " + name);
    }
    return this;
  }

  /**
   * Asserts that the seed files are exactly the seeds defined, or rewrites them when the {@value
   * #UPDATE_PROPERTY} system property is {@code true}.
   *
   * @throws IOException if the inputs directory cannot be read or written
   */
  public void verify() throws IOException {
    Map<String, byte[]> files = readSeedFiles();
    if (Boolean.getBoolean(UPDATE_PROPERTY)) {
      Files.createDirectories(directory);
      for (String file : files.keySet()) {
        if (!seeds.containsKey(file)) {
          Files.delete(directory.resolve(file));
        }
      }
      for (Map.Entry<String, byte[]> seed : seeds.entrySet()) {
        Files.write(directory.resolve(seed.getKey()), seed.getValue());
      }
      return;
    }
    TreeSet<String> names = new TreeSet<>(seeds.keySet());
    names.addAll(files.keySet());
    List<String> outdated =
        names.stream().filter(name -> !Arrays.equals(seeds.get(name), files.get(name))).toList();
    if (!outdated.isEmpty()) {
      fail(
          "Seeds out of date in "
              + directory
              + ": "
              + outdated
              + ". Rewrite them with ./gradlew :protocol:test --tests '"
              + testClass.getName()
              + "' -Pfuzz.updateSeeds");
    }
  }

  private Map<String, byte[]> readSeedFiles() throws IOException {
    Map<String, byte[]> files = new TreeMap<>();
    if (!Files.isDirectory(directory)) {
      return files;
    }
    try (Stream<Path> list = Files.list(directory)) {
      for (Path file : list.toList()) {
        String name = file.getFileName().toString();
        if (name.startsWith(PREFIX)) {
          files.put(name, Files.readAllBytes(file));
        }
      }
    }
    return files;
  }
}
