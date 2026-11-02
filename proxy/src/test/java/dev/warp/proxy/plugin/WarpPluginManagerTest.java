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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import dev.warp.api.Warp;
import dev.warp.api.plugin.PluginContainer;
import dev.warp.api.plugin.PluginState;
import dev.warp.proxy.event.WarpEventBus;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@DisplayName("WarpPluginManager")
class WarpPluginManagerTest {

  /** Plugins report what they saw through system properties, the only state they share. */
  private static final String PROBE = "warp.test.probe.";

  @TempDir private Path root;

  private Path plugins;
  private WarpEventBus events;
  private WarpPluginManager manager;

  @BeforeEach
  void setUp() {
    plugins = root.resolve("plugins");
    events = new WarpEventBus();
    manager = new WarpPluginManager(mock(Warp.class), events, plugins);
  }

  @AfterEach
  void tearDown() {
    manager.disableAll();
    events.close();
    System.getProperties().keySet().removeIf(key -> key.toString().startsWith(PROBE));
  }

  @Nested
  @DisplayName("loading")
  class Loading {

    @Test
    @DisplayName("should construct a plugin with its services injected, then enable it")
    void constructsAndEnables() throws IOException {
      TestPluginJars.build(
          plugins,
          "hello.jar",
          Map.of(
              "hello/Hello.java",
              """
              package hello;
              import dev.warp.api.Warp;
              import dev.warp.api.plugin.*;
              import java.nio.file.Path;
              import org.slf4j.Logger;
              @Plugin(id = "hello", version = "1.0.0", authors = "Steve")
              public final class Hello implements WarpPlugin {
                public Hello(PluginContext context, Warp proxy, Logger logger,
                    PluginDescription description, @DataDirectory Path data) {
                  if (context.proxy() != proxy || !logger.getName().equals("hello")
                      || !description.id().equals("hello")
                      || !data.getFileName().toString().equals("hello")) {
                    throw new IllegalStateException("wrong injection");
                  }
                  System.setProperty("warp.test.probe.constructed", "yes");
                }
                @Override public void enable() {
                  System.setProperty("warp.test.probe.enabled", "yes");
                }
              }
              """));

      manager.loadAll();

      PluginContainer hello = manager.plugin("hello").orElseThrow();
      assertEquals(PluginState.ENABLED, hello.state(), () -> hello.failure().toString());
      assertEquals("yes", System.getProperty(PROBE + "constructed"));
      assertEquals("yes", System.getProperty(PROBE + "enabled"));
      assertTrue(manager.isEnabled("hello"));
      Object instance = hello.instance().orElseThrow();
      assertSame(hello, manager.fromInstance(instance).orElseThrow());
      assertTrue(Files.isDirectory(hello.dataDirectory()));
    }

    @Test
    @DisplayName("should load dependencies first and let dependents use their classes")
    void dependencyOrderAndVisibility() throws IOException {
      Path database =
          TestPluginJars.build(
              plugins,
              "z-database.jar",
              Map.of(
                  "db/Database.java",
                  """
                  package db;
                  @dev.warp.api.plugin.Plugin(id = "database")
                  public final class Database {
                    public static String query() { return "rows"; }
                    public Database() {}
                  }
                  """));
      TestPluginJars.build(
          plugins,
          "a-app.jar",
          Map.of(
              "app/App.java",
              """
              package app;
              import dev.warp.api.plugin.*;
              @Plugin(id = "app", dependencies = @Dependency(id = "database"))
              public final class App {
                public App() {
                  System.setProperty("warp.test.probe.query", db.Database.query());
                }
              }
              """),
          database);

      manager.loadAll();

      assertEquals(
          List.of("database", "app"),
          manager.plugins().stream().map(p -> p.description().id()).toList());
      assertTrue(manager.isEnabled("app"));
      assertEquals("rows", System.getProperty(PROBE + "query"));
    }

    @Test
    @DisplayName("should run plugin code with the plugin's class loader as context class loader")
    void contextClassLoader() throws IOException {
      TestPluginJars.build(
          plugins,
          "tccl.jar",
          Map.of(
              "tccl/Main.java",
              """
              package tccl;
              @dev.warp.api.plugin.Plugin(id = "tccl")
              public final class Main {
                public Main() {
                  if (Thread.currentThread().getContextClassLoader() != Main.class.getClassLoader()) {
                    throw new IllegalStateException("wrong context class loader");
                  }
                }
              }
              """));

      manager.loadAll();

      assertTrue(manager.isEnabled("tccl"));
    }
  }

  @Nested
  @DisplayName("isolation")
  class Isolation {

    @Test
    @DisplayName("should let plugins use the API's libraries")
    void seesApiLibraries() throws IOException {
      TestPluginJars.build(
          plugins,
          "adventure.jar",
          Map.of(
              "adv/Main.java",
              """
              package adv;
              import net.kyori.adventure.text.Component;
              @dev.warp.api.plugin.Plugin(id = "adventure")
              public final class Main {
                public Main() { Component.text("hi"); }
              }
              """));

      manager.loadAll();

      assertTrue(manager.isEnabled("adventure"));
    }

    @Test
    @DisplayName("should hide the proxy's internals from plugins")
    void hidesProxyInternals() throws IOException {
      TestPluginJars.build(
          plugins,
          "sneaky.jar",
          Map.of(
              "sneaky/Main.java",
              """
              package sneaky;
              @dev.warp.api.plugin.Plugin(id = "sneaky")
              public final class Main {
                public Main() { new dev.warp.proxy.player.PlayerRegistry(); }
              }
              """));

      manager.loadAll();

      PluginContainer sneaky = manager.plugin("sneaky").orElseThrow();
      assertEquals(PluginState.FAILED, sneaky.state());
      assertInstanceOf(NoClassDefFoundError.class, sneaky.failure().orElseThrow());
    }

    @Test
    @DisplayName("should hide the libraries the proxy uses internally")
    void hidesInternalLibraries() throws IOException {
      TestPluginJars.build(
          plugins,
          "gson.jar",
          Map.of(
              "gs/Main.java",
              """
              package gs;
              @dev.warp.api.plugin.Plugin(id = "gson-user")
              public final class Main {
                public Main() { new com.google.gson.Gson(); }
              }
              """));

      manager.loadAll();

      assertEquals(PluginState.FAILED, manager.plugin("gson-user").orElseThrow().state());
    }

    @Test
    @DisplayName("should hide plugins that are not declared as dependencies")
    void hidesUndeclaredPlugins() throws IOException {
      Path database =
          TestPluginJars.build(
              plugins,
              "a-database.jar",
              Map.of(
                  "db/Database.java",
                  """
                  package db;
                  @dev.warp.api.plugin.Plugin(id = "database")
                  public final class Database { public Database() {} }
                  """));
      TestPluginJars.build(
          plugins,
          "b-intruder.jar",
          Map.of(
              "intruder/Main.java",
              """
              package intruder;
              @dev.warp.api.plugin.Plugin(id = "intruder")
              public final class Main {
                public Main() { new db.Database(); }
              }
              """),
          database);

      manager.loadAll();

      assertTrue(manager.isEnabled("database"));
      assertEquals(PluginState.FAILED, manager.plugin("intruder").orElseThrow().state());
    }
  }

  @Nested
  @DisplayName("failures")
  class Failures {

    @Test
    @DisplayName("should fail a plugin whose required dependency is missing, and load the others")
    void missingDependency() throws IOException {
      TestPluginJars.build(
          plugins,
          "needy.jar",
          Map.of(
              "needy/Main.java",
              """
              package needy;
              import dev.warp.api.plugin.*;
              @Plugin(id = "needy", dependencies = @Dependency(id = "absent"))
              public final class Main { public Main() {} }
              """));
      TestPluginJars.build(plugins, "fine.jar", Map.of("fine/Main.java", simplePlugin("fine")));

      manager.loadAll();

      PluginContainer needy = manager.plugin("needy").orElseThrow();
      assertEquals(PluginState.FAILED, needy.state());
      assertTrue(String.valueOf(needy.failure().orElseThrow().getMessage()).contains("'absent'"));
      assertTrue(needy.instance().isEmpty());
      assertTrue(manager.isEnabled("fine"));
    }

    @Test
    @DisplayName("should load a plugin whose optional dependency is missing")
    void missingOptionalDependency() throws IOException {
      TestPluginJars.build(
          plugins,
          "relaxed.jar",
          Map.of(
              "relaxed/Main.java",
              """
              package relaxed;
              import dev.warp.api.plugin.*;
              @Plugin(id = "relaxed", dependencies = @Dependency(id = "absent", optional = true))
              public final class Main { public Main() {} }
              """));

      manager.loadAll();

      assertTrue(manager.isEnabled("relaxed"));
    }

    @Test
    @DisplayName("should fail the plugins requiring a plugin whose enable threw")
    void cascadesEnableFailure() throws IOException {
      Path broken =
          TestPluginJars.build(
              plugins,
              "broken.jar",
              Map.of(
                  "broken/Main.java",
                  """
                  package broken;
                  @dev.warp.api.plugin.Plugin(id = "broken")
                  public final class Main implements dev.warp.api.plugin.WarpPlugin {
                    public Main() {}
                    @Override public void enable() { throw new IllegalStateException("no db"); }
                  }
                  """));
      TestPluginJars.build(
          plugins,
          "dependent.jar",
          Map.of(
              "dependent/Main.java",
              """
              package dependent;
              import dev.warp.api.plugin.*;
              @Plugin(id = "dependent", dependencies = @Dependency(id = "broken"))
              public final class Main { public Main() {} }
              """),
          broken);

      manager.loadAll();

      PluginContainer brokenPlugin = manager.plugin("broken").orElseThrow();
      assertEquals(PluginState.FAILED, brokenPlugin.state());
      assertEquals("no db", brokenPlugin.failure().orElseThrow().getMessage());
      assertEquals(PluginState.FAILED, manager.plugin("dependent").orElseThrow().state());
    }

    @Test
    @DisplayName("should explain which constructor parameter cannot be injected")
    void uninjectableParameter() throws IOException {
      TestPluginJars.build(
          plugins,
          "greedy.jar",
          Map.of(
              "greedy/Main.java",
              """
              package greedy;
              @dev.warp.api.plugin.Plugin(id = "greedy")
              public final class Main { public Main(String nope) {} }
              """));

      manager.loadAll();

      String message =
          String.valueOf(
              manager.plugin("greedy").orElseThrow().failure().orElseThrow().getMessage());
      assertTrue(message.contains("parameter 1"), message);
      assertTrue(message.contains("java.lang.String"), message);
      assertTrue(message.contains("Injectable types"), message);
    }

    @Test
    @DisplayName("should fail every plugin of a dependency cycle")
    void cycle() throws IOException {
      TestPluginJars.raw(
          plugins.resolve("a.jar"), Map.of("warp-plugin.json", descriptor("a", "b")));
      TestPluginJars.raw(
          plugins.resolve("b.jar"), Map.of("warp-plugin.json", descriptor("b", "a")));

      manager.loadAll();

      assertEquals(PluginState.FAILED, manager.plugin("a").orElseThrow().state());
      assertEquals(PluginState.FAILED, manager.plugin("b").orElseThrow().state());
    }

    @Test
    @DisplayName("should skip jars without a descriptor and jars sharing an identifier")
    void skipsInvalidJars() throws IOException {
      TestPluginJars.raw(plugins.resolve("velocity.jar"), Map.of("velocity-plugin.json", "{}"));
      TestPluginJars.raw(
          plugins.resolve("one.jar"), Map.of("warp-plugin.json", descriptor("twin", null)));
      TestPluginJars.raw(
          plugins.resolve("two.jar"), Map.of("warp-plugin.json", descriptor("twin", null)));
      TestPluginJars.raw(
          plugins.resolve("bad.jar"), Map.of("warp-plugin.json", "{\"id\": \"Bad Id\"}"));

      manager.loadAll();

      assertTrue(manager.plugins().isEmpty());
    }
  }

  @Nested
  @DisplayName("disabling")
  class Disabling {

    @Test
    @DisplayName("should disable dependents before their dependencies")
    void reverseOrder() throws IOException {
      Path base =
          TestPluginJars.build(
              plugins,
              "base.jar",
              Map.of(
                  "base/Main.java",
                  """
                  package base;
                  @dev.warp.api.plugin.Plugin(id = "base")
                  public final class Main implements dev.warp.api.plugin.WarpPlugin {
                    public Main() {}
                    @Override public void disable() {
                      System.setProperty("warp.test.probe.order",
                          System.getProperty("warp.test.probe.order", "") + "base;");
                    }
                  }
                  """));
      TestPluginJars.build(
          plugins,
          "top.jar",
          Map.of(
              "top/Main.java",
              """
              package top;
              import dev.warp.api.plugin.*;
              @Plugin(id = "top", dependencies = @Dependency(id = "base"))
              public final class Main implements WarpPlugin {
                public Main() {}
                @Override public void disable() {
                  System.setProperty("warp.test.probe.order",
                      System.getProperty("warp.test.probe.order", "") + "top;");
                }
              }
              """),
          base);
      manager.loadAll();

      manager.disableAll();

      assertEquals("top;base;", System.getProperty(PROBE + "order"));
      assertEquals(PluginState.DISABLED, manager.plugin("top").orElseThrow().state());
      assertEquals(PluginState.DISABLED, manager.plugin("base").orElseThrow().state());
      assertFalse(manager.isEnabled("base"));
    }

    @Test
    @DisplayName("should keep disabling the others when one throws")
    void survivesThrowingDisable() throws IOException {
      TestPluginJars.build(
          plugins,
          "angry.jar",
          Map.of(
              "angry/Main.java",
              """
              package angry;
              @dev.warp.api.plugin.Plugin(id = "angry")
              public final class Main implements dev.warp.api.plugin.WarpPlugin {
                public Main() {}
                @Override public void disable() { throw new RuntimeException("boom"); }
              }
              """));
      TestPluginJars.build(plugins, "calm.jar", Map.of("calm/Main.java", simplePlugin("calm")));
      manager.loadAll();

      manager.disableAll();

      assertEquals(PluginState.DISABLED, manager.plugin("angry").orElseThrow().state());
      assertEquals(PluginState.DISABLED, manager.plugin("calm").orElseThrow().state());
    }
  }

  @Nested
  @DisplayName("event subscriptions")
  class EventSubscriptions {

    @Test
    @DisplayName(
        "should keep a plugin's subscriptions while it is enabled and drop them on disable")
    void droppedOnDisable() throws IOException {
      TestPluginJars.build(
          plugins,
          "listener.jar",
          Map.of(
              "listener/Main.java",
              """
              package listener;
              import dev.warp.api.event.EventBus;
              @dev.warp.api.plugin.Plugin(id = "listener")
              public final class Main {
                public Main(EventBus events) {
                  events.subscribe(StringBuilder.class, builder -> builder.append("heard"));
                }
              }
              """));
      manager.loadAll();
      assertTrue(events.hasHandlers(StringBuilder.class));

      manager.disableAll();

      assertFalse(events.hasHandlers(StringBuilder.class));
    }

    @Test
    @DisplayName("should drop the subscriptions of a plugin that fails after subscribing")
    void droppedOnFailure() throws IOException {
      TestPluginJars.build(
          plugins,
          "flaky.jar",
          Map.of(
              "flaky/Main.java",
              """
              package flaky;
              import dev.warp.api.plugin.*;
              @Plugin(id = "flaky")
              public final class Main implements WarpPlugin {
                public Main(PluginContext context) {
                  context.events().subscribe(StringBuilder.class, builder -> builder.append("x"));
                }
                @Override public void enable() { throw new IllegalStateException("config missing"); }
              }
              """));

      manager.loadAll();

      assertEquals(PluginState.FAILED, manager.plugin("flaky").orElseThrow().state());
      assertFalse(events.hasHandlers(StringBuilder.class));
    }
  }

  @Test
  @DisplayName("should create the plugins directory when it is missing")
  void createsDirectory() {
    manager.loadAll();

    assertTrue(Files.isDirectory(plugins));
    assertTrue(manager.plugins().isEmpty());
    assertNull(manager.plugin("anything").orElse(null));
  }

  private static String simplePlugin(String id) {
    return "package "
        + id
        + ";\n@dev.warp.api.plugin.Plugin(id = \""
        + id
        + "\")\npublic final class Main { public Main() {} }\n";
  }

  private static String descriptor(String id, @Nullable String dependency) {
    String dependencies = dependency == null ? "" : "{\"id\": \"" + dependency + "\"}";
    return "{\"id\": \""
        + id
        + "\", \"main\": \"x.Main\", \"dependencies\": ["
        + dependencies
        + "]}";
  }
}
