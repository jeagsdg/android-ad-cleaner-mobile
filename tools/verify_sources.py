"""Check that every named target has traceable evidence in its pinned upstream recipe."""
import json
from pathlib import Path

root = Path(__file__).resolve().parents[1]
rules = json.loads((root / 'app/src/main/assets/rules.json').read_text(encoding='utf-8'))
ids = set()
for rule in rules:
    assert rule['id'] not in ids, rule['id']
    ids.add(rule['id'])
    sources = {rule['source']} | {r.get('source', rule['source']) for r in rule.get('routes', [])}
    texts = []
    for name in sources:
        source = (root / 'vendor/android-ad-cleaner' / name).resolve()
        assert source.is_relative_to((root / 'vendor/android-ad-cleaner/recipes').resolve())
        texts.append(source.read_text(encoding='utf-8'))
    text = '\n'.join(texts)
    targets = rule['targets'] + [label for labels in rule.get('aliases', {}).values() for label in labels]
    for target in targets:
        assert target in text, (rule['id'], target, rule['source'])
    for route in rule.get('routes', [rule]):
        assert len(route.get('path', [])) <= 8
        assert route.get('package', rule['package'])
    assert len(rule.get('routes', [rule])) <= 6
print(f"PASS: {len(rules)} semantic rules / {sum(len(r.get('routes', [r])) for r in rules)} routes / {sum(len(r['targets']) for r in rules)} target definitions with pinned source evidence. This is not device compatibility validation.")
