#!/usr/bin/env python3
"""Drive the channel UI against the existing synthetic media/large fixture.

Runs outside the app process. Reuses the repository's bounded ADB/XML helpers;
never changes device policies, media, credentials or the installed application.
Use --screen to prepare a capture, or --minutes with a fresh JSONL workload log.
"""
import argparse
import importlib.util
import json
from pathlib import Path
import re
import time

spec = importlib.util.spec_from_file_location(
    "device_helpers", Path(__file__).resolve().parents[3] / "scripts/drive-device-soak.py")
helpers = importlib.util.module_from_spec(spec)
spec.loader.exec_module(helpers)
adb, matching = helpers.adb, helpers.matching


def require_foreground():
    state = adb("shell", "dumpsys", "activity", "activities")
    resumed = [line for line in state.splitlines() if "topResumedActivity=" in line]
    if not resumed or not any(" dev.vvideo/" in line for line in resumed):
        raise RuntimeError("VVideo left the foreground; workload stopped")


def snapshot():
    require_foreground()
    nodes = helpers.snapshot()
    if any(n.get("text", "") in ("无法播放这个视频", "这台手机无法解码此视频") for n in nodes):
        raise RuntimeError("Visible playback error in channel UI")
    return nodes


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--screen", choices=("album", "video", "home", "storage"))
    parser.add_argument("--minutes", type=int, choices=range(1, 61))
    parser.add_argument("--output", type=Path)
    args = parser.parse_args()
    if not args.screen and not args.minutes:
        parser.error("Choose --screen or --minutes")
    if args.minutes and (args.output is None or args.output.exists()):
        parser.error("Workload requires a fresh --output JSONL path")
    size = re.findall(r"(?:Physical|Override) size: (\d+)x(\d+)", adb("shell", "wm", "size"))
    if not size:
        raise RuntimeError("Missing observed display size")
    width, height = map(int, size[-1])
    pid = adb("shell", "pidof", "dev.vvideo").strip()
    if not re.fullmatch(r"\d+", pid):
        raise RuntimeError("Open VVideo with the synthetic fixture first")

    def tap(node):
        require_foreground()
        helpers.tap(node, width, height)
        time.sleep(.5)

    def click(nodes, label):
        targets = matching(nodes, label)
        if not targets:
            raise RuntimeError(f"Missing visible control: {label}")
        tap(targets[0])

    def home():
        for _ in range(7):
            nodes = snapshot()
            if matching(nodes, "设置") and not matching(nodes, "返回"):
                return nodes
            if not matching(nodes, "返回"):
                require_foreground()
                adb("shell", "input", "tap", str(width // 2), str(height * 2 // 5))
                nodes = snapshot()
            if not matching(nodes, "返回"):
                raise RuntimeError("Cannot observe an app back control")
            click(nodes, "返回")
        raise RuntimeError("Home not reached within seven observed transitions")

    def root():
        click(home(), "media")
        nodes = snapshot()
        if not matching(nodes, "全部"):
            raise RuntimeError("Expected synthetic media root with media tabs")
        click(nodes, "全部")
        return snapshot()

    def album():
        # Subfolders are listed above the albums of the media root.
        click(root(), "large")
        nodes = snapshot()
        if not matching(nodes, "large"):
            raise RuntimeError("Synthetic large album did not open")

    def video():
        nodes = root()
        click(nodes, "视频")
        time.sleep(.5)
        nodes = snapshot()
        targets = [n for n in nodes if n.get("content-desc", "").endswith("，video")]
        if not targets:
            raise RuntimeError("Missing synthetic video thumbnail")
        tap(targets[0])
        time.sleep(1)
        nodes = snapshot()
        if not matching(nodes, "播放进度"):
            require_foreground()
            adb("shell", "input", "tap", str(width // 2), str(height * 2 // 5))
            if not matching(snapshot(), "播放进度"):
                raise RuntimeError("Missing playback controls after reveal")

    def storage():
        click(home(), "设置")
        click(snapshot(), "存储用量")
        if not matching(snapshot(), "缓存上限"):
            raise RuntimeError("Storage screen not reached")

    def swipe(reverse):
        require_foreground()
        start, end = (height // 3, height * 3 // 4) if reverse else (height * 3 // 4, height // 3)
        adb("shell", "input", "swipe", str(width // 2), str(start), str(width // 2), str(end), "300")

    if args.screen:
        dict(album=album, video=video, home=home, storage=storage)[args.screen]()
    if not args.minutes:
        return
    args.output.parent.mkdir(parents=True, exist_ok=True)
    start, cycles = time.monotonic(), 0

    def record(**fields):
        with args.output.open("a") as out:
            out.write(json.dumps(dict(seconds=time.monotonic() - start, **fields)) + "\n")

    try:
        while time.monotonic() - start < args.minutes * 60:
            if adb("shell", "pidof", "dev.vvideo").strip() != pid:
                raise RuntimeError("Application process changed")
            album()
            for reverse in (False, True):
                for _ in range(12):
                    swipe(reverse)
                    time.sleep(.1)
            video()
            for reverse in (False, True):
                for _ in range(6):
                    swipe(reverse)
                    time.sleep(.8)
                    snapshot()
            cycles += 1
            record(cycle=cycles, pid=int(pid))
            print(f"cycle={cycles} seconds={time.monotonic()-start:.1f}", flush=True)
        record(completed=True, cycles=cycles, minutes=args.minutes)
    except Exception as error:
        record(completed=False, error=str(error))
        raise


if __name__ == "__main__":
    main()
