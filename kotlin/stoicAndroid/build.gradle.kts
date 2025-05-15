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
    implementation(kotlin("stdlib"))
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")
    compileOnly(files("$androidHome/platforms/android-35/android.jar"))
}

tasks.withType<KotlinCompile> {
    kotlinOptions {
        jvmTarget = "17"
    }
}

tasks.register<Jar>("sourcesJar") {
    from(sourceSets["main"].allSource)
    archiveClassifier.set("sources")
}

artifacts {
    add("archives", tasks["sourcesJar"])
}
