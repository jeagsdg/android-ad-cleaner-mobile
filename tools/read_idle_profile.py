"""Measure a debug process from a selected Android device, without changing its settings."""
import argparse
import json
import os
import re
import shutil
import subprocess
import time
from pathlib import Path


def main():
    cli = argparse.ArgumentParser(description=__doc__)
    cli.add_argument('--serial', required=True)
    cli.add_argument('--adb-port', type=int, default=5037)
    cli.add_argument('--output', type=Path, required=True)
    args = cli.parse_args()
    tool = shutil.which('adb')
    if not tool and os.environ.get('ANDROID_HOME'):
        tool = Path(os.environ['ANDROID_HOME']) / 'platform-tools' / ('adb.exe' if os.name == 'nt' else 'adb')
    if not tool:
        cli.error('Add adb to PATH or set ANDROID_HOME')
    base = [str(tool), '-P', str(args.adb_port), '-s', args.serial, 'shell']

    def shell(*command):
        return subprocess.check_output(base + list(command), text=True, encoding='utf-8', timeout=20).strip()

    pids = shell('pidof', 'app.clearscreen').split()
    if len(pids) != 1:
        raise ValueError('Expected one running app.clearscreen process')
    pid = pids[0]
    ticks_per_second = int(shell('getconf', 'CLK_TCK'))

    def sample():
        # Linux /proc/pid/stat fields 14 and 15, adjusted for the parenthesized name.
        fields = shell('run-as', 'app.clearscreen', 'cat', f'/proc/{pid}/stat').rsplit(')', 1)[1].split()
        return time.monotonic(), int(fields[11]) + int(fields[12])

    observations = []
    previous = sample()
    for _ in range(8):
        time.sleep(2)
        current = sample()
        elapsed = current[0] - previous[0]
        usage = shell('dumpsys', 'meminfo', '-s', 'app.clearscreen')
        pss = re.search(r'TOTAL PSS:\s*(\d+)', usage)
        if not pss:
            raise ValueError('System did not report TOTAL PSS for the sampled process')
        observations.append({
            'elapsedS': round(elapsed, 3),
            'cpuPctOneCore': round(100 * (current[1] - previous[1]) / ticks_per_second / elapsed, 2),
            'pssKiB': int(pss[1]),
        })
        previous = current
    if pid not in shell('pidof', 'app.clearscreen').split():
        raise ValueError('Process restarted during the sample')
    data = {'fingerprint': shell('getprop', 'ro.build.fingerprint'), 'clkTck': ticks_per_second,
            'samples': observations}
    args.output.write_bytes((json.dumps(data, indent=2) + '\n').encode('utf-8'))


if __name__ == '__main__':
    main()
