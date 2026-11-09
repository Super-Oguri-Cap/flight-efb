plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    // 编译产物目录默认留在工程内（app/build）。
    // 工程路径含中文的机器上，测试进程会读不到 classpath，
    // 那时用 -Pefb.buildDir=E:/Android/efb-build/app 指到纯 ASCII 路径。
    providers.gradleProperty("efb.buildDir").orNull?.let { layout.buildDirectory.set(file(it)) }

    namespace = "com.oguricap.efb"
    compileSdk = 36
    buildToolsVersion = "36.0.0"

    defaultConfig {
        applicationId = "com.oguricap.efb"
        minSdk = 34
        targetSdk = 36
        // 1.3.13：网页工具升级——航路生成器 v8.6→v9.2（走廊只在有 CAAC 官方航路时
        //         作候选、cycle.json 按 AIRAC 元数据显示不标红、旧缓存标签洗数）；
        //         油量计算器 V7→V9（页面自带跨平台自定义下拉与 ZFW 直接展示，
        //         App 皮肤自动让位；data-URI 命名空间转义以符合单文件自包含契约）
        versionCode = 20
        versionName = "1.3.13"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        create("release") {
            storeFile = file("../keystore/efb-release.jks")
            storePassword = "efb-release-2026"
            keyAlias = "efb"
            keyPassword = "efb-release-2026"
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("release")
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.material3)
    debugImplementation(libs.androidx.compose.ui.tooling)

    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}