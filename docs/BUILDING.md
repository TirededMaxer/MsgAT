# 构建指南

以下命令均从仓库根目录开始，使用各组件固定的依赖声明。签名凭据由维护者在本地提供，不进入 Git。

## Android 1.5

需要 Java 21、Android SDK Platform 36，以及支持项目 Gradle/Android 插件版本的 IntelliJ IDEA 或 Android Studio。

```bash
cd MsgAT
./gradlew assembleDebug
```

正式 APK：在 IDE 打开 `MsgAT/`，执行 **Build → Generate Signed App Bundle or APK → APK**，选择自己的 keystore 和 `release`，生成 `app-release.apk`。项目发布密钥不随源码提供，其他签名的 APK 不能直接覆盖已安装的正式版本。

可选验证：`./gradlew testDebugUnitTest lintDebug`。

## Windows 1.0

需要 .NET 9 SDK。开发和运行使用 Windows；可在其他系统交叉构建。

```bash
cd MsgAT_Windows
dotnet restore MsgAT.Windows.slnx
dotnet publish src/MsgAT.Windows/MsgAT.Windows.csproj -c Release -r win-x64 --self-contained true -p:PublishSingleFile=true
```

产物位于 `src/MsgAT.Windows/bin/Release/net9.0-windows10.0.17763.0/win-x64/publish/MsgAT.exe`。运行时需要 WebView2 Evergreen Runtime。

可选验证：`dotnet test MsgAT.Windows.slnx -c Release`。

## macOS 1.0

需要 macOS、Xcode 和网络访问以解析 Swift Package 依赖。

```bash
cd MsgAT_macOS
xcodebuild -project MsgAT.xcodeproj -scheme MsgAT -configuration Release \
  -destination 'platform=macOS' -derivedDataPath build/XcodeDerivedData \
  CODE_SIGNING_ALLOWED=NO build
codesign --force --deep --sign - build/XcodeDerivedData/Build/Products/Release/MsgAT.app
```

该命令使用本机临时签名，不提供 Apple 公证。对外发布时可使用维护者自己的 Developer ID 和公证流程。本次发布产物为 Apple Silicon arm64。

可选验证：`swift test`。

## 服务端 1.1

需要 Java 21 和 Maven。

```bash
cd MsgAT_Console
mvn package
java -jar target/MsgAT_Console-1.1-obfuscated.jar
```

发布附件将该混淆 JAR 重命名为 `MsgAT-Console-1.1.jar`。首次构建需下载依赖；已有完整本地依赖缓存时可添加 `-o` 离线构建。构建会保留 JVM 所需的 StackMapTable。

## 缓存与发布

保留 `.gradle/`、`.kotlin/`、`.build/`、SwiftPM/Xcode 缓存、Maven/NuGet 缓存及当前增量构建目录可减少重新构建耗时。这些目录由 `.gitignore` 排除。临时日志、诊断转储、Python 缓存和旧发布副本可清理，勿删除真实应用数据或签名密钥。

本仓库四个组件独立版本化。本次集成发布使用 `release-2026-09-15` 标签，对应 Android 1.5、Windows/macOS 1.0、服务端 1.1。Release 附件应与该标签的源码构建一致，并提供 SHA-256 校验清单。
