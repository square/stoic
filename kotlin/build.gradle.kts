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

        val buildToolsVersion = libs.versions.androidBuildTools.get()
        val minSdk = libs.versions.androidMinSdk.get()

        tasks.register<Exec>("dexJar") {
            dependsOn(jarTask)

            inputs.file(jarFile)
            outputs.file(dexJarFile)

            doFirst {
                commandLine(
                    "$androidHome/build-tools/$buildToolsVersion/d8",
                    "--min-api", minSdk,
                    "--output", dexJarFile.get().absolutePath,
                    jarFile.get().absolutePath
                )
            }
        }

        // Expose the output path using a Gradle property
        extensions.add("dexJarPath", dexJarFile)
    }
}
