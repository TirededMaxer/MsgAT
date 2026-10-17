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
        versionCode = 11
        versionName = "1.6"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // GitHub-hosted release jobs restore the existing keystore into RUNNER_TEMP.
    val cloudSigning = System.getenv("ANDROID_KEYSTORE_PATH")?.takeIf { it.isNotBlank() }?.let { path ->
        signingConfigs.create("cloudRelease") {
            storeFile = file(path)
            storePassword = requireNotNull(System.getenv("ANDROID_STORE_PASSWORD"))
            keyAlias = requireNotNull(System.getenv("ANDROID_KEY_ALIAS"))
            keyPassword = requireNotNull(System.getenv("ANDROID_KEY_PASSWORD"))
        }
    }

    buildTypes {
        release {
            signingConfig = cloudSigning
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
        disable.add("StateFlowValueCalledInComposition")
        checkReleaseBuilds = true
        abortOnError = true
    }
    packaging {
        resources {
            // Exclude duplicate metadata contributed by shaded dependencies.
            excludes += "META-INF/INDEX.LIST"

            // Netty dependency metadata also conflicts in the final package.
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

    // Networking
    implementation("io.netty:netty-all:4.1.107.Final")
    // Cryptography (Ed25519, X25519, and AES-GCM primitives)
    implementation("org.bouncycastle:bcprov-jdk18on:1.77")
    // JSON serialization
    implementation("com.google.code.gson:gson:2.10.1")
    // Android secure storage
    implementation("androidx.compose.material:material-icons-extended:1.5.0")
    implementation("androidx.security:security-crypto:1.1.0")
    // Room database
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    implementation("androidx.room:room-paging:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")
    // SQLCipher database encryption engine with current Android page-size support.
    // 4.17 is the newest release compatible with this project's compileSdk 36 toolchain.
    implementation("net.zetetic:sqlcipher-android:4.17.0@aar")
    implementation("androidx.sqlite:sqlite:2.6.2")
    // Process lifecycle monitoring
    implementation("androidx.lifecycle:lifecycle-process:2.7.0")
    // Image loading
    implementation("io.coil-kt:coil-compose:2.6.0")
    // Image cropping
    implementation("com.github.yalantis:ucrop:2.2.8")
    // WebRTC Engine for Voice Calls
    implementation("io.github.webrtc-sdk:android:144.7559.09")
    // Paging 3
    implementation("androidx.paging:paging-runtime-ktx:3.3.0")
    implementation("androidx.paging:paging-compose:3.3.0")

    // ExoPlayer for Video Playback
    implementation("androidx.media3:media3-exoplayer:1.3.1")
    implementation("androidx.media3:media3-ui:1.3.1")
}
