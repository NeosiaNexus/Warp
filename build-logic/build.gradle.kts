plugins {
    `kotlin-dsl`
}

dependencies {
    implementation(libs.plugins.spotless.get().let {
        "${it.pluginId}:${it.pluginId}.gradle.plugin:${it.version}"
    })
    implementation(libs.plugins.shadow.get().let {
        "${it.pluginId}:${it.pluginId}.gradle.plugin:${it.version}"
    })
    // ErrorProne plugin (version managed via version catalog)
    implementation(libs.plugins.errorprone.get().let {
        "${it.pluginId}:${it.pluginId}.gradle.plugin:${it.version}"
    })
    implementation(libs.plugins.jmh.get().let {
        "${it.pluginId}:${it.pluginId}.gradle.plugin:${it.version}"
    })

    // Minimum versions of what these plugins put on the build classpath. Shadow 9.0.0 depends on
    // log4j-core 2.25.1 and plexus-utils 4.0.2, and its releases with patched versions (9.4.3 and
    // later) need Gradle 9: remove both constraints once Shadow is upgraded. log4j-core follows the
    // version Warp ships, whose plugin caches Shadow merges with it.
    constraints {
        implementation(libs.log4j.core) {
            because("fixed by 2.25.4: CVE-2025-68161, CVE-2026-34477, CVE-2026-34478, CVE-2026-34480")
        }
        implementation(libs.plexus.utils) {
            because("fixed by 4.0.3: CVE-2025-67030")
        }
    }
}
