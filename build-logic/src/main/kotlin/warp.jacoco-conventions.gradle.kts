plugins {
    jacoco
}

val libs = the<VersionCatalogsExtension>().named("libs")

jacoco {
    toolVersion = libs.findVersion("jacoco").get().requiredVersion
}

tasks.withType<JacocoReport>().configureEach {
    dependsOn(tasks.withType<Test>())
    reports {
        xml.required = true
        html.required = true
        csv.required = false
    }
}

// Generate coverage report automatically after tests run.
tasks.withType<Test>().configureEach {
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
