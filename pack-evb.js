// Generate an Enigma Virtual Box project that packs the jpackage app-image
// into a single portable EXE.
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
    // KEY: no compression -> DLLs are mapped directly from raw bytes
    // (avoids the jli.dll import-table corruption on real desktops)
    compressFiles: false,
    shareVirtualSystem: false,
    mapExecutableWithTemporaryFile: false,
    allowRunningOfVirtualExeFiles: true
  }
});

console.log('EVB project generated: dist/MsgAT.evb');
