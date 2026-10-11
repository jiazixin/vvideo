#!/usr/bin/env python3
"""Run the existing Perfetto collector with VVideo foreground action guards.

The frame formula, scroll counts, cache options and original trace outputs are
unchanged. Every injected swipe first checks the resumed app, so a user leaving
VVideo aborts the capture rather than sending gestures to another application.
"""
import importlib.util
from pathlib import Path


def module(name, path):
    spec = importlib.util.spec_from_file_location(name, path)
    loaded = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(loaded)
    return loaded


here = Path(__file__).resolve().parent
collector = module("frame_collector", here.parents[2] / "scripts/measure-device-frames.py")
workload = module("channel_workload", here / "profile-ui-workload.py")
original_adb = collector.adb


def guarded_adb(*args, **kwargs):
    if args[:3] == ("shell", "input", "swipe"):
        workload.require_foreground()
    return original_adb(*args, **kwargs)


if __name__ == "__main__":
    collector.adb = guarded_adb
    collector.main()
