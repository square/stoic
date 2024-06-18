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
include("plugin_appexitinfo")
include("plugin_breakpoint")
include("plugin_helloworld")
include("plugin_crasher")
include("plugin_testsuite")
include("exampleapp")
