plugins {
    id("warp.java-conventions")
    id("warp.spotless-conventions")
    id("warp.checkstyle-conventions")
    id("com.gradleup.shadow")
    application
}

description = "Warp Proxy — core proxy implementation"

application {
    mainClass = "dev.warp.proxy.WarpBootstrap"
}

dependencies {
    implementation(project(":api"))
    implementation(project(":protocol"))
    implementation(project(":jni"))

    // Netty transport
    implementation(platform(libs.netty.bom))
    implementation(libs.bundles.netty)
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

    // Utilities
    implementation(libs.caffeine)

    // Native crypto
    implementation(libs.netty.tcnative.boringssl)
}

tasks.shadowJar {
    archiveBaseName = "warp"
    archiveClassifier = ""
    mergeServiceFiles()
    // Merge Log4j2 plugin cache files across JARs (required for JsonTemplateLayout etc.)
    transform(com.github.jengelman.gradle.plugins.shadow.transformers.Log4j2PluginsCacheFileTransformer::class.java)
    exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA")
    exclude("**/module-info.class")
    exclude("META-INF/versions/*/module-info.class")
    manifest {
        attributes(
            "Main-Class" to application.mainClass.get(),
            "Multi-Release" to true,
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
