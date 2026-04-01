plugins {
    id("warp.java-conventions")
    id("warp.spotless-conventions")
    id("warp.checkstyle-conventions")
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
}
