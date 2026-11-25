plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.kotlin.android)
  alias(libs.plugins.kotlin.serialization)
  alias(libs.plugins.ksp)
}

android {
  namespace = "com.yebente.wallet"
  compileSdk = 34

  defaultConfig {
    applicationId = "com.yebente.wallet"
    minSdk = 26
    targetSdk = 34
    versionCode = 1
    versionName = "0.1.0"
    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    vectorDrawables { useSupportLibrary = true }
  }

  buildTypes {
    release {
      isMinifyEnabled = true
      proguardFiles(
        getDefaultProguardFile("proguard-android-optimize.txt"),
        "proguard-rules.pro"
      )
    }
  }

  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
    isCoreLibraryDesugaringEnabled = true
  }
  kotlinOptions { jvmTarget = "17" }

  buildFeatures {
    compose = true
    buildConfig = true
  }
  composeOptions { kotlinCompilerExtensionVersion = "1.5.14" }

  packaging {
    resources {
      excludes += "/META-INF/INDEX.LIST"
      excludes += "/META-INF/io.netty.versions.properties"
      excludes += "/META-INF/DEPENDENCIES"
      excludes += "DebugProbesKt.bin"
    }
  }

  testOptions {
    unitTests.isIncludeAndroidResources = true
  }
}

dependencies {
  coreLibraryDesugaring(libs.desugaring)

  implementation(libs.androidx.core.ktx)
  implementation(libs.androidx.lifecycle.runtime.ktx)
  implementation(libs.androidx.lifecycle.viewmodel.compose)
  implementation(libs.androidx.activity.compose)
  implementation(libs.androidx.navigation.compose)

  implementation(platform(libs.androidx.compose.bom))
  implementation(libs.androidx.compose.ui)
  implementation(libs.androidx.compose.ui.tooling.preview)
  implementation(libs.androidx.compose.material3)
  implementation(libs.androidx.compose.material.icons)

  implementation(libs.androidx.room.runtime)
  implementation(libs.androidx.room.ktx)
  ksp(libs.androidx.room.compiler)

  implementation(libs.androidx.datastore.preferences)
  implementation(libs.androidx.security.crypto)
  implementation(libs.androidx.workmanager)

  implementation(libs.retrofit)
  implementation(libs.retrofit.kotlinx.serialization)
  implementation(libs.okhttp)
  implementation(libs.okhttp.logging)
  implementation(libs.kotlinx.serialization.json)
  implementation(libs.kotlinx.coroutines.core)
  implementation(libs.kotlinx.coroutines.android)

  implementation(libs.camerax.core)
  implementation(libs.camerax.camera2)
  implementation(libs.camerax.lifecycle)
  implementation(libs.camerax.view)
  implementation(libs.mlkit.barcode.scanning)
  implementation(libs.zxing.core)

  implementation(libs.bitcoinj.core)

  testImplementation(libs.junit)
  testImplementation(libs.kotlinx.coroutines.test)
  testImplementation(libs.mockwebserver)
  testRuntimeOnly(libs.slf4j.simple)
}
