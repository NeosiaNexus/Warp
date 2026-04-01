import net.ltgt.gradle.errorprone.errorprone

plugins {
    `java-library`
    id("net.ltgt.errorprone")
}

val libs = the<VersionCatalogsExtension>().named("libs")

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
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
        "-XDaddTypeAnnotationsToSymbol=true",
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
    jvmArgs("-XX:+EnableDynamicAgentLoading")
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
    testImplementation(libs.findLibrary("mockito-core").get())
    testImplementation(libs.findLibrary("mockito-junit-jupiter").get())
}
