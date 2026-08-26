import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    kotlin("jvm") version "2.0.21"
    id("org.jetbrains.compose") version "1.7.0"
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.21"
    application
    id("edu.sc.seis.launch4j") version "3.0.5"
}

group = "org.msgat"
version = "1.0.0"

repositories {
    mavenCentral()
    maven("https://maven.pkg.jetbrains.space/public/p/compose/dev")
    google()
}

dependencies {
    // Compose Desktop (当前平台 + Windows x64 平台运行时)
    implementation(compose.desktop.currentOs)
    implementation("org.jetbrains.compose.desktop:desktop-jvm-windows-x64:1.7.0")
    implementation(compose.material3)
    implementation(compose.materialIconsExtended)
    implementation(compose.components.resources)
    
    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.8.1")

    // Netty 4.1 网络通讯底层 (与 Android 100% 一致)
    implementation("io.netty:netty-all:4.1.107.Final")

    // BouncyCastle 密码学库 (提供 Ed25519, X25519, Blake2b, HKDF, AES-GCM)
    implementation("org.bouncycastle:bcprov-jdk18on:1.77")

    // Gson 用于 JSON 序列化
    implementation("com.google.code.gson:gson:2.10.1")

    // SQLite JDBC 驱动 (自带全平台 Windows/macOS/Linux 原生动态链接库)
    implementation("org.xerial:sqlite-jdbc:3.45.3.0")
    
    // JNA 用于 Windows 原生交互 (DPAPI 等)
    implementation("net.java.dev.jna:jna:5.14.0")
    implementation("net.java.dev.jna:jna-platform:5.14.0")

    // WebRTC 引擎 (webrtc-java / libwebrtc) — 真实语音通话
    implementation("dev.onvoid.webrtc:webrtc-java:0.16.0")
    // Windows x64 原生库 (webrtc-java 运行时自动加载)
    runtimeOnly("dev.onvoid.webrtc:webrtc-java:0.16.0:windows-x86_64")
}

kotlin {
    jvmToolchain(21)
}

application {
    mainClass.set("org.msgat.MainKt")
    applicationDefaultJvmArgs = listOf(
        "-Dfile.encoding=UTF-8",
        "-Dskiko.renderApi=DIRECT3D",
        "-XX:+UseG1GC",
        "-XX:+UseStringDeduplication",
        "-Xmx2g"
    )
}

// 将标准 jar 任务扩展为 Fat JAR，并严格剔除第三方签名文件
tasks.named<Jar>("jar") {
    archiveBaseName.set("MsgAT-Windows")
    archiveVersion.set("1.0.0")
    
    manifest {
        attributes["Main-Class"] = "org.msgat.MainKt"
    }
    
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    
    // 过滤掉所有数字签名文件，防止 SecurityException
    exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA", "META-INF/INDEX.LIST")
    
    from(sourceSets.main.get().output)
    dependsOn(configurations.runtimeClasspath)
    from({
        configurations.runtimeClasspath.get().filter { it.name.endsWith(".jar") }.map { zipTree(it) }
    })
}

// 配置 Launch4j
launch4j {
    mainClassName.set("org.msgat.MainKt")
    headerType.set("gui")
    outfile.set("MsgAT.exe")
    outputDir.set("libs")
    icon.set("${projectDir}/src/main/resources/icons/icon.ico")
    
    bundledJrePath.set("jre")
    errTitle.set("MsgAT Error")
    
    jvmOptions.set(setOf(
        "-Dfile.encoding=UTF-8",
        "-Dskiko.renderApi=DIRECT3D",
        "-XX:+UseG1GC",
        "-XX:+UseStringDeduplication",
        "-Xmx2g"
    ))
    jreMinVersion.set("17")
    
    version.set("1.0.0")
    textVersion.set("1.0.0")
    copyright.set("© 2026 MsgAT Team. All rights reserved.")
    companyName.set("MsgAT")
    fileDescription.set("MsgAT - Secure P2P Encrypted Messenger")
    productName.set("MsgAT")
}
