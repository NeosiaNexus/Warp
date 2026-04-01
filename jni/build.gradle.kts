plugins {
    id("warp.java-conventions")
    id("warp.spotless-conventions")
}

description = "Warp JNI — native bindings for compression and cryptography"

dependencies {
    implementation(platform(libs.netty.bom))
    implementation(libs.netty.buffer)
    implementation(libs.netty.handler)
}
