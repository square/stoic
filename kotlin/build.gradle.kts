import java.util.Properties

plugins {
    // Plugins need to be declared here to avoid warnings like:
    //   The Kotlin Gradle plugin was loaded multiple times in different
    //   subprojects, which is not supported and may break the build.
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.serialization) apply false
}

repositories {
    google()
    mavenCentral()
}

fun versionCodeFromVersionName(versionName: String): Int {
    val snapshotSuffix = "-SNAPSHOT"
    val isSnapshot = versionName.endsWith(snapshotSuffix)

    val cleanedVersion = if (isSnapshot) {
        versionName.removeSuffix(snapshotSuffix)
    } else {
        versionName
    }

    val parts = cleanedVersion.split(".")
    require(parts.size == 3) { "Invalid version format: expected MAJOR.MINOR.PATCH (got '$versionName')" }

    val major = parts[0].toIntOrNull() ?: error("Invalid major version in '$versionName'")
    val minor = parts[1].toIntOrNull() ?: error("Invalid minor version in '$versionName'")
    val patch = parts[2].toIntOrNull() ?: error("Invalid patch version in '$versionName'")

    require(major in 0..1999) { "Major version must be between 0-1999 (got $major)" }
    require(minor in 0..999)  { "Minor version must be between 0–999 (got $minor)" }
    require(patch in 0..999)  { "Patch version must be between 0–999 (got $patch)" }

    val base = major * 1_000_000 +
               minor *    10_000 +
               patch *        10

    return if (isSnapshot) base - 1 else base
}

val prebuiltDir = rootProject.file("../prebuilt")
val versionName = prebuiltDir.resolve("STOIC_VERSION").readText().trim()

val stoicProps = Properties().apply {
    prebuiltDir.resolve("stoic.properties").reader().use { load(it) }
}

val androidMinSdk = stoicProps.getProperty("android_min_sdk") ?: error("Missing android_min_sdk")
val androidCompileSdk = stoicProps.getProperty("android_compile_sdk") ?: error("Missing android_compile_sdk")
val androidTargetSdk = stoicProps.getProperty("android_target_sdk") ?: error("Missing android_target_sdk")
val androidBuildToolsVersion = stoicProps.getProperty("android_build_tools_version") ?: error("Missing android_build_tools_version")

val androidHome = providers.environmentVariable("ANDROID_HOME").orNull
  ?: error("ANDROID_HOME environment variable is not set.")

val d8Path = file("$androidHome/build-tools/$androidBuildToolsVersion/d8")
check(d8Path.exists()) { "Missing d8: $d8Path" }

val stoicGeneratedSourceDir = layout.buildDirectory.dir("generated/stoic-version")
val generateStoicVersion by tasks.registering {
    inputs.property("version", versionName)
    outputs.dir(stoicGeneratedSourceDir)

    doLast {
        val file = stoicGeneratedSourceDir.get().asFile.resolve("com/squareup/stoic/StoicVersion.kt")
        file.parentFile.mkdirs()
        file.writeText(
          """
            package com.squareup.stoic

            object StoicProperties {
                const val STOIC_VERSION_NAME = "$versionName"
                const val ANDROID_MIN_SDK = $androidMinSdk
                const val ANDROID_COMPILE_SDK = $androidCompileSdk
                const val ANDROID_TARGET_SDK = $androidTargetSdk
                const val ANDROID_BUILD_TOOLS_VERSION = "$androidBuildToolsVersion"
            }
            """.trimIndent()
        )
    }
}

extra["stoicGeneratedSourceDir"] = stoicGeneratedSourceDir
extra["generateStoicVersion"] = generateStoicVersion

subprojects {
    extra["stoic.android_min_sdk"] = androidMinSdk
    extra["stoic.android_compile_sdk"] = androidCompileSdk
    extra["stoic.android_target_sdk"] = androidTargetSdk
    extra["stoic.android_build_tools_version"] = androidBuildToolsVersion
    extra["stoic.version_name"] = versionName
    extra["stoic.version_code"] = versionCodeFromVersionName(versionName)

    plugins.withId("java") {
        val jarTask = tasks.named<Jar>("jar")
        val jarFile = jarTask.flatMap { it.archiveFile }.map { it.asFile }

        val dexJarFile = jarFile.map { File(it.path.replace(".jar", ".dex.jar")) }

        tasks.register<Exec>("dexJar") {
            dependsOn(jarTask)

            inputs.file(jarFile)
            outputs.file(dexJarFile)

            doFirst {
                commandLine(
                  "$androidHome/build-tools/$androidBuildToolsVersion/d8",
                  "--min-api", androidMinSdk,
                  "--output", dexJarFile.get().absolutePath,
                  jarFile.get().absolutePath
                )
            }
        }
    }

    val projectPathSlug = project.path.removePrefix(":").replace(":", "-")
    tasks.withType<Jar>().configureEach {
        manifest {
            attributes(
              "Implementation-Title" to "stoic-$projectPathSlug",
              "Implementation-Version" to versionName
            )
        }
    }
}
