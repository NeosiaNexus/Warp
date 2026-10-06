import javax.inject.Inject

// Coverage-guided fuzz tests with Jazzer (https://github.com/CodeIntelligenceTesting/jazzer).
//
// A fuzz test is a JUnit method annotated with @FuzzTest, one per test class. The test task runs it
// in regression mode, as an ordinary parameterized test, on every file of its inputs directory
// (src/test/resources/<package>/<TestClass>Inputs/<method>/): the checked-in seeds and every past
// finding. The fuzz task fuzzes instead:
//
//   ./gradlew :protocol:fuzz                                               # every fuzz test, 1m each
//   ./gradlew :protocol:fuzz --tests '*FrameDecoderFuzzTest' -Pfuzz.duration=10m
//   ./gradlew :protocol:fuzzMinimize                                       # shrinks the corpus
//
// Jazzer fuzzes one test per JVM, so each test class runs in a JVM of its own, several in parallel.
// Inputs that reach new code are kept in .cifuzz-corpus/ (ignored by Git) and seed the next run. An
// input that fails, or runs for more than 10 seconds, is written to the test's inputs directory,
// where it stays a failing regression test until the bug is fixed.
//
// The corpus only grows: libFuzzer keeps every input that once reached new code, even after later
// ones cover the same and more. fuzzMinimize keeps, for each fuzz test, the fewest inputs that
// cover what the whole corpus covers (libFuzzer's -merge=1, run by Jazzer's own driver, as its
// JUnit integration cannot merge).

plugins {
    java
}

val libs = the<VersionCatalogsExtension>().named("libs")

dependencies {
    testImplementation(libs.findLibrary("jazzer-api").get())
    testImplementation(libs.findLibrary("jazzer-junit").get())
}

// Jazzer loads its native driver and reads fields through sun.misc.Unsafe.
val jazzerJvmArgs = listOf("--enable-native-access=ALL-UNNAMED", "--sun-misc-unsafe-memory-access=allow")

tasks.withType<Test>().configureEach {
    jvmArgs(jazzerJvmArgs)
}

tasks.named<Test>("test") {
    // -Pfuzz.updateSeeds (or =true) rewrites the checked-in seeds of the fuzz tests from their
    // definitions; =false, like no property at all, checks them.
    val updateSeeds = providers.gradleProperty("fuzz.updateSeeds").map { it.isEmpty() || it.toBoolean() }
    systemProperty("fuzz.updateSeeds", updateSeeds.getOrElse(false))
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

val toolchains = extensions.getByType<JavaToolchainService>()

tasks.register<MinimizeFuzzCorpus>("fuzzMinimize") {
    description = "Shrinks the corpus of every fuzz test to the fewest inputs covering as much."
    group = LifecycleBasePlugin.VERIFICATION_GROUP

    classpath.from(sourceSets.test.get().runtimeClasspath)
    corpus = layout.projectDirectory.dir(".cifuzz-corpus")
    launcher = toolchains.launcherFor(java.toolchain)
    jvmArgs = jazzerJvmArgs + "-Xmx1g"
}

/**
 * Replaces the corpus of each fuzz test, `.cifuzz-corpus/<class>/<method>/`, with the fewest inputs
 * that reach every edge it reaches: libFuzzer's `-merge=1` into an empty directory, run by Jazzer's
 * driver with the instrumentation of the JUnit integration (the classes of `dev.warp`). The driver
 * merges the checked-in inputs of the fuzz test too, so a corpus can gain the seeds it lacked.
 */
abstract class MinimizeFuzzCorpus : DefaultTask() {

    @get:Classpath
    abstract val classpath: ConfigurableFileCollection

    @get:Internal
    abstract val corpus: DirectoryProperty

    @get:Nested
    abstract val launcher: Property<JavaLauncher>

    @get:Input
    abstract val jvmArgs: ListProperty<String>

    @get:Inject
    abstract val exec: ExecOperations

    @get:Inject
    abstract val files: FileSystemOperations

    init {
        outputs.upToDateWhen { false } // its output is its input, rewritten
    }

    @TaskAction
    fun minimize() {
        val corpora =
            corpus.get().asFile.listFiles().orEmpty().sorted().flatMap { testClass ->
                testClass.listFiles().orEmpty().sorted().map { testClass.name to it }
            }
        for ((testClass, inputs) in corpora) {
            val merged = temporaryDir.resolve("${testClass}/${inputs.name}")
            files.delete { delete(merged) }
            merged.mkdirs()
            exec.javaexec {
                executable = launcher.get().executablePath.asFile.absolutePath
                classpath = this@MinimizeFuzzCorpus.classpath
                mainClass = "com.code_intelligence.jazzer.Jazzer"
                jvmArgs(this@MinimizeFuzzCorpus.jvmArgs.get())
                args(
                    "--target_class=$testClass",
                    "--target_method=${inputs.name}",
                    "--instrumentation_includes=dev.warp.**",
                    "-merge=1",
                    merged.absolutePath,
                    inputs.absolutePath,
                )
            }
            val before = inputs.list().orEmpty().size
            files.delete { delete(inputs) }
            check(merged.renameTo(inputs)) { "Cannot move $merged to $inputs" }
            logger.lifecycle("{}: {} inputs, {} once minimized", testClass, before, inputs.list().orEmpty().size)
        }
    }
}
