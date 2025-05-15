import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")  // Serialization plugin
}

repositories {
    mavenCentral()
}

val androidHome: String? = System.getenv("ANDROID_HOME")
dependencies {
    if (androidHome == null) {
        throw GradleException("ANDROID_HOME environment variable is not set.")
    }

    implementation(project(":stoicAndroid"))
    implementation(project(":common"))
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")

    compileOnly(files("$androidHome/platforms/android-34/android.jar"))
}