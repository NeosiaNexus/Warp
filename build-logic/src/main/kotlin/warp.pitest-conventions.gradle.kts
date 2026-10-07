import java.io.StringReader
import java.util.Properties

// Mutation testing with PIT (https://pitest.org): it plants small bugs (mutants) in the main classes
// and runs the unit tests against each; a mutant no test fails on is a gap in the tests.
//
//   ./gradlew pitest              # every module that applies this plugin
//   ./gradlew :protocol:pitest    # one module
//
// Reports: build/reports/pitest/index.html (browsable) and mutations.xml (one entry per mutant).
//
// Ratchet: the task fails when a module's mutation score (the percentage of mutants killed) drops
// below the module's threshold in config/pitest/thresholds.properties. CI runs it for each module
// a pull request changes (ci.yml), and for every module on each push to main (mutation.yml).
//
// Every run is a full analysis. PIT's incremental analysis (the pitest-history plugin) is left out:
// it tells JUnit 5 tests apart by their top-level class only, whose class file does not change when
// a test of one of its @Nested classes does, so it would reuse stale results. Gradle still skips the
// task, or takes its result from the build cache, when nothing changed.

plugins {
    java
    id("info.solidsoft.pitest")
}

val libs = the<VersionCatalogsExtension>().named("libs")

pitest {
    pitestVersion = libs.findVersion("pitest").get().requiredVersion
    junit5PluginVersion = libs.findVersion("pitest-junit5").get().requiredVersion
    // Only the main source set is mutated: never tests, benchmarks (src/jmh) or generated code.
    targetClasses = setOf("dev.warp.*")
    // The default mutators, plus removed conditionals and mutated switches.
    mutators = setOf("STRONGER")
    // Overridden only to skip capturing a stack trace in preallocated exceptions. Throwable's
    // constructor ignores the returned value, so no test can tell a mutant of it apart.
    excludedMethods = setOf("fillInStackTrace")
    // The fuzz tests (warp.fuzz-conventions) replay their whole corpus against every mutant they
    // reach, which doubles the analysis of protocol, yet kill no mutant the unit tests leave alive.
    excludedTestClasses = setOf("dev.warp.*FuzzTest")
    threads = Runtime.getRuntime().availableProcessors()
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
