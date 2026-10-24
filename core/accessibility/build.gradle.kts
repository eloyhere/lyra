plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.eloykim.lyra.accessibility"
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

    buildFeatures {
        aidl = true
    }
}

dependencies {
    api(project(":core:model"))
    implementation(project(":core:policy"))
    implementation(project(":core:amp"))
    implementation(project(":core:agent"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.android)
}
