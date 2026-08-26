; MsgAT portable single EXE (NSIS)
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
  ; dump resolved paths for diagnostics (written to $EXEDIR which is the workspace)
  FileOpen $9 "$EXEDIR\nsis-paths.txt" w
  FileWrite $9 "PLUGINSDIR=$PLUGINSDIR$\r$\nEXEDIR=$EXEDIR$\r$\nINSTDIR=$INSTDIR$\r$\nTEMP=$TEMP$\r$\n"
  FileClose $9
  ExecWait '"$PLUGINSDIR\MsgAT-Portable.exe"'
SectionEnd
