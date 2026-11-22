# SPDX-License-Identifier: MIT
# Invoked after the managed publish on the Windows hosted runner.
param(
    [ValidateSet('development', 'release')]
    [string] $Flavor = 'development'
)

$ErrorActionPreference = 'Stop'
$PSNativeCommandUseErrorActionPreference = $true

$qtVersion = '6.10.3'
$qtRoot = $env:QT_ROOT_DIR
$build = Join-Path $env:RUNNER_TEMP 'msgat-windows-qt-native'
$staging = Join-Path $env:RUNNER_TEMP 'msgat-windows-qt-package'
$runtime = Join-Path $staging 'qt'
$exe = Join-Path $env:RUNNER_TEMP "msgat-windows-$Flavor/MsgAT.exe"
$archive = Join-Path $env:RUNNER_TEMP "MsgAT-Windows-Qt-$Flavor.zip"

cmake -S MsgAT_Windows/Qt -B $build -G Ninja -DCMAKE_BUILD_TYPE=Release "-DCMAKE_PREFIX_PATH=$qtRoot"
cmake --build $build
New-Item -ItemType Directory -Path $runtime | Out-Null
Copy-Item $exe $staging
$bridge = Join-Path $build 'msgat_desktop_ui.dll'
Copy-Item $bridge $runtime
& (Join-Path $qtRoot 'bin/windeployqt.exe') --dir $runtime --release --no-patchqt --no-translations --no-opengl-sw --no-system-d3d-compiler --no-system-dxc-compiler $bridge

foreach ($asset in 'msgat_desktop_ui.dll', 'Qt6Core.dll', 'Qt6Gui.dll', 'Qt6Widgets.dll', 'platforms/qwindows.dll') {
    if (!(Test-Path (Join-Path $runtime $asset) -PathType Leaf)) {
        throw "Qt deployment is missing $asset"
    }
}

$sourceName = "qtbase-everywhere-src-$qtVersion.tar.xz"
$source = Join-Path $staging $sourceName
$sourceUrl = "https://download.qt.io/official_releases/qt/6.10/$qtVersion/submodules/$sourceName"
curl.exe --fail --location --retry 3 --silent --show-error --output $source $sourceUrl
$expected = '383dc907816338f0cba72088a524c07458dfc69ce684ca9132fcc4fe91c24b0b'
if ((Get-FileHash $source -Algorithm SHA256).Hash -ne $expected) {
    throw 'Qt Base source archive hash mismatch'
}
tar.exe -xf $source -C $staging --strip-components 1 "qtbase-everywhere-src-$qtVersion/LICENSES"
Copy-Item LICENSE, LICENSE-AGPL-3.0, THIRD_PARTY_NOTICES.md $staging
@'
[Paths]
Prefix = qt
Plugins = .
'@ | Set-Content -Encoding Ascii (Join-Path $staging 'qt.conf')
if ($Flavor -eq 'development') {
    @'
@echo off
"%~dp0MsgAT.exe" --qt-workspace
'@ | Set-Content -Encoding Ascii (Join-Path $staging 'Open MsgAT Qt.cmd')
}

Compress-Archive -Path (Join-Path $staging '*') -DestinationPath $archive -CompressionLevel Fastest
