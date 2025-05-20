plugins {
  id("com.android.application")
  id("org.jetbrains.kotlin.android")
}

android {
  namespace = "com.squareup.stoic.demoapp.withsdk"
  compileSdk = 34

  signingConfigs {
    create("release") {
      storeFile = file(System.getProperty("user.home") + "/.android/debug.keystore")
      storePassword = "android"
      keyAlias = "androiddebugkey"
      keyPassword = "android"
    }
  }

  defaultConfig {
    applicationId = "com.squareup.stoic.demoapp.withsdk"
    minSdk = 26
    targetSdk = 34
    versionCode = 1
    versionName = "1.0"

    testInstrumentationRunner = "android.support.test.runner.AndroidJUnitRunner"
  }

  buildTypes {
    // The with-sdk demo exists to demonstrate the ability to use stoic in a
    // non-debuggable build, so we always make our builds non-debuggable - even
    // the "debug" variant.
    all {
      isDebuggable = false
      isMinifyEnabled = false
      signingConfig = signingConfigs.getByName("release")
      proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
    }
  }
  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_1_8
    targetCompatibility = JavaVersion.VERSION_1_8
  }
  kotlinOptions {
    jvmTarget = "1.8"
  }
  buildFeatures {
    viewBinding = true
  }

  // TODO: this doesn't work for source dependencies, but apparently this is the way to pre-exclude
  //   from the .aar file
  packaging {
    resources {
      excludes += "META-INF/DEPENDENCIES"
    }
  }
}

dependencies {
  implementation("com.android.support:appcompat-v7:28.0.0")
  testImplementation("junit:junit:4.13.2")
  androidTestImplementation("com.android.support.test:runner:1.0.2")
  androidTestImplementation("com.android.support.test.espresso:espresso-core:3.0.2")
  implementation(project(":android:server:sdk"))
}
