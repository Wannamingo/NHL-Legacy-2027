"""Measure real guest output intervals; SDL redraws do not count as game frames."""
import argparse
import csv
import io
import json
from pathlib import Path
import statistics
import subprocess
import time

ROOT = Path(__file__).resolve().parents[1]
ADB = ['C:/Users/user/Documents/Codex/2026-10-02/new-chat/work/toolchains/platform-tools/adb.exe',
       '-s', 'RG477M01025672']
parser = argparse.ArgumentParser()
parser.add_argument('--seconds', type=float, default=60)
parser.add_argument('--label', required=True)
parser.add_argument('--serial', default='RG477M01025672')
args = parser.parse_args()
ADB[-1] = args.serial
if not args.label.replace('-', '').replace('_', '').isalnum() or not 5 <= args.seconds <= 600:
    raise SystemExit('Invalid label or duration')

def read():
    data = subprocess.check_output(ADB + ['exec-out', 'run-as', 'org.nhllegacy.port',
                                         'cat', 'files/user/android-frame-times.csv'])
    return list(csv.DictReader(io.StringIO(data.decode('ascii'))))

initial = read()
cutoff = int(initial[-1]['timestamp_us']) if initial else 0
print('Measuring guest frames:', args.label, 'for', args.seconds, 'seconds', flush=True)
start = time.monotonic()
while time.monotonic() - start < args.seconds:
    time.sleep(max(0, min(1, args.seconds - (time.monotonic() - start))))
rows = [row for row in read() if int(row['timestamp_us']) > cutoff]
if len(rows) < 2:
    raise SystemExit('Too few new guest frames; check game and benchmark flag')
intervals = [int(row['frame_us']) / 1000 for row in rows]
ordered = sorted(intervals)
def percentile(percent):
    return ordered[min(len(ordered)-1, round((len(ordered)-1)*percent/100))]
slowest = ordered[-max(1, round(len(ordered)*.01)):]
result = {'label': args.label, 'serial': args.serial, 'requested_seconds': args.seconds, 'frames': len(rows),
          'measured_seconds': sum(intervals)/1000, 'average_fps': 1000/statistics.mean(intervals),
          'median_frame_ms': statistics.median(intervals), 'p95_frame_ms': percentile(95),
          'p99_frame_ms': percentile(99), 'max_frame_ms': max(intervals),
          'one_percent_low_fps': 1000/statistics.mean(slowest),
          'frames_over_100ms': sum(x > 100 for x in intervals),
          'frames_over_250ms': sum(x > 250 for x in intervals)}
output = ROOT / 'private/probes/benchmarks'
output.mkdir(parents=True, exist_ok=True)
with (output / (args.label+'.csv')).open('w', newline='', encoding='utf-8') as file:
    writer = csv.DictWriter(file, fieldnames=['timestamp_us', 'frame_us'])
    writer.writeheader(); writer.writerows(rows)
(output / (args.label+'.json')).write_text(json.dumps(result, indent=2)+'\n', encoding='utf-8')
print(json.dumps(result, indent=2))
