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

    // Competitor baseline for benchmarks only: Velocity's libdeflate and OpenSSL natives, measured
    // against the exact Netty version Warp ships.
    jmh(libs.velocity.native) {
        exclude(group = "io.netty")
    }
}
