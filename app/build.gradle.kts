plugins { id("com.android.application") }

android {
    namespace = "mt.sleepsnitch"
    compileSdk = 34

    defaultConfig {
        applicationId = "mt.sleepsnitch"
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release { isMinifyEnabled = false }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
