plugins {
    id("warp.java-conventions")
    id("warp.spotless-conventions")
    id("warp.checkstyle-conventions")
    id("warp.jacoco-conventions")
    id("warp.pitest-conventions")
    id("warp.jmh-conventions")
    id("com.gradleup.shadow")
    application
}

description = "Warp Proxy — core proxy implementation"

application {
    mainClass = "dev.warp.proxy.WarpBootstrap"
}

val generateBuildInfo = tasks.register("generateBuildInfo") {
    val outputDir = layout.buildDirectory.dir("generated/build-info")
    val ver = project.version.toString()
    val commit = providers.exec { commandLine("git", "rev-parse", "--short", "HEAD") }
        .standardOutput.asText.map { it.trim() }
    val branch = providers.exec { commandLine("git", "rev-parse", "--abbrev-ref", "HEAD") }
        .standardOutput.asText.map { it.trim() }
    inputs.property("version", ver)
    inputs.property("gitCommit", commit)
    inputs.property("gitBranch", branch)
    outputs.dir(outputDir)
    doLast {
        val file = outputDir.get().file("warp-build.properties").asFile
        file.parentFile.mkdirs()
        file.writeText("version=${ver}\ngit.commit=${commit.get()}\ngit.branch=${branch.get()}\n")
    }
}

sourceSets.main {
    resources.srcDir(generateBuildInfo)
}

// The jar's entry point, compiled for Java 8: on a Java too old for Warp it says which Java to
// install, where the JVM would fail with an UnsupportedClassVersionError (BungeeCord #751).
val launcher: SourceSet = sourceSets.create("launcher")

tasks.named<JavaCompile>(launcher.compileJavaTaskName) {
    options.release = 8
    // javac warns that release 8 is obsolete: it is the point.
    options.compilerArgs.add("-Xlint:-options")
}

dependencies {
    implementation(project(":api"))
    implementation(project(":protocol"))
    implementation(project(":jni"))

    // Netty transport
    implementation(platform(libs.netty.bom))
    implementation(libs.bundles.netty)
    // HTTP for the management server only (probes, drain), on its own event loop
    implementation(libs.netty.codec.http)

    // PROXY protocol v1/v2 decoder, in the pipeline only when proxy-protocol is enabled.
    implementation(libs.netty.codec.haproxy)
    implementation(libs.netty.transport.native.epoll) {
        artifact { classifier = "linux-x86_64" }
    }
    implementation(libs.netty.transport.native.epoll) {
        artifact { classifier = "linux-aarch_64" }
    }
    implementation(libs.netty.transport.native.kqueue) {
        artifact { classifier = "osx-x86_64" }
    }
    implementation(libs.netty.transport.native.kqueue) {
        artifact { classifier = "osx-aarch_64" }
    }

    // Logging implementation
    implementation(libs.bundles.log4j)
    implementation(libs.disruptor)

    // Console: JLine through its FFM provider (Java 22+), without the JNI library it otherwise
    // brings; colours for Adventure components.
    implementation(libs.bundles.jline) {
        exclude(group = "org.jline", module = "jline-native")
    }
    implementation(libs.adventure.text.serializer.ansi)
    // Log4j's plugin processor indexes the console appender (WarpConsole in log4j2.xml).
    annotationProcessor(libs.log4j.core)

    // Utilities
    implementation(libs.caffeine)
    implementation(libs.gson)
    implementation(libs.adventure.text.serializer.gson)
    implementation(libs.configurate.hocon)
    // Read directly too, for the line of each key in configuration errors.
    implementation(libs.typesafe.config)

    "launcherCompileOnly"(libs.jspecify)
    testImplementation(launcher.output)

    // Native crypto
    implementation(libs.netty.tcnative.boringssl)
}

tasks.compileJava {
    // Options of Log4j's GraalVM metadata processor, which runs alongside its plugin processor.
    options.compilerArgs.addAll(
        listOf("-Alog4j.graalvm.groupId=dev.warp", "-Alog4j.graalvm.artifactId=warp-proxy"),
    )
}

tasks.shadowJar {
    archiveBaseName = "warp"
    archiveClassifier = ""
    // Every copy of a duplicated path must reach the transformers below. Shadow's default (EXCLUDE
    // since 9.0.1) keeps only the first one, which leaves Log4j with a single jar's plugin cache.
    // INCLUDE also keeps the other duplicates of the dependencies (LICENSE, NOTICE...) as they are.
    duplicatesStrategy = DuplicatesStrategy.INCLUDE
    mergeServiceFiles()
    // Merge Log4j2 plugin cache files across JARs (required for JsonTemplateLayout etc.)
    transform(com.github.jengelman.gradle.plugins.shadow.transformers.Log4j2PluginsCacheFileTransformer::class.java)
    exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA")
    exclude("**/module-info.class")
    exclude("META-INF/versions/*/module-info.class")
    from(launcher.output)
    manifest {
        attributes(
            "Main-Class" to "dev.warp.proxy.launcher.WarpLauncher",
            "Multi-Release" to true,
            // Netty's epoll/kqueue transports load JNI libraries (JEP 472 restricted methods).
            "Enable-Native-Access" to "ALL-UNNAMED",
            "Implementation-Title" to "Warp",
            "Implementation-Version" to project.version,
        )
    }
    relocate("com.google.gson", "dev.warp.libs.gson")
    relocate("com.github.benmanes.caffeine", "dev.warp.libs.caffeine")
    relocate("org.spongepowered.configurate", "dev.warp.libs.configurate")
    relocate("com.typesafe.config", "dev.warp.libs.typesafe.config")
}

tasks.named("build") {
    dependsOn(tasks.shadowJar)
}
