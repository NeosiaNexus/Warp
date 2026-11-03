import net.ltgt.gradle.errorprone.errorprone

plugins {
    `java-library`
    id("net.ltgt.errorprone")
}

val libs = the<VersionCatalogsExtension>().named("libs")

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
    withSourcesJar()
    withJavadocJar()
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.compilerArgs.addAll(listOf(
        "-Xlint:all",
        "-Xlint:-processing",
        "-Xlint:-serial",
        "-parameters",
    ))
    options.errorprone {
        error("NullAway")
        option("NullAway:AnnotatedPackages", "dev.warp")
    }
}

tasks.withType<Javadoc>().configureEach {
    options.encoding = "UTF-8"
    (options as StandardJavadocDocletOptions).apply {
        addStringOption("Xdoclint:none", "-quiet")
    }
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    // Native libraries (libdeflate through the FFM API, Netty's transports) load without warnings,
    // as they do from the shadow jar, whose manifest enables native access.
    jvmArgs("-XX:+EnableDynamicAgentLoading", "--enable-native-access=ALL-UNNAMED")
    // Native libraries install into the build directory rather than the user's cache, and with
    // -Pwarp.natives.required=true (CI) a test needing native compression fails instead of skipping.
    systemProperty("warp.natives.dir", layout.buildDirectory.dir("tmp/natives").get().asFile.absolutePath)
    systemProperty("warp.natives.required", providers.gradleProperty("warp.natives.required").getOrElse("false"))
}

testing {
    suites {
        register<JvmTestSuite>("integrationTest") {
            useJUnitJupiter()
            dependencies {
                implementation(project())
            }
        }
    }
}

dependencies {
    compileOnly(libs.findLibrary("jspecify").get())
    add("errorprone", libs.findLibrary("errorprone-core").get())
    add("errorprone", libs.findLibrary("nullaway").get())
    testImplementation(platform(libs.findLibrary("junit-bom").get()))
    testImplementation(libs.findLibrary("junit-jupiter").get())
    testRuntimeOnly(libs.findLibrary("junit-platform-launcher").get())
    testImplementation(libs.findLibrary("mockito-core").get())
    testImplementation(libs.findLibrary("mockito-junit-jupiter").get())
}

// Guava up to 33.7.1 is vulnerable (GHSA-xxph-c9ww-hj94). It comes in through other libraries:
// Guice for the API (whose constraint plugins see too), Velocity's natives for the benchmarks,
// Error Prone and Checkstyle for the build. Every configuration resolves the version in the catalog
// at least, so that none puts a vulnerable Guava in the dependency graph, even resolved on its own
// as the dependency graph submission resolves each of them. A newer one asked for is left alone.
val patchedGuava = libs.findVersion("guava").get().requiredVersion

configurations.configureEach {
    resolutionStrategy.eachDependency {
        val version = requested.version
        if (requested.group == "com.google.guava" && requested.name == "guava" &&
            version != null && olderThan(version, patchedGuava)) {
            useVersion(patchedGuava)
            because("fixed by $patchedGuava: GHSA-xxph-c9ww-hj94")
        }
    }
}

/** Compares the numeric parts of two Guava versions (`33.7.1-jre` with `33.7.2-jre`). */
fun olderThan(version: String, other: String): Boolean {
    fun parts(v: String) = v.substringBefore('-').split('.').map { it.toIntOrNull() ?: 0 }
    val a = parts(version)
    val b = parts(other)
    for (i in 0 until maxOf(a.size, b.size)) {
        val x = a.getOrElse(i) { 0 }
        val y = b.getOrElse(i) { 0 }
        if (x != y) return x < y
    }
    return false
}
