; MsgAT portable single EXE (NSIS)
; Extracts to $PLUGINSDIR (auto-cleaned after exit), runs the app, waits for it.

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
  ExecWait '"$PLUGINSDIR\MsgAT-Portable.exe"'
SectionEnd
