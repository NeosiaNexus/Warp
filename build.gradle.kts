plugins {
    id("warp.java-conventions") apply false
}

// version.txt holds the last release (release-please writes it). The release workflow builds it with
// -Pwarp.release=true, from the release's tag; every other build is a snapshot of the next version
// (0.1.0-beta.6-SNAPSHOT after 0.1.0-beta.5), so that no other build reuses a released number.
val releasedVersion = providers.fileContents(
    layout.projectDirectory.file("version.txt")
).asText.get().trim()
val isRelease = providers.gradleProperty("warp.release").map { it.toBoolean() }.getOrElse(false)
if (isRelease) {
    // A release build of any other commit would publish a released number for other code.
    val tags = providers.exec {
        commandLine("git", "tag", "--points-at", "HEAD")
        isIgnoreExitValue = true
    }.standardOutput.asText.get().lines().map { it.trim() }
    if ("v$releasedVersion" !in tags) {
        throw GradleException(
            "-Pwarp.release=true builds $releasedVersion, but HEAD is not tagged v$releasedVersion" +
                " (tags at HEAD: ${tags.filter { it.isNotEmpty() }}). Only the release workflow builds" +
                " releases; drop the property to build a snapshot."
        )
    }
}
val warpVersion = if (isRelease) releasedVersion else WarpVersions.nextSnapshot(releasedVersion)

allprojects {
    group = "dev.warp"
    version = warpVersion
}

// Install Git pre-commit hook on first build.
val installGitHooks = tasks.register("installGitHooks") {
    val source = layout.projectDirectory.file("config/hooks/pre-commit")
    val target = layout.projectDirectory.file(".git/hooks/pre-commit")
    inputs.file(source)
    outputs.file(target)
    doLast {
        source.asFile.copyTo(target.asFile, overwrite = true)
        target.asFile.setExecutable(true)
    }
}

tasks.register("build") {
    dependsOn(installGitHooks)
}
