"""Compare a public main ZIP with this checkout and stage an explicitly reviewed release locally."""
import argparse
import base64
import hashlib
import json
import os
from pathlib import Path
import posixpath
import re
import subprocess
import sys
import tempfile
from urllib.parse import unquote, urlsplit
from zipfile import ZipFile

ROOT = Path(__file__).resolve().parents[1]
REPO = 'jeagsdg/android-ad-cleaner-mobile'
PREFIX = REPO.split('/')[1] + '-main/'
DELTA = '0-update/rules-delta-v2.json'
SUMS = 'dist/SHA256SUMS.txt'
PIN = '40d36629d9780396e88d286fa6f43771f8d663fbc0b9e9f951ad84c5153ddf4e'
SOURCE_DIRS = ('app/src', 'fixture/src', 'gradle/wrapper', 'docs', 'tools', 'vendor', '.github/workflows')
LOCAL_TOOLS = {'tools/github_auth_check.py', 'tools/download_emulator_image.py'}
ASSETS = ('splash-rules.json', 'popup-ad-rules.json')


def require(ok, message):
    if not ok:
        raise ValueError(message)


def sha(data):
    return hashlib.sha256(data).hexdigest()


def local(name):
    path = (ROOT / name).resolve()
    require(path.is_relative_to(ROOT) and path.is_file(), f'Missing or unsafe source: {name}')
    return path


def changed_sources(previous, extra):
    current_mirror = re.compile(r'0-update/ClearScreen-[\d.]+\.apk$')
    names = {item.filename[len(PREFIX):] for item in previous.infolist() if not item.is_dir()}
    require(all(item.filename.startswith(PREFIX) for item in previous.infolist()), 'Unexpected archive prefix')
    mirrors = {name for name in names if current_mirror.fullmatch(name)}
    require(len(mirrors) == 1, 'Expected exactly one previous APK mirror')
    known = {name for name in names if not name.startswith('dist/') and name not in mirrors}
    paths = set(known)
    for folder in SOURCE_DIRS:
        for path in (ROOT / folder).rglob('*'):
            name = path.relative_to(ROOT).as_posix()
            if path.is_file() and name not in LOCAL_TOOLS and not {'build', '__pycache__'} & set(path.parts):
                paths.add(name)
    for name in extra:
        require(re.fullmatch(r'artifacts/[\w./-]+\.(?:json|jsonl|tsv|txt|md)', name)
                and '..' not in name.split('/'), f'Invalid extra evidence path: {name}')
    paths.update(extra)
    paths.add(DELTA)
    changed = {}
    for name in sorted(paths):
        if name not in known:
            require(Path(name).suffix.lower() in {'.java', '.py', '.md', '.tsv', '.json', '.jsonl', '.yaml', '.yml',
                    '.html', '.xml', '.ps1', '.txt', '.properties', '.gradle', '.sh', '.js', '.csv'},
                    f'Review unexpected new file type: {name}')
        data = local(name).read_bytes()
        if name not in known or previous.read(PREFIX + name) != data:
            changed[name] = data
    return changed, mirrors.pop()


def verify_delta(name, code, apk, previous_version=0):
    data = local(DELTA).read_bytes()
    require(len(data) <= 900 * 1024, 'Oversized signed feed')
    envelope = json.loads(data)
    require(set(envelope) == {'certificate', 'payload', 'signature'}, 'Invalid signed envelope')
    wire = '\n'.join(envelope[k] for k in ('certificate', 'payload', 'signature')).encode('ascii')
    java_home = os.environ.get('JAVA_HOME')
    java = str(Path(java_home) / 'bin/java') if java_home else 'java'
    subprocess.run([java, str(local('tools/SignRulePack.java')), '--verify', PIN], input=wire, check=True)
    p = json.loads(base64.b64decode(envelope['payload'], validate=True))
    require((p['package'], p['schema'], p['minVersionCode'], p['maxVersionCode'])
            == ('app.clearscreen', 2, code, code) and type(p['version']) is int
            and p['version'] > previous_version, f'Wrong delta target or version for {name}')
    with ZipFile(apk) as archive:
        catalogs = [archive.read('assets/' + asset) for asset in ASSETS]
    require(p['baseHash'] == ''.join(sha(raw) for raw in catalogs), 'Delta was signed for different APK catalogs')
    sizes = [len(json.loads(raw)['rules']) for raw in catalogs]
    for section, offset, length in (('splash', 0, sizes[0]), ('popup', sizes[0], sizes[1])):
        rows = p[section]
        require(isinstance(rows, list) and len(rows) > 0, f'Empty {section} feed')
        slots = [r for r in rows if type(r) is int]
        require(len(slots) == len(set(slots)) and all(offset <= r < offset + length for r in slots)
                and all(type(r) is int or isinstance(r, dict) for r in rows), f'Invalid {section} delta references')
    return p['version']


def build_inputs(root=ROOT):
    names = ['settings.gradle', 'build.gradle', 'gradle.properties', 'app/build.gradle', 'fixture/build.gradle',
             'gradlew', 'gradlew.bat']
    for directory in ('app/src', 'fixture/src', 'gradle/wrapper'):
        names.extend(p.relative_to(root).as_posix() for p in (root / directory).rglob('*') if p.is_file())
    return {name: sha((root / name).read_bytes()) for name in sorted(names)}


def referenced_files(document, data):
    found = set()
    for href in re.findall(r'\]\(([^)\s]+)\)', data.decode('utf-8-sig')):
        url = urlsplit(href.strip('<>'))
        if url.scheme or url.netloc or not url.path:
            continue
        target = posixpath.normpath(posixpath.join(posixpath.dirname(document), unquote(url.path)))
        found.add(target)
    return found


def stage(args, previous, changes, old_mirror):
    allow = set(args.allowlist.read_text(encoding='utf-8-sig').splitlines())
    require(allow == set(changes), f'Changed paths differ from the review: {sorted(allow ^ changes.keys())}')
    require({'update.json', '.update.json', 'README.md', 'app/build.gradle'} <= allow, 'Missing release metadata')
    require('0-update/rules-v22.json' not in allow and '0-update/rules.json' not in allow, 'Legacy feed changed')
    require(changes['update.json'] == changes['.update.json'] and len(changes['update.json']) <= 16384,
            'Update mirrors differ or exceed the client limit')
    info = json.loads(changes['update.json'])
    version, code = info['versionName'], info['versionCode']
    prev = json.loads(previous.read(PREFIX + 'update.json'))
    part = r'(?:0|[1-9][0-9]{0,4})'
    notes = info['notes']
    require(info['schema'] == 1 and re.fullmatch(rf'{part}\.{part}\.{part}', version) and type(code) is int
            and prev['versionCode'] < code <= 2_147_483_647 and isinstance(notes, str) and notes.strip()
            and len(notes.encode('utf-16-le')) <= 2400
            and not re.search(r'[\x00-\x08\x0b\x0c\x0e-\x1f\x7f-\x9f]', notes), 'Invalid app version or release notes')
    require(old_mirror == f"0-update/ClearScreen-{prev['versionName']}.apk", 'Previous app mirror differs')
    frozen = json.loads((args.binaries / 'build-source-hashes.json').read_bytes())
    current_inputs = build_inputs()
    drift = {n for n in frozen.keys() | current_inputs.keys() if frozen.get(n) != current_inputs.get(n)}
    tested = json.loads(args.verified_tests.read_bytes())['files'] if args.verified_tests else {}
    require(set(tested) == drift and all(n.startswith(('app/src/test/', 'app/src/networkTest/'))
            and n in current_inputs and current_inputs[n] == tested[n] for n in drift),
            f'Current inputs differ from signed source or verified JVM tests: {sorted(drift)}')
    entries = dict(changes)
    old_sums = previous.read(PREFIX + SUMS)
    for digest, filename in re.findall(rb'^([\da-f]{64})  ([^\r\n]+)', old_sums, re.M):
        require(sha(local('dist/' + filename.decode()).read_bytes()).encode() == digest, 'Historical APK differs')
    endline = b'\r\n' if b'\r\n' in old_sums else b'\n'
    added = b''
    old_feeds = ['0-update/rules-v22.json']
    if PREFIX + DELTA in previous.namelist():
        old_feeds.append(DELTA)
    old_rule_version = max(json.loads(base64.b64decode(json.loads(previous.read(PREFIX + f))['payload']))['version']
                           for f in old_feeds)
    for variant, suffix in [('full', ''), ('offline', '-offline')]:
        filename = f'ClearScreen-{version}{suffix}.apk'
        apk = args.binaries / filename
        subprocess.run([sys.executable, str(local('tools/verify_apk.py')), str(apk), '--variant', variant,
                        '--build-type', 'release', '--expected-signer', PIN], check=True)
        rule_version = verify_delta(version, code, apk, old_rule_version)
        data = apk.read_bytes()
        require(len(data) <= 64 * 1024 * 1024, 'Package exceeds old update downloader limit')
        entries['dist/' + filename] = data
        if variant == 'full':
            entries['0-update/' + filename] = data
        added += f'{sha(data)}  {filename}'.encode() + endline
    cert_line = endline + b'Release certificate SHA-256: ' + PIN.encode()
    require(old_sums.count(cert_line) == 1, 'Corrupt previous checksum list')
    entries[SUMS] = old_sums.replace(cert_line, added + cert_line)
    require(local(SUMS).read_bytes() in (old_sums, entries[SUMS]), 'Checksum file has independent changes')
    published = {i.filename[len(PREFIX):].rstrip('/') for i in previous.infolist()} | entries.keys()
    published.discard(old_mirror)
    for name, data in changes.items():
        if name.endswith('.md'):
            old = previous.read(PREFIX + name) if PREFIX + name in previous.namelist() else b''
            missing = referenced_files(name, data) - referenced_files(name, old) - published
            require(not missing, f'New links in {name} will be missing from the release: {sorted(missing)}')
    out = args.output.resolve()
    require(not out.exists() and not out.is_relative_to(ROOT), 'Use a new staging directory outside the project')
    out.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(dir=out.parent) as temp:
        pending = Path(temp) / 'stage'
        records = []
        for path, data in sorted(entries.items()):
            target = pending / 'payload' / path
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_bytes(data)
            records.append(dict(path=path, size=len(data), sha256=sha(data),
                                sha=hashlib.sha1(b'blob ' + str(len(data)).encode() + b'\0' + data).hexdigest()))
        manifest = dict(repository=REPO, parent=args.parent, versionName=version, versionCode=code, ruleVersion=rule_version,
                        removals=[old_mirror], verifiedJvmTestChanges=tested, files=records)
        (pending / 'manifest.json').write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
        pending.rename(out)
    print(f'Staged {len(records)} files, one superseded mirror, parent {args.parent}.')


def verify_archive(previous, candidate, manifest):
    plan = json.loads(manifest.read_bytes())
    require(plan['repository'] == REPO and plan['parent'] == previous.comment.decode(), 'Wrong archive parent')
    staged = {row['path']: row for row in plan['files']}
    baseline = {i.filename[len(PREFIX):] for i in previous.infolist() if not i.is_dir()}
    wanted = baseline.difference(plan['removals']).union(staged)
    contents = {i.filename[len(PREFIX):]: i for i in candidate.infolist() if not i.is_dir()}
    require(all(i.filename.startswith(PREFIX) for i in candidate.infolist()) and set(contents) == wanted
            and len(contents) == sum(not i.is_dir() for i in candidate.infolist()), 'Unexpected publication tree')
    current = '0-update/ClearScreen-' + plan['versionName'] + '.apk'
    early = {'.update.json', current, '0-update/rules.json', '0-update/rules-v22.json', DELTA}
    before = 0
    for index, item in enumerate(candidate.infolist()):
        name = item.filename[len(PREFIX):]
        if name in early:
            require(index < 128 and before <= 4 * 1024 * 1024, f'Old client archive search budget exceeded: {name}')
            early.remove(name)
        if not item.is_dir():
            data = candidate.read(item)
            if name in staged:
                require(sha(data) == staged[name]['sha256'], f'Published bytes differ: {name}')
            else:
                require(data == previous.read(PREFIX + name), f'Unexpected change to public file: {name}')
            before += len(data)
    require(not early, f'Missing early update entries: {sorted(early)}')
    print(f'Archive matches {len(contents)} files. All five update routes fit the client search budget.')


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--previous-archive', type=Path)
    p.add_argument('--parent', help='Commit SHA of the previous public main archive')
    p.add_argument('--evidence', action='append', default=[], help='Additional reviewed artifact path')
    p.add_argument('--allowlist', type=Path)
    p.add_argument('--binaries', type=Path)
    p.add_argument('--verified-tests', type=Path, help='Receipt with exact files/hashes for separately tested JVM-only changes')
    p.add_argument('--output', type=Path)
    p.add_argument('--verify-archive', type=Path, help='Verify a staged git archive or downloaded public ZIP')
    p.add_argument('--manifest', type=Path, help='Manifest from a local stage')
    p.add_argument('--fingerprint', action='store_true', help='Print build input SHA-256 inventory')
    p.add_argument('--fingerprint-root', type=Path, default=ROOT, help='Read the build inventory from a frozen checkout')
    args = p.parse_args()
    if args.fingerprint:
        print(json.dumps(build_inputs(args.fingerprint_root.resolve()), indent=2))
        return
    if not args.previous_archive or not args.parent or not re.fullmatch(r'[\da-f]{40}', args.parent):
        p.error('--previous-archive and a full --parent commit are required')
    with ZipFile(args.previous_archive) as prev:
        require(prev.comment.decode('ascii') == args.parent, 'Archive is from another commit')
        if args.verify_archive:
            if not args.manifest:
                p.error('--verify-archive requires --manifest')
            with ZipFile(args.verify_archive) as candidate:
                verify_archive(prev, candidate, args.manifest)
            return
        changes, old = changed_sources(prev, args.evidence)
        if not (args.allowlist or args.binaries or args.output):
            print('\n'.join(changes))
            return
        if not (args.allowlist and args.binaries and args.output):
            p.error('staging requires --allowlist, --binaries and --output')
        stage(args, prev, changes, old)


if __name__ == '__main__':
    main()
