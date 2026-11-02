"""Check that every named target has traceable evidence in its pinned upstream recipe."""
import json
from pathlib import Path

root = Path(__file__).resolve().parents[1]
rules = json.loads((root / 'app/src/main/assets/rules.json').read_text(encoding='utf-8'))
ids = set()
for rule in rules:
    assert rule['id'] not in ids, rule['id']
    ids.add(rule['id'])
    source = (root / 'vendor/android-ad-cleaner' / rule['source']).resolve()
    assert source.is_relative_to((root / 'vendor/android-ad-cleaner/recipes').resolve())
    text = source.read_text(encoding='utf-8')
    for target in rule['targets']:
        assert target in text, (rule['id'], target, rule['source'])
print(f"PASS: {len(rules)} paths / {sum(len(r['targets']) for r in rules)} target definitions with pinned source evidence. This is not device compatibility validation.")
