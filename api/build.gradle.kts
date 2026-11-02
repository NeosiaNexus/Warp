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

    // SLF4J: plugins log through this facade
    api(libs.slf4j.api)

    // JSpecify: the API's nullness annotations reach plugin compilers (Kotlin reads them)
    api(libs.jspecify)
}
