plugins {
    id("com.diffplug.spotless")
}

spotless {
    java {
        googleJavaFormat("1.35.0")
        importOrder("dev.warp", "java", "javax", "")
        removeUnusedImports()
        trimTrailingWhitespace()
        endWithNewline()
        licenseHeaderFile(rootProject.file("config/license-header.txt"))
    }
}
