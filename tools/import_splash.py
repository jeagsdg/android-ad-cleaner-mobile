"""Compile a reviewed subset of the pinned MIT splash selectors into fixed predicates.

Only startup groups with an identifiable ad target, container or activity are used.
The app never evaluates the selector strings or performs upstream coordinate actions.
"""
import hashlib
import json
import re
import sys
from pathlib import Path

import json5

ROOT = Path(__file__).resolve().parents[1]
VENDOR = ROOT / 'vendor/gkd-splash-reference'
ASSETS = ROOT / 'app/src/main/assets'
SOURCE_SHA = '5624137f8c70566a8dc985990dd18571600efa1c76129734703533ca5ee71c23'
LICENSE_SHA = '3771ee111d68071b0e2994817cff88599d59d7272e75ea026f87af810160340e'
SOURCE_COMMIT = '4746d192b4c57a4b62ed92a7f5deaf79e64fa811'

STRING = r"""(?:"[^"\\]*"|'[^'\\]*'|`[^`\\]*`)"""
ATTRIBUTE = r'\[(?:' + STRING + r'|[^\[\]"\'\x60])*\]'
NODE = re.compile(r'(@)?(\*|[A-Za-z][\w.]*)?((?:' + ATTRIBUTE + r')*)')
RELATION = re.compile(r'(<<|[<>+-])(n|[1-9]\d*|\([1-9]\d*(?:,[1-9]\d*)*\))?\s+')
STRING_TEST = re.compile(r'(id|vid|text|desc)\s*(\^=|\$=|\*=|!=|=)\s*(' + STRING + r'|null)')
NUMBER_TEST = re.compile(r'(text\.length|desc\.length|childCount)\s*(<=|>=|=|<|>)\s*(\d+)')
BOOLEAN_TEST = re.compile(r'(visibleToUser|clickable)\s*=\s*(true|false)')

# IDs whose meaning is specific to the corresponding app and its pinned splash evidence.
REVIEWED_TARGETS = {
    'cn.damai': {'homepage_advert_pb'},
    'com.cmstop.shaoxing': {'roundProgressBar'},
    'com.dubox.drive': {'ivClose'},
    'com.example.pptv': {'view_count_shake'},
    'com.handsgo.jiakao.android': {'closeLayout'},
    'com.hxak.liangongbao': {'time_down'},
    'com.kkeji.news.client': {'tv_counter'},
    'com.ky.medical.reference': {'btn_go'},
    'com.shanghaionstar': {'jump_view'},
    'gov.pianzong.androidnga': {'iv_tg_ad'},
}
REVIEWED_ANCHORS = {
    'com.dongqiudi.news': {'scale_iv'},
    'com.dubox.drive': {'inmobi-ad'},
    'com.ksf.yyx': {'ksad_splash_root_container'},
    'com.netease.mail': {'ad_placeholder'},
    'com.taobao.taobao': {'close'},
    'info.muge.appshare': {'rlAdView'},
}
# The Taobao branch also needs a visible child with the exact upstream skip-label constraint.
SKIP_RESOURCE = re.compile(r'skip|ad.*jump|jump.*ad|ad.*close|count_down|countdown|splash_(?:tv|adv)', re.I)
AD_CONTAINER = re.compile(
    r'(?:splash.*(?:container|root)|(?:container|root).*splash|ad_?(?:view|container)|'
    r'advertis(?:ing|ement)_?container|advert_?container|csj_?ad|fl_third_ad|tv_gdt_ad_text|gdt_ad_text)',
    re.I,
)
SDK_RESOURCES = ('tt_splash_skip_btn', 'ksad_splash_circle_skip_view')
EXACT_SUFFIX_RESOURCES = {*SDK_RESOURCES, 'hiad_skip_text'}
HUAWEI_SKIP_ID = 'com.huawei.hms.ads.uiengine:id/hiad_skip_text'
# Retain the exact SDK description branch. A plain 跳过 description is insufficient.
REVIEWED_ALTERNATIVES = {
    ('com.kugou.android', '[desc="跳过"||desc="GdtCountDownView"]'):
        '[desc="GdtCountDownView"][clickable=true]',
}
FINANCIAL = re.compile(r'bank|credit|wallet|wealth|antfortune|mymoney|wacai', re.I)
AD_ACTIVITY = re.compile(r'(?:ads?launch|open(?:screen)?ad|splashad)activity$', re.I)


def array(value):
    return value if isinstance(value, list) else [value] if value is not None else []


def parse_test(value):
    if match := STRING_TEST.fullmatch(value):
        field, op, operand = match.groups()
        if operand == 'null':
            return [field, op, None] if op in ('=', '!=') else None
        operand = operand[1:-1]
        if field == 'id' and op == '$=' and operand.lstrip('/') in EXACT_SUFFIX_RESOURCES:
            return ['resourceName', '=', operand.lstrip('/')]
        return [field, op, operand]
    if match := NUMBER_TEST.fullmatch(value):
        field, op, operand = match.groups()
        return [field, op, int(operand)]
    if match := BOOLEAN_TEST.fullmatch(value):
        field, operand = match.groups()
        return [field, '=', operand == 'true']
    return None


def parse_node(match):
    _, class_name, attributes = match.groups()
    predicates = []
    for attribute in re.finditer(ATTRIBUTE, attributes):
        # This subset has no embedded logical operators in literals, grouping or escape sequences.
        expression = attribute[0][1:-1]
        if any('||' in literal[0] or '&&' in literal[0] for literal in re.finditer(STRING, expression)):
            return None
        choices = [
            [parse_test(term.strip()) for term in branch.split('&&')]
            for branch in expression.split('||')
        ]
        if any(test is None for choice in choices for test in choice):
            return None
        predicates.append(choices)
    return {'class': class_name or '*', 'tests': predicates}


def compile_selector(selector):
    """Keep selector terms and relationships whole. An unknown term rejects the branch."""
    path, relations, marks = [], [], []
    offset = 0
    while offset < len(selector):
        match = NODE.match(selector, offset)
        if match.end() == offset or not (node := parse_node(match)):
            return None
        if match[1]:
            marks.append(len(path))
        path.append(node)
        offset = match.end()
        rest = selector[offset:]
        if not rest:
            break
        if not rest[0].isspace():
            return None
        offset += len(rest) - len(rest.lstrip())
        if relation := RELATION.match(selector, offset):
            operator, slots = relation.groups()
            offset = relation.end()
        else:
            operator, slots = '>', 'n'
        positions = [] if slots == 'n' else [int(n) for n in (slots or '1').strip('()').split(',')]
        if offset == len(selector) or any(n > 12 for n in positions):
            return None
        relations.append([operator, positions])
    if len(marks) > 1 or not 1 <= len(path) <= 8:
        return None
    return {'path': path, 'relations': relations, 'target': marks[0] if marks else len(path) - 1}


def guaranteed(node, test):
    return any(all(any(test(*term) for term in choice) for choice in alternatives)
               for alternatives in node['tests'])


def resource(field, op, value, recognize):
    if not isinstance(value, str) or field not in ('id', 'vid', 'resourceName') or op not in ('=', '$='):
        return False
    if op == '$=' and '/' not in value:
        return False
    return recognize(value.rsplit('/', 1)[-1])


def confidence(package, selector, activities):
    path, target = selector['path'], selector['target']
    def skip(field, op, value):
        return resource(field, op, value, lambda name: (
            bool(SKIP_RESOURCE.search(name)) or name in REVIEWED_TARGETS.get(package, ())
        ) and (not FINANCIAL.search(package) or bool(re.search(r'splash|ad_', name, re.I))))
    if guaranteed(path[target], skip):
        return 'resource'
    if guaranteed(path[target], lambda field, op, value: (
        op == '=' and (field, value) in (
            ('text', '关闭广告'), ('text', '跳过广告'), ('desc', 'GdtCountDownView')
        )
    )):
        return 'label'
    if activities and all(AD_ACTIVITY.search(activity) for activity in activities):
        if guaranteed(path[target], lambda field, op, value: (
            field in ('text', 'desc') and op in ('=', '^=', '*=') and value in ('跳过', '关闭')
        )):
            return 'activity'
    # An anonymous clickable descendant at any depth could be the ad itself.
    if len(path) == 2 and selector['relations'] == [['<<', []]] and not guaranteed(
        path[target], lambda field, op, value: field in ('id', 'vid', 'resourceName', 'text', 'desc'),
    ):
        return None
    if any(i != target and guaranteed(node, lambda f, op, v: resource(f, op, v, lambda name: (
        bool(AD_CONTAINER.search(name)) or name in REVIEWED_ANCHORS.get(package, ())
    ))) for i, node in enumerate(path)):
        return 'structure'
    return None


def verified(path, digest):
    data = path.read_bytes()
    if hashlib.sha256(data).hexdigest() != digest:
        raise ValueError(f'Unexpected SHA256 for {path}')
    return data


def import_rules(data):
    entries = []
    for app in data['apps']:
        package = app['id']
        for group in app.get('groups', []):
            if group['name'] != '开屏广告' or group.get('enable') is False:
                continue
            raw_rules = group['rules']
            if isinstance(raw_rules, str) or (isinstance(raw_rules, list) and all(isinstance(r, str) for r in raw_rules)):
                raw_rules = [{'matches': raw_rules}]
            for rule_index, rule in enumerate(array(raw_rules)):
                if isinstance(rule, str):
                    rule = {'matches': rule}
                if not isinstance(rule, dict) or rule.get('enable') is False:
                    continue
                constraints = group | rule
                action = constraints.get('action', 'click')
                if action not in ('click', 'clickNode', 'clickCenter') or any(
                    constraints.get(key) for key in (
                        'position', 'swipeArg', 'preKeys', 'excludeAllMatches', 'versionCode',
                        'versionCodes', 'excludeVersionCodes', 'versionName', 'versionNames',
                        'excludeVersionNames', 'actionDelay', 'matchDelay', 'excludeMatches',
                    )
                ):
                    continue
                matches = array(rule.get('matches'))
                alternatives = array(rule.get('anyMatches'))
                if matches and alternatives:
                    continue
                required = [compile_selector(value) for value in matches[:-1]]
                if any(selector is None or len(selector['path']) != 1 for selector in required):
                    continue
                activities, excluded_activities = [
                    [package + value if value.startswith('.') else value for value in array(constraints.get(key))]
                    for key in ('activityIds', 'excludeActivityIds')
                ]
                for original in matches[-1:] or alternatives:
                    original = REVIEWED_ALTERNATIVES.get((package, original), original)
                    selector = compile_selector(original)
                    if not selector:
                        continue
                    evidence = confidence(package, selector, activities)
                    # A page-level positive anchor still requires an explicit skip label on the target.
                    if not evidence and required and any(
                        guaranteed(node, lambda f, op, v: resource(f, op, v, lambda name: (
                            bool(AD_CONTAINER.search(name)) or name in REVIEWED_ANCHORS.get(package, ())
                        ))) for requirement in required for node in requirement['path']
                    ) and guaranteed(selector['path'][selector['target']], lambda f, op, v: (
                        f in ('text', 'desc') and op in ('=', '^=', '*=') and v in ('跳过', '跳过 ')
                    )):
                        evidence = 'page'
                    if not evidence or (action == 'clickCenter' and evidence not in ('resource', 'page')):
                        continue
                    entry = dict(
                        package=package, name=app.get('name', package),
                        groupKey=group['key'], ruleIndex=rule_index, selector=original,
                        snapshots=array(rule.get('snapshotUrls', group.get('snapshotUrls'))),
                        evidence=evidence, targetSelector=selector,
                    )
                    if activities:
                        entry['activityIds'] = activities
                    if excluded_activities:
                        entry['excludeActivityIds'] = excluded_activities
                    if constraints.get('matchTime', 0) > 0:
                        entry['matchTime'] = constraints['matchTime']
                    if required:
                        entry['required'] = required
                        entry['requiredSelectors'] = matches[:-1]
                    if action == 'clickCenter':
                        entry['requireClickable'] = True
                    entries.append(entry)
    return entries


def import_reviewed_samples(path):
    """Compile narrow selectors from manually inspected, public startup snapshots."""
    entries = []
    for index, sample in enumerate(json.loads(path.read_text(encoding='utf-8'))):
        selector = compile_selector(sample['selector'])
        anchors = sample.get('requiredSelectors', [])
        required = [compile_selector(anchor) for anchor in anchors]
        if not selector or any(item is None or len(item['path']) != 1 for item in required):
            raise ValueError(f'Unsupported reviewed selector in {sample["package"]}')
        evidence = sample.get('evidence', 'structure')
        target = selector['path'][selector['target']]
        if evidence not in ('structure', 'resource', 'label') or (evidence == 'resource' and not guaranteed(
            target, lambda field, op, value: field == 'id' and op == '=' and isinstance(value, str)
                    and value.startswith(sample['package'] + ':id/')
        )):
            raise ValueError(f'Reviewed resource requires an exact app ID in {sample["package"]}')
        if evidence == 'label' and (sample['activity'] != 'com.google.android.gms.ads.AdActivity'
                or target['class'] != 'ImageButton' or not guaranteed(
                    target, lambda field, op, value: (field, op, value) == ('desc', '=', 'Interstitial close button')
                )):
            raise ValueError(f'Reviewed label requires the exact interstitial close control in {sample["package"]}')
        countdown_close = sample.get('adCountdownClose') is True
        touch_only = sample.get('touchOnly') is True
        if countdown_close and (evidence != 'structure' or not required):
            raise ValueError(f'Countdown close needs a visible ad anchor in {sample["package"]}')
        labelled_touch = guaranteed(target, lambda field, op, value: field in ('text', 'desc') and (
            (op in ('=', '^=') and value == '跳过') or (op == '=' and value == '跳过广告')
        ))
        baidu_touch = (sample['package'], sample['activity'], evidence) == (
            'com.baidu.BaiduMap', 'com.baidu.baidumaps.MapsActivity', 'resource',
        ) and guaranteed(target, lambda field, op, value: (
            field, op, value
        ) == ('id', '=', 'com.baidu.BaiduMap:id/ms_skipView'))
        if touch_only and (sample.get('tap') is not True or not required or not guaranteed(
            target, lambda field, op, value: (field, op, value) == ('childCount', '=', 0)
        ) or not (labelled_touch or baidu_touch)):
            raise ValueError(f'Reviewed touch control needs an identified leaf and visible ad anchor in {sample["package"]}')
        entry = dict(
            package=sample['package'], name=sample['name'], groupKey=-90, ruleIndex=index,
            selector=sample['selector'], snapshots=[sample['snapshot']], evidence=evidence,
            targetSelector=selector, activityIds=[sample['activity']], matchTime=10_000,
            requireClickable=not touch_only, required=required, requiredSelectors=anchors,
            reference=dict(versionName=sample['versionName'], snapshotSha256=sample['sha256']),
        )
        if sample.get('tap') is True:
            entry['tap'] = True
        if countdown_close:
            entry['adCountdownClose'] = True
        entries.append(entry)
    return entries


def main():
    source = Path(sys.argv[1]) if len(sys.argv) > 1 else VENDOR / 'ganlin_gkd.json5'
    raw = verified(source, SOURCE_SHA)
    license_path = source.parent / 'LICENSE'
    license_data = verified(license_path if license_path.exists() else VENDOR / 'LICENSE', LICENSE_SHA)
    data = json5.loads(raw.decode('utf-8'))
    entries = import_rules(data) + import_reviewed_samples(VENDOR / 'startup-samples.json')
    # App Market 16.1.11.302 replaced tv_operation_skip. Its ad-only layout
    # and AdSplashScreen both use btn_skip under the same MainActivity.
    honor_skip = '[id="com.hihonor.appmarket:id/btn_skip"][clickable=true]'
    entries.append(dict(
        package='com.hihonor.appmarket', name='荣耀应用市场', groupKey=0, ruleIndex=1,
        selector=honor_skip, evidence='activity', targetSelector=compile_selector(honor_skip),
        activityIds=['com.hihonor.appmarket.module.main.MainActivity'], matchTime=10_000,
        reference=dict(
            versionName='16.1.11.302',
            apkSha256='7bba4ae416bb758286986b9e419661fc0bf3ea3666f84eaae93a04cb9dcc7dc2',
            layout='layout_splash_level_top',
        ),
    ))
    # Tencent Video's splash skip TextView has no resource ID and uses a touch listener.
    # The audited ad template builds this leaf without an ID or a click listener.
    # Require one of that template's native ad badges in the ordinary home activity.
    video_skip = 'TextView[text^="跳过"][text.length<=10][id=null][childCount=0]'
    video_badge = 'TextView[text="广告"||text="广告 | 已Wi-Fi预加载"||text="已Wi-Fi预加载"]'
    for index, (activity, badge) in enumerate((
        ('com.tencent.qqlive.ona.activity.SplashHomeActivity', video_badge),
        ('com.tencent.qqlive.qadsplash.template.QAdHotSplashActivity', None),
    )):
        entry = dict(
            package='com.tencent.qqlive', name='腾讯视频', groupKey=0, ruleIndex=index,
            selector=video_skip, evidence='page' if badge else 'activity',
            targetSelector=compile_selector(video_skip), activityIds=[activity], matchTime=10_000, tap=True,
            reference=dict(
                versionName='8.10.80.26895',
                apkSha256='8445fe47ca3b5c632cc9ccb0eaf21f3cde56eb865b8bc54f085cfb0d62126e30',
            ),
        )
        if badge:
            entry.update(required=[compile_selector(badge)], requiredSelectors=[badge])
        entries.append(entry)
    sdk = []
    for resource_name in SDK_RESOURCES:
        evidence = next(entry for entry in entries if len(entry['targetSelector']['path']) == 1
                        and f':id/{resource_name}' in entry['selector'])
        sdk.append(dict(
            resourceName=resource_name, name='广告 SDK 开屏跳过控件',
            evidencePackage=evidence['package'], snapshots=evidence['snapshots'],
        ))
    huawei_evidence = [entry for entry in entries if entry['selector'] == f'[id="{HUAWEI_SKIP_ID}"]'
                       and entry.get('requireClickable') and entry['snapshots']]
    assert len({entry['package'] for entry in huawei_evidence}) >= 2
    sdk.append(dict(
        clickableResourceId=HUAWEI_SKIP_ID, name='华为广告 SDK 开屏跳过控件',
        evidencePackages=[entry['package'] for entry in huawei_evidence],
        snapshots=[url for entry in huawei_evidence for url in entry['snapshots']],
    ))
    out = dict(
        source='https://github.com/ganlinte/GKD-subscription', sourcePath='dist/ganlin_gkd.json5',
        sourceCommit=SOURCE_COMMIT, sourceSha256=SOURCE_SHA, version=data['version'], license='MIT', retrieved='2026-11-03',
        rules=entries, sdkRules=sdk,
    )
    (ASSETS / 'splash-rules.json').write_text(
        json.dumps(out, ensure_ascii=False, indent=2) + '\n', encoding='utf-8',
    )
    (VENDOR / 'ganlin_gkd.json5').write_bytes(raw)
    (VENDOR / 'LICENSE').write_bytes(license_data)
    (ASSETS / 'splash-license.txt').write_bytes(license_data)
    print(json.dumps(dict(rules=len(entries), apps=len({x['package'] for x in entries}), sdkRules=len(sdk))))


if __name__ == '__main__':
    main()
