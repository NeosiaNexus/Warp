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
    // Where Git looks for hooks: .git/hooks in a clone, the main repository's hooks directory from a
    // linked worktree (whose .git is a file), or core.hooksPath. Empty outside a Git checkout.
    val hooksPath = providers.exec {
        commandLine("git", "rev-parse", "--git-path", "hooks")
        isIgnoreExitValue = true
    }.standardOutput.asText.map { it.trim() }
    val source = layout.projectDirectory.file("config/hooks/pre-commit")
    val root = layout.projectDirectory.asFile
    val target = hooksPath.map { path ->
        val hooks = File(path)
        File(if (hooks.isAbsolute) hooks else root.resolve(path), "pre-commit")
    }
    inputs.file(source)
    outputs.file(target)
    onlyIf("inside a Git checkout") { hooksPath.get().isNotEmpty() }
    doLast {
        val hook = target.get()
        hook.parentFile.mkdirs()
        source.asFile.copyTo(hook, overwrite = true)
        hook.setExecutable(true)
    }
}

tasks.register("build") {
    dependsOn(installGitHooks)
}
