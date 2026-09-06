import java.util.Properties

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.hilt) apply false
    alias(libs.plugins.ksp) apply false
}

// Load local.properties for SDK path
val localProperties = Properties()
val localPropertiesFile = rootProject.file("local.properties")
if (localPropertiesFile.exists()) {
    localProperties.load(localPropertiesFile.inputStream())
}

// Ensure vendor/proot has the production patch applied
val prootDir = file("vendor/proot")
val patchFile = file("vendor/patches/proot-static-talloc-and-arm64-untag.patch")
val cmakeLists = File(prootDir, "CMakeLists.txt")

if (prootDir.exists() && patchFile.exists() && cmakeLists.exists()) {
    val content = cmakeLists.readText()
    if (!content.contains("add_library(talloc STATIC")) {
        println("Applying production PRoot patch (static talloc + ARM64 TBI untagging)...")
        val pb = ProcessBuilder("git", "apply", "--ignore-whitespace", patchFile.absolutePath)
            .directory(prootDir)
            .redirectErrorStream(true)
            .start()
        val out = pb.inputStream.bufferedReader().readText()
        if (pb.waitFor() != 0) {
            val patchPb = ProcessBuilder("patch", "-p1", "-i", patchFile.absolutePath)
                .directory(prootDir)
                .redirectErrorStream(true)
                .start()
            val patchOut = patchPb.inputStream.bufferedReader().readText()
            if (patchPb.waitFor() != 0) {
                logger.warn("Failed to apply PRoot patch automatically: $out\n$patchOut")
            }
        }
    }
}

