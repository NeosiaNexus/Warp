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
    // velocity-native asks for Guava 33.7.1. The benchmark classpath gets 33.7.2 through the API's
    // constraint, but the `jmh` configuration resolved alone (as the dependency graph submission
    // does) does not see it, and reported the vulnerable version.
    constraints {
        jmh(libs.guava) {
            because("fixed by 33.7.2: GHSA-xxph-c9ww-hj94")
        }
    }
}
