; MsgAT portable single EXE (NSIS) - runs the app directly via bundled javaw.exe
Unicode true
RequestExecutionLevel user
SilentInstall silent
ShowInstDetails nevershow

Name "MsgAT"
OutFile "dist\MsgAT-Portable.exe"
Icon "MsgAT_Windows\src\main\resources\icons\icon.ico"

VIProductVersion "1.1.0.0"
VIAddVersionKey /LANG=1033 "ProductName" "MsgAT"
VIAddVersionKey /LANG=1033 "FileDescription" "MsgAT - Secure P2P Encrypted Messenger"
VIAddVersionKey /LANG=1033 "CompanyName" "MsgAT Team"
VIAddVersionKey /LANG=1033 "FileVersion" "1.1.0.0"
VIAddVersionKey /LANG=1033 "ProductVersion" "1.1.0.0"
VIAddVersionKey /LANG=1033 "LegalCopyright" "(c) 2026 MsgAT Team"

Section "Portable"
  SetOutPath "$PLUGINSDIR"
  File /r "dist\MsgAT-Portable\*"
  ; Run the app directly (no jpackage launcher, no cfg path magic)
  ExecWait '"$PLUGINSDIR\runtime\bin\javaw.exe" -Dfile.encoding=UTF-8 -Dskiko.renderApi=DIRECT3D -XX:+UseG1GC -XX:+UseStringDeduplication -Xmx2g -jar "$PLUGINSDIR\app\MsgAT-Windows-1.0.0.jar"'
SectionEnd
