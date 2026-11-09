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

import dev.warp.proxy.console.ConsoleMode;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.jspecify.annotations.Nullable;

/**
 * {@code java -jar warp.jar migrate}: converts a Velocity or BungeeCord installation to a Warp
 * configuration, and reports what became of every setting and plugin.
 */
public final class MigrateCli {

  /** Everything went well. */
  public static final int OK = 0;

  /** The source cannot be read or parsed, or the result cannot be written. */
  public static final int FAILED = 1;

  /** The arguments are wrong. */
  public static final int USAGE = 2;

  /** The target files exist and {@code --force} was not given. */
  public static final int EXISTS = 3;

  static final String WARP_CONF = "warp.conf";

  static final String SECRET_FILE = "forwarding.secret";

  private static final List<Migration> MIGRATIONS =
      List.of(new VelocityMigration(), new BungeeCordMigration());

  private static final String USAGE_TEXT =
      """
      Usage:
        java -jar warp.jar migrate [options] [SOURCE]

      Converts a Velocity or BungeeCord (Waterfall) installation to a Warp configuration: writes
      warp.conf, copies the forwarding secret, and reports what became of every setting and every
      plugin. SOURCE is the proxy's directory (default: the current directory).

        --from velocity|bungeecord  the source proxy (default: detected from velocity.toml or
                                    config.yml in SOURCE)
        --to DIR                    where to write warp.conf (default: the current directory)
        --dry-run                   print warp.conf on standard output instead of writing it; the
                                    report goes to standard error
        --force                     overwrite an existing warp.conf and forwarding.secret

      Exit codes: 0 done, 1 the source cannot be read or the result cannot be written, 2 a usage
      mistake, 3 the target exists (use --force).
      """;

  private MigrateCli() {}

  /** The parsed command line. */
  record Options(Path source, Path target, @Nullable String from, boolean dryRun, boolean force) {}

  /** A command-line mistake. */
  static final class UsageException extends Exception {

    private static final long serialVersionUID = 1L;

    UsageException(String message) {
      super(message);
    }
  }

  /**
   * Runs {@code migrate ...}; called by the proxy's {@code main} before its logging starts.
   *
   * @param args the arguments after {@code migrate}
   * @return the exit code: {@link #OK}, {@link #FAILED}, {@link #USAGE} or {@link #EXISTS}
   */
  public static int run(String[] args) {
    List<String> list = List.of(args);
    if (list.contains("--help") || list.contains("-h")) {
      System.out.print(USAGE_TEXT);
      return OK;
    }
    return run(list, System.out, System.err, ConsoleMode.detect());
  }

  /**
   * Runs a migration with explicit streams.
   *
   * @param args the arguments after {@code migrate}
   * @param out standard output: the configuration with {@code --dry-run}, the report otherwise
   * @param err standard error: mistakes, and the report with {@code --dry-run}
   * @param mode how to colour the report
   * @return the exit code
   */
  static int run(List<String> args, PrintStream out, PrintStream err, ConsoleMode mode) {
    Options options;
    try {
      options = parse(args, Path.of("."));
    } catch (UsageException e) {
      err.println("warp migrate: " + e.getMessage());
      err.println("Try java -jar warp.jar migrate --help");
      return USAGE;
    }
    Migration migration;
    Migration.Result result;
    List<PluginCatalog.Plugin> plugins;
    try {
      migration = select(options);
      result = migration.migrate(options.source());
      plugins = PluginCatalog.load().scan(options.source().resolve("plugins"));
    } catch (MigrationException e) {
      err.println("warp migrate: " + e.getMessage());
      return FAILED;
    } catch (IOException e) {
      err.println("warp migrate: cannot list plugins: " + e.getMessage());
      return FAILED;
    }
    Printer printer = new Printer(options.dryRun() ? err : out, mode);
    String heading =
        "Migrating "
            + migration.name()
            + " from "
            + options.source().toAbsolutePath().normalize()
            + (options.dryRun()
                ? " (dry run)"
                : " to " + options.target().toAbsolutePath().normalize());
    if (options.dryRun()) {
      printer.heading(heading);
      out.print(result.warpConf());
      out.flush();
    } else {
      List<Path> written = new ArrayList<>();
      Path conf = options.target().resolve(WARP_CONF);
      Path secret = options.target().resolve(SECRET_FILE);
      if (!options.force()) {
        List<String> existing = new ArrayList<>();
        if (Files.exists(conf)) {
          existing.add(conf.toString());
        }
        if (result.forwardingSecret() != null && Files.exists(secret)) {
          existing.add(secret.toString());
        }
        if (!existing.isEmpty()) {
          err.println(
              "warp migrate: "
                  + String.join(" and ", existing)
                  + (existing.size() > 1 ? " exist" : " exists")
                  + ": pass --force to overwrite, or --to another directory");
          return EXISTS;
        }
      }
      printer.heading(heading);
      try {
        Files.createDirectories(options.target());
        Files.writeString(conf, result.warpConf(), StandardCharsets.UTF_8);
        written.add(conf);
        if (result.forwardingSecret() != null) {
          writeSecret(secret, result.forwardingSecret(), err);
          written.add(secret);
        }
      } catch (IOException e) {
        err.println("warp migrate: cannot write " + options.target() + ": " + e.getMessage());
        return FAILED;
      }
      printer.written(written);
    }
    printer.report(result.report(), plugins);
    return OK;
  }

  /**
   * Writes a secret to a file only its owner can read: created with mode 0600 in the same
   * directory, then moved into place, so that the file never exists with wider permissions. Where
   * the file system has no POSIX permissions (Windows), the file inherits its directory's access
   * and a warning says so.
   *
   * @param file the file to write
   * @param secret its content
   * @param err where to warn
   * @throws IOException if the file cannot be written
   */
  static void writeSecret(Path file, String secret, PrintStream err) throws IOException {
    Path directory = file.toAbsolutePath().getParent();
    if (directory == null) {
      throw new IOException(file + " has no parent directory");
    }
    boolean posix = directory.getFileSystem().supportedFileAttributeViews().contains("posix");
    Path temp;
    if (posix) {
      temp =
          Files.createTempFile(
              directory,
              "." + file.getFileName(),
              ".tmp",
              PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
    } else {
      temp = Files.createTempFile(directory, "." + file.getFileName(), ".tmp");
      err.println(
          "warp migrate: "
              + file
              + " inherits the permissions of its directory, which this file system cannot"
              + " restrict: keep the directory private");
    }
    try {
      Files.writeString(temp, secret, StandardCharsets.UTF_8, StandardOpenOption.WRITE);
      Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    } finally {
      Files.deleteIfExists(temp);
    }
  }

  /**
   * Parses the arguments after {@code migrate}.
   *
   * @param args the arguments
   * @param cwd the current directory, the default source and target
   * @return the options
   * @throws UsageException if the arguments are wrong
   */
  static Options parse(List<String> args, Path cwd) throws UsageException {
    Path source = null;
    Path target = cwd;
    String from = null;
    boolean dryRun = false;
    boolean force = false;
    Iterator<String> rest = args.iterator();
    while (rest.hasNext()) {
      String arg = rest.next();
      String value = null;
      int equals = arg.indexOf('=');
      if (arg.startsWith("--") && equals > 0) {
        value = arg.substring(equals + 1);
        arg = arg.substring(0, equals);
      }
      switch (arg) {
        case "--from" -> {
          from = (value != null ? value : next(rest, arg)).toLowerCase(Locale.ROOT);
          if (!from.equals("velocity") && !from.equals("bungeecord") && !from.equals("waterfall")) {
            throw new UsageException("--from takes velocity or bungeecord, not " + from);
          }
          if (from.equals("waterfall")) {
            from = "bungeecord";
          }
        }
        case "--to" -> target = cwd.resolve(value != null ? value : next(rest, arg));
        case "--dry-run" -> dryRun = true;
        case "--force" -> force = true;
        default -> {
          if (arg.startsWith("-")) {
            throw new UsageException("unknown option " + arg);
          }
          if (source != null) {
            throw new UsageException("one source directory at most: " + source + " and " + arg);
          }
          source = cwd.resolve(arg);
        }
      }
    }
    if (source == null) {
      source = cwd;
    }
    if (!Files.isDirectory(source)) {
      throw new UsageException(source + " is not a directory");
    }
    return new Options(source, target, from, dryRun, force);
  }

  private static String next(Iterator<String> rest, String option) throws UsageException {
    if (!rest.hasNext()) {
      throw new UsageException(option + " needs a value");
    }
    return rest.next();
  }

  private static Migration select(Options options) throws MigrationException {
    if (options.from() != null) {
      for (Migration migration : MIGRATIONS) {
        if (migration.name().toLowerCase(Locale.ROOT).equals(options.from())) {
          return migration;
        }
      }
      throw new MigrationException("no migration from " + options.from());
    }
    List<Migration> found = new ArrayList<>();
    for (Migration migration : MIGRATIONS) {
      if (Files.isRegularFile(options.source().resolve(migration.marker()))) {
        found.add(migration);
      }
    }
    if (found.size() == 1) {
      return found.getFirst();
    }
    if (found.isEmpty()) {
      throw new MigrationException(
          options.source().toAbsolutePath().normalize()
              + " holds neither velocity.toml nor config.yml: is it the proxy's directory?");
    }
    throw new MigrationException(
        options.source().toAbsolutePath().normalize()
            + " holds both velocity.toml and config.yml: say which with --from");
  }

  // ---------------------------------------------------------------------------
  // The report
  // ---------------------------------------------------------------------------

  /** Writes the report, coloured when the terminal allows it. */
  private static final class Printer {

    private final PrintStream out;
    private final ConsoleMode mode;

    Printer(PrintStream out, ConsoleMode mode) {
      this.out = out;
      this.mode = mode;
    }

    void heading(String text) {
      out.println(mode.render(Component.text(text, NamedTextColor.AQUA, TextDecoration.BOLD)));
    }

    void written(List<Path> files) {
      StringBuilder names = new StringBuilder();
      for (Path file : files) {
        if (!names.isEmpty()) {
          names.append(", ");
        }
        names.append(file.getFileName());
      }
      out.println(mode.render(Component.text("Written: " + names, NamedTextColor.GREEN)));
    }

    void report(Report report, List<PluginCatalog.Plugin> plugins) {
      section("Migrated", report.migrated().size());
      int width = 0;
      for (Report.Migrated entry : report.migrated()) {
        width = Math.max(width, entry.source().length() + entry.value().length() + 3);
      }
      for (Report.Migrated entry : report.migrated()) {
        String left = entry.source() + " = " + entry.value();
        out.println(
            mode.render(
                Component.text("  " + pad(left, width) + "  ", NamedTextColor.WHITE)
                    .append(Component.text("-> " + entry.target(), NamedTextColor.GREEN))));
      }
      section("Dropped", report.dropped().size());
      width = 0;
      for (Report.Dropped entry : report.dropped()) {
        width = Math.max(width, entry.source().length());
      }
      for (Report.Dropped entry : report.dropped()) {
        out.println(
            mode.render(
                Component.text("  " + pad(entry.source(), width) + "  ", NamedTextColor.WHITE)
                    .append(Component.text(entry.reason(), NamedTextColor.GRAY))));
      }
      if (!report.warnings().isEmpty()) {
        section("Warnings", report.warnings().size());
        for (String warning : report.warnings()) {
          out.println(mode.render(Component.text("  ! " + warning, NamedTextColor.YELLOW)));
        }
      }
      section("Plugins", plugins.size());
      if (plugins.isEmpty()) {
        out.println(mode.render(Component.text("  none in plugins/", NamedTextColor.GRAY)));
      }
      width = 0;
      for (PluginCatalog.Plugin plugin : plugins) {
        width = Math.max(width, plugin.file().length());
      }
      for (PluginCatalog.Plugin plugin : plugins) {
        NamedTextColor color =
            switch (plugin.status()) {
              case BUILT_IN, REPLACED -> NamedTextColor.GREEN;
              case UNAVAILABLE -> NamedTextColor.RED;
              case UNKNOWN -> NamedTextColor.YELLOW;
            };
        String status = plugin.status().name().toLowerCase(Locale.ROOT).replace('_', '-');
        out.println(
            mode.render(
                Component.text("  " + pad(plugin.file(), width) + "  ", NamedTextColor.WHITE)
                    .append(Component.text(pad(status, 11) + " ", color))
                    .append(Component.text(plugin.text(), NamedTextColor.GRAY))));
      }
      if (!report.nextSteps().isEmpty()) {
        section("Next steps", report.nextSteps().size());
        int number = 1;
        for (String step : report.nextSteps()) {
          out.println(
              mode.render(Component.text("  " + number++ + ". " + step, NamedTextColor.WHITE)));
        }
      }
      out.flush();
    }

    private void section(String title, int count) {
      out.println();
      out.println(
          mode.render(
              Component.text(title, NamedTextColor.AQUA, TextDecoration.BOLD)
                  .append(Component.text(" (" + count + ")", NamedTextColor.GRAY))));
    }

    private static String pad(String text, int width) {
      return text.length() >= width ? text : text + " ".repeat(width - text.length());
    }
  }
}
