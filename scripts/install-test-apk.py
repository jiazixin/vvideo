#!/usr/bin/env python3
"""Install this project's test builds and acknowledge the observed OEM prompt.

No settings or authentication protections are bypassed. Unexpected risk/password
prompts fail so that the caller can retain the diagnostic and report the block.
"""
import argparse
import re
import subprocess
import time
import xml.etree.ElementTree as ET
from pathlib import Path


def adb(*args, **kwargs):
    return subprocess.run(["adb", *args], check=True, timeout=10, **kwargs)


def center(node):
    values = list(map(int, re.findall(r"\d+", node.get("bounds", ""))))
    if len(values) != 4:
        raise RuntimeError("Invalid installer bounds")
    return str((values[0] + values[2]) // 2), str((values[1] + values[3]) // 2)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("apk", type=Path)
    parser.add_argument("--package", required=True, choices=["dev.vvideo", "dev.vvideo.test", "dev.vvideo.benchmark"])
    args = parser.parse_args()
    apk = args.apk.resolve()
    root = Path(__file__).resolve().parents[1]
    if not apk.is_relative_to(root / "android") or not apk.is_file():
        raise RuntimeError("Only locally built Android project APKs are accepted")
    process = subprocess.Popen(["adb", "install", "-r", str(apk)], stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)
    try:
        deadline = time.monotonic() + 60
        label = "VVideo" if args.package == "dev.vvideo" else args.package
        while process.poll() is None and time.monotonic() < deadline:
            try:
                adb("shell", "uiautomator", "dump", "/sdcard/vvideo-installer-helper.xml", stdout=subprocess.DEVNULL)
                snapshot = adb("exec-out", "cat", "/sdcard/vvideo-installer-helper.xml", stdout=subprocess.PIPE).stdout
                nodes = list(ET.fromstring(snapshot).iter("node"))
                installer = [n for n in nodes if n.get("package") == "com.android.packageinstaller"]
                texts = [n.get("text", "") for n in installer]
                if label not in texts:
                    time.sleep(1)
                    continue
                if any(n.get("password") == "true" for n in installer) or any("病毒" in t or "恶意" in t for t in texts):
                    raise RuntimeError("Unexpected security/authentication prompt")
                check = next((n for n in installer if n.get("text") == "已了解应用的风险检测结果"), None)
                if check is not None and check.get("checked") == "false":
                    adb("shell", "input", "tap", *center(check))
                button = next((n for n in installer if n.get("content-desc") == "继续安装"), None)
                if button is not None:
                    adb("shell", "input", "tap", *center(button))
            except (subprocess.TimeoutExpired, ET.ParseError):
                pass
            time.sleep(.3)
        if process.poll() is None:
            process.terminate()
            raise RuntimeError("Installation still requires attention after 60 seconds")
        output = process.communicate()[0]
        print(output, end="")
        if process.returncode != 0 or "Success" not in output:
            raise RuntimeError("APK installation failed")
    finally:
        if process.poll() is None:
            process.terminate()
            try:
                process.wait(timeout=3)
            except subprocess.TimeoutExpired:
                process.kill()
                process.wait(timeout=3)



if __name__ == "__main__":
    main()
