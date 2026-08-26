// Generate an Enigma Virtual Box project that packs the jpackage app-image
// into a single portable EXE (JVM DLLs mapped in-memory, no temp-file race).
const generateEvb = require('generate-evb');

const appDir = 'dist/MsgAT-Portable';
const inputExe = appDir + '/MsgAT-Portable.exe';
const outputExe = 'dist/MsgAT-Portable.exe';

generateEvb('dist/MsgAT.evb', inputExe, outputExe, appDir, {
  filter: function (fullPath, name, isDir) {
    // Do not pack the launcher exe itself; it becomes the host stub.
    if (!isDir && name.toLowerCase() === 'msgat-portable.exe') return false;
    return true;
  },
  evbOptions: {
    deleteExtractedOnExit: true,
    compressFiles: true,
    shareVirtualSystem: false,
    // JVM loads many DLLs concurrently at startup; the temp-file mapping
    // races and corrupts jli.dll -> "InitCommonControlsEx entry point" error.
    mapExecutableWithTemporaryFile: false,
    allowRunningOfVirtualExeFiles: true
  }
});

console.log('EVB project generated: dist/MsgAT.evb');
