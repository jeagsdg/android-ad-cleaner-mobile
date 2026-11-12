"""Compile evidence-backed ad close rules from the pinned MIT subscription."""
import json
import re

import json5

from import_splash import (
    ASSETS, FINANCIAL, LICENSE_SHA, SOURCE_COMMIT, SOURCE_SHA, VENDOR, array, compile_selector, guaranteed, verified,
)

SOURCE = 'https://github.com/ganlinte/GKD-subscription'
GROUP_FIELDS = {
    'key', 'name', 'desc', 'enable', 'rules', 'activityIds', 'excludeActivityIds', 'fastQuery',
    'snapshotUrls', 'exampleUrls', 'order', 'matchTime', 'actionMaximum', 'resetMatch',
}
RULE_FIELDS = {
    'key', 'name', 'desc', 'enable', 'matches', 'anyMatches', 'activityIds', 'excludeActivityIds',
    'fastQuery', 'snapshotUrls', 'exampleUrls', 'action', 'matchTime', 'actionMaximum', 'resetMatch',
}
SENSITIVE = re.compile(
    r'隐私|权限|授权|定位提醒|通知提示|请求通知|开启通知|订单|付款|支付|续费|收款|安装|登录|'
    r'更新|升级|青少年|未成年|实名认证|评价|评分|好评|调查|反馈|激励|领奖|奖励|关注|分享|退出|'
    r'privacy|permission|login|checkout|reward', re.I,
)
PROTECTED_PACKAGE = re.compile(r'settings|systemui|securitycenter|systemmanager|wallet|banking', re.I)
AD_WORDS = {'广告', '廣告', '推广', '推廣', 'ad', 'ads', 'advertisement', '腾讯广告', '优量汇广告'}
AD_CLOSE_WORDS = {'关闭广告', '關閉廣告', '关闭此广告', '關閉此廣告', '跳过广告', '跳過廣告',
                  '跳过此广告', '跳過此廣告', 'close ad', 'close advertisement', 'skip ad',
                  'skip ads', 'skip advertisement'}
CLOSE_WORDS = AD_CLOSE_WORDS | {'关闭', '關閉', '关闭弹窗', '取消', '跳过', '跳過', '跳过视频',
                              '直接关闭', '关闭弹层', '不感兴趣', '不感興趣', 'close', 'dismiss', 'skip',
                              'not interested', '×'}
AD_ACTIVITIES = re.compile(
    r'(?:Ads?|ADS?|Advert(?:ising)?|Interstitial)(?:(?i:portrait|landscape|full|video|dialog|popup|webview))*Activity$',
)
AD_NAMES = {'ad', 'ads', 'advert', 'advertisement', 'interstitial', 'advertise', 'advertising', 'adview'}
AD_CONTEXT_IDS = {
    'logo', 'mark', 'badge', 'container', 'frame', 'layout', 'tag', 'label',
    'title', 'text', 'view', 'image', 'img', 'icon', 'flag', 'portrait', 'landscape',
}
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
    ('com.qiyi.video', 2, 0):
        '@[id="com.qiyi.video:id/unused_res_a"][clickable=true] > [id="com.qiyi.video:id/close"]',
    ('com.netease.cloudmusic', 2, 0):
        '[id="com.netease.cloudmusic:id/auto_scroll_switcher"] + [id="com.netease.cloudmusic:id/close_btn"]',
    ('com.youku.phone', 3, 0): '[id="com.youku.phone:id/closeView"][desc="关闭弹层"]',
    ('com.tencent.qqmusic', 2, 0): 'View[id="js_close_btn"][desc="关闭"]',
    ('com.baidu.netdisk', 2, 0): '[id="com.baidu.netdisk:id/banner_item_close"]',
    ('com.baidu.searchcraft', 1, 0): '[desc="ad-logo"] + [id="logo-close-btn"][clickable=true]',
    # Paired promotion/layout and close controls in the snapshot's own activity.
    ('com.lxt.cfmoto', 1, 0):
        'RelativeLayout[childCount=2] > [id="com.lxt.cfmoto:id/image"] + [id="com.lxt.cfmoto:id/closeClick"]',
    ('youqu.android.todesk', 1, 0):
        '[id="youqu.android.todesk:id/sivDeviceBanner"] + [id="youqu.android.todesk:id/llClose"]',
    ('cn.damai', 1, 0):
        '[id="cn.damai:id/homepage_popup_window_image"] + [id="cn.damai:id/homepage_popup_window_close_btn"]',
    ('com.maidu.gkld', 2, 0):
        '[id="com.maidu.gkld:id/fl_float"] + [id="com.maidu.gkld:id/close_image_view"]',
    ('com.jingyao.easybike', 12, 0):
        '@[id="com.jingyao.easybike:id/closeFloatArea"] + [id="com.jingyao.easybike:id/floatADContainer"]',
    ('com.huatu.handheld_huatu', 1, 0):
        '[id="com.huatu.handheld_huatu:id/main_img_adv_bg"] + [id="com.huatu.handheld_huatu:id/main_img_close"]',
    ('com.handsgo.jiakao.android', 2, 1):
        '[id="com.handsgo.jiakao.android:id/adsdk__ids_cta_layout"] - * > [id="com.handsgo.jiakao.android:id/close"]',
    ('com.copymanga.app', 2, 0):
        '[id^="com.copymanga.app:id/ad_flag_source"] - * > [id="com.copymanga.app:id/close"]',
    ('com.jisu.manhua', 1, 0):
        '[id="com.jisu.manhua:id/iv_promotion"] + [id="com.jisu.manhua:id/iv_close"]',
    ('com.hunantv.imgo.activity', 2, 0):
        '[id="com.hunantv.imgo.activity:id/imgPromotion"] + [id="com.hunantv.imgo.activity:id/vClosePromotionView"]',
    ('com.hunantv.imgo.activity', 2, 2):
        '[id="com.hunantv.imgo.activity:id/ivPromotion"] + [id="com.hunantv.imgo.activity:id/btnClose"]',
    ('com.cmcc.cmvideo', 5, 0):
        '@[id="com.cmcc.cmvideo:id/iv_big_close"] + [id="com.cmcc.cmvideo:id/sdv_big_pic"]',
    ('com.modian.app', 1, 0):
        '[id="com.modian.app:id/banner_view"] + [id="com.modian.app:id/iv_close"]',
    ('com.xinhang.mobileclient', 2, 0):
        '@[id="com.xinhang.mobileclient:id/iv_close"] + [id="com.xinhang.mobileclient:id/iv_advert"]',
    ('com.shinyv.cnr', 1, 0):
        '[id="com.shinyv.cnr:id/iv_ad"] + [id="com.shinyv.cnr:id/iv_close"]',
    ('com.zzw.october', 2, 0):
        '[id="com.zzw.october:id/interact_ad_root"] >n ImageView[id="com.zzw.october:id/iv_close"]',
    ('com.pcncn.jj', 6, 0):
        '[id="com.pcncn.jj:id/interact_ad_root"] [id="com.pcncn.jj:id/iv_close"]',
    ('cn.TuHu.android', 1, 0):
        '@[id="cn.TuHu.android:id/imgClose"] - [id="cn.TuHu.android:id/rlContent"] > [id="cn.TuHu.android:id/img_jump"]',
    ('com.moutai.mall', 1, 0):
        '[id="com.moutai.mall:id/ivPic"] + ViewGroup + [id="com.moutai.mall:id/vClose"]',
    # Ad-specific dismissal IDs and controls inside the source's ad activity.
    ('com.ksf.yyx', 2, 0): '[id="com.ksf.yyx:id/ksad_container"] [text="跳过"]',
    ('com.ksf.yyx', 2, 2): '[id="ad_area"] [id="close_btn"][clickable=true]',
    ('com.picacomic.fregata', 3, 0): '[id="com.picacomic.fregata:id/imageButton_banner_close"]',
    ('com.qcymall.earphonesetup', 1, 0): '[vid="close_img_banner"]',
    ('com.zhizhou.days', 1, 0): '[desc^="webview-close"] > View[clickable=true]',
    ('com.duokan.reader', 4, 0): '@ImageView < FrameLayout[id="com.duokan.reader:id/reading__app_ad_view__close"]',
    ('tool.seagull.v', 2, 2): '[desc="Interstitial close button"]',
    ('com.jd.app.reader', 3, 0): '[id$="ad_read_close"]',
    ('com.maxhom.weibu', 3, 2): '[id="com.maxhom.weibu:id/anythink_myoffer_banner_close"]',
    ('com.klcxkj.zqxy', 1, 0): '[id="com.klcxkj.zqxy:id/anythink_myoffer_btn_close_id"]',
    ('io.iftech.android.box', 2, 2): '[id="io.iftech.android.box:id/anythink_myoffer_btn_close_id"]',
    ('com.zhaopin.social', 2, 0): '[id="com.zhaopin.social:id/rl_banner_close"][clickable=true]',
    ('com.xyhui', 1, 6): '[id="com.xyhui:id/ms_activity_sdk_interstitial_cacel"]',
    ('com.nowcasting.activity', 3, 0): '[id="com.nowcasting.activity:id/jad_feed_close"]',
    ('com.guodongbaohe.app', 6, 0): 'LinearLayout[id="com.guodongbaohe.app:id/close_dialog_lv"]',
    ('com.hihonor.appmarket', 1, 0): '[id="com.hihonor.appmarket:id/iv_dialog_operation_close"]',
    # Screen-specific ad dismissals in the pinned v72 subscription.
    ('cn.com.langeasy.LangEasyLexis', 2, 0):
        '[id="cn.com.langeasy.LangEasyLexis:id/close_float_button"]',
    ('cn.speedtest.lite', 10, 0):
        '@[id="cn.speedtest.lite:id/iv_close"] - RelativeLayout >n [id="cn.speedtest.lite:id/ad_banner"]',
    ('cn.yonghui.hyd', 1, 0):
        '@TextView[id="cn.yonghui.hyd:id/close"] -n [id="cn.yonghui.hyd:id/title"][text="天降红包"]',
    ('com.baidu.BaiduMap', 1, 0):
        'ImageView[id="com.baidu.BaiduMap:id/yellow_banner_close"]',
    ('com.baidu.BaiduMap', 6, 0):
        'ImageView[id="com.baidu.BaiduMap:id/operational_activities_content_close"]',
    ('com.baidu.netdisk', 1, 0):
        '[id="com.baidu.netdisk:id/cl_content"] - [id="com.baidu.netdisk:id/iv_close"]',
    ('com.baidu.netdisk', 3, 0):
        '[id="com.baidu.netdisk:id/vf_content"] + [id="com.baidu.netdisk:id/close"]',
    ('com.baidu.netdisk.samsung', 2, 0):
        '[id="com.baidu.netdisk.samsung:id/buy_product_root"] > [id="com.baidu.netdisk.samsung:id/iv_close"]',
    ('com.baidu.youavideo', 3, 0):
        '[id="com.baidu.youavideo:id/img_operation_close"]',
    ('com.baidu.youavideo', 6, 0):
        '[id="com.baidu.youavideo:id/dialog_home_pop_close_button"] ',
    ('com.cainiao.wireless', 1, 0):
        '[id="com.cainiao.wireless:id/draw_dialog_iv_close"]',
    ('com.cainiao.wireless', 7, 0):
        '[vid="dialog_full_image_close"]',
    ('com.cctv.cctv5ultimate', 1, 0):
        '[id="com.cctv.cctv5ultimate:id/iv_close"]',
    ('com.chinamobile.mcloud', 3, 0):
        '[id="com.chinamobile.mcloud:id/iv_logo"] + [id="com.chinamobile.mcloud:id/iv_close"]',
    ('com.chinatower.tthd', 1, 0):
        '[id="com.chinatower.tthd:id/fl_content_container"] + [id="com.chinatower.tthd:id/iv_close"]',
    ('com.cmcc.cmvideo', 2, 0):
        '[id="com.cmcc.cmvideo:id/iv_right_bottom_close"]',
    ('com.ct.client', 3, 1):
        'ImageView[id="com.ct.client:id/ivFloatClose"]',
    ('com.ddsy.songyao', 1, 0):
        '[id="com.ddsy.songyao:id/iv_coupon_bottom"] + [id="com.ddsy.songyao:id/iv_close"]',
    ('com.dianping.v1', 4, 0):
        '[id="com.dianping.v1:id/operate_cross_icon"]',
    ('com.dianping.v1', 3, 0):
        '[id="com.dianping.v1:id/float_image"] + [id="com.dianping.v1:id/close"][clickable=true]',
    ('com.dmzj.manhua', 1, 0):
        '[id="com.dmzj.manhua:id/iv_close"]',
    ('com.dubox.drive', 1, 0):
        'ImageView[id="com.dubox.drive:id/ivClose"]',
    ('com.fcbox.hivebox', 1, 0):
        '[id="com.fcbox.hivebox:id/fl_content_container"] + [id="com.fcbox.hivebox:id/iv_close"]',
    ('com.gamersky', 1, 0):
        '[text="广告"] < * + [id="com.gamersky:id/delete"]',
    ('com.gotokeep.keep', 2, 0):
        '[id="com.gotokeep.keep:id/layoutLottie"] +(1,2) [id="com.gotokeep.keep:id/imgCloseHomePageDialog"]',
    ('com.gspace.android', 1, 0):
        '[id="com.gspace.android:id/hiad_banner_close_button"]',
    ('com.hihonor.appmarket', 2, 0):
        '[id="com.hihonor.appmarket:id/iv_floating_close"]',
    ('com.hihonor.vmall', 1, 0):
        '[id="com.hihonor.vmall:id/gift_close_iv"]',
    ('com.huawei.health', 4, 0):
        '[id="com.huawei.health:id/marketing_close_icon"]',
    ('com.huawei.music', 3, 0):
        'TextView[id="com.huawei.music:id/live_info"] < LinearLayout -2 [id="com.huawei.music:id/close_button"]',
    ('com.huawei.search', 1, 0):
        '[id="com.huawei.search:id/iv_topic_close_in_image"||id="com.huawei.search:id/iv_topic_left_close"||id="com.huawei.search:id/iv_topic_close_in_pps"]',
    ('com.hunantv.imgo.activity', 3, 0):
        '@[id="com.hunantv.imgo.activity:id/apperience_close"] + [id="com.hunantv.imgo.activity:id/apperience_enter"]',
    ('com.hunantv.imgo.activity', 3, 1):
        '@[id="com.hunantv.imgo.activity:id/iv_float_close"] + [id="com.hunantv.imgo.activity:id/iv_float_content"]',
    ('com.jd.app.reader', 2, 0):
        '[id="com.jd.app.reader:id/mCardView"] + [id="com.jd.app.reader:id/mCloseBtn"]',
    ('com.jingdong.app.mall', 1, 0):
        '[id="com.jd.lib.cart.feature:id/iv_egg_close"]',
    ('com.jingdong.pdj', 1, 1):
        '[id="com.jingdong.pdj:id/ivClose"][desc="关闭"]',
    ('com.jingyao.easybike', 10, 0):
        'FrameLayout[id="com.jingyao.easybike:id/hbDialogLayout"] + ImageView[id="com.jingyao.easybike:id/actionDialogClose"][clickable=true]',
    ('com.jingyao.easybike', 11, 0):
        '[id="com.jingyao.easybike:id/lottie_view"] < ViewGroup + [id="com.jingyao.easybike:id/btn_close"]',
    ('com.kuangxiangciweimao.novel', 2, 0):
        '@[id="com.kuangxiangciweimao.novel:id/imgclose"] - [vid="img_activity"]',
    ('com.lptiyu.tanke', 0, 0):
        '[id="com.lptiyu.tanke:id/interact_ad_root"] >n [id="com.lptiyu.tanke:id/iv_close"]',
    ('com.luna.music', 2, 1):
        '[id="com.luna.music:id/fl_pendant_container"] > [id="com.luna.music:id/view_close"]',
    ('com.m4399.gamecenter', 1, 1):
        '@[id="com.m4399.gamecenter:id/close"] + [id="com.m4399.gamecenter:id/image"]',
    ('com.miui.player', 4, 0):
        '[id="com.miui.player:id/cl_floating_promote"] > [id="com.miui.player:id/iv_close"]',
    ('com.miui.player', 11, 0):
        '[id="com.miui.player:id/banner_image"] + [id="com.miui.player:id/close_banner"]',
    ('com.miui.video', 1, 0):
        '@ImageView[id="com.miui.video:id/iv_close"][visibleToUser=true] - [text="立即下载"] - ImageView[id="com.miui.video:id/download_icon"] < [id="com.miui.video:id/v_download"]',
    ('com.mygolbs.mybus', 1, 4):
        '[text="广告"] <3 View + @View[clickable=true] >2 [text="×"][visibleToUser=true]',
    ('com.mygolbs.mybus', 1, 6):
        '[id="com.mygolbs.mybus:id/ll_ad"] [id="com.mygolbs.mybus:id/btn_cancel"]',
    ('com.netease.gl', 2, 0):
        '@[id="com.netease.gl:id/iv_close"] + [id="com.netease.gl:id/iv_cover"]',
    ('com.oppo.store', 2, 0):
        'ImageView[id="com.oppo.store:id/dialog_delete"][desc="关闭"]',
    ('com.qidian.QDReader', 13, 0):
        '[id="com.qidian.QDReader:id/rootView"] > [id="com.qidian.QDReader:id/btnHongbaoClose"]',
    ('com.qinlin.edoor', 1, 0):
        '[id="com.qinlin.edoor:id/close"]',
    ('com.showstartfans.activity', 1, 0):
        '[id="com.showstartfans.activity:id/img_close_coupon"]',
    ('com.shuqi.controller', 0, 0):
        '[id="com.shuqi.controller:id/promotion_close"]',
    ('com.sina.weibo', 13, 0):
        '[id="com.sina.weibo:id/floating_window"] >2 [id="com.sina.weibo:id/close"]',
    ('com.sina.weibo', 13, 1):
        '[id="com.sina.weibo:id/floating_window"] >n [id="com.sina.weibo:id/closeView"]',
    ('com.sinovatech.unicom.ui', 4, 0):
        '[id="com.sinovatech.unicom.ui:id/home_xuanfu_close"]',
    ('com.smile.gifmaker', 11, 0):
        '[id="com.smile.gifmaker:id/pendant_mask_bg"] + [id="com.smile.gifmaker:id/pendant_close"][visibleToUser=true]',
    ('com.smzdm.client.android', 1, 0):
        '[id="com.smzdm.client.android:id/dialog_home_close"]',
    ('com.taobao.taobao', 1, 0):
        '[desc^="限时福利"] < FrameLayout + [desc="关闭按钮"]',
    ('com.tencent.news', 2, 0):
        '@[text="关闭"] + [text="查看活动详情"]',
    ('com.umetrip.android.msky.app', 11, 0):
        '@[id="com.umetrip.android.msky.app:id/iv_close_envelope"] + [id="com.umetrip.android.msky.app:id/media_view_envelope"]',
    ('com.wm.dmall', 2, 0):
        '@[id="com.wm.dmall:id/iv_close"] + [id="com.wm.dmall:id/view_float"]',
    ('com.xiachufang', 1, 1):
        '[id="com.xiachufang:id/jad_feed_close"][visibleToUser=true]',
    ('com.ximalaya.ting.android', 11, 0):
        '@[id="com.ximalaya.ting.android:id/host_close_firework"] +2 [id="com.ximalaya.ting.android:id/host_firework_ad_tag"]',
    ('com.xunmeng.pinduoduo', 22, 0):
        '@ImageView[id=null][clickable=true] < ViewGroup + ViewGroup > ImageView + ViewGroup > ImageView +(2) FrameLayout > TextView[text="专属现金红包"]',
    ('com.yek.android.uniqlo', 1, 0):
        'ImageView[id="com.yek.android.uniqlo:id/pop_close"]',
    ('com.youdao.dict', 1, 1):
        '[vid="close_pop"][text="收起广告"]',
    ('com.zhihu.android', 11, 0):
        'ImageView[id="com.zhihu.android:id/floating_img"] + ImageView[id="com.zhihu.android:id/floating_close_btn"][clickable=true]',
    ('com.zhihu.android', 12, 0):
        '[id="com.zhihu.android:id/image"] < RelativeLayout + [id="com.zhihu.android:id/dismiss"]',
    ('com.zmzx.college.search', 3, 1):
        '[id^="com.zmzx.college.search:id/ad_flag_source"] - * > [id="com.zmzx.college.search:id/close"]',
}

# Keep dismissals on the activity where the ad was documented.
REVIEWED_ACTIVITY_FILTERS = {
    ('com.hihonor.appmarket', 1, 0): {'com.hihonor.appmarket.module.main.MainActivity'},
    ('com.baidu.BaiduMap', 1, 0): {'com.baidu.baidumaps.MapsActivity'},
    ('com.baidu.BaiduMap', 6, 0): {'com.baidu.baidumaps.MapsActivity'},
    ('com.jd.app.reader', 2, 0): {'com.jingdong.app.reader.main.ui.JdMainActivity'},
}

# Preserve the exact source pattern, then require a visible, clickable close.
# When the source accepts a generic label too, retain only the ad-specific branch.
REVIEWED_TARGETS = {
    ('com.netease.cloudmusic', 4, 1): (
        '[text="广告"] < ViewGroup + ImageView',
        '[text="广告"] < ViewGroup + ImageView[clickable=true]',
    ),
    ('com.tencent.qqlive', 4, 0): (
        '@[text$="跳过广告"][clickable=true] < LinearLayout > [text="VIP可关闭该广告"]',
        '@[text$="跳过广告"][text.length<=10][clickable=true] < LinearLayout > [text="VIP可关闭该广告"]',
    ),
    ('com.tencent.qqlive', 4, 2): (
        '@[text$="关闭广告"][clickable=true]',
        '[text="关闭广告"][clickable=true]',
    ),
    ('com.baidu.tieba', 9, 1): (
        '@TextView[clickable=true && text=null] - FrameLayout TextView[text="广告"]',
        '@TextView[clickable=true][text=null][childCount=0] - FrameLayout TextView[text="广告"]',
    ),
    ('com.google.android.youtube', 1, 0): (
        'ImageView[desc="更多选项"||desc^="More"] + @ImageView[desc="关闭广告面板"||desc^="Close"]',
        'ImageView[desc="更多选项"] + @ImageView[desc="关闭广告面板"][clickable=true]',
    ),
    ('com.sina.weibo', 5, 3): (
        '[id="com.sina.weibo:id/tvTrendsTitle"] + [id="com.sina.weibo:id/iv_ad_x"]',
        '[id="com.sina.weibo:id/tvTrendsTitle"] + [id="com.sina.weibo:id/iv_ad_x"][clickable=true]',
    ),
    ('com.wallstreetcn.news', 10, 0): (
        '[text="广告" || text="活动"] + [id="com.wallstreetcn.news:id/close"][visibleToUser=true]',
        '[text="广告"] + [id="com.wallstreetcn.news:id/close"][visibleToUser=true][clickable=true]',
    ),
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
    return bool(id_tokens(field, op, value) & (DISMISS_NAMES | {'dislike'}))


def ad_marker(field, op, value):
    return text_test(AD_WORDS)(field, op, value) or bool(
        id_tokens(field, op, value) & AD_NAMES and id_tokens(field, op, value) & AD_CONTEXT_IDS,
    )


def labeled_control(selector):
    path, target = selector['path'], selector['target']
    # When the source marks a wrapper as the click target, require a fixed ID and
    # a direct child explicitly identifying the action as closing an advertisement.
    if not guaranteed(path[target], lambda f, op, v: f in ('id', 'vid') and op == '='
                      and isinstance(v, str) and bool(v)):
        return False
    neighbors = ((target + 1, target, '>'), (target - 1, target - 1, '<'))
    return any(
        0 <= neighbor < len(path)
        and selector['relations'][index] == [operator, [1]]
        and guaranteed(path[neighbor], text_test(AD_CLOSE_WORDS))
        for neighbor, index, operator in neighbors
    )


def short_ad_close_label(node):
    for field in ('text', 'desc'):
        prefix = guaranteed(node, lambda f, op, v: f == field and op == '^=' and v in AD_CLOSE_WORDS)
        bounded = guaranteed(node, lambda f, op, v: f == field + '.length'
                             and op in ('<', '<=') and type(v) is int and v <= 12)
        if prefix and bounded:
            return True
    return False


def confidence(selector, activities):
    path, target = selector['path'], selector['target']
    if guaranteed(path[target], text_test(AD_WORDS)):
        return None
    if guaranteed(path[target], ad_close_resource):
        return 'ad-close-resource'
    if guaranteed(path[target], text_test(AD_CLOSE_WORDS)):
        return 'ad-close-label'
    if activities and labeled_control(selector):
        return 'ad-close-child'
    # A countdown suffix is safe only for a scoped, short label with an explicit
    # "skip ad" prefix, never a generic "skip" or "close".
    if activities and short_ad_close_label(path[target]):
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


def constraints(props, alternatives):
    """Keep upstream limits only when the on-device model can honor them."""
    limits = {}
    reset = props.get('resetMatch', 'activity')
    if reset not in ('activity', 'app', 'match'):
        return None
    if 'matchTime' in props:
        time = props['matchTime']
        # The runtime's time window starts at the activity, never a later match
        # or the app transition, so reject the other reset models here.
        if type(time) is not int or time <= 0 or reset != 'activity':
            return None
        limits['matchTimeMs'] = time
    if 'actionMaximum' in props:
        maximum = props['actionMaximum']
        if type(maximum) is not int or maximum <= 0 or len(alternatives) > 1:
            return None
        # Per-app visit budgets are at least as strict as per-activity budgets.
        limits['maxPerVisit'] = min(maximum, 3)
    return limits


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
                snapshots = array(props.get('snapshotUrls'))
                if not snapshots:
                    continue
                matches, alternatives = array(rule.get('matches')), array(rule.get('anyMatches'))
                if matches and alternatives:
                    continue
                limits = constraints(props, alternatives)
                if limits is None:
                    continue
                requirements = [compile_popup_selector(value) for value in matches[:-1]]
                if any(not value for value in requirements):
                    continue
                for original in matches[-1:] or alternatives:
                    accepted = original
                    identity = (package, group['key'], index)
                    reviewed_target = REVIEWED_TARGETS.get(identity)
                    if reviewed_target and reviewed_target[0] == original and activities:
                        accepted = reviewed_target[1]
                    selector = compile_popup_selector(accepted)
                    if not selector:
                        continue
                    evidence = confidence(selector, activities)
                    reviewed = REVIEWED.get(identity) == original or accepted != original
                    entry_activities = activities
                    if activities and reviewed:
                        evidence = 'reviewed-ad-path'
                        if identity in REVIEWED_ACTIVITY_FILTERS:
                            entry_activities = [activity for activity in activities
                                                if activity in REVIEWED_ACTIVITY_FILTERS[identity]]
                            if not entry_activities:
                                continue
                    if not evidence or any(SENSITIVE.search(activity) for activity in entry_activities):
                        continue
                    entry = dict(
                        package=package, name=app['name'], group=group['name'], groupKey=group['key'], ruleIndex=index,
                        selector=accepted, snapshots=snapshots, evidence=evidence, targetSelector=selector,
                        activityIds=entry_activities, excludeActivityIds=excludes,
                        required=requirements, requiredSelectors=matches[:-1],
                        **limits,
                    )
                    if accepted != original:
                        entry['upstreamSelector'] = original
                    entries.append(entry)
    return entries


def import_reviewed_samples(path):
    entries = []
    for sample in json.loads(path.read_text(encoding='utf-8')):
        selector = compile_popup_selector(sample['selector'])
        package = sample['package']
        activity = sample['activity']
        if protected(package) or not activity or SENSITIVE.search(activity) or not selector:
            raise ValueError(f'Invalid reviewed popup evidence in {package}')
        nodes = selector['path']
        target = selector['target']
        page_down = sample.get('pageDown') is True
        if page_down:
            if (package != 'com.phoenix.read'
                    or activity != 'com.dragon.read.component.shortvideo.impl.ShortSeriesActivity'
                    or sample.get('exactActivity') is not True
                    or not guaranteed(nodes[target], lambda f, o, v: (f, o, v) == ('text', '=', '上滑继续观看短剧'))
                    or not any(guaranteed(node, text_test(AD_WORDS)) for node in nodes)):
                raise ValueError(f'Reviewed page turn requires the exact short-drama advertisement in {package}')
        elif not guaranteed(nodes[target], lambda f, o, v: (f, o, v) == ('clickable', '=', True)) or not any(
            i != target and guaranteed(node, lambda f, o, v: f == 'id' and o == '='
                and isinstance(v, str) and v.startswith(package + ':id/'))
            for i, node in enumerate(nodes)
        ):
            raise ValueError(f'Reviewed popup requires a clickable target and an app-specific anchor in {package}')
        entry = dict(
            package=package, name=sample['name'], group=sample['group'],
            activityIds=[activity], maxPerVisit=1, evidence='reviewed-ad-path',
            snapshots=[sample['snapshot']], selector=sample['selector'], targetSelector=selector,
            required=[], reference=dict(versionName=sample['versionName'], snapshotSha256=sample['sha256']),
        )
        if sample.get('exactActivity') is True:
            entry['exactActivity'] = True
        if page_down:
            entry['pageDown'] = True
        entries.append(entry)
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
    rules.extend(import_reviewed_samples(VENDOR / 'popup-samples.json'))
    manifest = dict(source=SOURCE, sourceCommit=SOURCE_COMMIT, sourceSha256=SOURCE_SHA,
                    version=source['version'], license='MIT', rules=rules)
    (ASSETS / 'popup-ad-rules.json').write_text(
        json.dumps(manifest, ensure_ascii=False, separators=(',', ':')) + '\n', encoding='utf-8',
    )
    print(json.dumps(dict(rules=len(rules), apps=len({rule['package'] for rule in rules}))))


if __name__ == '__main__':
    main()
