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
