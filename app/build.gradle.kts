plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.eloykim.lyra"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.eloykim.lyra"
        minSdk = 29
        targetSdk = 37
        versionCode = 1
        versionName = "0.1.0"

        ndk {
            // sherpa-onnx 自带四套 ABI；只保留手机端 arm，避免 APK 被 x86 撑大
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }
    }

    buildTypes {
        release {
            optimization {
                enable = false
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
        // 合并自 core:accessibility：IAutomation.aidl 在 app/src/main/aidl 下
        aidl = true
    }

    testOptions {
        // 合并自 core:amp-protocol：单测里会碰到 android.jar 的桩实现
        unitTests.isReturnDefaultValues = true
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

dependencies {
    // core:* 已并入本模块，这里不再有 project(":core:...") 依赖
    implementation("com.google.mlkit:text-recognition-chinese:16.0.1")

    // 端侧语音识别：sherpa-onnx + SenseVoice（JitPack AAR）
    implementation("com.github.k2-fsa.sherpa-onnx:sherpa-onnx:v1.13.6")

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.recyclerview)
    implementation(libs.kotlinx.coroutines.android)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.foundation)
    implementation(libs.compose.animation)
    implementation(libs.compose.material3)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)

    // Markdown 引擎已并入 app（com.eloykim.lyra.markdown + com.eloykim.lyra.structure），
    // 单元测试跟着搬过来
    testImplementation(libs.junit)
}
