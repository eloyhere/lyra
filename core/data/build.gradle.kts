plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.eloykim.lyra.data"
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

dependencies {
    implementation(project(":core:agent"))
    implementation(project(":core:amp"))
    implementation(project(":core:attention"))
    implementation(libs.kotlinx.coroutines.android)
    testImplementation(libs.junit)
}
