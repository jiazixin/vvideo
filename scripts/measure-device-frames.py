#!/usr/bin/env python3
"""Capture VVideo's already selected screen and analyze its UI frame timeline.

Requires the installed non-debuggable profile APK, ADB and an official Perfetto
trace_processor_shell. Does not change system high-refresh or battery policies.
Select the desired album/player screen before invoking this tool. Use separate
output directories for cold, warm and full-screen conditions.
"""
import argparse
import bisect
import collections
import csv
import hashlib
import io
import json
import math
from pathlib import Path
import re
import subprocess
import time
import uuid

PACKAGE = 'dev.vvideo'
CONFIG = '''buffers { size_kb: 131072 fill_policy: RING_BUFFER }
buffers { size_kb: 8192 fill_policy: RING_BUFFER }
duration_ms: 24000
data_sources { config { name: "android.surfaceflinger.frametimeline" target_buffer: 1 } }
data_sources { config { name: "linux.ftrace" ftrace_config {
 atrace_categories: "gfx"
 atrace_categories: "view"
 atrace_apps: "dev.vvideo"
} } }
data_sources { config { name: "linux.process_stats" target_buffer: 1 process_stats_config { scan_all_processes_on_start: true } } }
'''


def adb(*args, **kwargs):
    return subprocess.run(['adb', *args], check=True, timeout=30, **kwargs)


def percentile(values, p):
    if not values:
        return None
    ordered = sorted(values)
    return ordered[math.ceil(len(ordered) * p) - 1]


def analyze(trace, processor, output, layer):
    # MainActivity's UI layer must be distinct from video SurfaceView timelines.
    # OEM trace thread names can be absent: unique app DrawFrame TID identifies
    # RenderThread; ambiguous traces fail instead of silently mixing threads.
    condition = "layer_name GLOB '" + layer.replace("'", "''") + "' AND layer_name NOT LIKE '%Bounds for%'"
    sql = f'''SELECT 'ui' kind,s.name,s.ts,s.dur,thread.tid FROM slice s JOIN thread_track t ON s.track_id=t.id JOIN thread USING(utid) JOIN process USING(upid) WHERE process.name='{PACKAGE}' AND thread.tid=process.pid AND s.name LIKE 'Choreographer#doFrame%' AND s.name NOT LIKE '%resynced%'
UNION ALL SELECT 'rt',s.name,s.ts,s.dur,thread.tid FROM slice s JOIN thread_track t ON s.track_id=t.id JOIN thread USING(utid) JOIN process USING(upid) WHERE process.name='{PACKAGE}' AND thread.tid!=process.pid AND s.name LIKE 'DrawFrame%'
UNION ALL SELECT 'actual',a.name,a.ts,a.dur,0 FROM actual_frame_timeline_slice a JOIN process USING(upid) WHERE process.name='{PACKAGE}' AND {condition}
UNION ALL SELECT 'expected',e.name,e.ts,e.dur,0 FROM expected_frame_timeline_slice e JOIN process USING(upid) WHERE process.name='{PACKAGE}'
UNION ALL SELECT 'scroll',name,ts,dur,0 FROM slice WHERE name='VVideoScroll' ORDER BY ts;'''
    query = output.with_suffix('.sql')
    query.write_text(sql)
    result = subprocess.run([str(processor), 'query', '-f', str(query), str(trace)], check=True, capture_output=True, text=True, timeout=90)
    output.with_suffix('.csv').write_text(result.stdout)
    output.with_suffix('.parse.txt').write_text(result.stderr)
    groups = collections.defaultdict(list)
    incomplete = collections.Counter()
    for row in csv.DictReader(io.StringIO(result.stdout)):
        if int(row['dur']) <= 0:
            incomplete[row['kind']] += 1
            continue
        groups[row['kind']].append(dict(name=row['name'], ts=int(row['ts']), dur=int(row['dur']), tid=int(row['tid'])))
    rt_ids = sorted({s['tid'] for s in groups['rt']})
    if len(rt_ids) != 1:
        raise RuntimeError(f'Ambiguous/missing app RenderThread: {rt_ids}; inspect raw trace')
    def frame_id(s):
        return int(re.search(r'(\d+)$', s['name']).group(1))
    def end(s):
        return s['ts'] + s['dur']
    def scrolling(s):
        return any(t['ts'] <= s['ts'] < end(t) for t in groups['scroll'])
    ui = {frame_id(s): s for s in groups['ui']}
    # On this OEM, expected slices sharing the UI transaction token can carry
    # a SurfaceView layer label. Match them by actual token, as AndroidX does;
    # applying the UI layer filter to expected slices would drop valid frames.
    expected = {frame_id(s): s for s in groups['expected']}
    actual = groups['actual']
    timestamps = [s['ts'] for s in actual]
    used = set()
    matched = []
    unmatched = 0
    for rt in groups['rt']:
        u = ui.get(frame_id(rt))
        if u is None or not scrolling(u):
            continue
        a = None
        # AndroidX FrameTimingQuery's 50us/overlap workaround for b/279088460.
        for index in range(bisect.bisect_left(timestamps, u['ts'] + 50_000) - 1, -1, -1):
            candidate = actual[index]
            if index not in used and candidate['ts'] <= u['ts'] + u['dur'] // 2 <= end(candidate):
                a = candidate
                used.add(index)
                break
        e = expected.get(frame_id(a)) if a else None
        if e is None:
            unmatched += 1
            continue
        matched.append(dict(ts=u['ts'], cpu_ms=(end(rt)-u['ts'])/1e6, overrun_ms=(max(end(a),end(rt))-end(e))/1e6))
    active = sorted({s['ts'] for s in ui.values() if scrolling(s)})
    intervals = [(b-a)/1e6 for a,b in zip(active,active[1:]) if any(t['ts']<=a<b<end(t) for t in groups['scroll'])]
    over = [s['overrun_ms'] for s in matched]
    cpu = [s['cpu_ms'] for s in matched]
    if not over or not intervals or len(matched) < len(active)*.8:
        raise RuntimeError(f'Insufficient valid interaction coverage ({len(matched)}/{len(active)}); preserve trace and inspect markers/layer')
    report = dict(trace=str(trace.resolve()), sha256=hashlib.sha256(trace.read_bytes()).hexdigest(), ui_layer_glob=layer,
                  render_thread_tid=rt_ids[0], scroll_markers=len(groups['scroll']), scroll_seconds=sum(s['dur'] for s in groups['scroll'])/1e9,
                  active_ui_frames=len(active), matched_frames=len(matched), unmatched_rt_frames=unmatched, incomplete_slices=dict(incomplete),
                  positive_overrun_percent=100*sum(v>0 for v in over)/len(over),
                  overrun_p50_ms=percentile(over,.5), overrun_p95_ms=percentile(over,.95), overrun_p99_ms=percentile(over,.99), overrun_max_ms=max(over),
                  cpu_p95_ms=percentile(cpu,.95), cpu_max_ms=max(cpu),
                  ui_interval_p50_ms=percentile(intervals,.5), ui_interval_p95_ms=percentile(intervals,.95), ui_interval_max_ms=max(intervals))
    output.with_suffix('.matched.json').write_text(json.dumps(matched))
    output.with_suffix('.json').write_text(json.dumps(report,indent=2))
    return report


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--trace-processor', type=Path, required=True)
    parser.add_argument('--prewarm', action='store_true')
    parser.add_argument('--rounds', type=int, default=3, choices=range(1,4))
    parser.add_argument('--bounce-size', type=int, choices=(2, 3, 4, 6, 12), default=12,
                        help='Swipes before reversing; 2 exercises a three-video 4K feed without staying at its ends')
    parser.add_argument('--ui-layer-glob', default='*dev.vvideo/dev.vvideo.app.MainActivity#*')
    parser.add_argument('--analyze', type=Path, help='Analyze one existing trace; do not operate the device')
    args = parser.parse_args()
    processor = args.trace_processor.resolve()
    if not processor.is_file():
        parser.error('trace_processor_shell is missing')
    args.output.mkdir(parents=True,exist_ok=True)
    if args.analyze:
        print(json.dumps(analyze(args.analyze,processor,args.output/'analysis',args.ui_layer_glob),indent=2))
        return
    sizes = re.findall(r'(?:Physical|Override) size: (\d+)x(\d+)',adb('shell','wm','size',capture_output=True,text=True).stdout)
    if not sizes:
        raise RuntimeError('Cannot establish device display size')
    width,height = map(int,sizes[-1])
    pid = adb('shell','pidof',PACKAGE,capture_output=True,text=True).stdout.strip()
    if not re.fullmatch(r'\d+',pid):
        raise RuntimeError('Open the configured VVideo screen first; expected one process')
    def gestures():
        for _ in range(12//args.bounce_size):
            for reverse in (False,True):
                for _ in range(args.bounce_size):
                    start,end_y=(height//3,height*3//4) if reverse else (height*3//4,height//3)
                    adb('shell','input','swipe',str(width//2),str(start),str(width//2),str(end_y),'300',stdout=subprocess.DEVNULL)
    (args.output/'config.txt').write_text(CONFIG)
    (args.output/'device-size.json').write_text(json.dumps(dict(width=width,height=height,pid=int(pid),prewarm=args.prewarm,bounce_size=args.bounce_size)))
    if args.prewarm:
        gestures()
    time.sleep(3)
    reports=[]
    for index in range(1,args.rounds+1):
        nonce=uuid.uuid4().hex
        remote=f'/data/misc/perfetto-traces/vvideo-{nonce}.perfetto'
        trace=args.output/f'round-{index}.perfetto'
        if trace.exists():
            raise RuntimeError('Use a new output directory; never overwrite previous rounds')
        result=adb('shell','perfetto','--txt','-c','-','--background-wait','-o',remote,input=CONFIG,capture_output=True,text=True)
        (args.output/f'round-{index}-start.txt').write_text(result.stdout+result.stderr)
        tracer=result.stdout.strip()
        if not re.fullmatch(r'\d+',tracer) or int(tracer)<=0:
            raise RuntimeError('Perfetto did not return a valid owned process ID')
        try:
            gestures()
            time.sleep(3)
        finally:
            adb('shell','kill','-TERM',tracer)
        time.sleep(3)
        adb('pull',remote,str(trace),stdout=subprocess.DEVNULL)
        if trace.stat().st_size<1024:
            raise RuntimeError('Incomplete trace; retain remote diagnostic')
        adb('shell','rm',remote) # Only this invocation's unique copied artifact.
        (args.output/f'round-{index}-display.txt').write_text(adb('shell','dumpsys','display',capture_output=True,text=True).stdout)
        (args.output/f'round-{index}-battery.txt').write_text(adb('shell','dumpsys','battery',capture_output=True,text=True).stdout)
        current=adb('shell','pidof',PACKAGE,capture_output=True,text=True).stdout.strip()
        if current!=pid:
            raise RuntimeError('Application process changed during capture; inspect failure')
        reports.append(analyze(trace,processor,args.output/f'round-{index}-frames',args.ui_layer_glob))
        (args.output/'summary.json').write_text(json.dumps(reports,indent=2))
        print(json.dumps(reports[-1]),flush=True)


if __name__=='__main__':
    main()
