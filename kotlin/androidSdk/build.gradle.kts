import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
  id("com.android.library")
  id("org.jetbrains.kotlin.android")
}

// TODO: provide a way for apps to specify a different key
val stoicSshKeyFile = File(System.getProperty("user.home"), ".config/stoic/sync/ssh/id_rsa.pub")

android {
  namespace = "com.square.stoic.android.sdk"
  compileSdk = 34

  defaultConfig {
    minSdk = 26

    testInstrumentationRunner = "android.support.test.runner.AndroidJUnitRunner"
    consumerProguardFiles("consumer-rules.pro")
  }

  buildTypes {
    all {
      // Read and escape the key, or use null if missing
      val authorizedKey = if (stoicSshKeyFile.exists()) {
        stoicSshKeyFile.readText().trim()
          .replace("\\", "\\\\")
          .replace("\"", "\\\"")
      } else {
        null
      }

      buildConfigField(
        "String",
        "STOIC_AUTHORIZED_KEY",
        authorizedKey?.let { "\"$it\"" } ?: "null"
      )
    }
    release {
      isMinifyEnabled = false
      proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
    }
  }
  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
  }
  kotlinOptions {
    jvmTarget = "17"
  }

  packaging {
    resources {
      excludes += "META-INF/DEPENDENCIES"
    }
  }

  buildFeatures {
    buildConfig = true
  }
}

// Inform gradle that Kotlin compilation depends on the stoicSshKeyFile (the path and the contents)
// Without this, anything that inlines STOIC_AUTHORIZED_KEY (e.g. StoicContentProvider) might pull
// stale outputs from the cache.
tasks.withType<KotlinCompile>().configureEach {
  inputs.file(stoicSshKeyFile)
    .withPathSensitivity(PathSensitivity.ABSOLUTE)
    .withPropertyName("sshKeyFileUsedInBuildConfig")
}

dependencies {
  implementation(project(":common"))
  implementation(project(":stoicAndroid"))
  implementation(project(":androidServer"))
  implementation("com.android.support:appcompat-v7:28.0.0")
  testImplementation("junit:junit:4.13.2")
  androidTestImplementation("com.android.support.test:runner:1.0.2")
  androidTestImplementation("com.android.support.test.espresso:espresso-core:3.0.2")
}
