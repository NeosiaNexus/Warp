import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Classpath
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import java.io.DataInputStream
import java.io.InputStream
import java.util.zip.ZipFile

/**
 * Fails when a class file of [classpath] needs a newer Java than [release], or when the Gradle
 * module metadata announces a newer one than consumers on that Java can resolve.
 *
 * The plugin API is compiled with `--release`, which already stops its own code from using a newer
 * language feature or JDK method; this check also covers what `--release` does not see: the API's
 * dependencies, which plugins load too, and the `org.gradle.jvm.version` attribute Gradle consumers
 * select variants with. Multi-release entries (`META-INF/versions/N/`) and `module-info.class` are
 * skipped: older runtimes ignore them.
 */
@CacheableTask
abstract class ClassFileVersionCheck : DefaultTask() {

    /** The jars and class directories to scan: the API jar and its runtime classpath. */
    @get:Classpath
    abstract val classpath: ConfigurableFileCollection

    /** The newest Java release the classes may need, such as 21. */
    @get:Input
    abstract val release: Property<Int>

    /** The Gradle module metadata of the publication (`module.json`). */
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val moduleMetadata: RegularFileProperty

    /** Written once the check passed, so that the task is up to date until an input changes. */
    @get:OutputFile
    abstract val report: RegularFileProperty

    @TaskAction
    fun check() {
        val maxMajor = release.get() + JAVA_MAJOR_OFFSET
        val offenders = mutableListOf<String>()
        var scanned = 0
        for (file in classpath.files.sortedBy { it.name }) {
            when {
                file.isDirectory -> file.walkTopDown()
                    .filter { it.isFile && isScanned(it.relativeTo(file).invariantSeparatorsPath) }
                    .forEach { classFile ->
                        scanned++
                        val major = classFile.inputStream().use(::majorVersion)
                        if (major > maxMajor) offenders += describe("${file.name}/${classFile.relativeTo(file)}", major)
                    }
                file.isFile && file.name.endsWith(".jar") -> ZipFile(file).use { zip ->
                    for (entry in zip.entries()) {
                        if (entry.isDirectory || !isScanned(entry.name)) continue
                        scanned++
                        val major = zip.getInputStream(entry).use(::majorVersion)
                        if (major > maxMajor) offenders += describe("${file.name}!${entry.name}", major)
                    }
                }
            }
        }
        val metadata = moduleMetadata.get().asFile.readText()
        val announced = JVM_VERSION_ATTRIBUTE.findAll(metadata).map { it.groupValues[1].toInt() }.toSet()
        val metadataProblems = announced.filter { it > release.get() }
            .map { "module.json announces org.gradle.jvm.version $it" }
        if (announced.isEmpty()) {
            throw GradleException("module.json announces no org.gradle.jvm.version: cannot check it")
        }
        if (offenders.isNotEmpty() || metadataProblems.isNotEmpty()) {
            val listed = (metadataProblems + offenders.take(MAX_LISTED)).joinToString("\n  ", prefix = "  ")
            val more = if (offenders.size > MAX_LISTED) "\n  ... and ${offenders.size - MAX_LISTED} more" else ""
            throw GradleException(
                "The plugin API must run on Java ${release.get()} (plugins and their tooling build on it)," +
                    " but needs a newer Java:\n$listed$more\nKeep the API and its dependencies on" +
                    " Java ${release.get()}, or raise the API's release on purpose (a breaking change for plugins).",
            )
        }
        report.get().asFile.writeText(
            "$scanned class files at most Java ${release.get()} (major $maxMajor); module.json: $announced\n",
        )
    }

    private fun isScanned(path: String) =
        path.endsWith(".class") && !path.endsWith("module-info.class") && !path.startsWith("META-INF/versions/")

    private fun describe(where: String, major: Int) =
        "$where is a class file $major (Java ${major - JAVA_MAJOR_OFFSET})"

    private fun majorVersion(input: InputStream): Int {
        val data = DataInputStream(input)
        if (data.readInt() != CLASS_MAGIC) return 0 // not a class file: nothing to check
        data.readUnsignedShort() // minor version
        return data.readUnsignedShort()
    }

    private companion object {
        const val CLASS_MAGIC = 0xCAFEBABE.toInt()
        /** Java N writes class files of major version N + 44 (Java 21: 65). */
        const val JAVA_MAJOR_OFFSET = 44
        const val MAX_LISTED = 20
        val JVM_VERSION_ATTRIBUTE = Regex("\"org\\.gradle\\.jvm\\.version\"\\s*:\\s*(\\d+)")
    }
}
