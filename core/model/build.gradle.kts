plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.eloykim.lyra.model"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        minSdk = 29
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
