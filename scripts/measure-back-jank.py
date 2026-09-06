"""Device regression: measure frame jank while the predictive back gesture runs.

Launches XBlocker, opens the theme page, resets gfxinfo, performs the same edge
swipe used by check-predictive-back.py, then parses the gfxinfo summary.
Requires an attached, unlocked Android device. Keeps output local.

Usage: python scripts/measure-back-jank.py --label before
"""
import argparse
import json
import re
import subprocess
import time
from pathlib import Path

parser = argparse.ArgumentParser()
parser.add_argument("--label", default="measure")
args = parser.parse_args()

ADB = ["D:/Android/Sdk/platform-tools/adb.exe", "-s", "3B164L002P800000"]
OUT = Path("artifacts/ui-device")
OUT.mkdir(parents=True, exist_ok=True)


def run(*cmd):
    return subprocess.run(ADB + list(cmd), capture_output=True, check=True).stdout


def tap(x, y):
    run("shell", "input", "tap", str(x), str(y))
    time.sleep(0.6)


run("shell", "am", "force-stop", "io.github.xblocker")
run("shell", "am", "start", "-W", "-n", "io.github.xblocker/.ui.MainActivity")
time.sleep(1.0)
tap(1037, 2656)  # settings tab
tap(300, 600)    # theme settings row

run("shell", "dumpsys", "gfxinfo", "io.github.xblocker", "reset")
subprocess.run(
    ADB + ["shell", "input", "swipe", "1", "1300", "800", "1300", "2400"],
    stdout=subprocess.DEVNULL,
)
time.sleep(0.8)
raw = run("shell", "dumpsys", "gfxinfo", "io.github.xblocker").decode()
(OUT / f"{args.label}-jank.txt").write_text(raw, encoding="utf-8")


def grab(pattern):
    match = re.search(pattern, raw)
    return int(match.group(1)) if match else None


summary = {
    "total_frames": grab(r"Total frames rendered: (\d+)"),
    "janky_frames": grab(r"Janky frames: (\d+)"),
    "janky_percent": (lambda m: float(m.group(1)) if m else None)(
        re.search(r"Janky frames: \d+ \(([\d.]+)%\)", raw)),
    "p50": grab(r"50th percentile: (\d+)"),
    "p90": grab(r"90th percentile: (\d+)"),
    "p95": grab(r"95th percentile: (\d+)"),
    "p99": grab(r"99th percentile: (\d+)"),
}
(OUT / f"{args.label}-jank.json").write_text(json.dumps(summary))
print(json.dumps(summary))
