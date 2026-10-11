#!/usr/bin/env python3
"""Sample the running VVideo process without changing its UI or forcing GC.

Drive the intended workload separately. Keep the APK/build, workload and cache
conditions alongside the output. A passing PSS result does not imply functional
or frame-timing acceptance. Raw meminfo and battery dumps are retained.
"""
import argparse
import datetime
import json
import math
from pathlib import Path
import re
import statistics
import subprocess
import time

PACKAGE = "dev.vvideo"


def adb(*args):
    return subprocess.run(["adb", *args], check=True, capture_output=True,
                          text=True, timeout=20).stdout


def summarize(rows, interval):
    if any(b["seconds"] <= a["seconds"] for a, b in zip(rows, rows[1:])):
        raise RuntimeError("Sample timestamps must increase strictly")
    for start, end in ((300, 600), (1500, 1800)):
        window = [r["seconds"] for r in rows if start <= r["seconds"] < end]
        if (len(window) < math.ceil(300 / interval * .8) or
                min(window, default=end) > start + interval * 1.5 or
                max(window, default=start) < end - interval * 1.5):
            raise RuntimeError("Incomplete comparison window; retain partial data")
    baseline = [r["pss_mib"] for r in rows if 300 <= r["seconds"] < 600]
    final = [r["pss_mib"] for r in rows if 1500 <= r["seconds"] < 1800]
    if not baseline or not final:
        raise RuntimeError("Both complete 5–10 and 25–30 minute windows are required")
    growth = statistics.median(final) - statistics.median(baseline)
    peak = max(r["pss_mib"] for r in rows)
    report = dict(samples=len(rows), baseline_count=len(baseline), final_count=len(final),
                  baseline_median_mib=statistics.median(baseline),
                  final_median_mib=statistics.median(final), growth_mib=growth,
                  peak_pss_mib=peak, numerical_gates_pass=peak <= 512 and growth <= 25,
                  scope="PSS gates only; inspect trend, functional and frame results separately")
    for key in ("java_mib", "native_mib", "graphics_mib", "temperature_c"):
        values = [r[key] for r in rows if r.get(key) is not None]
        report[key] = dict(min=min(values), max=max(values)) if values else None
    return report


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--interval", type=int, default=10, choices=range(5, 61))
    parser.add_argument("--analyze", type=Path, help="Read an existing samples.json without ADB")
    args = parser.parse_args()
    args.output.mkdir(parents=True, exist_ok=True)
    report_file = args.output / "summary.json"
    if report_file.exists():
        parser.error("Use a new output directory; previous results are never overwritten")
    if args.analyze:
        rows = json.loads(args.analyze.read_text())
    else:
        if (args.output / "samples.json").exists():
            parser.error("Use a new output directory")
        pid = adb("shell", "pidof", PACKAGE).strip()
        if not re.fullmatch(r"\d+", pid):
            raise RuntimeError("Open VVideo first; expected one running process")
        (args.output / "conditions.json").write_text(json.dumps(dict(
            pid=int(pid), interval_seconds=args.interval, duration_seconds=1800,
            started=datetime.datetime.now().astimezone().isoformat()), indent=2))
        start = time.monotonic()
        rows = []
        index = 0
        while time.monotonic() - start < 1800:
            seconds = time.monotonic() - start
            if adb("shell", "pidof", PACKAGE).strip() != pid:
                raise RuntimeError("VVideo process changed; retain partial data and inspect failure")
            text = adb("shell", "dumpsys", "meminfo", PACKAGE)
            battery = adb("shell", "dumpsys", "battery")
            (args.output / f"sample-{index:03d}.txt").write_text(
                datetime.datetime.now().astimezone().isoformat() +
                f" offset_seconds={seconds:.3f}\n" + text + "\n" + battery)
            total = re.search(r"TOTAL PSS:\s*(\d+)", text)
            if not total:
                raise RuntimeError("Missing PSS; retain raw dump, never substitute zero")
            row = dict(seconds=seconds, pss_mib=int(total[1]) / 1024)
            for key, label in (("java_mib", "Java Heap"), ("native_mib", "Native Heap"),
                               ("graphics_mib", "Graphics")):
                field = re.search(r"^\s*" + label + r":\s*(\d+)", text, re.M)
                row[key] = int(field[1]) / 1024 if field else None
            temperature = re.search(r"^\s*temperature:\s*(\d+)", battery, re.M)
            row["temperature_c"] = int(temperature[1]) / 10 if temperature else None
            rows.append(row)
            (args.output / "samples.json").write_text(json.dumps(rows, indent=2))
            index += 1
            time.sleep(max(0, start + min(index * args.interval, 1800) - time.monotonic()))
    report = summarize(rows, args.interval)
    report_file.write_text(json.dumps(report, indent=2))
    print(json.dumps(report, indent=2))
    if not report["numerical_gates_pass"]:
        raise SystemExit(1)


if __name__ == "__main__":
    main()
