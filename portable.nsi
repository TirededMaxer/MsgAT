; MsgAT portable single EXE (NSIS) - extract to EXPLICIT fixed temp dir, run launcher
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
  StrCpy $0 "$TEMP\MsgATPortable"
  ; paths dump into $TEMP (readable by the CI step afterwards)
  FileOpen $9 "$TEMP\nsis-paths.txt" w
  FileWrite $9 "TEMP=$TEMP$\r$\nAPPDIR=$0$\r$\nEXEDIR=$EXEDIR$\r$\n"
  FileClose $9
  IfFileExists "$0\MsgAT-Portable.exe" skipExtract
  CreateDirectory "$0"
  SetOutPath "$0"
  File /r "dist\MsgAT-Portable\*"
skipExtract:
  ExecWait '"$0\MsgAT-Portable.exe"'
SectionEnd
