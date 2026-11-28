"""ADB measurements without credentials or message contents. Does not clear app data."""
import argparse
import json
import os
from pathlib import Path
import re
import statistics
import subprocess
import time
from datetime import datetime, timezone


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--adb', default=str(Path(os.environ.get('LOCALAPPDATA', '')) / 'Android/Sdk/platform-tools/adb.exe'))
    parser.add_argument('--serial', required=True)
    parser.add_argument('--package', default='com.mango.messenger')
    parser.add_argument('--mode', choices=['startup', 'scroll'], required=True)
    parser.add_argument('--iterations', type=int, default=10)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    if not 1 <= args.iterations <= 100:
        parser.error('iterations must be 1..100')
    if not re.fullmatch(r'[a-zA-Z0-9_.]+', args.package):
        parser.error('invalid package')

    def adb(*cmd):
        return subprocess.check_output([args.adb, '-s', args.serial, *cmd], timeout=45, encoding='utf-8', errors='replace')

    component = adb('shell', 'cmd', 'package', 'resolve-activity', '--brief', args.package).strip().splitlines()[-1]
    if '/' not in component:
        raise RuntimeError('Application not installed')
    result = {'timestampUtc': datetime.now(timezone.utc).isoformat(), 'mode': args.mode,
              'package': args.package, 'model': adb('shell', 'getprop', 'ro.product.model').strip(),
              'android': adb('shell', 'getprop', 'ro.build.version.release').strip(),
              'iterations': args.iterations,
              'limitations': 'ADB smoke benchmark; no battery measurement, not Macrobenchmark or TTID/TTFD.'}
    if args.mode == 'startup':
        times = []
        for _ in range(args.iterations):
            adb('shell', 'am', 'force-stop', args.package)
            time.sleep(1)
            launch = adb('shell', 'am', 'start', '-W', '-n', component)
            match = re.search(r'^TotalTime:\s*(\d+)', launch, re.M)
            if not match or 'Status: ok' not in launch:
                raise RuntimeError('Launch did not return a valid TotalTime')
            times.append(int(match[1]))
            time.sleep(2)
        result['activityLaunchTotalTimeMs'] = times
        result['medianMs'] = statistics.median(times)
        result['p95Ms'] = sorted(times)[max(0, __import__('math').ceil(len(times) * .95) - 1)]
    else:
        # Operator opens a populated chat first. Never enters or sends any text.
        focus = adb('shell', 'dumpsys', 'window')
        if not any(args.package in line for line in focus.splitlines() if 'mCurrentFocus' in line):
            raise RuntimeError('Open the target application and a long chat first')
        size = re.findall(r'(\d+)x(\d+)', adb('shell', 'wm', 'size'))[-1]
        width, height = map(int, size)
        adb('shell', 'dumpsys', 'gfxinfo', args.package, 'reset')
        for _ in range(args.iterations):
            adb('shell', 'input', 'swipe', str(width // 2), str(height * 3 // 10),
                str(width // 2), str(height * 7 // 10), '350')
            time.sleep(.5)
        gfx = adb('shell', 'dumpsys', 'gfxinfo', args.package)
        result['frameSummary'] = [line.strip() for line in gfx.splitlines()
                                 if re.search(r'Total frames rendered:|Janky frames:|\d+th percentile:', line)]
        if not result['frameSummary']:
            raise RuntimeError('No frame measurements available')
    memory = adb('shell', 'dumpsys', 'meminfo', args.package)
    result['memorySummary'] = [line.strip() for line in memory.splitlines()
                               if re.search(r'TOTAL PSS:|TOTAL RSS:', line)]
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(result, indent=2), encoding='utf-8')
    print(json.dumps(result, indent=2))


if __name__ == '__main__':
    main()
