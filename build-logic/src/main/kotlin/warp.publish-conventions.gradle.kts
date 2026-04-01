plugins {
    `java-library`
    `maven-publish`
}

publishing {
    publications {
        register<MavenPublication>("mavenJava") {
            from(components["java"])
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
