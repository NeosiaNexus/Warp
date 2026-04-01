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
