plugins {
    id("warp.java-conventions") apply false
}

val warpVersion = providers.fileContents(
    layout.projectDirectory.file("version.txt")
).asText.get().trim()

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
