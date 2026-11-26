"""Prepare the signed repository rule feed. Does not upload any files."""
import argparse
import base64
import json
import hashlib
import os
import re
import subprocess
import tempfile
from pathlib import Path


PROJECT = Path(__file__).resolve().parents[1]
ASSETS = PROJECT / 'app/src/main/assets'
FEED = PROJECT / '0-update/rules-v22.json'
LEGACY_FEED = PROJECT / '0-update/rules.json'
MAX_ENVELOPE_SIZE = 900 * 1024


def load_catalog(path):
    rows = json.loads(path.read_text(encoding='utf-8'))['rules']
    if not isinstance(rows, list) or any(not isinstance(row, dict) for row in rows):
        raise ValueError('Catalog must contain an array of rule objects')
    return rows


def compact_catalog(splash, popup, base_splash, base_popup):
    """Use ordered references into a byte-for-byte identified APK catalog."""
    base_hash = ''.join(hashlib.sha256(path.read_bytes()).hexdigest() for path in (base_splash, base_popup))
    def row_key(row):
        return json.dumps(row, ensure_ascii=False, sort_keys=True, allow_nan=False, separators=(',', ':'))
    output = []
    first_slot = 0
    for rows, path in ((splash, base_splash), (popup, base_popup)):
        original = load_catalog(path)
        slots = {row_key(row): first_slot + i for i, row in enumerate(original)}
        output.append([slots.get(row_key(row), row) for row in rows])
        first_slot += len(original)
    return {'schema': 2, 'baseHash': base_hash, 'splash': output[0], 'popup': output[1]}


def replay_changes(catalog, changed, reviews):
    if not changed:
        return
    if not reviews:
        raise ValueError('New or modified rows require --reviews with one positive and one ordinary sample per rule ID')
    mapping = json.loads(reviews.read_bytes())
    cases = []
    for row in changed:
        entry = mapping[row['id']]
        samples = {}
        for role in ('positive', 'negative'):
            path = reviews.parent / entry[role]
            if path.stat().st_size > 4 * 1024 * 1024:
                raise ValueError(f'Sample exceeds the client limit: {path}')
            samples[role] = json.loads(path.read_bytes())
        cases.append({'id': row['id'], **samples})
    with tempfile.TemporaryDirectory() as temp:
        plan = Path(temp) / 'review.json'
        plan.write_text(json.dumps({'catalog': catalog, 'cases': cases}, ensure_ascii=False), encoding='utf-8')
        wrapper = PROJECT / ('gradlew.bat' if os.name == 'nt' else 'gradlew')
        subprocess.run([str(wrapper), ':app:testDebugUnitTest', '--tests',
                        'app.clearscreen.RulePackEvidenceTest.publishingPreflight', '--rerun'],
                       cwd=PROJECT, check=True, env={**os.environ, 'CLEARSCREEN_RULE_REVIEW': str(plan)})


def make_envelope(payload):
    rule_pack = PROJECT / 'app/src/main/java/app/clearscreen/RulePack.java'
    pin = re.search(r'CERTIFICATE_SHA256 = "([a-f0-9]{64})"', rule_pack.read_text(encoding='utf-8'))
    if pin is None:
        raise ValueError('Cannot read the official certificate pin')
    java_home = os.environ.get('JAVA_HOME')
    java = str(Path(java_home) / 'bin/java') if java_home else 'java'
    output = subprocess.run(
        [java, str(PROJECT / 'tools/SignRulePack.java'), str(PROJECT), pin[1]],
        input=payload, stdout=subprocess.PIPE, check=True,
    ).stdout
    if len(output) > MAX_ENVELOPE_SIZE:
        raise ValueError('Signed feed exceeds the client size limit')
    return output


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--version', type=int, required=True, help='Increment for every public rule feed update')
    parser.add_argument('--min-version-code', type=int, required=True)
    parser.add_argument('--max-version-code', type=int, required=True)
    parser.add_argument('--splash', type=Path, default=ASSETS / 'splash-rules.json')
    parser.add_argument('--popup', type=Path, default=ASSETS / 'popup-ad-rules.json')
    parser.add_argument('--reviews', type=Path, help='JSON object keyed by changed rule ID; values name positive/negative sample files')
    parser.add_argument('--delta', action='store_true', help='Reference unchanged rules in the bundled catalog (schema 2)')
    parser.add_argument('--output', type=Path, default=FEED)
    args = parser.parse_args()
    try:
        if not 1 <= args.version <= 2**63 - 1:
            raise ValueError('Version must be positive and fit a signed 64-bit integer')
        if not 1 <= args.min_version_code <= args.max_version_code <= 2**31 - 1:
            raise ValueError('Invalid client version-code range')
        for current in {LEGACY_FEED.resolve(), FEED.resolve(), args.output.resolve()}:
            if current.is_file():
                wrapper = json.loads(current.read_bytes())
                public_version = json.loads(base64.b64decode(wrapper['payload'], validate=True))['version']
                if args.version <= public_version:
                    raise ValueError(f'New version must be above existing feed version {public_version}')
        splash = load_catalog(args.splash)
        popup = load_catalog(args.popup)
        if len(splash) + len(popup) > 1024:
            raise ValueError('Catalog exceeds the client rule-count limit')
        references = compact_catalog(splash, popup, ASSETS / 'splash-rules.json', ASSETS / 'popup-ad-rules.json')
        catalog = references if args.delta else {
            'schema': 1, 'splash': splash, 'popup': popup,
        }
        if args.delta and args.output.resolve() in {FEED.resolve(), LEGACY_FEED.resolve()}:
            raise ValueError('Use a separate delta output so older clients can still read the full feed')
        document = {
            'package': 'app.clearscreen', 'version': args.version,
            'minVersionCode': args.min_version_code, 'maxVersionCode': args.max_version_code,
            **catalog,
        }
        changed = [row for kind in ('splash', 'popup') for row in references[kind] if isinstance(row, dict)]
        replay_changes(document, changed, args.reviews)
        payload = json.dumps(document, ensure_ascii=False, allow_nan=False, separators=(',', ':')).encode('utf-8')
        envelope = make_envelope(payload)
        args.output.parent.mkdir(parents=True, exist_ok=True)
        with tempfile.NamedTemporaryFile(dir=args.output.parent, delete=False) as pending:
            pending.write(envelope)
            temporary = Path(pending.name)
        try:
            temporary.replace(args.output)
        finally:
            temporary.unlink(missing_ok=True)
        print(f'Rule pack {args.version} signed: {len(splash)} splash, {len(popup)} popup, {len(envelope)} bytes')
    except (OSError, ValueError, KeyError, subprocess.CalledProcessError) as error:
        parser.exit(1, f'Could not prepare rule pack: {error}\n')


if __name__ == '__main__':
    main()
