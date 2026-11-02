pluginManagement {
    includeBuild("build-logic")
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

dependencyResolutionManagement {
    repositoriesMode = RepositoriesMode.FAIL_ON_PROJECT_REPOS
    repositories {
        mavenCentral()
        // Mojang's Brigadier, the command library of the API: published on Mojang's own repository.
        exclusiveContent {
            forRepository {
                maven("https://libraries.minecraft.net") { name = "mojang" }
            }
            filter { includeGroup("com.mojang") }
        }
        // ViaVersion and ViaBackwards, bundled into the via plugin: published on their own repository.
        exclusiveContent {
            forRepository {
                maven("https://repo.viaversion.com") { name = "viaversion" }
            }
            filter { includeGroup("com.viaversion") }
        }
        // Velocity's natives, used only as the competitor baseline in JMH benchmarks.
        exclusiveContent {
            forRepository {
                maven("https://repo.papermc.io/repository/maven-public/") { name = "papermc" }
            }
            filter { includeGroup("com.velocitypowered") }
        }
    }
}

rootProject.name = "warp"

include("api")
include("protocol")
include("proxy")
include("jni")
include("via")

// The plugin the end-to-end harness loads into Warp (e2e/run.sh), next to the harness.
include("e2e-plugin")
project(":e2e-plugin").projectDir = file("e2e/plugin")
