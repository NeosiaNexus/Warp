import java.io.ByteArrayOutputStream
import javax.inject.Inject

plugins {
    id("warp.java-conventions")
    id("warp.spotless-conventions")
    id("warp.checkstyle-conventions")
    id("warp.jacoco-conventions")
}

description = "Warp JNI — native libraries (libdeflate) bound through the FFM API"

dependencies {
    implementation(platform(libs.netty.bom))
    implementation(libs.netty.buffer)
    implementation(libs.netty.handler)
    // @Nullable in tests as in the main code: compileOnly does not reach the test classpath.
    testCompileOnly(libs.jspecify)
}

// ---------------------------------------------------------------------------
// Native libraries
// ---------------------------------------------------------------------------
//
// The jar bundles libdeflate under dev/warp/jni/native/<platform>/, built from the release pinned
// in native/libdeflate.env by native/build-libdeflate.sh:
//
//   ./gradlew build                                   # builds libdeflate for this machine; without
//                                                     # bash, curl or a C compiler the jar has none
//                                                     # and Warp falls back to java.util.zip
//   ./gradlew build -Pwarp.natives.dir=<dir>          # bundles <dir>/<platform>/ instead: what the
//                                                     # release does with every platform's build
//                                                     # (.github/workflows/_natives.yml)
//   ./gradlew build -Pwarp.natives.required=true      # fails instead of bundling no library (and,
//                                                     # with a directory, any platform missing), and
//                                                     # the tests require the native codec

/** Every platform a release bundles, as named by native/build-libdeflate.sh. */
val releasePlatforms = mapOf(
    "linux-x86_64" to "libdeflate.so",
    "linux-aarch64" to "libdeflate.so",
    "linux-x86_64-musl" to "libdeflate.so",
    "linux-aarch64-musl" to "libdeflate.so",
    "macos-x86_64" to "libdeflate.dylib",
    "macos-aarch64" to "libdeflate.dylib",
    "windows-x86_64" to "deflate.dll",
)

val libdeflateEnv = layout.projectDirectory.file("native/libdeflate.env")
val libdeflateVersion = providers.fileContents(libdeflateEnv).asText.map { text ->
    Regex("""(?m)^LIBDEFLATE_VERSION=(\S+)""").find(text)?.groupValues?.get(1)
        ?: throw GradleException("No LIBDEFLATE_VERSION in jni/native/libdeflate.env")
}
val nativesDirectory = providers.gradleProperty("warp.natives.dir")
val nativesRequired = providers.gradleProperty("warp.natives.required").map { it.toBoolean() }.orElse(false)

/** Builds libdeflate for this machine; leaves its output empty if it cannot, unless required. */
abstract class BuildHostNative : DefaultTask() {
    @get:Inject
    abstract val execOperations: ExecOperations

    @get:InputFile
    abstract val script: RegularFileProperty

    @get:InputFile
    abstract val versionFile: RegularFileProperty

    @get:Input
    abstract val required: Property<Boolean>

    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    @TaskAction
    fun build() {
        val output = outputDirectory.get().asFile
        output.deleteRecursively()
        output.mkdirs()
        val log = ByteArrayOutputStream()
        val exitValue = try {
            execOperations.exec {
                commandLine("bash", script.get().asFile.absolutePath, "host", output.absolutePath)
                standardOutput = log
                errorOutput = log
                isIgnoreExitValue = true
            }.exitValue
        } catch (e: Exception) {
            log.write("$e\n".toByteArray())
            -1
        }
        if (exitValue == 0) {
            logger.lifecycle(log.toString().trim().lines().last())
            return
        }
        val message = "Could not build libdeflate for this machine; this jar will have no native" +
            " library and Warp will use java.util.zip:\n" + log.toString().trim()
        if (required.get()) {
            throw GradleException(message)
        }
        logger.warn(message)
        output.deleteRecursively()
        output.mkdirs()
    }
}

val buildHostNative = tasks.register<BuildHostNative>("buildHostNative") {
    description = "Builds libdeflate for this machine (jni/native/build-libdeflate.sh)."
    script = layout.projectDirectory.file("native/build-libdeflate.sh")
    versionFile = libdeflateEnv
    required = nativesRequired
    outputDirectory = layout.buildDirectory.dir("natives/host")
    // A machine without a compiler builds nothing: try again next time rather than trust that.
    outputs.upToDateWhen { outputDirectory.get().asFile.walk().any { it.isFile } }
}

val nativeProperties = tasks.register<WriteProperties>("nativeProperties") {
    description = "Records the bundled libdeflate version for the loader."
    destinationFile = layout.buildDirectory.file("natives/natives.properties")
    property("libdeflate.version", libdeflateVersion)
}

val nativeResources = tasks.register<Sync>("nativeResources") {
    description = "Lays the native libraries out as jar resources."
    into(layout.buildDirectory.dir("generated/native-resources"))
    into("dev/warp/jni/native") {
        from(nativeProperties)
        if (nativesDirectory.isPresent) {
            from(nativesDirectory) {
                include(releasePlatforms.map { (platform, library) -> "$platform/$library" })
            }
        } else {
            from(buildHostNative)
        }
    }
    val bundledFromDirectory = nativesDirectory.isPresent
    val required = nativesRequired
    val expected = releasePlatforms.map { (platform, library) -> "dev/warp/jni/native/$platform/$library" }
    doLast {
        val root = destinationDir
        val bundled = root.walk().filter { it.isFile && it.name != "natives.properties" }
            .map { it.relativeTo(root).invariantSeparatorsPath }.toSet()
        val missing = if (bundledFromDirectory) expected - bundled else if (bundled.isEmpty()) listOf("this machine's library") else listOf()
        if (required.get() && missing.isNotEmpty()) {
            throw GradleException("Native libraries missing from the jar: ${missing.joinToString()}")
        }
        logger.info("Bundled native libraries: ${bundled.sorted().joinToString()}")
    }
}

sourceSets.main {
    resources.srcDir(nativeResources)
}
