#!/usr/bin/env python3
"""Build the pinned native security core for the host or an explicit Rust target."""

import argparse
import os
from pathlib import Path
import shutil
import subprocess
import sys


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--release', action='store_true')
    parser.add_argument('--target', help='Installed Rust target triple; cross-linker configuration must be supplied by the environment')
    parser.add_argument('--protoc', type=Path, help='Protobuf compiler path; otherwise use PATH or the reconstruction tool directory')
    parser.add_argument('--ndk', type=Path, help='Android NDK directory for an Android target')
    parser.add_argument('--android-jni', action='store_true', help='Link and stage the Android JNI library after the Rust build')
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[2]
    core = root / 'shared/security-core'
    executable = 'cargo.exe' if os.name == 'nt' else 'cargo'
    cargo = shutil.which(executable) or str(Path.home() / '.cargo/bin' / executable)
    if not Path(cargo).is_file():
        parser.error('Rust is required; install the toolchain specified in shared/security-core/rust-toolchain.toml')
    protoc = args.protoc or os.environ.get('PROTOC') or shutil.which('protoc')
    if not protoc:
        local = root / '.build-tools/protoc-3.25.5/bin/protoc'
        if local.is_file():
            protoc = local
    if not protoc or not Path(protoc).is_file():
        parser.error('Protobuf compiler is required; provide --protoc or put protoc on PATH')
    if args.target and any(c not in 'abcdefghijklmnopqrstuvwxyz0123456789_-' for c in args.target):
        parser.error('Invalid Rust target triple')
    environment = dict(os.environ)
    environment['PROTOC'] = str(Path(protoc).resolve())
    android_targets = {
        'aarch64-linux-android': ('arm64-v8a', 'aarch64-linux-android'),
        'armv7-linux-androideabi': ('armeabi-v7a', 'armv7a-linux-androideabi'),
    }
    android = android_targets.get(args.target)
    if args.android_jni and not android:
        parser.error('--android-jni requires a supported Android --target')
    if android:
        ndk = args.ndk or os.environ.get('ANDROID_NDK_HOME') or os.environ.get('ANDROID_NDK_ROOT')
        if not ndk:
            parser.error('Provide --ndk or ANDROID_NDK_HOME for Android builds')
        hosts = {'darwin': 'darwin-x86_64', 'linux': 'linux-x86_64', 'win32': 'windows-x86_64'}
        host = hosts.get(sys.platform)
        if not host:
            parser.error('Unsupported Android build host')
        binary = Path(ndk).resolve() / 'toolchains/llvm/prebuilt' / host / 'bin'
        clang = binary / (android[1] + '29-clang' + ('.cmd' if os.name == 'nt' else ''))
        archive = binary / ('llvm-ar.exe' if os.name == 'nt' else 'llvm-ar')
        if not clang.is_file() or not archive.is_file():
            parser.error('Android API 29 Clang and llvm-ar are missing from the selected NDK')
        target_env = args.target.replace('-', '_')
        environment['CC_' + target_env] = str(clang)
        environment['AR_' + target_env] = str(archive)
        environment['CARGO_TARGET_' + target_env.upper() + '_LINKER'] = str(clang)
        environment['CARGO_TARGET_' + target_env.upper() + '_RUSTFLAGS'] = '-C link-arg=-Wl,-z,max-page-size=16384'
    command = [cargo, 'build', '--locked', '--manifest-path', str(core / 'Cargo.toml')]
    if args.release:
        command.append('--release')
    if args.target:
        command.extend(['--target', args.target])
    result = subprocess.call(command, cwd=core, env=environment)
    if result != 0:
        return result
    if args.android_jni:
        output = core / 'target' / args.target / ('release' if args.release else 'debug') / 'libmsgat_security_core.a'
        destination = root / 'MsgAT/app/src/main/jniLibs' / android[0]
        destination.mkdir(parents=True, exist_ok=True)
        command = [str(clang), '-std=c11', '-shared', '-fPIC', '-fvisibility=hidden', '-O2',
                   '-Werror', '-Wall', '-Wextra', '-Wl,-z,max-page-size=16384', '-Wl,--exclude-libs,ALL', '-Wl,--strip-debug',
                   '-I' + str(core / 'include'), str(root / 'MsgAT/app/src/main/cpp/security_core_jni.c'),
                   str(output), '-ldl', '-llog', '-lm', '-o', str(destination / 'libmsgat_security_jni.so')]
        result = subprocess.call(command, cwd=root, env=environment)
        if result != 0:
            return result
    if sys.platform == 'darwin' and (args.target is None or args.target.endswith('apple-darwin')):
        output = core / 'target'
        if args.target:
            output /= args.target
        output /= 'release' if args.release else 'debug'
        destination = root / 'MsgAT_macOS/NativeSecurity/lib' / ('Release' if args.release else 'Debug')
        destination.mkdir(parents=True, exist_ok=True)
        shutil.copy2(output / 'libmsgat_security_core.a', destination / 'libmsgat_security_core.a')
    if os.name == 'nt' and (args.target is None or args.target == 'x86_64-pc-windows-msvc'):
        output = core / 'target'
        if args.target:
            output /= args.target
        output /= 'release' if args.release else 'debug'
        destination = root / 'MsgAT_Windows/NativeSecurity/win-x64'
        destination.mkdir(parents=True, exist_ok=True)
        shutil.copy2(output / 'msgat_security_core.dll', destination / 'msgat_security_core.dll')
    return 0


if __name__ == '__main__':
    sys.exit(main())
