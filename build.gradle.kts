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
