"""Local interpretations of public accessibility snapshots; no third-party actions are executed."""
def extra_rules(kind, compile_selector):
    rows=[]
    def add(pkg,name,title,activity,selector,ids,**options):
        tree=compile_selector(selector)
        if not tree: raise ValueError(selector)
        rows.append(dict(package=pkg,name=name,group=title,groupKey=220,ruleIndex=len(rows),
            activityIds=[activity],exactActivity=True,selector=selector,targetSelector=tree,
            snapshots=['https://i.gkd.li/i/'+str(i) for i in ids],required=[],requiredSelectors=[],**options))
    if kind=='splash':
        pkg='com.coolapk.market'
        add(pkg,'酷安','专用开屏页 · 跳过和倒计时并排',pkg+'.view.splash.SplashAdActivity',
            '@TextView[text="跳过"][childCount=0] + TextView[text.length=1]',[26328668],
            evidence='activity',tap=True,matchTime=10000)
        add(pkg,'酷安','首页开屏 · 广告层中的跳过按钮',pkg+'.view.main.MainActivity',
            '[vid="main_splash_ad"] >n @[vid="anythink_myoffer_splash_skip_area"][clickable=true] > [vid="anythink_myoffer_splash_skip"]',
            [23097140],evidence='resource',requireClickable=True,matchTime=10000)
    else:
        add('com.hunantv.imgo.activity','芒果TV','视频横屏 · 广告举报旁的关闭按钮','com.mgtv.ui.videoplay.MGVideoPlayActivity',
            '[vid="ad_report"] + @TextView[vid="close_ad"][text="关闭广告"][clickable=true]',
            [14322017],evidence='reviewed-ad-path')
        pkg='com.sina.weibo' 
        add(pkg,'微博','详情页 · 广告标记旁的关闭入口',pkg+'.feed.detailrefactor.DetailPageActivity',
            '@[vid="ll_close"][clickable=true] > [vid="tv_tips"][text="广告"] + [vid="iv_close_icon"]',
            [19526000,21828244],evidence='reviewed-ad-path')
    return rows
