import java.nio.file.Files
import java.nio.file.StandardCopyOption

plugins {
    kotlin("jvm") version "1.9.24" apply false
    kotlin("plugin.serialization") version "1.9.24" apply false
    id("com.android.application") version "8.9.0" apply false
    id("org.jetbrains.kotlin.android") version "2.0.0" apply false
}

repositories {
    google()
    mavenCentral()
}

subprojects {
    plugins.withId("java") {
        val androidHome = providers.environmentVariable("ANDROID_HOME")
            .orNull ?: throw GradleException("ANDROID_HOME environment variable not set.")

        val jarTask = tasks.named<Jar>("jar")
        val jarFile = jarTask.flatMap { it.archiveFile }.map { it.asFile }

        val dexJarFile = jarFile.map { File(it.path.replace(".jar", ".dex.jar")) }

        val dexJar = tasks.register<Exec>("dexJar") {
            dependsOn(jarTask)

            inputs.file(jarFile)
            outputs.file(dexJarFile)

            doFirst {
                commandLine(
                    "$androidHome/build-tools/35.0.1/d8",
                    "--min-api", "26",
                    "--output", dexJarFile.get().absolutePath,
                    jarFile.get().absolutePath
                )
            }
        }

        // Optional: expose the output path using a Gradle property
        extensions.add("dexJarPath", dexJarFile)
    }
}
