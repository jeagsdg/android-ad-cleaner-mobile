"""Validate packaged assets and, when requested, a build candidate's identity."""
import argparse
import gzip
import hashlib
import json
import os
import re
import subprocess
import zipfile
from collections import Counter
from pathlib import Path


NETWORK_PERMISSIONS = {
    'android.permission.INTERNET',
    'android.permission.ACCESS_NETWORK_STATE',
    'android.permission.FOREGROUND_SERVICE_SYSTEM_EXEMPTED',
}
VPN_BIND = 'android.permission.BIND_VPN_SERVICE'
VPN_ACTION = 'android.net.VpnService'
INSTALL_PERMISSION = 'android.permission.REQUEST_INSTALL_PACKAGES'


def android_tools(project):
    local_properties = project / 'local.properties'
    sdk_setting = None
    if local_properties.is_file():
        sdk_setting = re.search(r'^\s*sdk\.dir\s*=\s*(.+)$', local_properties.read_text(encoding='utf-8'), re.M)
    if sdk_setting:
        sdk = re.sub(r'\\([\\:= ])', r'\1', sdk_setting[1].strip())
    else:
        sdk = os.environ.get('ANDROID_HOME') or os.environ.get('ANDROID_SDK_ROOT')
    if not sdk:
        raise ValueError('Android SDK path is missing from local.properties, ANDROID_HOME and ANDROID_SDK_ROOT')
    build_tools = Path(sdk) / 'build-tools'
    if not build_tools.is_absolute():
        build_tools = project / build_tools
    versions = [path for path in build_tools.iterdir() if path.is_dir() and re.fullmatch(r'\d+(?:\.\d+)*', path.name)]
    for version in sorted(versions, key=lambda path: tuple(map(int, path.name.split('.'))), reverse=True):
        aapt = version / ('aapt.exe' if os.name == 'nt' else 'aapt')
        signer = version / 'lib/apksigner.jar'
        if aapt.is_file() and signer.is_file():
            return aapt, signer
    raise ValueError(f'Android SDK build-tools with aapt and apksigner not found in {build_tools}')


def run_tool(tool, *arguments):
    result = subprocess.run([str(tool), *map(str, arguments)], capture_output=True, encoding='utf-8', errors='replace')
    if result.returncode:
        detail = (result.stderr or result.stdout).strip()
        raise ValueError(f'{tool.name} failed to verify APK: {detail}')
    return result.stdout


def elements(manifest, tag):
    lines = manifest.splitlines()
    for index, line in enumerate(lines):
        if not line.lstrip().startswith(f'E: {tag} '):
            continue
        indent = len(line) - len(line.lstrip())
        end = index + 1
        while end < len(lines) and len(lines[end]) - len(lines[end].lstrip()) > indent:
            end += 1
        yield '\n'.join(lines[index:end])


def verify_package(apk, project, variant, build_type):
    gradle = (project / 'app/build.gradle').read_text(encoding='utf-8')
    expected = {}
    for attribute, field, value in (
        ('name', 'applicationId', r"""['"]([^'"]+)['"]"""),
        ('versionName', 'versionName', r"""['"]([^'"]+)['"]"""),
        ('versionCode', 'versionCode', r'(\d+)'),
        ('minSdk', 'minSdk', r'(\d+)'),
        ('targetSdk', 'targetSdk', r'(\d+)'),
    ):
        match = re.search(rf'^\s*{field}\s+{value}\s*$', gradle, re.M)
        if not match:
            raise ValueError(f'Cannot read {field} from app/build.gradle')
        expected[attribute] = match[1]

    aapt, signer = android_tools(project)
    badging = run_tool(aapt, 'dump', 'badging', apk)
    header = next((line for line in badging.splitlines() if line.startswith('package: ')), '')
    packaged = dict(re.findall(r"(\w+)='([^']*)'", header))
    for attribute, field in (('minSdk', 'sdkVersion'), ('targetSdk', 'targetSdkVersion')):
        match = re.search(rf"^{field}:'([^']+)'$", badging, re.M)
        packaged[attribute] = match[1] if match else None
    for attribute, value in expected.items():
        if packaged.get(attribute) != value:
            raise ValueError(f'Packaged {attribute} is {packaged.get(attribute)!r}, expected {value!r}')
    debuggable = 'application-debuggable' in badging.splitlines()
    if debuggable != (build_type == 'debug'):
        raise ValueError(f'Packaged debuggable flag does not match {build_type} build')

    permissions = set(re.findall(r"^uses-permission(?:-[\w-]+)?: name='([^']+)'", badging, re.M))
    manifest = run_tool(aapt, 'dump', 'xmltree', apk, 'AndroidManifest.xml')
    vpn_services = tuple(elements(manifest, 'service'))
    filter_names = (f'{expected["name"]}.AdFilterService', '.AdFilterService')
    provider_names = (f'{expected["name"]}.UpdateApkProvider', '.UpdateApkProvider')
    update_authority = f'{expected["name"]}.release-apk'
    update_providers = tuple(elements(manifest, 'provider'))
    if variant == 'offline':
        if permissions & NETWORK_PERMISSIONS or any(
            f'"{value}"' in manifest for value in (*filter_names, VPN_BIND, VPN_ACTION)
        ):
            raise ValueError('Offline APK declares a network permission or VPN service')
        if INSTALL_PERMISSION in permissions or any(
            f'"{value}"' in manifest for value in (*provider_names, update_authority)
        ):
            raise ValueError('Offline APK declares the internal update installer')
    elif not NETWORK_PERMISSIONS <= permissions or not any(
        any(f'"{name}"' in service for name in filter_names)
        and f'"{VPN_BIND}"' in service and f'"{VPN_ACTION}"' in service
        for service in vpn_services
    ):
        raise ValueError('Full APK is missing a network permission or the declared VPN service')
    elif INSTALL_PERMISSION not in permissions or not any(
        f'"{update_authority}"' in provider and any(f'"{name}"' in provider for name in provider_names)
        for provider in update_providers
    ):
        raise ValueError('Full APK is missing the permission or provider for internal updates')

    java_home = os.environ.get('JAVA_HOME')
    java = Path(java_home) / 'bin' / ('java.exe' if os.name == 'nt' else 'java') if java_home else Path('java')
    certificates = run_tool(java, '-jar', signer, 'verify', '--print-certs', apk)
    digests = re.findall(r'^Signer #\d+ certificate SHA-256 digest: ([\da-fA-F]{64})$', certificates, re.M)
    if not digests:
        raise ValueError('No verified APK signing certificate was found')
    if build_type == 'release' and re.search(r'^Signer #\d+ certificate DN: .*\bCN=Android Debug(?:,|$)', certificates, re.M):
        raise ValueError('Release APK is signed with an Android debug certificate')
    print(f'PASS: {variant} {build_type}, {expected["name"]} {expected["versionName"]} '
          f'({expected["versionCode"]}), SDK {expected["minSdk"]}–{expected["targetSdk"]}, '
          f'signer SHA-256 {", ".join(digests)}')


parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('apk', type=Path)
parser.add_argument('--variant', choices=('full', 'offline'))
parser.add_argument('--build-type', choices=('debug', 'release'))
args = parser.parse_args()
if bool(args.variant) != bool(args.build_type):
    parser.error('--variant and --build-type are required together')
apk = args.apk
if args.variant:
    try:
        verify_package(apk, Path(__file__).resolve().parents[1], args.variant, args.build_type)
    except (OSError, ValueError) as error:
        raise SystemExit(str(error)) from error

with zipfile.ZipFile(apk) as z:
    meta=json.loads(z.read('assets/dns-source.json'))
    payload=gzip.decompress(z.read('assets/dns-hosts.bin'))
    assert hashlib.sha256(payload).hexdigest()==meta['normalizedSha256']
    hosts=payload.decode('ascii').splitlines()
    assert len(hosts)==meta['hosts'] and hosts==sorted(set(hosts))
    assert 'connect.rom.miui.com' not in hosts
    assert 'doubleclick.net' in hosts
    assert z.read('assets/dns-license.txt').startswith(b'This is free and unencumbered')
    assert z.read('assets/upstream-license.txt')
    assert json.loads(z.read('assets/rules.json'))
    splash=json.loads(z.read('assets/splash-rules.json'))
    assert splash['rules'] and splash['sdkRules'] and z.read('assets/splash-license.txt').startswith(b'MIT License')
    popup=json.loads(z.read('assets/popup-ad-rules.json'))
    assert popup['rules'] and popup['sourceSha256']==splash['sourceSha256']
    assert all(rule['package'] and rule['targetSelector'] for rule in popup['rules'])
    splash_packages={rule['package'] for rule in splash['rules']}
    popup_packages={rule['package'] for rule in popup['rules']}
    splash_counts=Counter(rule['package'] for rule in splash['rules'])
    popup_counts=Counter(rule['package'] for rule in popup['rules'])
    catalog=json.loads(z.read('assets/app-ad-coverage.json'))
    assert len(catalog)==len(splash_packages | popup_packages)
    assert {entry['package']:(entry['splash'],entry['popup']) for entry in catalog} == {
        pkg:(splash_counts[pkg],popup_counts[pkg]) for pkg in splash_packages | popup_packages
    }
    assert hashlib.sha256(z.read('assets/support-code.jpg')).hexdigest()=='a7821f2dc8ea85999ff1159a7e399a7de3d3981dfba55f6acbbc897d1aff4508'
print(f'PASS: packaged rules, DNS source hash, {len(hosts)} hosts, protected connectivity host, licenses; APK SHA256 {hashlib.sha256(apk.read_bytes()).hexdigest()}')
print(f'App-specific coverage: {len(splash_packages)} splash, {len(popup_packages)} popup, '
      f'{len(splash_packages | popup_packages)} distinct packages')
