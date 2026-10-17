#!/usr/bin/env python3
"""Package GitHub-hosted builds; no local caches or third-party Python packages required."""
import base64
import hashlib
import json
import os
import plistlib
import re
import shutil
import subprocess
import sys
import tempfile
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
DIST = ROOT / 'dist'
SIGNER = '4a4f2fa7a3564906000c4daf577c03c61cc4876e5d29c0c8e723f9f297c42ce0'


def run(*args, capture=False):
    return subprocess.run([str(a) for a in args], cwd=ROOT, check=True,
                          stdout=subprocess.PIPE if capture else None, text=True).stdout


def versions():
    android = (ROOT / 'MsgAT/app/build.gradle.kts').read_text()
    windows = ET.parse(ROOT / 'MsgAT_Windows/src/MsgAT.Windows/MsgAT.Windows.csproj').getroot()
    ns = {'m': 'http://maven.apache.org/POM/4.0.0'}
    relay = ET.parse(ROOT / 'MsgAT_Console/pom.xml').getroot().find('m:version', ns).text
    with (ROOT / 'MsgAT_macOS/Sources/App/Info.plist').open('rb') as stream:
        mac = plistlib.load(stream)
    return {'android': re.search(r'versionName\s*=\s*"([^"]+)"', android)[1],
            'androidVersionCode': int(re.search(r'versionCode\s*=\s*(\d+)', android)[1]),
            'windows': windows.find('.//Version').text,
            'macos': mac['CFBundleShortVersionString'], 'macBuild': mac['CFBundleVersion'],
            'relay': relay}


def filename(component):
    v = versions()
    return {'android': f'MsgAT-Android-{v["android"]}.apk',
            'windows': f'MsgAT-Windows-{v["windows"].removesuffix(".0")}-x64.exe',
            'macos': f'MsgAT-macOS-{v["macos"]}-arm64.dmg',
            'relay': f'MsgAT-Console-{v["relay"]}.jar'}[component]


def copy(source, name):
    DIST.mkdir(exist_ok=True)
    shutil.copy2(source, DIST / name)


def preflight():
    required = ('ANDROID_KEYSTORE_BASE64', 'ANDROID_STORE_PASSWORD', 'ANDROID_KEY_ALIAS', 'ANDROID_KEY_PASSWORD')
    missing = [name for name in required if not os.environ.get(name)]
    if missing:
        raise RuntimeError('Configure GitHub Actions Secrets: ' + ', '.join(missing))
    if os.environ.get('PUBLISH') == 'true':
        tag = os.environ.get('RELEASE_TAG', '')
        if not re.fullmatch(r'release-[A-Za-z0-9][A-Za-z0-9._-]*', tag):
            raise RuntimeError('Publishing requires a release-* tag')
        if os.environ.get('GITHUB_REF') != 'refs/heads/main':
            raise RuntimeError('Publish from the main branch')
    print('Cloud package inputs and signing secrets are configured.')


def restore_signing():
    directory = Path(os.environ['RUNNER_TEMP']) / 'msgat-signing'
    directory.mkdir(mode=0o700, exist_ok=True)
    key = directory / 'release.jks'
    key.write_bytes(base64.b64decode(os.environ['ANDROID_KEYSTORE_BASE64'], validate=True))
    key.chmod(0o600)


def android():
    apk = ROOT / 'MsgAT/app/build/outputs/apk/release/app-release.apk'
    tools = Path(os.environ['ANDROID_HOME']) / 'build-tools/36.1.0'
    output = run(tools / 'apksigner', 'verify', '--verbose', '--print-certs', apk, capture=True)
    if f'Signer #1 certificate SHA-256 digest: {SIGNER}' not in output:
        raise RuntimeError('APK must use the existing MsgAT release certificate')
    badging = run(tools / 'aapt', 'dump', 'badging', apk, capture=True)
    v = versions()
    if f"versionCode='{v['androidVersionCode']}'" not in badging or f"versionName='{v['android']}'" not in badging:
        raise RuntimeError('APK version does not match source')
    print('Original APK signing certificate and source version verified.')
    copy(apk, filename('android'))


def windows():
    copy(ROOT / 'MsgAT_Windows/src/MsgAT.Windows/bin/Release/net9.0-windows10.0.17763.0/win-x64/publish/MsgAT.exe', filename('windows'))


def macos():
    with tempfile.TemporaryDirectory(prefix='msgat-dmg-') as temporary:
        stage = Path(temporary)
        app = stage / 'MsgAT.app'
        run('ditto', Path(os.environ['RUNNER_TEMP']) / 'msgat-derived/Build/Products/Release/MsgAT.app', app)
        resource = app / 'Contents/Resources'
        for name in ('LICENSE', 'THIRD_PARTY_NOTICES.md'):
            shutil.copy2(ROOT / name, resource / name)
        run('lipo', '-verify_arch', 'arm64', app / 'Contents/MacOS/MsgAT')
        run('codesign', '--force', '--deep', '--sign', '-', '--entitlements', ROOT / 'MsgAT_macOS/Sources/App/MsgAT.entitlements', app)
        run('codesign', '--verify', '--deep', '--strict', app)
        (stage / 'Applications').symlink_to('/Applications')
        DIST.mkdir(exist_ok=True)
        dmg = DIST / filename('macos')
        run('hdiutil', 'create', '-volname', 'MsgAT', '-srcfolder', stage, '-format', 'UDZO', '-ov', dmg)
        run('hdiutil', 'verify', dmg)


def relay():
    v = versions()
    copy(ROOT / f'MsgAT_Console/target/MsgAT_Console-{v["relay"]}-obfuscated.jar', filename('relay'))
    copy(ROOT / 'MsgAT_Console/server-config.example.json', 'server-config.json')
    copy(ROOT / 'docs/RELAY_DEPLOYMENT.md', 'SERVER-README.txt')


def digest(path):
    with path.open('rb') as stream:
        value = hashlib.sha256()
        for chunk in iter(lambda: stream.read(1024 * 1024), b''):
            value.update(chunk)
        return value.hexdigest()


def bundle():
    for component in ('android', 'windows', 'macos', 'relay'):
        path = DIST / filename(component)
        if not path.is_file() or path.stat().st_size == 0:
            raise RuntimeError(f'Missing package: {path.name}')
    for name in ('LICENSE', 'THIRD_PARTY_NOTICES.md'):
        copy(ROOT / name, name)
    manifest = {'commit': os.environ['GITHUB_SHA'], 'versions': versions(),
                'buildOrigin': 'GitHub-hosted runners, no restored dependency/build caches',
                'workflow': f"{os.environ['GITHUB_SERVER_URL']}/{os.environ['GITHUB_REPOSITORY']}/actions/runs/{os.environ['GITHUB_RUN_ID']}",
                'androidSignerCertificateSha256': SIGNER, 'macSigning': 'ad-hoc-not-notarized',
                'assets': [{'file': p.name, 'bytes': p.stat().st_size, 'sha256': digest(p)} for p in sorted(DIST.iterdir())]}
    (DIST / 'release-manifest.json').write_text(json.dumps(manifest, indent=2) + '\n')
    paths = sorted(p for p in DIST.iterdir() if p.name != 'SHA256SUMS.txt')
    (DIST / 'SHA256SUMS.txt').write_text(''.join(f'{digest(p)}  {p.name}\n' for p in paths))
    print('All four cloud-built packages assembled with SHA-256 checksums.')


def releases():
    pages = json.loads(run('gh', 'api', '--paginate', '--slurp',
                          f'repos/{os.environ["GITHUB_REPOSITORY"]}/releases?per_page=100', capture=True))
    return [release for page in pages for release in page]


def publish():
    repo, tag, commit = (os.environ[name] for name in ('GITHUB_REPOSITORY', 'RELEASE_TAG', 'GITHUB_SHA'))
    manifest = json.loads((DIST / 'release-manifest.json').read_text())
    if manifest['commit'] != commit:
        raise RuntimeError('Packages must come from the current workflow commit')
    expected = {p.name: {'size': p.stat().st_size, 'digest': 'sha256:' + digest(p)} for p in DIST.iterdir()}
    existing = next((r for r in releases() if r['tag_name'] == tag), None)
    if existing and existing['target_commitish'] != commit:
        raise RuntimeError('Use a new tag: existing release targets another commit')
    files = [DIST / name for name in sorted(expected)]
    if not existing:
        v = versions()
        title = f'MsgAT — Android {v["android"]} · Windows/macOS {v["macos"]} · Console {v["relay"]}'
        notes = Path(os.environ['RUNNER_TEMP']) / 'msgat-release-notes.md'
        notes.write_text(f'全部安装包由 [GitHub Actions]({manifest["workflow"]}) 从提交 `{commit}` 构建。\n\n'
                         '包含正式签名 Android APK、Windows 自包含 EXE、macOS arm64 DMG、服务端 JAR 和 JSON 配置。\n\n'
                         'Mac 使用 ad-hoc 签名，未经过 Apple 公证。服务端默认端口 34478，可在 JAR 同目录 server-config.json 修改。\n\n'
                         '文件完整性见 SHA256SUMS.txt，版本和构建来源见 release-manifest.json。\n')
        run('gh', 'release', 'create', tag, '--repo', repo, '--target', commit, '--draft', '--title', title, '--notes-file', notes)
        run('gh', 'release', 'upload', tag, '--repo', repo, *files)
    elif existing['draft']:
        run('gh', 'release', 'upload', tag, '--repo', repo, *files, '--clobber')
    remote = next(r for r in releases() if r['tag_name'] == tag)
    actual = {a['name']: a for a in remote['assets']}
    if set(actual) != set(expected) or any(actual[n]['state'] != 'uploaded' or
            actual[n]['size'] != value['size'] or actual[n].get('digest') != value['digest'] for n, value in expected.items()):
        raise RuntimeError('Remote release assets differ from verified workflow artifacts; retaining draft and older releases')
    run('gh', 'release', 'edit', tag, '--repo', repo, '--draft=false', '--latest')
    for old in releases():
        if old['id'] != remote['id']:
            run('gh', 'release', 'delete', old['tag_name'], '--repo', repo, '--yes')
    remaining = releases()
    if len(remaining) != 1 or remaining[0]['draft'] or remaining[0]['tag_name'] != tag:
        raise RuntimeError('Release retention check failed')
    print(f'Published and verified: https://github.com/{repo}/releases/tag/{tag}')


if __name__ == '__main__':
    commands = {'preflight': preflight, 'restore-signing': restore_signing, 'android': android,
                'windows': windows, 'macos': macos, 'relay': relay, 'bundle': bundle, 'publish': publish}
    commands[sys.argv[1]]()
