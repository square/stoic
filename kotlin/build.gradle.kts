import java.nio.file.Files
import java.nio.file.StandardCopyOption

repositories {
    google()
    mavenCentral()
}

subprojects {
    // Assuming all subprojects apply the 'java' plugin and potentially produce a JAR
    plugins.withId("java") {
        val copyJar by tasks.registering {
            dependsOn("jar")

            // Register JAR_PATH as an input to influence up-to-date checks
            val jarPath: String? = System.getenv("JAR_PATH") ?: throw IllegalArgumentException("Environment variable JAR_PATH not set or invalid")
            inputs.property("jarPath", jarPath)

            doLast {
                val jarTask = tasks.named<Jar>("jar").get()
                val sourcePath = jarTask.archiveFile.get().asFile.toPath()
                val jarFile = File(jarPath)
                jarFile.parentFile.mkdirs()

                Files.copy(sourcePath, jarFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
                //println("JAR file copied to: $jarPath")
            }
        }

        val androidHome: String? = System.getenv("ANDROID_HOME")
        val dexJar by tasks.registering(Exec::class) {
            if (androidHome == null) {
                throw GradleException("ANDROID_HOME environment variable is not set.")
            }

            dependsOn("jar")

            val jarTask = tasks.named<Jar>("jar").get()
            val sourcePath = jarTask.archiveFile.get().asFile.getAbsolutePath()
            val dexJar = sourcePath.replace(".jar$".toRegex(), ".dex.jar")
            extensions.add("dexJar", dexJar)
            commandLine("$androidHome/build-tools/35.0.1/d8", "--min-api", "26", "--output", dexJar, sourcePath)
        }

        val copyDexJar by tasks.registering {
            dependsOn("dexJar")

            // Register JAR_PATH as an input to influence up-to-date checks
            val dstPathStr: String? = System.getenv("JAR_PATH") ?: throw IllegalArgumentException("Environment variable JAR_PATH not set or invalid")
            inputs.property("dstPathStr", dstPathStr)

            doLast {
                val jarTask = tasks.named("dexJar").get()
                val sourceFile = File(jarTask.extensions.getByName("dexJar") as String)
                val dstFile = File(dstPathStr)
                dstFile.parentFile.mkdirs()

                Files.copy(sourceFile.toPath(), dstFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        }
    }
}
