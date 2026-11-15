"""Prepare the signed repository rule feed. Does not upload any files."""
import argparse
import base64
import json
import os
import re
import subprocess
import tempfile
from pathlib import Path


PROJECT = Path(__file__).resolve().parents[1]
ASSETS = PROJECT / 'app/src/main/assets'
FEED = PROJECT / '0-update/rules.json'
MAX_ENVELOPE_SIZE = 900 * 1024


def load_catalog(path):
    rows = json.loads(path.read_text(encoding='utf-8'))['rules']
    if not isinstance(rows, list) or any(not isinstance(row, dict) for row in rows):
        raise ValueError('Catalog must contain an array of rule objects')
    return rows


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
    parser.add_argument('--output', type=Path, default=FEED)
    args = parser.parse_args()
    try:
        if not 1 <= args.version <= 2**63 - 1:
            raise ValueError('Version must be positive and fit a signed 64-bit integer')
        if not 1 <= args.min_version_code <= args.max_version_code <= 2**31 - 1:
            raise ValueError('Invalid client version-code range')
        for current in {FEED.resolve(), args.output.resolve()}:
            if current.is_file():
                wrapper = json.loads(current.read_bytes())
                public_version = json.loads(base64.b64decode(wrapper['payload'], validate=True))['version']
                if args.version <= public_version:
                    raise ValueError(f'New version must be above existing feed version {public_version}')
        splash = load_catalog(args.splash)
        popup = load_catalog(args.popup)
        if len(splash) + len(popup) > 1024:
            raise ValueError('Catalog exceeds the client rule-count limit')
        payload = json.dumps({
            'package': 'app.clearscreen', 'schema': 1, 'version': args.version,
            'minVersionCode': args.min_version_code, 'maxVersionCode': args.max_version_code,
            'splash': splash, 'popup': popup,
        }, ensure_ascii=False, allow_nan=False, separators=(',', ':')).encode('utf-8')
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
