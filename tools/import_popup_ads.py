"""Compile evidence-backed ad close rules from the pinned MIT subscription."""
import json
import re

import json5

from import_splash import (
    ASSETS, FINANCIAL, LICENSE_SHA, SOURCE_SHA, VENDOR, array, compile_selector, guaranteed, verified,
)

SOURCE = 'https://github.com/ganlinte/GKD-subscription'
GROUP_FIELDS = {
    'key', 'name', 'desc', 'enable', 'rules', 'activityIds', 'excludeActivityIds', 'fastQuery',
    'snapshotUrls', 'exampleUrls', 'order',
}
RULE_FIELDS = {
    'key', 'name', 'desc', 'enable', 'matches', 'anyMatches', 'activityIds', 'excludeActivityIds',
    'fastQuery', 'snapshotUrls', 'exampleUrls', 'action',
}
SENSITIVE = re.compile(
    r'隐私|权限|授权|定位提醒|通知提示|请求通知|开启通知|订单|付款|支付|续费|收款|安装|登录|'
    r'更新|升级|青少年|未成年|实名认证|评价弹窗|调查|反馈|激励|领奖|奖励|关注|评论|分享|退出|'
    r'privacy|permission|login|checkout|reward', re.I,
)
PROTECTED_PACKAGE = re.compile(r'settings|systemui|securitycenter|systemmanager|wallet|banking', re.I)
AD_WORDS = {'广告', '廣告', '推广', '推廣', 'ad', 'ads', 'advertisement', '腾讯广告', '优量汇广告'}
AD_CLOSE_WORDS = {'关闭广告', '關閉廣告', '关闭此广告', '關閉此廣告', '跳过广告', '跳過廣告',
                  '跳过此广告', '跳過此廣告', 'close ad', 'close advertisement', 'skip ad'}
CLOSE_WORDS = AD_CLOSE_WORDS | {'关闭', '關閉', '关闭弹窗', '取消', '跳过', '跳過', '跳过视频',
                              '不感兴趣', '不感興趣', 'close', 'dismiss', 'skip', 'not interested', '×'}
AD_ACTIVITIES = re.compile(r'(?:ads?|advert|interstitial)(?:portrait|landscape|full)?(?:video)?activity$', re.I)
AD_NAMES = {'ad', 'ads', 'advert', 'advertisement', 'interstitial', 'advertise'}
DISMISS_NAMES = {'close', 'dismiss', 'skip', 'cancel'}

# These exact source branches have a documented ad-only sibling or overlay anchor.
# Match the source spelling as well as the identifiers so a future source cannot reuse the review.
REVIEWED = {
    ('com.sdu.didi.psnger', 1, 0):
        'ImageView[id=`com.sdu.didi.psnger:id/second_entrance_img`] + '
        '@ImageView[id=`com.sdu.didi.psnger:id/left_close_img`]',
    ('com.sankuai.meituan', 4, 0): 'FrameLayout > @ImageView + ImageView[desc="资质与规则"]',
    ('com.sankuai.meituan', 2, 1):
        '@ImageView < ViewGroup +3 ViewGroup > ViewGroup > TextView[text^="红包已到账"]',
    ('me.ele', 2, 1): '[desc$="今日红包"] +(n) [desc$="关闭"][clickable=true]',
    ('com.autonavi.minimap', 12, 0):
        '@ImageView < [desc="关闭"][clickable=true] - ViewGroup > ViewGroup > ViewGroup > View[text="立即领取"]',
}


def text_test(words):
    return lambda field, op, value: (field in ('text', 'desc') and op == '='
                                    and isinstance(value, str) and value.lower() in words)


def id_tokens(field, op, value):
    if field not in ('id', 'vid', 'resourceName') or op != '=' or not isinstance(value, str):
        return set()
    name = value.rsplit('/', 1)[-1]
    name = re.sub(r'([a-z0-9])([A-Z])', r'\1_\2', name)
    return set(re.findall(r'[a-z]+', name.lower()))


def ad_close_resource(field, op, value):
    parts = id_tokens(field, op, value)
    return bool(parts & AD_NAMES and parts & DISMISS_NAMES and not parts & {'reward', 'feedback', 'more', 'menu'})


def close_resource(field, op, value):
    return bool(id_tokens(field, op, value) & DISMISS_NAMES)


def ad_marker(field, op, value):
    return text_test(AD_WORDS)(field, op, value) or bool(
        id_tokens(field, op, value) & AD_NAMES and id_tokens(field, op, value) & {'logo', 'mark', 'badge', 'container'},
    )


def confidence(selector, activities):
    path, target = selector['path'], selector['target']
    if guaranteed(path[target], text_test(AD_WORDS)):
        return None
    if guaranteed(path[target], ad_close_resource):
        return 'ad-close-resource'
    if guaranteed(path[target], text_test(AD_CLOSE_WORDS)):
        return 'ad-close-label'
    if not guaranteed(path[target], lambda *term: text_test(CLOSE_WORDS)(*term) or close_resource(*term)):
        return None
    if any(i != target and guaranteed(node, ad_marker) for i, node in enumerate(path)):
        return 'related-ad-marker'
    if activities and all(AD_ACTIVITIES.search(value) for value in activities):
        return 'ad-activity'
    return None


def protected(package):
    return (package.startswith(('com.android.', 'com.google.android.gms', 'com.google.android.gsf'))
            or package in ('android', 'app.clearscreen') or FINANCIAL.search(package)
            or PROTECTED_PACKAGE.search(package) or 'permissioncontroller' in package or 'packageinstaller' in package)


def compile_popup_selector(value):
    # GKD accepts the parenthesized any-distance form as well as the shorthand n.
    return compile_selector(re.sub(r'(?<=\s)([<>+-])\(n\)(?=\s)', r'\1n', value.strip()))


def import_popup_rules(source):
    entries = []
    for app in source['apps']:
        package = app['id']
        if protected(package) or set(app) - {'id', 'name', 'groups'}:
            continue
        for group in app.get('groups', []):
            if (set(group) - GROUP_FIELDS or not group['name'].startswith(('全屏广告', '局部广告'))
                    or SENSITIVE.search(group['name'])):
                continue
            # Upstream popup groups are optional. The app's popup toggle is the separate opt-in.
            raw_rules = array(group['rules'])
            predecessors = {key for item in raw_rules if isinstance(item, dict) for key in array(item.get('preKeys'))}
            for index, raw in enumerate(raw_rules):
                rule = {'matches': raw} if isinstance(raw, str) else raw
                if (not isinstance(rule, dict) or set(rule) - RULE_FIELDS or rule.get('enable') is False
                        or rule.get('key') in predecessors
                        or SENSITIVE.search(rule.get('name', '')) or rule.get('action', 'click') not in ('click', 'clickNode')):
                    continue
                props = group | rule
                activities, excludes = [
                    [package + value if value.startswith('.') else value for value in array(props.get(field))]
                    for field in ('activityIds', 'excludeActivityIds')
                ]
                if any(SENSITIVE.search(activity) for activity in activities):
                    continue
                snapshots = array(props.get('snapshotUrls'))
                if not snapshots:
                    continue
                matches, alternatives = array(rule.get('matches')), array(rule.get('anyMatches'))
                if matches and alternatives:
                    continue
                requirements = [compile_popup_selector(value) for value in matches[:-1]]
                if any(not value for value in requirements):
                    continue
                for original in matches[-1:] or alternatives:
                    selector = compile_popup_selector(original)
                    if not selector:
                        continue
                    evidence = confidence(selector, activities)
                    if REVIEWED.get((package, group['key'], index)) == original and activities:
                        evidence = 'reviewed-ad-path'
                    if not evidence:
                        continue
                    entries.append(dict(
                        package=package, name=app['name'], group=group['name'], groupKey=group['key'], ruleIndex=index,
                        selector=original, snapshots=snapshots, evidence=evidence, targetSelector=selector,
                        activityIds=activities, excludeActivityIds=excludes,
                        required=requirements, requiredSelectors=matches[:-1],
                    ))
    return entries


def main():
    raw = verified(VENDOR / 'ganlin_gkd.json5', SOURCE_SHA)
    verified(VENDOR / 'LICENSE', LICENSE_SHA)
    source = json5.loads(raw.decode('utf-8'))
    rules = import_popup_rules(source)
    # Independently scoped from the documented ad marker/claim control relationship.
    # The native close is evaluated only with both anchors in the same ad branch.
    ctrip = '@ImageView[clickable=true][visibleToUser=true] < RelativeLayout + RelativeLayout >5 [desc^="广告"] + [text="立即领取"]'
    compiled = compile_popup_selector(ctrip)
    assert compiled
    rules.append(dict(
        package='ctrip.android.view', name='携程旅行', group='首页广告弹窗',
        activityIds=['ctrip.android.publicproduct.home.view.CtripHomeActivity'],
        matchTimeMs=10_000, maxPerVisit=1,
        reference='https://github.com/AIsouler/GKD_subscription/blob/main/src/apps/ctrip.android.view.ts',
        snapshots=['https://i.gkd.li/i/16357287'], evidence='reviewed-ad-path',
        selector=ctrip, targetSelector=compiled, required=[],
    ))
    manifest = dict(source=SOURCE, sourceSha256=SOURCE_SHA, version=source['version'], license='MIT', rules=rules)
    (ASSETS / 'popup-ad-rules.json').write_text(
        json.dumps(manifest, ensure_ascii=False, separators=(',', ':')) + '\n', encoding='utf-8',
    )
    print(json.dumps(dict(rules=len(rules), apps=len({rule['package'] for rule in rules}))))


if __name__ == '__main__':
    main()
