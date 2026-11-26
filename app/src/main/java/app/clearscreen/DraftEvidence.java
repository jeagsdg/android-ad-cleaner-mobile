package app.clearscreen;

/** Checks the exact rule which will be saved against two user-labelled local snapshots. */
final class DraftEvidence {
    private DraftEvidence() {}

    static void validate(CustomSkipRule draft,RuleSample ad,RuleSample ordinary) {
        if (!draft.pkg.equals(ad.pkg)||!draft.pkg.equals(ordinary.pkg)||!complete(ad)||!complete(ordinary))
            throw new IllegalArgumentException("请选择同一应用的完整广告页和普通页样本");
        if (!draft.legacy||draft.scope!=null||draft.gesture||draft.backsOut()||draft.replaceDefaults
                ||draft.times!=1||draft.sequenceIndex!=-1||draft.searches!=1||draft.delayMs!=0)
            throw new IllegalArgumentException("AI 草稿仅支持一次点击已核对的文字控件");
        String target=literal(draft.label);
        boolean popup=CustomSkipRule.POPUP.equals(draft.mode);
        if (!(SplashSkipper.explicitAd(target)||popup && (SplashSkipper.skipLabel(target)
                ||target.matches("关闭|關閉|[×xX]|(?i:close)"))))
            throw new IllegalArgumentException("开屏目标须明确写出跳过广告，弹窗须有可区分的关闭入口");
        LegacySelector marker=null;
        if(popup){
            String prompt=literal(draft.context);
            if(prompt.equals(target)||!prompt.matches("广告|廣告|推广|推廣|(?i:ad|advertisement)"))
                throw new IllegalArgumentException("弹窗须有独立的广告标识，例如广告或推广");
            marker=new LegacySelector(draft.context);
        }
        LegacySelector button=new LegacySelector(draft.label);
        if(count(draft,ad,button,marker)!=1)
            throw new IllegalArgumentException("广告页未唯一命中可见的关闭入口，请调整草稿或重新选样本");
        if(count(draft,ordinary,button,marker)!=0)
            throw new IllegalArgumentException("普通页也会命中，请收紧草稿后重新核对");
    }

    static boolean complete(RuleSample sample){
        if(!sample.complete)return false;
        int roots=0;
        for(RuleSample.Item item:sample.items){
            if(item.parent==null)roots++;
            for(RuleSample.Item child:item.children)if(child==null)return false;
        }
        return roots==1;
    }

    private static String literal(String selector){
        if (!selector.startsWith("=")||selector.length()<2||selector.indexOf('&')>=0)
            throw new IllegalArgumentException("AI 草稿须完整匹配按钮文字");
        return selector.substring(1);
    }

    private static int count(CustomSkipRule draft,RuleSample sample,LegacySelector button,LegacySelector marker){
        if(!draft.appliesTo(sample.pkg,sample.page,draft.mode))return 0;
        int targets=0, markers=0;
        RuleSample.Item onlyTarget=null, onlyMarker=null;
        for(RuleSample.Item item:sample.items){
            if(!item.visible||item.bounds.isEmpty())continue;
            String id=item.id==null?"":item.id, text=item.text==null?"":item.text, desc=item.desc==null?"":item.desc;
            if(button.matchesAttributes(id,text,desc)){targets++;onlyTarget=item;}
            if(marker!=null && marker.matchesAttributes(id,text,desc)){markers++;onlyMarker=item;}
        }
        if(marker!=null&&(markers==0||targets==1&&markers==1&&onlyTarget==onlyMarker))return 0;
        return targets;
    }
}
