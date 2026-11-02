plugins {
    id("warp.java-conventions")
    id("warp.spotless-conventions")
    id("warp.checkstyle-conventions")
    id("warp.jacoco-conventions")
    id("warp.publish-conventions")
    id("warp.api-release-conventions")
}

description = "Warp API — public plugin API for the Warp Minecraft proxy"

dependencies {
    // Adventure (text components, audiences)
    api(platform(libs.adventure.bom))
    api(libs.adventure.api)
    api(libs.adventure.text.minimessage)
    api(libs.adventure.text.serializer.plain)
    api(libs.adventure.text.serializer.legacy)

    // Brigadier: commands, as the vanilla client knows them
    api(libs.brigadier)

    // SLF4J: plugins log through this facade
    api(libs.slf4j.api)

    // JSpecify: the API's nullness annotations reach plugin compilers (Kotlin reads them)
    api(libs.jspecify)

    // Netty: the types of the network pipeline API (dev.warp.api.network). On the compile class
    // path of plugins only: at run time, plugins that declare the NETWORK_PIPELINE capability see
    // the proxy's Netty, and the others see none.
    compileOnlyApi(platform(libs.netty.bom))
    compileOnlyApi(libs.netty.transport)
}
