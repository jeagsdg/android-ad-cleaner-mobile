"""Validate delivered assets in the packaged APK, not merely the source asset folder."""
import gzip, hashlib, json, sys, zipfile
from pathlib import Path
apk=Path(sys.argv[1])
with zipfile.ZipFile(apk) as z:
    meta=json.loads(z.read('assets/dns-source.json'))
    payload=gzip.decompress(z.read('assets/dns-hosts.bin'))
    assert hashlib.sha256(payload).hexdigest()==meta['normalizedSha256']
    hosts=payload.decode('ascii').splitlines()
    assert len(hosts)==meta['hosts'] and hosts==sorted(set(hosts))
    assert 'connect.rom.miui.com' not in hosts
    assert 'doubleclick.net' in hosts
    assert z.read('assets/dns-license.txt').startswith(b'This is free and unencumbered')
    assert z.read('assets/upstream-license.txt')
    assert json.loads(z.read('assets/rules.json'))
    splash=json.loads(z.read('assets/splash-rules.json'))
    assert splash['rules'] and splash['sdkRules'] and z.read('assets/splash-license.txt').startswith(b'MIT License')
    popup=json.loads(z.read('assets/popup-ad-rules.json'))
    assert popup['rules'] and popup['sourceSha256']==splash['sourceSha256']
    assert all(rule['package'] and rule['targetSelector'] for rule in popup['rules'])
    splash_packages={rule['package'] for rule in splash['rules']}
    popup_packages={rule['package'] for rule in popup['rules']}
    assert hashlib.sha256(z.read('assets/support-code.jpg')).hexdigest()=='a7821f2dc8ea85999ff1159a7e399a7de3d3981dfba55f6acbbc897d1aff4508'
print(f'PASS: packaged rules, DNS source hash, {len(hosts)} hosts, protected connectivity host, licenses; APK SHA256 {hashlib.sha256(apk.read_bytes()).hexdigest()}')
print(f'App-specific coverage: {len(splash_packages)} splash, {len(popup_packages)} popup, '
      f'{len(splash_packages | popup_packages)} distinct packages')
