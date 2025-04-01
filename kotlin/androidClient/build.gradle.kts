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

    implementation(kotlin("stdlib"))
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")
    implementation(project(":common"))

    compileOnly(files("$androidHome/platforms/android-35/android.jar"))
}

tasks.withType<KotlinCompile> {
    kotlinOptions {
        jvmTarget = "17"
    }
}

tasks.jar {
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    manifest {
        attributes("Main-Class" to "com.square.stoic.android.client.ClientKt")
    }

    // To include all dependencies in the JAR file, uncomment the following lines:
    from({
        configurations.runtimeClasspath.get().filter { it.exists() }.map { if (it.isDirectory) it else zipTree(it) }
    })
}
