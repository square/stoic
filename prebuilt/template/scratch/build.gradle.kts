plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.shadow)
    alias(libs.plugins.application)
}

repositories {
    mavenCentral()
}

application {
    mainClass.set("MainKt")
}

dependencies {
    val androidHome = providers.environmentVariable("ANDROID_HOME").orNull
        ?: throw GradleException("ANDROID_HOME is not set")
    val androidCompileSdk = libs.versions.androidCompileSdk.get()
    val androidJar = "$androidHome/platforms/android-$androidCompileSdk/android.jar"
    val stoicPluginSdk = "${rootProject.projectDir}/../../sdk/stoic-android-plugin-sdk.jar"

    implementation(kotlin("stdlib"))
    compileOnly(files(stoicPluginSdk))
    compileOnly(files(androidJar))
}

// isEnableRelocation=true avoids conflicts if we have different
// versions of the same library in the plugin and in stoic and/or the app we
// attach to
tasks.shadowJar {
    isEnableRelocation = true
    relocationPrefix = "stoicplugin"
}
