plugins {
    id("warp.java-conventions")
    id("warp.spotless-conventions")
    id("warp.checkstyle-conventions")
    id("warp.jacoco-conventions")
    id("warp.publish-conventions")
}

description = "Warp API — public plugin API for the Warp Minecraft proxy"

dependencies {
    // Adventure (text components, audiences)
    api(platform(libs.adventure.bom))
    api(libs.adventure.api)
    api(libs.adventure.text.minimessage)

    // SLF4J — plugins log through this facade
    api(libs.slf4j.api)

    // Guice — DI available to plugins
    api(libs.guice)

    // Config
    api(libs.configurate.hocon)

    // Guava comes from Guice, whose latest release (7.0.0) depends on 31.0.1-jre. Declared on `api`,
    // the constraint is published with the API: plugin builds resolve the Guava the proxy ships.
    constraints {
        api(libs.guava) {
            because("fixed by 33.7.2: CVE-2020-8908, CVE-2023-2976, CVE-2026-102554")
        }
    }
}
