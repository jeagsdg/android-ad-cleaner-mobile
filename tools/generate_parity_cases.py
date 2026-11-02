"""Regenerate the cross-language classification fixture from the pinned upstream."""
import json
from import_rules import ROOT, policy

names = ['活动优惠', '内容与营销', '运营消息', '天气预警', '订单提醒', '支付通知',
         '机位推荐', '找机位活动', '其他', '普通通知', '资讯推荐', '热点和网络共享',
         '广告', '新闻', '权限建议', '直播提醒', '会员权益']
ids = ['promo', 'VPushChannel_0', 'VPushChannel_4', 'ADroiUnion', 'TencentAdNetCH1', 'normal']
cases = []
for name in names:
    for channel_id in ids:
        result = policy.classify(name, channel_id, package='com.example.app', app='测试应用')
        cases.append(dict(name=name, id=channel_id, package='com.example.app', app='测试应用',
                          candidate=result.marketing_candidate, excluded=result.scan_excluded,
                          uncertain=result.default_unchecked, protected=result.protected))
path = ROOT / 'artifacts/policy-cases.json'
path.parent.mkdir(parents=True, exist_ok=True)
path.write_text(json.dumps(cases, ensure_ascii=False), encoding='utf-8')
print(f'Generated {len(cases)} parity cases')
