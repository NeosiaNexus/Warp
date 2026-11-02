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

import dev.warp.api.plugin.Dependency;
import dev.warp.api.plugin.Plugin;
import dev.warp.api.plugin.PluginDescription;

import java.io.IOException;
import java.io.Writer;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.RoundEnvironment;
import javax.annotation.processing.SupportedAnnotationTypes;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.NestingKind;
import javax.lang.model.element.TypeElement;
import javax.lang.model.util.ElementFilter;
import javax.tools.Diagnostic;
import javax.tools.FileObject;
import javax.tools.StandardLocation;

import org.jspecify.annotations.Nullable;

/**
 * Checks the class annotated with {@link Plugin} and writes the {@value #DESCRIPTOR} descriptor the
 * proxy loads the plugin from.
 *
 * <p>Every mistake the proxy would otherwise only find when loading the plugin is a compile error
 * here: an invalid identifier, a duplicate or self dependency, a second annotated class, a main
 * class the proxy cannot instantiate (not public, abstract, an inner class, without exactly one
 * public constructor).
 */
@SupportedAnnotationTypes("dev.warp.api.plugin.Plugin")
public final class PluginProcessor extends AbstractProcessor {

  /** Name of the descriptor, at the root of the plugin jar. */
  public static final String DESCRIPTOR = "warp-plugin.json";

  /** The main class already described in this compilation, to reject a second one. */
  private @Nullable String described;

  /** Creates the processor; the compiler instantiates it. */
  public PluginProcessor() {}

  @Override
  public SourceVersion getSupportedSourceVersion() {
    return SourceVersion.latestSupported();
  }

  @Override
  public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment round) {
    for (Element element : round.getElementsAnnotatedWith(Plugin.class)) {
      if (element.getKind() != ElementKind.CLASS) {
        error(element, "@Plugin goes on the plugin's main class");
        continue;
      }
      TypeElement type = (TypeElement) element;
      String binaryName = processingEnv.getElementUtils().getBinaryName(type).toString();
      if (described != null && !described.equals(binaryName)) {
        error(
            type,
            "A plugin has exactly one @Plugin class, and "
                + described
                + " already is the main class of this one");
        continue;
      }
      if (!checkInstantiable(type)) {
        continue;
      }
      Plugin plugin = type.getAnnotation(Plugin.class);
      if (plugin == null || !checkDeclaration(type, plugin)) {
        continue;
      }
      described = binaryName;
      write(type, describe(plugin, binaryName));
    }
    // Never claim the annotation: other processors may read it too.
    return false;
  }

  // ---------------------------------------------------------------------------
  // Checks
  // ---------------------------------------------------------------------------

  private boolean checkInstantiable(TypeElement type) {
    boolean valid = true;
    if (!type.getModifiers().contains(Modifier.PUBLIC)) {
      error(type, "The plugin's main class must be public");
      valid = false;
    }
    if (type.getModifiers().contains(Modifier.ABSTRACT)) {
      error(type, "The plugin's main class cannot be abstract");
      valid = false;
    }
    if (type.getNestingKind() == NestingKind.MEMBER
        && !type.getModifiers().contains(Modifier.STATIC)) {
      error(type, "The plugin's main class cannot be an inner class: make it static");
      valid = false;
    }
    if (type.getNestingKind() == NestingKind.LOCAL
        || type.getNestingKind() == NestingKind.ANONYMOUS) {
      error(type, "The plugin's main class must be a top-level or static nested class");
      valid = false;
    }
    List<ExecutableElement> publicConstructors =
        ElementFilter.constructorsIn(type.getEnclosedElements()).stream()
            .filter(constructor -> constructor.getModifiers().contains(Modifier.PUBLIC))
            .toList();
    if (publicConstructors.size() != 1) {
      error(
          type,
          "The plugin's main class needs exactly one public constructor, whose parameters the"
              + " proxy injects; it has "
              + publicConstructors.size());
      valid = false;
    }
    return valid;
  }

  private boolean checkDeclaration(TypeElement type, Plugin plugin) {
    boolean valid = true;
    if (!PluginDescription.isValidId(plugin.id())) {
      error(
          type,
          "Invalid plugin id '"
              + plugin.id()
              + "': use 1 to 64 lowercase letters, digits, '-' or '_', starting with a letter");
      valid = false;
    }
    Set<String> seen = new HashSet<>();
    for (Dependency dependency : plugin.dependencies()) {
      if (!PluginDescription.isValidId(dependency.id())) {
        error(type, "Invalid dependency id '" + dependency.id() + "'");
        valid = false;
      } else if (dependency.id().equals(plugin.id())) {
        error(type, "A plugin cannot depend on itself");
        valid = false;
      } else if (!seen.add(dependency.id())) {
        error(type, "Dependency '" + dependency.id() + "' is declared twice");
        valid = false;
      }
    }
    return valid;
  }

  // ---------------------------------------------------------------------------
  // Descriptor
  // ---------------------------------------------------------------------------

  /**
   * Writes the descriptor of a plugin as JSON.
   *
   * @param plugin the annotation
   * @param mainClass the binary name of the main class
   * @return the descriptor's content
   */
  static String describe(Plugin plugin, String mainClass) {
    StringBuilder json = new StringBuilder(256).append("{\n");
    field(json, "id", plugin.id()).append(",\n");
    field(json, "name", plugin.name().isBlank() ? plugin.id() : plugin.name()).append(",\n");
    field(json, "version", plugin.version()).append(",\n");
    field(json, "description", plugin.description()).append(",\n");
    field(json, "url", plugin.url()).append(",\n");
    json.append("  \"authors\": [");
    String[] authors = plugin.authors();
    for (int i = 0; i < authors.length; i++) {
      json.append(i == 0 ? "" : ", ");
      string(json, authors[i]);
    }
    json.append("],\n  \"dependencies\": [");
    Dependency[] dependencies = plugin.dependencies();
    for (int i = 0; i < dependencies.length; i++) {
      json.append(i == 0 ? "\n    {\"id\": " : ",\n    {\"id\": ");
      string(json, dependencies[i].id());
      json.append(", \"optional\": ").append(dependencies[i].optional()).append('}');
    }
    json.append(dependencies.length == 0 ? "],\n" : "\n  ],\n");
    field(json, "main", mainClass).append("\n}\n");
    return json.toString();
  }

  private static StringBuilder field(StringBuilder json, String key, String value) {
    json.append("  ");
    string(json, key);
    json.append(": ");
    return string(json, value);
  }

  private static StringBuilder string(StringBuilder json, String value) {
    json.append('"');
    for (int i = 0; i < value.length(); i++) {
      char c = value.charAt(i);
      switch (c) {
        case '"' -> json.append("\\\"");
        case '\\' -> json.append("\\\\");
        case '\n' -> json.append("\\n");
        case '\r' -> json.append("\\r");
        case '\t' -> json.append("\\t");
        default -> {
          // Escape control and non-ASCII characters: the descriptor stays ASCII whatever the
          // compiler's encoding.
          if (c < 0x20 || c > 0x7e) {
            json.append(String.format("\\u%04x", (int) c));
          } else {
            json.append(c);
          }
        }
      }
    }
    return json.append('"');
  }

  private void write(TypeElement type, String descriptor) {
    try {
      FileObject file =
          processingEnv
              .getFiler()
              .createResource(StandardLocation.CLASS_OUTPUT, "", DESCRIPTOR, type);
      try (Writer writer = file.openWriter()) {
        writer.write(descriptor);
      }
    } catch (IOException e) {
      error(type, "Cannot write " + DESCRIPTOR + ": " + e.getMessage());
    }
  }

  private void error(Element element, String message) {
    processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR, message, element);
  }
}
