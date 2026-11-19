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
COMPONENTS = ('android', 'windows', 'macos', 'relay')
LICENSE_FILES = ('LICENSE', 'LICENSE-AGPL-3.0', 'THIRD_PARTY_NOTICES.md')


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


def filename(component, package_versions=None):
    v = versions() if package_versions is None else package_versions
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
    print('Cloud package inputs and signing secrets are configured.')


def preflight_publish():
    if os.environ['GITHUB_REF'] != 'refs/heads/main':
        raise RuntimeError('Publish from the main branch')
    tag = os.environ.get('RELEASE_TAG', '')
    build_run_id = os.environ.get('ACCEPTED_RUN_ID', '')
    manifest_sha256 = os.environ.get('ACCEPTED_MANIFEST_SHA256', '')
    if not re.fullmatch(r'release-[A-Za-z0-9][A-Za-z0-9._-]*', tag):
        raise RuntimeError('Publishing requires a release-* tag')
    if not re.fullmatch(r'[1-9][0-9]*', build_run_id) or not re.fullmatch(r'[A-Fa-f0-9]{64}', manifest_sha256):
        raise RuntimeError('Publishing requires the accepted build run ID and manifest SHA-256')

    repo = os.environ['GITHUB_REPOSITORY']
    build = json.loads(run('gh', 'api', f'repos/{repo}/actions/runs/{build_run_id}', capture=True))
    workflow = json.loads(run('gh', 'api', f'repos/{repo}/actions/workflows/packages.yml', capture=True))
    commit = build.get('head_sha', '')
    if (build.get('workflow_id') != workflow['id'] or build.get('event') != 'workflow_dispatch' or
            build.get('conclusion') != 'success' or (build.get('head_repository') or {}).get('full_name') != repo or
            not re.fullmatch(r'[0-9a-f]{40}', commit)):
        raise RuntimeError('The build must be a successful manual Cloud packages run in this repository')
    try:
        run('git', 'merge-base', '--is-ancestor', commit, os.environ['GITHUB_SHA'])
    except subprocess.CalledProcessError as error:
        raise RuntimeError('The accepted build commit must be in the main branch history') from error

    tag_ref = f'refs/tags/{tag}'
    existing_tag = subprocess.run(['git', 'rev-parse', '--verify', '--quiet', tag_ref],
                                  cwd=ROOT, stdout=subprocess.PIPE, check=False, text=True)
    if existing_tag.returncode not in (0, 1) or (existing_tag.returncode == 0 and
            run('git', 'rev-parse', '--verify', f'{tag_ref}^{{commit}}', capture=True).strip() != commit):
        raise RuntimeError('The release tag must identify the accepted build commit')
    with open(os.environ['GITHUB_OUTPUT'], 'a', encoding='utf-8') as output:
        output.write(f'commit={commit}\n')


def restore_signing():
    directory = Path(os.environ['RUNNER_TEMP']) / 'msgat-signing'
    directory.mkdir(mode=0o700, exist_ok=True)
    key = directory / 'release.jks'
    key.write_bytes(base64.b64decode(os.environ['ANDROID_KEYSTORE_BASE64'], validate=True))
    key.chmod(0o600)


def restore_development_signing():
    value = os.environ.get('ANDROID_DEVELOPMENT_KEYSTORE_BASE64')
    if not value:
        print('Development signing is unavailable; this build uses the ephemeral SDK debug certificate.')
        return
    if os.environ.get('GITHUB_ACTIONS') != 'true':
        raise RuntimeError('Development signing is restored only on hosted CI')
    if not os.environ.get('ANDROID_DEVELOPMENT_STORE_PASSWORD'):
        raise RuntimeError('Development signing password is missing')
    directory = Path(os.environ['RUNNER_TEMP']) / 'msgat-development-signing'
    directory.mkdir(mode=0o700, exist_ok=True)
    key = directory / 'development.p12'
    key.write_bytes(base64.b64decode(value, validate=True))
    key.chmod(0o600)
    with open(os.environ['GITHUB_ENV'], 'a', encoding='utf-8') as environment:
        environment.write(f'ANDROID_DEVELOPMENT_KEYSTORE_PATH={key}\nMSGAT_FIXED_DEVELOPMENT_SIGNING=true\n')


def development_android():
    sdk = Path(os.environ['ANDROID_HOME'])
    candidates = [p for p in (sdk / 'build-tools').iterdir() if re.fullmatch(r'\d+\.\d+\.\d+', p.name)]
    tools = max(candidates, key=lambda p: tuple(map(int, p.name.split('.'))))
    apk = ROOT / 'MsgAT/app/build/outputs/apk/debug/app-debug.apk'
    output = run(tools / 'apksigner', 'verify', '--verbose', '--print-certs', apk, capture=True)
    if os.environ.get('MSGAT_FIXED_DEVELOPMENT_SIGNING') == 'true':
        configuration = json.loads((ROOT / 'scripts/ci/development-signing.json').read_text())
        expected = configuration['certificateSha256']
        signers = re.findall(r'^Signer #\d+ certificate SHA-256 digest:\s*([A-Fa-f0-9:]+)\s*$', output, re.MULTILINE)
        fingerprints = [fingerprint.replace(':', '').lower() for fingerprint in signers]
        if fingerprints != [expected]:
            certificate = subprocess.check_output([
                'keytool', '-exportcert', '-storetype', 'PKCS12', '-keystore',
                os.environ['ANDROID_DEVELOPMENT_KEYSTORE_PATH'], '-alias', configuration['keyAlias'],
                '-storepass:env', 'ANDROID_DEVELOPMENT_STORE_PASSWORD',
            ])
            configured = hashlib.sha256(certificate).hexdigest()
            raise RuntimeError(
                f'Development signing mismatch. Expected: {expected}. '
                f'Configured certificate: {configured}. APK signers: {fingerprints}.'
            )
    badging = run(tools / 'aapt', 'dump', 'badging', apk, capture=True)
    if "package: name='org.msgat.development'" not in badging:
        raise RuntimeError('Development APK must use its isolated application ID')
    print('Development APK signature and isolated application ID verified.')


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
    copy(Path(os.environ['RUNNER_TEMP']) / 'msgat-windows-release/MsgAT.exe', filename('windows'))


def prepare_macos_app(source, app, identifier=None, name=None):
    run('ditto', source, app)
    if identifier:
        metadata = app / 'Contents/Info.plist'
        with metadata.open('rb') as stream:
            info = plistlib.load(stream)
        info.update(CFBundleIdentifier=identifier, CFBundleName=name, CFBundleDisplayName=name)
        with metadata.open('wb') as stream:
            plistlib.dump(info, stream)
    resource = app / 'Contents/Resources'
    for license_name in ('LICENSE', 'LICENSE-AGPL-3.0', 'THIRD_PARTY_NOTICES.md'):
        shutil.copy2(ROOT / license_name, resource / license_name)
    run('lipo', app / 'Contents/MacOS/MsgAT', '-verify_arch', 'arm64')
    run('codesign', '--force', '--deep', '--sign', '-', '--entitlements', ROOT / 'MsgAT_macOS/Sources/App/MsgAT.entitlements', app)
    run('codesign', '--verify', '--deep', '--strict', app)


def development_macos():
    if os.environ.get('GITHUB_ACTIONS') != 'true':
        raise RuntimeError('Development apps are packaged only on hosted CI')
    output = Path(os.environ['RUNNER_TEMP']) / 'msgat-macos-development'
    output.mkdir(exist_ok=True)
    source = Path(os.environ['RUNNER_TEMP']) / 'msgat-derived/Build/Products/Debug/MsgAT.app'
    with tempfile.TemporaryDirectory(prefix='msgat-development-') as temporary:
        stage = Path(temporary)
        for suffix, name in (('qaone', 'MsgAT QA One'), ('qatwo', 'MsgAT QA Two')):
            prepare_macos_app(source, stage / (name + '.app'), 'org.msgat.mac.development.' + suffix, name)
        run('ditto', '-c', '-k', '--sequesterRsrc', stage, output / 'MsgAT-macOS-development.zip')


def macos():
    with tempfile.TemporaryDirectory(prefix='msgat-dmg-') as temporary:
        stage = Path(temporary)
        app = stage / 'MsgAT.app'
        prepare_macos_app(Path(os.environ['RUNNER_TEMP']) / 'msgat-derived/Build/Products/Release/MsgAT.app', app)
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
    for component in COMPONENTS:
        path = DIST / filename(component)
        if not path.is_file() or path.stat().st_size == 0:
            raise RuntimeError(f'Missing package: {path.name}')
    for name in LICENSE_FILES:
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
    print(f"release-manifest.json SHA-256: {digest(DIST / 'release-manifest.json')}")


def releases():
    pages = json.loads(run('gh', 'api', '--paginate', '--slurp',
                          f'repos/{os.environ["GITHUB_REPOSITORY"]}/releases?per_page=100', capture=True))
    return [release for page in pages for release in page]


def verify_accepted_packages(commit):
    manifest_file = DIST / 'release-manifest.json'
    manifest_sha256 = os.environ['ACCEPTED_MANIFEST_SHA256'].lower()
    if not manifest_file.is_file() or digest(manifest_file) != manifest_sha256:
        raise RuntimeError('The release manifest does not match the accepted SHA-256')

    manifest = json.loads(manifest_file.read_text())
    build_url = (f"{os.environ['GITHUB_SERVER_URL']}/{os.environ['GITHUB_REPOSITORY']}"
                 f"/actions/runs/{os.environ['ACCEPTED_RUN_ID']}")
    if (manifest['commit'] != commit or manifest['workflow'] != build_url or
            manifest.get('androidSignerCertificateSha256') != SIGNER):
        raise RuntimeError('The manifest does not match the accepted run, commit or Android signer')

    assets = {asset['file']: {'size': asset['bytes'], 'digest': 'sha256:' + asset['sha256']}
              for asset in manifest['assets']}
    required = {filename(component, manifest['versions']) for component in COMPONENTS}
    required.update((*LICENSE_FILES, 'server-config.json', 'SERVER-README.txt'))
    if len(assets) != len(manifest['assets']) or not required.issubset(assets):
        raise RuntimeError('The accepted manifest must list all packages and supporting files without duplicates')
    paths = list(DIST.iterdir())
    if any(path.is_symlink() or not path.is_file() for path in paths):
        raise RuntimeError('The accepted artifact must contain only release files')
    expected = {path.name: {'size': path.stat().st_size, 'digest': 'sha256:' + digest(path)} for path in paths}
    if set(expected) != set(assets) | {'release-manifest.json', 'SHA256SUMS.txt'} or any(
            expected[name] != metadata or metadata['size'] <= 0 for name, metadata in assets.items()):
        raise RuntimeError('The accepted artifact files differ from the manifest')
    checksums = ''.join(f"{expected[name]['digest'].removeprefix('sha256:')}  {name}\n"
                        for name in sorted(expected) if name != 'SHA256SUMS.txt')
    if (DIST / 'SHA256SUMS.txt').read_text() != checksums:
        raise RuntimeError('The checksum list does not match the accepted artifact files')
    return manifest, expected


def publish():
    repo, tag, commit = (os.environ[name] for name in ('GITHUB_REPOSITORY', 'RELEASE_TAG', 'ACCEPTED_COMMIT'))
    manifest, expected = verify_accepted_packages(commit)
    existing = next((r for r in releases() if r['tag_name'] == tag), None)
    if existing and existing['target_commitish'] != commit:
        raise RuntimeError('Use a new tag: existing release targets another commit')
    files = [DIST / name for name in sorted(expected)]
    if not existing:
        v = manifest['versions']
        title = f'MsgAT — Android {v["android"]} · Windows {v["windows"]} · macOS {v["macos"]} · Console {v["relay"]}'
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
    commands = {'preflight': preflight, 'preflight-publish': preflight_publish, 'restore-signing': restore_signing,
                'restore-development-signing': restore_development_signing, 'development-android': development_android,
                'development-macos': development_macos, 'android': android,
                'windows': windows, 'macos': macos, 'relay': relay, 'bundle': bundle, 'publish': publish}
    commands[sys.argv[1]]()
