"""Apply reviewed mobile route variants to the checked-in base rules; idempotent.

These are candidates from the pinned upstream snapshot, NOT real OEM verification.
Only explicit advertising/recommendation switches are imported. Unknown UI is skipped.
"""
import copy
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
ASSETS = ROOT / 'app/src/main/assets'
rules = json.loads((ASSETS / 'rules.json').read_text(encoding='utf-8-sig'))
by_id = {r['id']: r for r in rules}
MI = ['xiaomi', 'redmi', 'poco']
OP = ['oppo', 'oneplus', 'realme']
VI = ['vivo', 'iqoo']
HO = ['honor']
mi = 'recipes/xiaomi-2304FPN6DC/recipe.py'
mi15 = 'recipes/xiaomi-24129PN74C/recipe.py'
redmi = 'recipes/redmi-23113RKC6C/recipe.py'
op = 'recipes/oppo-PLG110/recipe.py'
op17 = 'recipes/oppo-PKT110/recipe.py'
ho = 'recipes/honor-WKL-AN20/recipe.py'
ho10 = 'recipes/honor-PNM-AN10/recipe.py'
vi = 'recipes/vivo-V2324HA/recipe.py'


def steps(*names):
    return [n if isinstance(n, (dict, list)) else [n] for n in names]


def route(pkg, path=(), activity='', source='', **extras):
    return dict(package=pkg, path=list(path), activity=activity, source=source,
                name='设置直达入口' if activity else '应用菜单入口', **extras)


def base(r):
    return route(r['package'], r.get('path', []), r.get('activity', ''), r['source'])


def variants(id, *extras, first=False):
    r = by_id[id]
    routes = [base(r)] + list(extras)
    if first:
        routes = list(extras) + [base(r)]
    # No proliferation on repeated runs.
    unique = {json.dumps(x, ensure_ascii=False, sort_keys=True): x for x in routes}
    r['routes'] = list(unique.values())


def add(id, title, pkg, path, targets, brands, source, activity='', **extra):
    r = dict(id=id, title=title, package=pkg, path=path, targets=targets,
             brands=brands, source=source, activity=activity, group='系统推荐', **extra)
    if id in by_id:
        by_id[id].clear(); by_id[id].update(r)
    else:
        rules.append(r); by_id[id] = r
    return r


# MIUI / HyperOS: prefer the public activity if exported; otherwise ordinary menus.
variants('mi-system', route('com.android.settings', activity='.ad.AdServiceSettings', source=mi15),
         route('com.android.settings', steps('密码与安全', ['系统安全', '隐私'], '广告服务'), source=mi, reference='https://terms.miui.com/doc/privacy/cn.html'),
         route('com.android.settings', steps('安全', ['更多设置', '更多安全设置'], '广告服务'), source=mi, reference='https://privacy.mi.com/support/faq?locale=zh-cn'),
         route('com.android.settings', steps('隐私与安全', ['更多安全设置', '更多设置'], '广告服务'), source=mi), first=True)
by_id['mi-system']['routes'][-1]['path'][2] = ['更多安全设置', '更多设置']
variants('mi-store', route('com.xiaomi.market', steps('我的', '设置', ['隐私设置', '隐私']), source=redmi))
for id in ['mi-cleaner', 'mi-manager-privacy']:
    r = by_id[id]
    variants(id, route(r['package'], steps('设置') + r['path'], source=mi15))
variants('mi-download', route('com.android.providers.downloads.ui', steps('更多', '设置', '信息流设置'), source=mi15), first=True)
variants('mi-theme', route('com.miui.themestore', by_id['mi-theme']['path'], source=redmi, foreground='com.android.thememanager'))
browser_entry = steps(['菜单', '我的'], '设置')
for id, page in [('mi-browser', '隐私和安全'), ('mi-browser-grid', '主页设置'), ('mi-browser-search', '搜索设置')]:
    variants(id, route('com.android.browser', browser_entry + steps(page), source=mi15))
by_id['mi-browser-search']['aliases'] = {'猜你想搜': ['显示猜你想搜模块']}
add('mi-browser-tasks', '浏览器金币营销任务', 'com.android.browser', browser_entry + steps('功能设置'), ['运营任务'], MI, mi15)
add('mi-weather', '天气视频与内容推广', 'com.miui.weather2', steps('更多设置', '设置', '用户体验计划'), ['天气视频卡片', '内容推广'], MI, redmi)
variants('mi-weather', route('com.miui.weather2', steps('设置', '用户体验计划'), source=mi15))
add('mi-calendar', '日历内容推广', 'com.android.calendar', steps('设置', '设置', '用户体验计划'), ['内容推广'], MI, redmi)
variants('mi-calendar', route('com.android.calendar', steps('设置', '用户体验计划'), source=mi15))
for id in ['mi-search', 'mi-search-apps']:
    variants(id, route('com.android.quicksearchbox', steps(['更多', '菜单'], '设置') + by_id[id]['path'], source=mi))

# ColorOS: settings URI is explicitly scoped to the search package, never an arbitrary URL.
variants('op-store-privacy', route('com.heytap.market', steps('我的', '设置', '隐私授权管理', '个性化推荐'), source=op),
         route('com.heytap.market', steps('我的', '设置', '隐私', '个性化推荐'), source=by_id['op-store-privacy']['source']))
by_id['op-store-promo']['targets'] = ['首页：悬浮活动图标', '首页：活动弹窗', '首页：顶部轮播卡片', '底部活动专区',
    '游戏详情页：游戏活动卡片', '游戏详情页：游戏礼包', '软件专区：福利', '我的专区：签到按钮', '我的专区：积分商城',
    '我的专区：安装有礼', '我的专区：福利中心', '我的专区：金豆乐园', '更新&下载专区：底部弹窗推荐',
    '活动式详情页', '详情页：活动', '详情页：限时活动', '手机管家：应用推荐', '文件管理：应用推荐', '福利专区']
by_id['op-store-promo']['source'] = op
# Disable child before its parent where exposed; a disabled child stays a reported skip.
by_id['op-browser']['targets'] = ['摇一摇广告', '开屏广告', '资讯列表广告']
variants('op-browser', route('com.heytap.browser', steps('隐私', '广告管理'), '.settings.component.BrowserPreferencesPage', op17), first=True)
add('op-browser-personal', '浏览器搜索与广告推荐', 'com.heytap.browser', steps('隐私', '个性化管理'),
    ['个性化搜索推荐', '个性化广告推荐'], OP, op, '.settings.component.BrowserPreferencesPage')
variants('op-browser-personal', route('com.heytap.browser', steps(['我的', '菜单', '更多'], '设置', '隐私', '个性化管理'), source=op))
add('op-video-personal', '视频内容与广告推荐', 'com.heytap.yoli', steps('我的', '设置', '个性化管理'),
    ['个性化广告推荐', '个性化内容推荐'], OP, op17)
for id, title, path, targets, source in [
    ('op-search-home', '搜索首页推广卡片', steps('首页卡片管理'), ['热搜应用', '精选推荐', '热搜榜单', '活动皮肤', '推荐卡片', '未下载应用推荐'], op17),
    ('op-search-source', '搜索内容推荐', steps('搜索范围'), ['个性化推荐', '精选资讯', '搜索建议'], op17),
    ('op-search-hint', '搜索框推广提示词', steps('功能管理'), ['搜索框提示词'], op),
    ('op-search-personal', '搜索个性化推荐', steps('隐私'), ['个性化推荐'], op),
]:
    r = add(id, title, 'com.heytap.quicksearchbox', path, targets, OP, source)
    r['routes'] = [route(r['package'], path, source=source, uri='gs://search/setting')]
# Precise cross-package transition after 小布建议. Never expand a global foreground allowlist.
advice = 'com.oplus.pantanal.ums'
advice_path = steps('OPPO AI', {'labels': ['小布建议'], 'package': advice}, '资讯服务')
by_id['op-ai']['path'] = advice_path
by_id['op-ai']['source'] = op
variants('op-ai', route(advice, steps('资讯服务'), 'com.oplus.advice.settings.ui.AdviceSettingActivity', op))
for id, title, target in [('op-ai-daily', '小布视界日报', '视界日报'), ('op-ai-news', '小布资讯推荐', '小红书资讯')]:
    add(id, title, 'com.android.settings', advice_path + steps(target), [target], OP, op17)
# Some devices expose only web/drawn controls; retain exact instructions, never claim auto success.
add('op-theme-manual', '新版主题商店推荐设置', 'com.heytap.themestore', steps('我的', '设置', '隐私'),
    ['个性化推荐设置'], OP, op17, manual=True,
    explanation='部分版本关闭后会弹窗并返回上一页。请手动关闭「个性化推荐设置」，再重新进入隐私页核对；不要切换服务模式或同意新协议。')

# OriginOS / iQOO: combine the two hot-search entries into a semantic rule.
variants('v-hot', route('com.vivo.globalsearch', steps('首页展示内容', '推荐热搜榜单'), '.view.SearchSettingsActivity', 'recipes/vivo-V2547A/recipe.py'))
rules = [r for r in rules if r['id'] != 'v-hot-nested']
for id in ['v-search', 'v-hot', 'v-search-personal']:
    r = by_id[id]
    candidates = r.get('routes', [base(r)]) if id == 'v-hot' else [base(r)]
    candidates.append(route('com.android.settings', steps('系统管理与升级', {'labels': ['全局搜索'], 'package': r['package']}) + r['path'], source=vi))
    r['routes'] = candidates
by_id['v-system']['path'][0] = ['安全', '安全与隐私']
by_id['v-store-promo']['aliases'] = {'悬浮窗': ['悬浮球']}
variants('v-store-promo', route('com.bbk.appstore', by_id['v-store-promo']['path'], source='recipes/vivo-V1986A/recipe.py'))
by_id['v-browser-more']['targets'] = ['WiFi连接跳转新闻资讯', '下载管理页展示热门应用', '下载管理页展示下载热榜']
by_id['v-browser-more']['aliases'] = {'WiFi连接跳转新闻资讯': ['WiFi 连接跳转新闻资讯', '跳转新闻资讯']}
variants('v-browser-more', route('com.vivo.browser', by_id['v-browser-more']['path'], source=vi))

# MagicOS, including both current browser package families.
add('h-system', '系统智能推荐服务', 'com.hihonor.id', [], ['智能推荐服务'], HO, ho, '.oaid.ui.IntelligentRecommendActivity')
variants('h-system', route('com.android.settings', steps('隐私和安全', '其他隐私和安全功能',
         {'labels': ['广告与隐私'], 'package': 'com.hihonor.id'}, '智能推荐服务'), source=ho10))
add('h-manager', '系统管家应用推荐', 'com.hihonor.systemmanager', [], ['应用推荐'], HO, ho, '.mainscreen.SettingActivity')
# System Manager 15.0.0.347 forwards its public launcher to Device Manager 15.0.0.316.
# Device Manager's setting_menu listener opens the original package's protected SettingActivity.
variants('h-manager', route('com.hihonor.systemmanager', steps({
    'labels': ['@id:com.hihonor.devicemanager:id/setting_menu'],
    'package': 'com.hihonor.systemmanager',
}), source=ho10, foreground='com.hihonor.devicemanager'), first=True)
by_id['h-manager']['routes'].append(route('com.hihonor.systemmanager', steps('设置'),
    source=ho10, skipWhenPackagePresent='com.hihonor.devicemanager'))
add('h-store-hot', '应用市场热门推荐', 'com.hihonor.appmarket', steps('我的', '设置', '热门推荐管理'), ['热门推荐管理'], HO, ho)
add('h-browser-search', '浏览器搜索推荐', 'com.hihonor.baidu.browser', steps('我的', '设置', '隐私设置'), ['搜索个性化推荐', '搜索下一条推荐'], HO, ho10)
add('h-browser-web-manual', '浏览器网页广告偏好', 'com.hihonor.baidu.browser', steps('我的', '设置', '隐私设置', '偏好设置', '广告设置'),
    ['不看程序化广告', '不看个性化广告'], HO, ho10, manual=True,
    explanation='此页为网页开关，无障碍无法可靠回读。请手动打开「不看程序化广告」「不看个性化广告」，注意这里的目标是打开。')
for id, title, path, targets in [
    ('h-browser-classic-push', '荣耀浏览器资讯推送', steps('更多', '设置', '消息通知'), ['精彩推荐', '每日推送', '新闻热点', '兴趣推送']),
    ('h-browser-classic-personal', '荣耀浏览器个性化内容', steps('更多', '设置', '隐私管理', '个性化推荐'), ['个性化内容']),
    ('h-browser-classic-ads', '荣耀浏览器互动广告', steps('更多', '设置', '隐私管理', '互动广告管理'), ['个性化广告', '允许摇一摇进入广告']),
]:
    add(id, title, 'com.honor.browser', path, targets, HO, ho)
add('h-theme', '主题内容与广告推荐', 'com.hihonor.android.thememanager', steps('我的', '设置', '个性化服务'), ['个性化内容', '个性化广告'], HO, ho)
add('h-video', '视频广告与内容推荐', 'com.hihonor.youku.video', steps('其他设置'), ['个性化内容', '个性化广告', '允许视频采集OAID推荐广告'], HO, ho,
    'com.youku.android.youkusetting.activity.SettingsActivity')
variants('h-video', route('com.hihonor.youku.video', steps('我的', '设置', '其他设置'), source=ho10))
add('h-search', '荣耀搜索热榜', 'com.hihonor.search', steps('更多', '设置', '首页显示'), ['热搜榜', '热搜词推荐'], HO, ho,
    manual=True, explanation='系统搜索通常没有可直接打开的应用入口。请从桌面下拉搜索，进入「更多 → 设置 → 首页显示」，关闭热搜榜、热搜词推荐；保留搜索功能。')
add('h-assistant-manual', '负一屏推荐与广告', 'com.hihonor.hiboard', steps('头像', '设置'), ['智能推荐', '个性化广告', '个性化消息'], HO, ho,
    manual=True, explanation='请在桌面滑至负一屏，从头像进入设置关闭列出的推荐项。资讯精选可在头像 → 资讯服务内关闭。保留日程、快递与常用功能卡片。')
music_path = steps('我的', ['设置', '@id:com.hihonor.cloudmusic:id/ic_setting'], '更多设置', '消息和隐私设置')
add('h-music', '音乐动态与摇一摇广告', 'com.hihonor.cloudmusic', music_path, ['朋友页显示推荐动态', '展示“摇一摇”类开屏广告'], HO, ho)
variants('h-music', route('com.hihonor.cloudmusic', music_path, source=ho10))
add('h-music-ads', '音乐个性化广告', 'com.hihonor.cloudmusic', music_path + steps('广告推荐设置'), ['个性化广告推荐'], HO, ho)
add('h-reader-manual', '阅读个性化广告', 'com.hihonor.dz.reader', steps('我的', '更多设置', '广告推荐服务'), ['个性化广告'], HO, ho,
    manual=True, explanation='此页使用自绘开关，无法可靠读取状态。请手动关闭个性化广告，保留接收新通知与阅读功能。')

# Newer pinned upstream variants. Explicit labels only; unnamed toggles are excluded.
op_new = 'recipes/oppo-PLP120/recipe.py'
vi_new = 'recipes/vivo-V2547DA/recipe_android17.py'
by_id['op-browser']['aliases'] = {'摇一摇广告': ['摇一摇']}
by_id['op-browser']['routes'].append(route('com.heytap.browser', steps(['我的', '菜单', '更多'], '设置', '隐私', '广告管理'), source=op_new))
add('op-theme-splash', '主题商店开屏广告', 'com.heytap.themestore', steps('我的', '设置', '服务管理', '消息与广告'), ['开屏广告'], OP, op_new)
# Content-source switches and generic search suggestions are not unambiguous advertising.
by_id['op-search-source']['targets'] = ['个性化推荐', '精选资讯']
for id in ['v-store', 'v-store-promo']:
    r = by_id[id]
    extras = [route(r['package'], r['path'][2:], '.ui.menu.AppstoreSettingsActivity', vi_new)]
    if id == 'v-store-promo': extras.append(route(r['package'], r['path'], source='recipes/vivo-V1986A/recipe.py'))
    variants(id, *extras)
for id in ['v-browser-home', 'v-browser-web', 'v-browser-more', 'v-browser-privacy']:
    r = by_id[id]
    variants(id, route(r['package'], steps('我的', '设置') + r['path'][2:], source='recipes/vivo-V2417A/recipe.py'))

# Route provenance remains public and static; package visibility is scoped to bundled targets.
packages = {'com.android.settings'}
for r in rules:
    for rt in r.get('routes', [base(r)]):
        packages.add(rt['package']); packages.add(rt.get('foreground', rt['package']))
        for step in rt['path']:
            if isinstance(step, dict) and step.get('package'):
                packages.add(step['package'])
(ASSETS / 'rules.json').write_text(json.dumps(rules, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
manifest_path = ROOT / 'app/src/main/AndroidManifest.xml'
manifest = manifest_path.read_text(encoding='utf-8')
start, end = manifest.index('    <queries>'), manifest.index('</queries>') + len('</queries>')
queries = '    <queries>\n        <intent><action android:name="android.intent.action.MAIN"/><category android:name="android.intent.category.LAUNCHER"/></intent>\n'
queries += ''.join(f'        <package android:name="{pkg}"/>\n' for pkg in sorted(packages)) + '    </queries>'
manifest_path.write_text(manifest[:start] + queries + manifest[end:], encoding='utf-8')
print(f'{len(rules)} semantic rules, {sum(len(r.get("routes", [r])) for r in rules)} routes, '
      f'{sum(len(r["targets"]) for r in rules if not r.get("manual"))} automatic target definitions')
