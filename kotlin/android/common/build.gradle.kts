import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
  kotlin("jvm")
}

repositories {
  mavenCentral()
}

val androidHome: String? = System.getenv("ANDROID_HOME")
dependencies {
  implementation(project(":common"))
  implementation("org.apache.sshd:sshd-core:2.15.0")
  implementation("org.slf4j:slf4j-simple:1.7.36")
  implementation("org.slf4j:slf4j-api:1.7.36")
  compileOnly(files("$androidHome/platforms/android-35/android.jar"))
}

tasks.withType<KotlinCompile> {
  kotlinOptions {
    jvmTarget = "17"
  }
}