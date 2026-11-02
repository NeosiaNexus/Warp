plugins {
    id("warp.java-conventions")
    id("warp.spotless-conventions")
    id("warp.checkstyle-conventions")
}

description = "Warp Via: ViaVersion and ViaBackwards as a Warp plugin"

dependencies {
    // The proxy provides the API at run time; the processor writes warp-plugin.json.
    compileOnly(project(":api"))
    annotationProcessor(project(":api"))

    // Netty comes from the proxy, which plugins with the NETWORK_PIPELINE capability see.
    compileOnly(platform(libs.netty.bom))
    compileOnly(libs.netty.buffer)
    compileOnly(libs.netty.codec)
    compileOnly(libs.netty.transport)

    // Bundled into the plugin jar. ViaVersion and ViaBackwards already relocate the libraries they
    // ship (fastutil, Gson, SnakeYAML, MCStructs); Guava is expected from the platform, which Warp
    // does not provide, so the plugin carries it too. The plugin's own class loader keeps all of
    // them away from the proxy and from other plugins.
    implementation(libs.viaversion.common)
    implementation(libs.viabackwards.common)
    implementation(libs.guava)

    testImplementation(project(":api"))
    testImplementation(platform(libs.netty.bom))
    testImplementation(libs.bundles.netty)

    // Integration tests: real ViaVersion behind Warp's real pipeline, codecs and pipeline registry.
    "integrationTestImplementation"(project(":api"))
    "integrationTestImplementation"(project(":protocol"))
    "integrationTestImplementation"(project(":proxy"))
    "integrationTestImplementation"(libs.viaversion.common)
    "integrationTestImplementation"(platform(libs.netty.bom))
    "integrationTestImplementation"(libs.bundles.netty)
    "integrationTestImplementation"(libs.mockito.core)
}

// A Warp plugin is a single jar: merge the bundled libraries into it, unrelocated (nothing else
// sees them). Their platform descriptors are dropped so the jar is not mistaken for a plugin of
// another platform, and their licences are kept under META-INF/licenses.
tasks.jar {
    archiveBaseName = "warp-via"
    val bundled = configurations.runtimeClasspath
    inputs.files(bundled)
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    from({
        bundled.get().map { jar ->
            zipTree(jar).matching {
                exclude(
                    "META-INF/MANIFEST.MF",
                    "META-INF/*.SF",
                    "META-INF/*.DSA",
                    "META-INF/*.RSA",
                    "module-info.class",
                    "META-INF/versions/*/module-info.class",
                    "LICENSE",
                    "plugin.yml",
                    "bungee.yml",
                    "velocity-plugin.json",
                    "fabric.mod.json",
                    "META-INF/sponge_plugins.json",
                )
            }
        }
    })
    from({
        bundled.get().filter { it.name.startsWith("via") }.map { jar ->
            zipTree(jar).matching { include("LICENSE") }
        }
    }) {
        into("META-INF/licenses")
        // ViaVersion and ViaBackwards are both GPLv3 and ship the same licence text.
        rename { "ViaVersion-ViaBackwards-LICENSE.txt" }
    }
    from(rootProject.file("LICENSE")) {
        into("META-INF/licenses")
        rename { "warp-via-LICENSE.txt" }
    }
    manifest {
        attributes(
            "Implementation-Title" to "Warp Via",
            "Implementation-Version" to project.version,
        )
    }
}
