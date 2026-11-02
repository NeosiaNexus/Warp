plugins {
    id("warp.java-conventions")
    id("warp.spotless-conventions")
    id("warp.checkstyle-conventions")
    id("warp.jacoco-conventions")
    id("warp.jmh-conventions")
    id("warp.fuzz-conventions")
    id("warp.pitest-conventions")
}

description = "Warp Protocol — Minecraft protocol codec and packet definitions"

dependencies {
    // API module (provides Adventure transitively)
    api(project(":api"))

    // Netty for codec pipeline
    api(platform(libs.netty.bom))
    api(libs.netty.buffer)
    api(libs.netty.codec)

    // Gson for protocol serialization
    implementation(libs.gson)

    // NBT values carried by components (item hover data components, custom click payloads), parsed
    // from the SNBT Adventure holds them in
    implementation(libs.adventure.nbt)

    // Reads the JSON components backends send (disconnect reasons)
    implementation(libs.adventure.text.serializer.gson)

    // Competitor baseline for benchmarks only: Velocity's libdeflate and OpenSSL natives, measured
    // against the exact Netty version Warp ships.
    jmh(libs.velocity.native) {
        exclude(group = "io.netty")
    }
}
