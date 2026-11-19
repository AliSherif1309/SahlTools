plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}
android {
    namespace = "com.sahl.tools"
    compileSdk = 34
    defaultConfig {
        applicationId = "com.sahl.tools"
        minSdk = 26
        targetSdk = 34
        // GitHub Actions run number becomes the version, so every release is newer
        val run = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()
        versionCode = run
        versionName = "1.$run"
    }
    // Release key comes from GitHub secrets (never stored in the repo)
    val ks = System.getenv("KEYSTORE_FILE")
    signingConfigs {
        if (ks != null) create("sahl") {
            storeFile = file(ks)
            storePassword = System.getenv("KEYSTORE_PASS")
            keyAlias = "sahl"
            keyPassword = System.getenv("KEYSTORE_PASS")
        }
    }
    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
            signingConfig = if (ks != null) signingConfigs.getByName("sahl") else signingConfigs.getByName("debug")
        }
    }
    buildFeatures { buildConfig = true }
    lint { checkReleaseBuilds = false; abortOnError = false }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
}
dependencies {
    implementation("com.journeyapps:zxing-android-embedded:4.3.0")
}
