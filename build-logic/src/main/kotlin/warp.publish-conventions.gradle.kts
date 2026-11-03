plugins {
    `java-library`
    `maven-publish`
}

publishing {
    repositories {
        // GitHub Packages: the snapshots of main (.github/workflows/snapshots.yml). Credentials come
        // from the GitHubPackagesUsername and GitHubPackagesPassword properties, such as the
        // ORG_GRADLE_PROJECT_GitHubPackagesUsername environment variable, read only when publishing.
        maven {
            name = "GitHubPackages"
            url = uri("https://maven.pkg.github.com/NeosiaNexus/Warp")
            credentials(PasswordCredentials::class)
        }
    }
    publications {
        register<MavenPublication>("mavenJava") {
            from(components["java"])
            // dev.warp:warp-api rather than dev.warp:api: unambiguous next to other projects' "api".
            artifactId = "warp-${project.name}"
            pom {
                name = project.name
                description = project.description
                url = "https://github.com/NeosiaNexus/warp"
                licenses {
                    license {
                        name = "GNU Affero General Public License v3.0"
                        url = "https://www.gnu.org/licenses/agpl-3.0.html"
                    }
                }
                scm {
                    connection = "scm:git:git://github.com/NeosiaNexus/warp.git"
                    developerConnection = "scm:git:ssh://github.com/NeosiaNexus/warp.git"
                    url = "https://github.com/NeosiaNexus/warp"
                }
            }
        }
    }
}
