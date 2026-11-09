import java.util.Properties

plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.compose.compiler)
}

val voicexVersionName = "1.2.0"
val voicexVersionCode = 4

// Optional accelerator SDKs, see scripts/fetch-deps.sh. Each backend is switched on when its files exist.
val thirdParty = rootProject.file("third_party")
val hasOpenclLoader = File(thirdParty, "opencl-android/lib/libOpenCL.so").exists()
val hasHexagonSdk = File(thirdParty, "hexagon/6.7.0.0").isDirectory
val wantHexagon = (findProperty("voicex.hexagon") as String?)?.toBoolean() ?: true
val wantOpencl = (findProperty("voicex.opencl") as String?)?.toBoolean() ?: true
val wantVulkan = (findProperty("voicex.vulkan") as String?)?.toBoolean() ?: true

// Release signing: keystore.properties (git-ignored) next to this project, see BUILD_NOTES.md
val keystoreProps = Properties().apply {
  val f = rootProject.file("keystore.properties")
  if (f.exists()) f.inputStream().use { load(it) }
}

android {
  namespace = "com.voicex.app"
  compileSdk = 37
  ndkVersion = "30.0.16248370"

  defaultConfig {
    applicationId = "com.voicex.app"
    minSdk = 31
    targetSdk = 37
    versionCode = voicexVersionCode
    versionName = voicexVersionName

    ndk { abiFilters += "arm64-v8a" }
    externalNativeBuild {
      cmake {
        arguments += "-DCMAKE_BUILD_TYPE=Release"
        // shared STL: C++ exceptions (e.g. a GPU driver error) must unwind across libggml-*.so
        arguments += "-DANDROID_STL=c++_shared"
        arguments += "-DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON"
        arguments += "-DVOICEX_VULKAN=" + (if (wantVulkan) "ON" else "OFF")
        arguments += "-DVOICEX_OPENCL=" + (if (wantOpencl && hasOpenclLoader) "ON" else "OFF")
        arguments += "-DVOICEX_HEXAGON=" + (if (wantHexagon && hasHexagonSdk) "ON" else "OFF")
      }
    }
  }

  externalNativeBuild {
    cmake {
      path("src/main/cpp/CMakeLists.txt")
      version = "4.1.2"
    }
  }

  signingConfigs {
    if (keystoreProps.getProperty("storeFile") != null) {
      create("release") {
        storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
        storePassword = keystoreProps.getProperty("storePassword")
        keyAlias = keystoreProps.getProperty("keyAlias")
        keyPassword = keystoreProps.getProperty("keyPassword")
      }
    }
  }

  buildTypes {
    release {
      isMinifyEnabled = true
      isShrinkResources = true
      proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
      signingConfig = signingConfigs.findByName("release")
    }
  }

  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
  }

  buildFeatures {
    compose = true
    buildConfig = true
  }

  testOptions { unitTests.isReturnDefaultValues = true }

  packaging {
    resources { excludes += "/META-INF/{AL2.0,LGPL2.1}" }
    jniLibs {
      // transcribe_init_backends() scans nativeLibraryDir for the ggml backend modules: extract them
      useLegacyPackaging = true
      // link-time ICD loader only; the phone's own vendor libOpenCL.so is used at run time
      excludes += "**/libOpenCL.so"
      // ggml drops tinyBLAS from SVE / i8mm CPU builds; ship the tinyBLAS ones (armv8.0, 8.2+dotprod, 8.2+fp16)
      excludes += "**/libggml-cpu-android_armv8.6_1.so"
      excludes += "**/libggml-cpu-android_armv9.0_1.so"
      excludes += "**/libggml-cpu-android_armv9.2_1.so"
      excludes += "**/libggml-cpu-android_armv9.2_2.so"
      // Hexagon DSP code: the NDK's strip doesn't understand it
      keepDebugSymbols += "**/libggml-htp-*.so"
    }
  }
}

kotlin { jvmToolchain(17) }

dependencies {
  implementation(platform(libs.androidx.compose.bom))
  implementation(libs.androidx.core.ktx)
  implementation(libs.androidx.core.splashscreen)
  implementation(libs.androidx.activity.compose)
  implementation(libs.androidx.lifecycle.runtime.ktx)
  implementation(libs.androidx.lifecycle.runtime.compose)
  implementation(libs.androidx.lifecycle.viewmodel.compose)
  implementation(libs.androidx.compose.ui)
  implementation(libs.androidx.compose.ui.graphics)
  implementation(libs.androidx.compose.ui.tooling.preview)
  implementation(libs.androidx.compose.material3)
  implementation(libs.androidx.compose.material.icons.extended)
  implementation(libs.kotlinx.coroutines.android)
  debugImplementation(libs.androidx.compose.ui.tooling)

  testImplementation(libs.junit)
  // real org.json for JVM tests (the android.jar copy is a stub there)
  testImplementation("org.json:json:20240303")
  testImplementation(libs.kotlinx.coroutines.test)
}

// Host JNI smoke test: point it at a host build of libvoicex_jni.so + the model (see README, "Tests")
tasks.withType<Test>().configureEach {
  listOf("voicex.hostLib", "voicex.model", "voicex.urModel", "voicex.clip").forEach { k ->
    System.getProperty(k)?.let { systemProperty(k, it) }
  }
}

// VoiceX-<version>-release.apk next to the project (dist/)
val releaseApkName = "VoiceX-$voicexVersionName-release.apk"
val copyReleaseApk = tasks.register<Copy>("copyReleaseApk") {
  from(layout.buildDirectory.dir("outputs/apk/release")) { include("*.apk") }
  into(rootProject.layout.projectDirectory.dir("dist"))
  rename(".*\\.apk", releaseApkName) // plain strings only: configuration-cache safe
}
tasks.matching { it.name == "assembleRelease" }.configureEach { finalizedBy(copyReleaseApk) }
