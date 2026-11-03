"""Compile the pinned public-domain ad hosts list. No network request at app runtime."""
import gzip, hashlib, json, re, sys
from pathlib import Path
ROOT = Path(__file__).resolve().parents[1]
SHA = 'bf23271a8dab547e6aeff1940d1a4d1263940c92fc3a5d1e299779a3ca712692'
URL = 'https://raw.githubusercontent.com/blocklistproject/Lists/ee3bdbaddf49c756cf3340bb2e6a9dee5282d1c6/ads.txt'
raw = Path(sys.argv[1] if len(sys.argv)>1 else ROOT/'.local/dns-ads-upstream.txt').read_bytes()
assert hashlib.sha256(raw).hexdigest()==SHA, 'Source hash differs; review before updating'
domains=set()
for line in raw.decode('utf-8').splitlines():
    row=line.split('#',1)[0].split()
    if len(row)!=2 or row[0]!='0.0.0.0': continue
    name=row[1].lower().rstrip('.')
    if len(name)<=253 and '.' in name and all(re.fullmatch(r'[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?',p) for p in name.split('.')): domains.add(name)
# Exact host matching only: entries never implicitly block every child domain.
protected={'localhost','www.google.com','google.com','www.baidu.com','baidu.com','qq.com','weixin.qq.com','login.live.com','connectivitycheck.gstatic.com','connectivitycheck.platform.hicloud.com','connect.rom.miui.com','conn1.oppomobile.com','conn2.oppomobile.com'}
removed=sorted(domains & protected);domains-=protected
payload=('\n'.join(sorted(domains))+'\n').encode('ascii')
asset=ROOT/'app/src/main/assets';asset.joinpath('dns-hosts.bin').write_bytes(gzip.compress(payload,mtime=0))
meta=dict(source=URL,commit='ee3bdbaddf49c756cf3340bb2e6a9dee5282d1c6',sourceSha256=SHA,license='Unlicense',hosts=len(domains),matching='exact-host-only',excluded=removed,normalizedSha256=hashlib.sha256(payload).hexdigest())
asset.joinpath('dns-source.json').write_text(json.dumps(meta,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
print(json.dumps(meta,ensure_ascii=False))
