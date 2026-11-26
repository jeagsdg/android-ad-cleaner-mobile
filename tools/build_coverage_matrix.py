"""Join shipped catalog entries to recorded evidence. Uses no network."""
import argparse
import hashlib
import json
from collections import defaultdict
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]


def build(ledger_path):
    packages = {}
    sources = {}
    for kind, name in [('splash', 'splash-rules.json'), ('popup', 'popup-ad-rules.json')]:
        asset = ROOT / 'app/src/main/assets' / name
        data = asset.read_bytes()
        catalog = json.loads(data)
        sources[kind] = {
            'asset': asset.relative_to(ROOT).as_posix(),
            'sha256': hashlib.sha256(data).hexdigest(),
            'upstream': catalog.get('source'),
            'upstreamCommit': catalog.get('sourceCommit'),
        }
        for index, rule in enumerate(catalog['rules']):
            pkg = rule['package']
            row = packages.setdefault(pkg, {
                'package': pkg, 'name': rule.get('name', ''), 'splash': 0, 'popup': 0,
                'catalogRows': defaultdict(list), 'scenes': set(), 'referencedSnapshots': set(),
            })
            row[kind] += 1
            row['catalogRows'][kind].append(index)
            row['scenes'].add(rule.get('group') or ('开屏' if kind == 'splash' else '应用内'))
            row['referencedSnapshots'].update(url.rsplit('/', 1)[-1] for url in rule.get('snapshots', []))

    ledger = defaultdict(list)
    for line in ledger_path.read_text(encoding='utf-8').splitlines():
        if line.strip():
            sample = json.loads(line)
            ledger[sample['package']].append(sample)

    for pkg, row in packages.items():
        evidence = ledger[pkg]
        verified = [s for s in evidence if s['level'] == 'asserted_offline']
        limitations = [
            {'treeId': sample['treeId'], 'scene': check['scene'],
             'status': check['status'], 'reason': check['reason']}
            for sample in evidence for check in sample['checks']
            if check.get('status') in {'needs_readaptation', 'no_safe_exit'}
        ]
        row.update({
            'scenes': sorted(row['scenes']),
            'referencedSnapshots': sorted(row['referencedSnapshots']),
            'samplesInspected': len(evidence),
            'samplesAssertedOffline': len(verified),
            'verifiedSampleVersions': sorted({s['appVersion'] for s in verified if s['appVersion']}),
            'lastOfflineVerification': max((s['date'] for s in verified), default=None),
            'physicalDeliverySessions': 0,
            'emulatorRealAppSessions': 0,
            'realResult': None,
            'status': 'offline_sample_verified' if verified else 'candidate_only',
            'failureReasons': sorted({item['reason'] for item in limitations}),
            'sceneLimitations': limitations,
            'sampleGap': 'no_local_tree' if not evidence else 'no_physical_ad_session',
        })
    return {
        'format': 1, 'sources': sources, 'ledger': ledger_path.name,
        'ruleCount': sum(row['splash'] + row['popup'] for row in packages.values()),
        'packageCount': len(packages),
        'packages': [packages[pkg] for pkg in sorted(packages)],
    }


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--ledger', type=Path, default=ROOT / 'docs/适配样本索引.jsonl')
    parser.add_argument('--output', type=Path, default=ROOT / 'docs/适配质量矩阵.json')
    args = parser.parse_args()
    data = build(args.ledger)
    args.output.write_bytes((json.dumps(data, ensure_ascii=False, indent=2) + '\n').encode('utf-8'))
    print(f"{data['packageCount']} packages, {data['ruleCount']} candidate rules")


if __name__ == '__main__':
    main()
