plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

// 安卓 9 版走独立条件构建：默认构建仍是现代版，只有显式传 -Pandroid9=true 时才切换。
// 两个版本使用不同 applicationId，可以同时安装在同一台设备上。
val android9Build = providers.gradleProperty("android9").orNull == "true"

android {
    namespace = "com.glassplayer"
    compileSdk = 37
    buildToolsVersion = "37.0.0"

    defaultConfig {
        applicationId = if (android9Build) "com.glassplayer.android9" else "com.glassplayer"
        minSdk = if (android9Build) 28 else 33
        targetSdk = 37
        versionCode = 131
        versionName = if (android9Build) "1.4.55-android9" else "1.4.55"
        manifestPlaceholders["appName"] = "GlassPlayer"
    }

    sourceSets {
        getByName("main") {
            // 安卓 9 版不链接 Glasmio（它的 AAR 最低 API 33，且依赖 RenderEffect/RuntimeShader），
            // 改由这里的同包名兼容实现提供主题、玻璃卡片和基础控件。
            if (android9Build) {
                java.srcDir("src/compat/java")
                kotlin.srcDir("src/compat/java")
            }
        }
    }

    buildTypes {
        release {
            // 便于直接安装体验：release 用 debug 签名 + R8 压缩，体积远小于 debug 包
            signingConfig = signingConfigs.getByName("debug")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2026.09.00"))

    implementation("androidx.core:core-ktx:1.19.1")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")

    if (android9Build) {
        // 兼容层用 Material 核心图标补齐常见图标，避免 Android 9 上 SF Symbols 字体缺失
        implementation("androidx.compose.material:material-icons-core")
    } else {
        implementation("io.github.neoruaa.glasmio:glasmio-core:0.1.2")
        implementation("io.github.neoruaa.glasmio:glasmio-glass:0.1.2")
        implementation("io.github.neoruaa.glasmio:glasmio-icons:0.1.2")
        implementation("io.github.neoruaa.glasmio:glasmio-ui:0.1.2")
        implementation("io.github.neoruaa.glasmio:glasmio-scaffold:0.1.2")
    }

    implementation("androidx.media3:media3-exoplayer:1.11.1")
    // HLS 模块本地 AAR：用于网盘「流播」的 m3u8 转码地址
    implementation(files("libs/media3-exoplayer-hls-1.11.1.aar"))
    implementation("androidx.media3:media3-ui:1.11.1")
    implementation("androidx.media3:media3-session:1.11.1")
}
