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
// below the module's threshold in config/pitest/thresholds.properties.
//
// Every run is a full analysis. PIT's incremental analysis (the pitest-history plugin) is left out:
// it tells JUnit 5 tests apart by their top-level class only, so it misses changes made inside a
// @Nested class and reuses stale results. Gradle still skips the task when nothing changed.

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
    threads = Runtime.getRuntime().availableProcessors()
    // As for the test task: Mockito attaches its agent at run time.
    jvmArgs = listOf("-XX:+EnableDynamicAgentLoading")
    outputFormats = setOf("HTML", "XML")
    timestampedReports = false

    val module = project.name
    val thresholds = "config/pitest/thresholds.properties"
    mutationThreshold = providers
        .fileContents(rootProject.layout.projectDirectory.file(thresholds))
        .asText
        .map { text ->
            Properties().apply { load(StringReader(text)) }.getProperty(module)?.toInt()
                ?: throw GradleException("No mutation threshold for '$module' in $thresholds")
        }
}
