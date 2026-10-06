// Coverage-guided fuzz tests with Jazzer (https://github.com/CodeIntelligenceTesting/jazzer).
//
// A fuzz test is a JUnit method annotated with @FuzzTest, one per test class. The test task runs it
// in regression mode, as an ordinary parameterized test, on every file of its inputs directory
// (src/test/resources/<package>/<TestClass>Inputs/<method>/): the checked-in seeds and every past
// finding. The fuzz task fuzzes instead:
//
//   ./gradlew :protocol:fuzz                                               # every fuzz test, 1m each
//   ./gradlew :protocol:fuzz --tests '*FrameDecoderFuzzTest' -Pfuzz.duration=10m
//
// Jazzer fuzzes one test per JVM, so each test class runs in a JVM of its own, several in parallel.
// Inputs that reach new code are kept in .cifuzz-corpus/ (ignored by Git) and seed the next run. An
// input that fails, or runs for more than 10 seconds, is written to the test's inputs directory,
// where it stays a failing regression test until the bug is fixed.

plugins {
    java
}

val libs = the<VersionCatalogsExtension>().named("libs")

dependencies {
    testImplementation(libs.findLibrary("jazzer-api").get())
    testImplementation(libs.findLibrary("jazzer-junit").get())
}

tasks.withType<Test>().configureEach {
    // Jazzer loads its native driver and reads fields through sun.misc.Unsafe.
    jvmArgs("--enable-native-access=ALL-UNNAMED", "--sun-misc-unsafe-memory-access=allow")
}

tasks.named<Test>("test") {
    // -Pfuzz.updateSeeds rewrites the checked-in seeds of the fuzz tests from their definitions.
    systemProperty("fuzz.updateSeeds", providers.gradleProperty("fuzz.updateSeeds").isPresent)
}

tasks.register<Test>("fuzz") {
    description = "Fuzzes every @FuzzTest with Jazzer for -Pfuzz.duration each (default 1m)."
    group = LifecycleBasePlugin.VERIFICATION_GROUP

    val test = sourceSets.test.get()
    testClassesDirs = test.output.classesDirs
    classpath = test.runtimeClasspath
    useJUnitPlatform {
        includeTags("jazzer") // carried by @FuzzTest
    }

    environment("JAZZER_FUZZ", "1")
    systemProperty("jazzer.max_duration", providers.gradleProperty("fuzz.duration").getOrElse("1m"))
    // libFuzzer flags, passed the way Jazzer's own launcher does: an input running for 10 seconds is
    // a finding (a JUnit timeout would bound the whole fuzzing run instead).
    systemProperty("jazzer.internal.arg.0", "fuzz")
    systemProperty("jazzer.internal.arg.1", "-timeout=10")
    forkEvery = 1
    maxParallelForks = Runtime.getRuntime().availableProcessors()
    maxHeapSize = "1g"

    // A fuzzing run explores new inputs: it is never up to date and never comes from the cache.
    outputs.upToDateWhen { false }
    outputs.cacheIf { false }
}
