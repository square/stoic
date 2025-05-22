plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.application)
}

dependencies {
    implementation(project(":bridge"))
}

application {
    mainClass.set("com.squareup.stoic.d8pm.MainKt")
}
