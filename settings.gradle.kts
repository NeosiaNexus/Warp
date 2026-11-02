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
