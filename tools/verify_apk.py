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
    assert hashlib.sha256(z.read('assets/support-code.jpg')).hexdigest()=='5dfc946feeca2ae7956bc4e6322b331a47e12d1ad6d511c1a4f2caea9bc33e77'
print(f'PASS: packaged rules, DNS source hash, {len(hosts)} hosts, protected connectivity host, licenses; APK SHA256 {hashlib.sha256(apk.read_bytes()).hexdigest()}')
