# MsgAT for Windows

The Windows client is a native .NET 9 WPF application. Shared protocol, cryptography, storage, relay, and routing code lives in `MsgAT.Windows.Core`; Windows UI and WebView2-hosted WebRTC audio live in `MsgAT.Windows`.

## Requirements

- .NET 9 SDK for building
- Windows 10 version 1809 or later, x64
- Microsoft Edge WebView2 Evergreen Runtime

## Verify and publish

```powershell
dotnet test MsgAT.Windows.slnx -c Release
dotnet publish src\MsgAT.Windows\MsgAT.Windows.csproj -c Release -r win-x64 --self-contained true -p:PublishSingleFile=true
```

The publish directory contains one self-contained `MsgAT.exe`. Native SQLCipher and WebView2 loader files are embedded and extracted by the .NET single-file host when required.

## Local data and migration

Data is stored in `%LOCALAPPDATA%\MsgAT`. Identity and database keys are protected with per-user DPAPI. The first launch preserves and upgrades the DPAPI vault written by the previous Kotlin/JNA client, then atomically migrates an existing plaintext `msgat_vault.db` into SQLCipher. If an existing identity vault cannot be decrypted, startup fails instead of silently replacing the identity.

Relay connections accept untrusted self-signed certificates by default. System certificate validation and SHA-256 pinning remain available in settings. See the root SECURITY.md for the trust boundaries.
