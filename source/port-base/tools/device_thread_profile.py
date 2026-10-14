"""Record Android thread CPU deltas without suspending or altering the game."""
import argparse
import json
from pathlib import Path
import re
import subprocess
import time

ROOT = Path(__file__).resolve().parents[1]
parser = argparse.ArgumentParser()
parser.add_argument('--serial', required=True)
parser.add_argument('--label', required=True)
parser.add_argument('--seconds', type=float, default=5)
args = parser.parse_args()
if not re.fullmatch(r'[A-Za-z0-9_-]+', args.label) or not 1 <= args.seconds <= 30:
    raise SystemExit('Invalid label or duration')
adb = ['C:/Users/user/Documents/Codex/2026-10-02/new-chat/work/toolchains/platform-tools/adb.exe',
       '-s', args.serial]
pid = subprocess.check_output(adb + ['shell', 'pidof', 'org.nhllegacy.port:game'], text=True).strip()
if not pid.isdecimal():
    raise SystemExit('Game must be running')

def snapshot():
    raw = subprocess.check_output(adb + ['shell', 'run-as', 'org.nhllegacy.port',
                                       'sh', '-c', f'"cat /proc/{pid}/task/*/stat"'], text=True)
    result = {}
    for line in raw.splitlines():
        start, end = line.find('('), line.rfind(') ')
        if start < 0 or end < 0:
            continue
        fields = line[end+2:].split()
        result[int(line[:start])] = {'name': line[start+1:end], 'state': fields[0],
            'ticks': int(fields[11]) + int(fields[12]), 'nice': int(fields[16]),
            'last_cpu': int(fields[36])}
    return result

before = snapshot()
started = time.monotonic()
time.sleep(args.seconds)
after = snapshot()
elapsed = time.monotonic() - started
rows = []
for tid, end in after.items():
    if tid in before:
        rows.append({'tid': tid, **end, 'delta_ticks': end['ticks'] - before[tid]['ticks'],
                     'first_cpu': before[tid]['last_cpu']})
rows.sort(key=lambda row: row['delta_ticks'], reverse=True)
for row in rows[:12]:
    status = subprocess.run(adb + ['shell', 'run-as', 'org.nhllegacy.port', 'cat',
        f"/proc/{pid}/task/{row['tid']}/status"], capture_output=True, text=True).stdout
    allowed = re.search(r'^Cpus_allowed_list:\s*(.+)$', status, re.MULTILINE)
    row['allowed_cpus'] = allowed.group(1).strip() if allowed else None
cgroup = subprocess.run(adb + ['shell', 'run-as', 'org.nhllegacy.port', 'cat',
                              f'/proc/{pid}/cgroup'], capture_output=True, text=True).stdout
thermal = subprocess.check_output(adb + ['shell', 'dumpsys', 'thermalservice'], text=True)
folder = ROOT / 'private/probes/y700'
folder.mkdir(exist_ok=True, parents=True)
(folder / (args.label + '-thermal.txt')).write_text(thermal, encoding='utf-8')
report = {'serial': args.serial, 'pid': int(pid), 'elapsed_seconds': elapsed,
          'ticks_are_linux_stat_clock_ticks': True, 'cgroup': cgroup, 'threads': rows}
(folder / (args.label + '-threads.json')).write_text(json.dumps(report, indent=2) + '\n', encoding='utf-8')
print(json.dumps({'cgroup': cgroup, 'elapsed_seconds': elapsed, 'threads': rows[:12]}, indent=2))
