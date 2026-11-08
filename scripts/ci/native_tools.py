#!/usr/bin/env python3
"""Install the pinned native build tools on an ephemeral GitHub-hosted runner."""
import hashlib
import os
from pathlib import Path
import platform
import subprocess
import urllib.request


def main():
    if os.environ.get('GITHUB_ACTIONS') != 'true' or not os.environ.get('RUNNER_TEMP'):
        raise RuntimeError('This bootstrap is for GitHub Actions runners only')
    root = Path(__file__).resolve().parents[2]
    # Read the single source of truth without requiring a Python TOML dependency.
    import tomllib
    toolchain = tomllib.loads((root / 'shared/security-core/rust-toolchain.toml').read_text())['toolchain']['channel']
    subprocess.run(['rustup', 'toolchain', 'install', toolchain, '--profile', 'minimal', '--no-self-update'], check=True)
    targets = {
        ('Linux', 'x86_64'): ('linux-x86_64', 'd69b0a9ac8264bb3d464ae22742430943aa0a6cdf3734180af5a607c0bf8adaa'),
        ('Darwin', 'arm64'): ('osx-aarch_64', 'c990aaabeda2fe040ba6f9f93f719abfece30fd20bfb07ad094b14629f2c066b'),
        ('Windows', 'AMD64'): ('windows-x86_64', 'b0a77918138b42de5692c94ad5c099c203cede89b1132acc614ddffc82672da9'),
    }
    classifier, expected = targets[(platform.system(), platform.machine())]
    directory = Path(os.environ['RUNNER_TEMP']) / 'msgat-native-tools'
    directory.mkdir(exist_ok=True)
    executable = directory / ('protoc.exe' if os.name == 'nt' else 'protoc')
    url = f'https://repo.maven.apache.org/maven2/com/google/protobuf/protoc/3.25.5/protoc-3.25.5-{classifier}.exe'
    digest = hashlib.sha256()
    size = 0
    try:
        with urllib.request.urlopen(url, timeout=60) as response, executable.open('wb') as output:
            while chunk := response.read(1024 * 1024):
                size += len(chunk)
                if size > 32 * 1024 * 1024:
                    raise RuntimeError('Protobuf compiler download exceeds the expected limit')
                digest.update(chunk)
                output.write(chunk)
        if digest.hexdigest() != expected:
            raise RuntimeError('Protobuf compiler SHA-256 differs from the pinned source')
        executable.chmod(0o700)
    except BaseException:
        executable.unlink(missing_ok=True)
        raise
    with open(os.environ['GITHUB_ENV'], 'a', encoding='utf-8') as environment:
        environment.write(f'PROTOC={executable}\n')
    print(f'Rust {toolchain} and SHA-256-verified Protobuf 3.25.5 are ready.')


if __name__ == '__main__':
    main()
