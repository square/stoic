import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")  // Serialization plugin
}

val androidHome: String? = System.getenv("ANDROID_HOME")
dependencies {
    if (androidHome == null) {
        throw GradleException("ANDROID_HOME environment variable is not set.")
    }
    compileOnly(files("$androidHome/platforms/android-35/android.jar"))

    implementation(kotlin("stdlib"))
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")
    implementation(project(":stoicAndroid"))
}

tasks.withType<KotlinCompile> {
    kotlinOptions {
        jvmTarget = "17"
    }
}

tasks.jar {
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    // To include all dependencies in the JAR file, uncomment the following lines:
  //from({
  //    configurations.runtimeClasspath.get().filter { it.exists() }.map { if (it.isDirectory) it else zipTree(it) }
  //})
}
