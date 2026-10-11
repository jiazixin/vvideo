#!/usr/bin/env python3
"""Drive VVideo's synthetic media/large/representative UI on the foreground phone.

Use with the configured profile APK and a separate memory collector. This host
driver avoids adding an instrumentation runner to the production process. It
touches only VVideo, uses observed accessibility bounds, and preserves its own
cycle/error log. It does not install, configure or change device policies.
"""
import argparse
import json
from pathlib import Path
import re
import subprocess
import time
import uuid
import xml.etree.ElementTree as ET

PACKAGE = "dev.vvideo"


def adb(*args):
    return subprocess.run(["adb", *args], check=True, capture_output=True,
                          text=True, timeout=30).stdout


def snapshot():
    remote = f"/data/local/tmp/vvideo-soak-{uuid.uuid4().hex}.xml"
    try:
        adb("shell", "uiautomator", "dump", remote)
        xml = adb("exec-out", "cat", remote)
    finally:
        adb("shell", "rm", "-f", remote)
    # A unique path prevents a failed dump from reusing a previous screen.
    all_nodes = list(ET.fromstring(xml).iter("node"))
    if not all_nodes or all_nodes[0].get("package") != PACKAGE:
        raise RuntimeError("A different app owns the foreground UI")
    nodes = [n for n in all_nodes if n.get("package") == PACKAGE]
    if not nodes:
        raise RuntimeError("VVideo is not the foreground UI; stop without touching other apps")
    if any("无法播放这个媒体" in n.get("text", "") for n in nodes):
        raise RuntimeError("Visible playback error; preserve failure")
    return nodes


def matching(nodes, text):
    return [n for n in nodes if (n.get("text") == text or n.get("content-desc") == text) and n.get("enabled") == "true"]


def tap(node, width, height):
    values = re.fullmatch(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", node.get("bounds", ""))
    if not values:
        raise RuntimeError("Missing observed target bounds")
    left, top, right, bottom = map(int, values.groups())
    if not 0 <= left < right <= width or not 0 <= top < bottom <= height:
        raise RuntimeError("Target is outside the visible screen")
    adb("shell", "input", "tap", str((left + right) // 2), str((top + bottom) // 2))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--minutes", type=int, default=30, choices=range(1, 61))
    parser.add_argument("--root-name", default="media", help="Observed synthetic library/root label")
    parser.add_argument("--video-directory", help="Observed video folder; defaults to the root")
    args = parser.parse_args()
    if args.output.exists():
        parser.error("Use a fresh output file")
    args.output.parent.mkdir(parents=True, exist_ok=True)
    size = re.findall(r"(?:Physical|Override) size: (\d+)x(\d+)", adb("shell", "wm", "size"))
    if not size:
        raise RuntimeError("Cannot establish display size")
    width, height = map(int, size[-1])
    pid = adb("shell", "pidof", PACKAGE).strip()
    if not re.fullmatch(r"\d+", pid):
        raise RuntimeError("Open the configured VVideo app first")
    start = time.monotonic()
    cycles = 0
    def swipe(reverse=False):
        first, last = (height // 3, height * 3 // 4) if reverse else (height * 3 // 4, height // 3)
        adb("shell", "input", "swipe", str(width // 2), str(first), str(width // 2), str(last), "300")
    def click(nodes, text):
        targets = matching(nodes, text)
        if len(targets) != 1:
            raise RuntimeError(f"Expected one visible {text!r} control, got {len(targets)}")
        tap(targets[0], width, height)
        time.sleep(.7)
    def root():
        nodes = snapshot()
        if not matching(nodes, "设置"):
            click(nodes, "返回")
            nodes = snapshot()
        if matching(nodes, "媒体库"):
            click(nodes, args.root_name)
            nodes = snapshot()
        if not matching(nodes, args.root_name):
            if matching(nodes, "返回"):
                click(nodes, "返回")
                nodes = snapshot()
            if matching(nodes, "媒体库"):
                click(nodes, args.root_name)
                nodes = snapshot()
        if not matching(nodes, args.root_name) or not matching(nodes, "设置") or not matching(nodes, "返回"):
            raise RuntimeError("Expected synthetic media root")
        for _ in range(12):
            if matching(nodes, "large"):
                return nodes
            swipe(True)
            time.sleep(.3)
            nodes = snapshot()
        raise RuntimeError("Synthetic root navigation did not become visible")
    def directory(name):
        nodes = root()
        if name != args.root_name:
            click(nodes, name)
            nodes = snapshot()
            if not matching(nodes, name) or not matching(nodes, "设置"):
                raise RuntimeError(f"Did not enter {name!r}")
        return nodes
    def video(nodes):
        targets = [n for n in nodes if n.get("content-desc", "").endswith("，video")]
        for _ in range(12):
            if targets:
                break
            swipe(True)
            time.sleep(.3)
            nodes = snapshot()
            targets = [n for n in nodes if n.get("content-desc", "").endswith("，video")]
        if not targets:
            raise RuntimeError("No observed synthetic video target")
        tap(targets[0], width, height)
        time.sleep(2)
        nodes = snapshot()
        if not any(n.get("content-desc") == "播放进度" for n in nodes):
            # Playback controls intentionally auto-hide. Reveal them through
            # the documented tap gesture before checking accessibility.
            adb("shell", "input", "tap", str(width // 2), str(height // 2))
            nodes = snapshot()
            if not any(n.get("content-desc") == "播放进度" for n in nodes):
                raise RuntimeError("Playback controls are missing after reveal")
    try:
        while time.monotonic() - start < args.minutes * 60:
            if adb("shell", "pidof", PACKAGE).strip() != pid:
                raise RuntimeError("VVideo process changed during workload")
            print(f"stage=album seconds={time.monotonic()-start:.1f}", flush=True)
            directory("large")
            for reverse in (False, True):
                for _ in range(12):
                    swipe(reverse)
                    time.sleep(.1)
            for name, changes in ((args.video_directory or args.root_name, 12), ("representative", 2)):
                print(f"stage={name} seconds={time.monotonic()-start:.1f}", flush=True)
                video(directory(name))
                for reverse in (False, True):
                    for _ in range(changes):
                        swipe(reverse)
                        time.sleep(.8)
                        snapshot()  # Observe every settled transition/error.
                adb("shell", "input", "keyevent", "4")
                time.sleep(.7)
            cycles += 1
            with args.output.open("a") as out:
                out.write(json.dumps(dict(cycle=cycles, seconds=time.monotonic()-start, pid=int(pid))) + "\n")
            print(f"cycle={cycles} seconds={time.monotonic()-start:.1f}", flush=True)
        with args.output.open("a") as out:
            out.write(json.dumps(dict(completed=True, cycles=cycles, seconds=time.monotonic()-start, minutes=args.minutes)) + "\n")
    except Exception as error:
        with args.output.open("a") as out:
            out.write(json.dumps(dict(completed=False, error=str(error), seconds=time.monotonic()-start)) + "\n")
        raise


if __name__ == "__main__":
    main()
