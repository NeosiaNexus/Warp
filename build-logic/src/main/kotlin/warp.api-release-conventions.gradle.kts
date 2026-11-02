// The plugin API targets an older Java than the proxy: plugins build on JDK 21 toolchains, and so
// do their tools (Lombok, ArchUnit, Byte Buddy, JaCoCo), which lag behind each new class file
// version. The proxy and the protocol stay on Warp's toolchain (25); only what plugins compile
// against is held back.
//
// Three gates keep it there:
//   - javac --release on the main and test sources: no newer language feature or JDK method;
//   - the tests run on a JDK of that release: the API and its dependencies really work there;
//   - checkApiRelease: no class file of the API or of its dependencies, and no Gradle module
//     metadata, needs a newer Java (what --release cannot see).

plugins {
    `java-library`
}

/** The Java release plugins need: raising it is a breaking change for every plugin. */
val apiRelease = 21

tasks.withType<JavaCompile>().configureEach {
    options.release = apiRelease
}

tasks.withType<Javadoc>().configureEach {
    (options as CoreJavadocOptions).addStringOption("-release", apiRelease.toString())
}

val apiLauncher = the<JavaToolchainService>().launcherFor {
    languageVersion = JavaLanguageVersion.of(apiRelease)
}
tasks.withType<Test>().configureEach {
    javaLauncher = apiLauncher
}

val checkApiRelease = tasks.register<ClassFileVersionCheck>("checkApiRelease") {
    group = "verification"
    description = "Checks that the API, its dependencies and its module metadata need at most Java $apiRelease."
    release = apiRelease
    classpath.from(tasks.named("jar"), configurations.named("runtimeClasspath"))
    moduleMetadata = tasks.named<GenerateModuleMetadata>("generateMetadataFileForMavenJavaPublication")
        .flatMap { it.outputFile }
    report = layout.buildDirectory.file("reports/api-release.txt")
}

tasks.named("check") {
    dependsOn(checkApiRelease)
}
