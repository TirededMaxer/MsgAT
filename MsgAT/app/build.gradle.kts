plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    id("com.google.devtools.ksp") version "2.0.21-1.0.28"
}

android {
    namespace = "org.msgat"
    compileSdk = 36

    defaultConfig {
        applicationId = "org.msgat"
        minSdk = 29
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = "11"
    }
    buildFeatures {
        compose = true
    }
    lint {
        // 禁用导致崩溃的特定检查规则
        disable.add("StateFlowValueCalledInComposition")

        // 【强烈建议加上这两行】：防止以后其他的 Google Lint Bug 再次阻断我们的 Release 打包
        checkReleaseBuilds = false
        abortOnError = false
    }
    packaging {
        resources {
            // 排除冲突的 INDEX.LIST 文件
            excludes += "META-INF/INDEX.LIST"

            // 为了保险起见，建议把 Netty 经常冲突的另一个版本声明文件也排除了
            excludes += "META-INF/io.netty.versions.properties"
            excludes += "META-INF/DEPENDENCIES"
        }
    }
}

dependencies {

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.compose.material3)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)
    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)

    // Netty 通讯底层
    implementation("io.netty:netty-all:4.1.107.Final")
    // BouncyCastle 密码学库 (提供 Ed25519 和 Argon2)
    implementation("org.bouncycastle:bcprov-jdk18on:1.77")
    // Gson 用于 JSON 序列化
    implementation("com.google.code.gson:gson:2.10.1")
    // AndroidX 安全增强存储
    implementation("androidx.compose.material:material-icons-extended:1.5.0")
    implementation("androidx.security:security-crypto:1.1.0-alpha06")
    // Room 数据库
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    implementation("androidx.room:room-paging:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")
    // SQLCipher 数据库加密引擎
    implementation("net.zetetic:android-database-sqlcipher:4.5.4")
    // 生命周期监控 (用于前后台切换)
    implementation("androidx.lifecycle:lifecycle-process:2.7.0")
    // 图片加载
    implementation("io.coil-kt:coil-compose:2.6.0")
    // 图片裁剪
    implementation("com.github.yalantis:ucrop:2.2.8")
    // WebRTC Engine for Voice Calls
    implementation("io.github.webrtc-sdk:android:144.7559.09")
    // Paging 3
    implementation("androidx.paging:paging-runtime-ktx:3.3.0")
    implementation("androidx.paging:paging-compose:3.3.0")
}