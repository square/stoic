import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
  alias(libs.plugins.kotlin.jvm)
}

repositories {
  mavenCentral()
}

val androidHome = providers.environmentVariable("ANDROID_HOME").orNull
  ?: throw GradleException("ANDROID_HOME is not set")

dependencies {
  implementation(project(":common"))
  implementation("org.apache.sshd:sshd-core:2.15.0")
  implementation("org.slf4j:slf4j-simple:1.7.36")
  implementation("org.slf4j:slf4j-api:1.7.36")
  compileOnly(files("$androidHome/platforms/android-${libs.versions.androidCompileSdk.get()}/android.jar"))
}

tasks.withType<KotlinCompile> {
  kotlinOptions {
    jvmTarget = "17"
  }
}
