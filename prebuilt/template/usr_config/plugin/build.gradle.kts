import java.nio.file.Files
import java.nio.file.StandardCopyOption

subprojects {
    // Assuming all subprojects apply the 'java' plugin and potentially produce a JAR
    plugins.withId("java") {
        val androidHome: String? = System.getenv("ANDROID_HOME")
        val dexJar by tasks.registering(Exec::class) {
            if (androidHome == null) {
                throw GradleException("ANDROID_HOME environment variable is not set.")
            }

            dependsOn("jar")

            val jarTask = tasks.named<Jar>("jar").get()
            val sourcePath = jarTask.archiveFile.get().asFile.absolutePath
            val dexJar = sourcePath.replace(".jar$".toRegex(), ".dex.jar")
            extensions.add("dexJar", dexJar)
            commandLine("$androidHome/build-tools/34.0.0/d8", "--min-api", "34", "--output", dexJar, sourcePath)
        }
    }
}
