import java.io.StringReader
import java.math.BigDecimal
import java.util.Properties

// Mutation testing with PIT (https://pitest.org): it plants small bugs (mutants) in the main classes
// and runs the unit tests against each; a mutant no test fails on is a gap in the tests.
//
//   ./gradlew pitest                                    # every module that applies this plugin
//   ./gradlew :protocol:pitest                          # one module
//   ./gradlew :proxy:pitest -Pwarp.pitest.since=main    # the classes changed since main only
//
// Reports: build/reports/pitest/index.html (browsable) and mutations.xml (one entry per mutant).
//
// Ratchet: the task fails when the mutation score (the percentage of mutants killed) drops below
// the module's threshold in config/pitest/thresholds.properties.
//
// Scoped analysis: with -Pwarp.pitest.since=<git revision>, only the main classes whose source
// differs between the revision's merge base with HEAD and the working tree are mutated, nested
// classes included, and their mutants are held to the same threshold. The task is skipped when no
// main class changed. Pull requests run it against their base (ci.yml), so that its time follows
// the size of the change, not of the module; every class is analysed nightly (mutation.yml).
//
// Every run analyses its classes from scratch. PIT's incremental analysis (the pitest-history
// plugin) is left out: it tells JUnit 5 tests apart by their top-level class only, whose class file
// does not change when a test of one of its @Nested classes does, so it would reuse stale results.
// Gradle still skips the task, or takes its result from the build cache, when nothing changed.

plugins {
    java
    id("info.solidsoft.pitest")
}

val libs = the<VersionCatalogsExtension>().named("libs")

// ---------------------------------------------------------------------------
// Scope: every main class, or those changed since a revision
// ---------------------------------------------------------------------------

val since = providers.gradleProperty("warp.pitest.since")

// The main classes whose source changed since the revision, as PIT globs: each top-level class and
// its nested classes. A deleted file leaves nothing to mutate, and package-info.java has no code.
val changedClasses: Provider<Set<String>> = since
    .flatMap { revision ->
        providers.exec {
            workingDir = layout.projectDirectory.asFile
            commandLine(
                "git", "diff", "--name-only", "--relative", "--diff-filter=d",
                "--merge-base", "--end-of-options", revision, "--", "src/main/java",
            )
        }.standardOutput.asText
    }
    .map { output ->
        output.lineSequence()
            .map { it.trim().removePrefix("src/main/java/") }
            .filter { it.endsWith(".java") && !it.endsWith("package-info.java") }
            .map { it.removeSuffix(".java").replace('/', '.') }
            .flatMap { sequenceOf(it, "$it$*") }
            .toSortedSet()
    }

// ---------------------------------------------------------------------------
// Analysis
// ---------------------------------------------------------------------------

pitest {
    pitestVersion = libs.findVersion("pitest").get().requiredVersion
    junit5PluginVersion = libs.findVersion("pitest-junit5").get().requiredVersion
    // Only the main source set is mutated: never tests, benchmarks (src/jmh) or generated code.
    targetClasses = changedClasses.orElse(setOf("dev.warp.*"))
    // Any unit test of the module may kill a mutant; PIT runs those that cover it. Without this, PIT
    // would look for the tests among the target classes.
    targetTests = setOf("dev.warp.*")
    // A changed class may hold no code to mutate, such as an interface or a plain record.
    failWhenNoMutations = since.map { false }.orElse(true)
    // The default mutators, plus removed conditionals and mutated switches.
    mutators = setOf("STRONGER")
    // Overridden only to skip capturing a stack trace in preallocated exceptions. Throwable's
    // constructor ignores the returned value, so no test can tell a mutant of it apart.
    excludedMethods = setOf("fillInStackTrace")
    // The fuzz tests (warp.fuzz-conventions) replay their whole corpus against every mutant they
    // reach, which doubles the analysis of protocol, yet kill no mutant the unit tests leave alive.
    excludedTestClasses = setOf("dev.warp.*FuzzTest")
    threads = Runtime.getRuntime().availableProcessors()
    // A mutant that makes a test hang, or crawl, is stopped once the test has run a quarter longer
    // than without mutants plus two seconds (PIT's default: four), and counts as killed. Stopping it
    // also ends the minion JVM, which PIT then restarts: a hang costs seconds, and still leaves room
    // for a fresh minion's first test, which loads Netty and Mockito.
    timeoutFactor = BigDecimal("1.25")
    timeoutConstInMillis = 2000
    // The minions run the unit tests as the test task does, with its JVM arguments (Mockito's agent,
    // Jazzer's native access). Read through a plain provider: one mapped from the task would make
    // this task depend on it, and run every test first.
    val test = tasks.named<Test>("test")
    jvmArgs = providers.provider { test.get().jvmArgs.orEmpty() }
    outputFormats = setOf("HTML", "XML")
    timestampedReports = false

    val module = project.name
    val thresholds = rootProject.layout.projectDirectory.file("config/pitest/thresholds.properties")
    // A module never runs without its ratchet: a missing file or entry fails the task. One error
    // covers both: a fallback provider throwing for a missing file would run, and throw, whenever
    // the configuration cache is stored.
    mutationThreshold = providers.fileContents(thresholds).asText
        .orElse("")
        .map { text ->
            Properties().apply { load(StringReader(text)) }.getProperty(module)?.toInt()
                ?: throw GradleException(
                    "No mutation threshold for '$module': ${thresholds.asFile} is missing or has " +
                        "no '$module' entry"
                )
        }
}

if (since.isPresent) {
    tasks.named("pitest") {
        val revision = since.get()
        val classes = changedClasses
        onlyIf("a main class changed since $revision") { classes.get().isNotEmpty() }
        doFirst {
            logger.lifecycle(
                "Mutating the main classes changed since {}: {}",
                revision,
                classes.get().filterNot { it.endsWith("*") }.joinToString(),
            )
        }
    }
}
