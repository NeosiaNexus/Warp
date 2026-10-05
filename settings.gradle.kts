pluginManagement {
    includeBuild("build-logic")
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.9.0"
}

dependencyResolutionManagement {
    repositoriesMode = RepositoriesMode.FAIL_ON_PROJECT_REPOS
    repositories {
        mavenCentral()
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
