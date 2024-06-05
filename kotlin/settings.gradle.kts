rootProject.name = "stoic"

pluginManagement {
  repositories {
    gradlePluginPortal()
    google()
    mavenCentral()
  }
}

dependencyResolutionManagement {
  repositories {
    google()
    mavenCentral()
  }
}

include("common")
include("hostMain")
include("androidClient")
include("androidServer")
include("stoicAndroid")
include("plugin_helloworld")
include("plugin_appexitinfo")
include("plugin_error")
include("exampleapp")
