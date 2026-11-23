plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

// ---------------------------------------------------------------- 构建目录
//
// 编译产物默认留在工程内（app/build）。工程路径含中文的机器上，测试进程读不到
// classpath，那时用 -Pefb.buildDir=E:/Android/efb-build/app 指到纯 ASCII 路径。
//
// 参数拼错时不能静默退回默认目录：产物会写回含中文的路径，之后测试进程报
// "ClassNotFound / 找不到主类"，而真正的原因（路径编码）离报错点很远。
// 所以在配置阶段就把这种情况拦下来。
val efbBuildDir: String? = providers.gradleProperty("efb.buildDir").orNull
val projectPathRaw: String = rootProject.projectDir.absolutePath

// 现场判断，不把路径写死：换机器、换克隆位置都自动适用。
val projectPathIsAscii: Boolean = projectPathRaw.all { it.code < 128 }

if (efbBuildDir == null && !projectPathIsAscii) {
    throw GradleException(
        """
        |工程路径含非 ASCII 字符，必须显式指定构建目录。
        |
        |当前工程路径：$projectPathRaw
        |
        |为什么不行：这个路径里有非 ASCII 字符（例如中文目录名）。
        |Gradle 会把构建产物与依赖 classpath 写在这个目录下；测试在独立 JVM 里按命令行
        |参数读取 classpath 时，非 ASCII 路径会被系统编码转换破坏，表现为
        |「找不到主类」「ClassNotFoundException」这类与真实原因无关的报错。
        |
        |怎么改：给构建命令加一个纯 ASCII 的产物目录参数，例如
        |    .\gradlew.bat testDebugUnitTest "-Pefb.buildDir=E:/Android/efb-build/app"
        |    .\gradlew.bat assembleRelease "-Pefb.buildDir=E:/Android/efb-build/app"
        |
        |注意参数要整体加引号：PowerShell 不带引号时会把它拆成两段，
        |Gradle 会报 "Cannot locate tasks that match '.buildDir=...'"。
        |
        |如果确实要把产物留在工程内：把工程移到纯 ASCII 路径下（例如 E:/EFB），
        |那时本检查自动放行，不需要这个参数。
        """.trimMargin(),
    )
}

efbBuildDir?.let { layout.buildDirectory.set(file(it)) }

android {
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