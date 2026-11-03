"""Import only simple, fully understood ID selectors from MIT splash groups.

Complex relationships, coordinate actions and conditional selectors are deliberately omitted.
Requires json5 for development; the app reads generated plain JSON with no parser dependency.
"""
import hashlib,json,re,sys
from pathlib import Path
import json5
ROOT=Path(__file__).resolve().parents[1]
SOURCE_SHA='5624137f8c70566a8dc985990dd18571600efa1c76129734703533ca5ee71c23'
source_path=Path(sys.argv[1]) if len(sys.argv)>1 else ROOT/'vendor/gkd-splash-reference/ganlin_gkd.json5'
raw=source_path.read_bytes()
assert hashlib.sha256(raw).hexdigest()==SOURCE_SHA
data=json5.loads(raw.decode('utf-8'));entries=[];sdk=[]
def array(value): return value if isinstance(value,list) else [value] if value else []
for app in data['apps']:
    for group in app.get('groups',[]):
        if group.get('name')!='开屏广告' or group.get('enable') is False: continue
        for raw_rule in array(group.get('rules')):
            rule={'matches':raw_rule} if isinstance(raw_rule,str) else raw_rule
            if rule.get('action') not in [None,'clickNode'] or any(rule.get(k) or group.get(k) for k in ['excludeMatches','preKeys','anyMatchesExclude','versionCodes','excludeVersionCodes']): continue
            selectors=array(rule.get('anyMatches')) or ([rule['matches']] if isinstance(rule.get('matches'),str) else [])
            for selector in selectors:
                if not re.fullmatch(r'(?:@?[A-Za-z][A-Za-z0-9_.]*)?(?:\[[^\[\]]+\])+',selector): continue
                attrs=re.findall(r'\[([^\[\]]+)\]',selector);item={};safe=True
                cls=selector.split('[',1)[0].lstrip('@')
                if cls:item['class']=cls
                for attr in attrs:
                    pair=re.fullmatch(r'(id|vid|text|desc)(\^=|\*=|=)"([^"]*)"',attr)
                    if pair:
                        key,op,value=pair.groups()
                        if key in ['id','vid']:
                            if op!='=':safe=False;break
                            item['id']=value if key=='id' else app['id']+':id/'+value
                        else:item[key]={'op':op,'value':value}
                    elif attr in ['clickable=true','visibleToUser=true','childCount=0']:item[attr.split('=')[0]]=True
                    elif re.fullmatch(r'text.length(<=|<)\d+',attr):item['textMax']=int(re.search(r'\d+',attr).group())-(0 if '<=' in attr else 1)
                    else:safe=False;break
                rid=item.get('id','')
                if not safe or ':id/' not in rid or not re.search(r'skip|splash|ad.*jump|jump.*ad|ad.*close|count_down',rid.split(':id/')[-1],re.I):continue
                if re.search(r'bank|credit|wallet',app['id'],re.I) and not re.search(r'splash|ad_',rid.split(':id/')[-1],re.I):continue
                item.update(package=app['id'],name=app.get('name',app['id']),selector=selector,groupKey=group.get('key'),snapshots=rule.get('snapshotUrls',group.get('snapshotUrls',[])))
                for key in ['activityIds','excludeActivityIds']:
                    values=rule.get(key,group.get(key,[]))
                    if values:item[key]=array(values)
                entries.append(item)
# Exact SDK skip ID, observed in several independently packaged app splash groups.
for resource in ['tt_splash_skip_btn','ksad_splash_circle_skip_view']:
    evidence=next((x for x in entries if x['id'].endswith(':id/'+resource)),None)
    if evidence:sdk.append(dict(resourceName=resource,name='广告 SDK 开屏跳过控件',evidencePackage=evidence['package'],snapshots=evidence['snapshots']))
out={'source':'https://github.com/ganlinte/GKD-subscription','sourcePath':'dist/ganlin_gkd.json5','sourceSha256':SOURCE_SHA,'version':data['version'],'license':'MIT','retrieved':'2026-11-03','rules':entries,'sdkRules':sdk}
(ROOT/'app/src/main/assets/splash-rules.json').write_text(json.dumps(out,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
vendor=ROOT/'vendor/gkd-splash-reference';vendor.mkdir(exist_ok=True)
(vendor/'ganlin_gkd.json5').write_bytes(raw)
license_path=source_path.parent/'LICENSE'
if not license_path.exists():license_path=ROOT/'.local/splash-LICENSE'
license_data=license_path.read_bytes();assert hashlib.sha256(license_data).hexdigest()=='3771ee111d68071b0e2994817cff88599d59d7272e75ea026f87af810160340e'
(vendor/'LICENSE').write_bytes(license_data);(ROOT/'app/src/main/assets/splash-license.txt').write_bytes(license_data)
print(json.dumps({'rules':len(entries),'apps':len(set(x['package'] for x in entries)),'sdkRules':len(sdk),'names':[x['name'] for x in entries]},ensure_ascii=False))
