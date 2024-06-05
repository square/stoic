import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    kotlin("jvm") version "1.9.24"
    kotlin("plugin.serialization") version "1.9.24"  // Serialization plugin
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
    implementation(kotlin("stdlib"))
    implementation(project(":common"))
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")

    compileOnly(files("$androidHome/platforms/android-34/android.jar"))
}

tasks.withType<KotlinCompile> {
    kotlinOptions {
        jvmTarget = "17"
    }
}

tasks.jar {
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    manifest {
        attributes("Main-Class" to "com.square.stoic.android.server.ServerKt")
    }
    // To include all dependencies in the JAR file, uncomment the following lines:
    from({
        configurations.runtimeClasspath.get().filter { it.exists() }.map { if (it.isDirectory) it else zipTree(it) }
    })
}
