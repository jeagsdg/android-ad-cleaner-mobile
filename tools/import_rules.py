"""Reproducible, offline migration of the pinned MIT upstream rules. Never executes recipes."""
import ast
import hashlib
import importlib.util
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
UP = ROOT / 'vendor/android-ad-cleaner'
ASSETS = ROOT / 'app/src/main/assets'
ASSETS.mkdir(parents=True, exist_ok=True)
SHA = '19bb74fbefece01763f713c3c26220f63659ed87'
spec = importlib.util.spec_from_file_location('upstream_policy', UP / 'tool/channel_policy.py')
policy = importlib.util.module_from_spec(spec)
import sys
sys.modules[spec.name] = policy
spec.loader.exec_module(policy)
rules = {key: getattr(policy, key).pattern for key in ('PHOTO_SPOT', 'KW', 'SKIP', 'VIVO_MARKETING', 'AD_NETWORK', 'SERVICE_TERMS')}
rules['NEG_PREFIX'] = r'^(防止|限制|阻止|禁止|拒绝|不允许|屏蔽|关闭|停用|不接收|不显示|不推送|停止)'
(ASSETS / 'policy.json').write_text(json.dumps(rules, ensure_ascii=False, indent=2), encoding='utf-8')
(ASSETS / 'upstream-license.txt').write_text((UP / 'LICENSE').read_text(encoding='utf-8'), encoding='utf-8')
manifest = []
for path in sorted((UP / 'recipes').rglob('*.py')):
    tree = ast.parse(path.read_text(encoding='utf-8'))
    build = ''
    models = [path.parent.name.split('-', 1)[-1]]
    for n in tree.body:
        if isinstance(n, ast.Assign):
            for t in n.targets:
                if isinstance(t, ast.Name) and t.id in ('BUILD', 'MODELS'):
                    try:
                        value = ast.literal_eval(n.value)
                        if t.id == 'BUILD': build = value
                        else: models = value
                    except (ValueError, TypeError): pass
    manifest.append({'file': str(path.relative_to(UP)).replace('\\', '/'), 'build': build, 'models': models,
                     'sha256': hashlib.sha256(path.read_bytes()).hexdigest(), 'status': 'reference_only'})
(ASSETS / 'upstream-manifest.json').write_text(json.dumps({'commit': SHA, 'recipes': manifest}, ensure_ascii=False, indent=2), encoding='utf-8')
print(f'Migrated channel policy and indexed {len(manifest)} reference recipes at {SHA}')
