plugins {
    id("warp.java-conventions")
    id("warp.spotless-conventions")
    id("warp.checkstyle-conventions")
}

description = "Warp e2e audience probe: a test plugin the end-to-end harness loads into Warp"

dependencies {
    compileOnly(project(":api"))
    annotationProcessor(project(":api"))
}

// A fixed name: the harness and CI find the jar by it.
tasks.jar {
    archiveFileName = "e2e-audience-probe.jar"
}
