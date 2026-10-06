plugins {
    jacoco
}

val libs = the<VersionCatalogsExtension>().named("libs")

jacoco {
    toolVersion = libs.findVersion("jacoco").get().requiredVersion
}

// The test tasks whose coverage is reported. Fuzzing (warp.fuzz-conventions) runs a Test task too,
// but never as part of a build, and without the JaCoCo agent.
val coveredTests = tasks.withType<Test>().named { it != "fuzz" }

tasks.withType<Test>().named { it == "fuzz" }.configureEach {
    the<JacocoTaskExtension>().isEnabled = false
}

tasks.withType<JacocoReport>().configureEach {
    dependsOn(coveredTests)
    reports {
        xml.required = true
        html.required = true
        csv.required = false
    }
}

// Generate coverage report automatically after tests run.
coveredTests.configureEach {
    finalizedBy(tasks.withType<JacocoReport>())
}

// Coverage verification — enforce minimum thresholds.
// Wired to 'check' so the build fails if coverage drops below the floor.
tasks.withType<JacocoCoverageVerification>().configureEach {
    violationRules {
        rule {
            limit {
                // Start low, ratchet up as the codebase matures.
                minimum = "0.0".toBigDecimal()
            }
        }
    }
}

tasks.named("check") {
    dependsOn(tasks.withType<JacocoCoverageVerification>())
}
