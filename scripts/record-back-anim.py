"""Record the back animations on device and analyze the video frame by frame.

Usage: python scripts/record-back-anim.py arrow|gesture
Requires ffmpeg on PATH. Keeps output in artifacts/ui-device.
"""
import subprocess
import sys
import time
from pathlib import Path

MODE = sys.argv[1] if len(sys.argv) > 1 else "arrow"
ADB = ["D:/Android/Sdk/platform-tools/adb.exe", "-s", "3B164L002P800000"]
OUT = Path("artifacts/ui-device")
OUT.mkdir(parents=True, exist_ok=True)


def run(*a):
    return subprocess.run(ADB + list(a), capture_output=True)


def shell(*a):
    return run("shell", *a)


def has_text(t):
    shell("uiautomator", "dump", "/data/local/tmp/x.xml")
    return f'text="{t}"' in run("shell", "cat", "/data/local/tmp/x.xml").stdout.decode(errors="ignore")


shell("am", "force-stop", "io.github.xblocker")
shell("am", "start", "-W", "-n", "io.github.xblocker/.ui.MainActivity")
time.sleep(2.0)
for _ in range(4):
    shell("input", "tap", "1037", "2656")
    time.sleep(1.0)
    if has_text("主题设置"):
        break
for _ in range(4):
    shell("input", "tap", "300", "600")
    time.sleep(1.0)
    if has_text("启用 Monet 颜色"):
        break
time.sleep(0.8)

rec = subprocess.Popen(
    ADB + ["shell", "screenrecord", "--time-limit", "3", "/data/local/tmp/back.mp4"],
    stdout=subprocess.DEVNULL,
)
time.sleep(0.6)
if MODE == "arrow":
    shell("input", "tap", "125", "230")
    time.sleep(1.6)
else:
    shell("input", "swipe", "1", "1300", "800", "1300", "2400")
    time.sleep(1.6)
rec.wait()
run("pull", "/data/local/tmp/back.mp4", str(OUT / f"{MODE}.mp4"))
print("saved", OUT / f"{MODE}.mp4")
