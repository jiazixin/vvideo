#!/usr/bin/env python3
"""Clear VVideo's local cache through its visible storage confirmation.

Only use when the storage screen is already open and cache clearing is intended.
Checks foreground ownership and the confirmation copy before touching anything.
No media, connection, bookmark or database files are accessed directly.
"""
import importlib.util
from pathlib import Path
import re
import time


def main():
    spec = importlib.util.spec_from_file_location(
        "ui", Path(__file__).with_name("profile-ui-workload.py"))
    ui = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(ui)
    nodes = ui.snapshot()
    if not ui.matching(nodes, "缓存上限"):
        raise RuntimeError("Open the storage screen before clearing its cache")
    targets = [n for n in nodes if n.get("text", "").startswith("清除缓存 ·")]
    if len(targets) != 1:
        raise RuntimeError("Expected one storage cache control")
    sizes = re.findall(r"(?:Physical|Override) size: (\d+)x(\d+)", ui.adb("shell", "wm", "size"))
    if not sizes:
        raise RuntimeError("Missing observed display size")
    width, height = map(int, sizes[-1])
    ui.require_foreground()
    ui.helpers.tap(targets[0], width, height)
    time.sleep(.5)
    nodes = ui.snapshot()
    targets = ui.matching(nodes, "清除缓存")
    if len(targets) != 2 or not any(
            "原件、连接与浏览位置都会保留" in n.get("text", "") for n in nodes):
        raise RuntimeError("Expected the local cache confirmation")
    ui.require_foreground()
    ui.helpers.tap(targets[-1], width, height)
    time.sleep(.5)
    values = [n.get("text", "") for n in ui.snapshot()]
    if values.count("0.0 KB") < 2:
        raise RuntimeError("Video and image usage did not become zero")
    print("video_image_usage_zero=true")
    for text in values:
        if text.startswith("缓存已清理"):
            print(text)


if __name__ == "__main__":
    main()
