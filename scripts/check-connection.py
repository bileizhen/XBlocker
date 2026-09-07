"""Read diagnostic-only data from the installed DEBUG APK; never read X content."""
import json
import subprocess
import sys
import time

result = subprocess.run(
    ["adb", "shell", "run-as", "io.github.bileizhen.xblocker", "cat", "files/bridge-status.json"],
    capture_output=True, text=True, encoding="utf-8", errors="replace",
)
try:
    status = json.loads(result.stdout)
except ValueError:
    print("FAIL: No X process has reported to XBlocker. Open XBlocker, enable its X scope, restart X.")
    sys.exit(1)
age = int(time.time() * 1000) - status.get("lastSeen", 0)
pid = subprocess.run(["adb", "shell", "pidof", "com.twitter.android"], capture_output=True, text=True).stdout.split()
passed = status.get("hooks", 0) > 0 and str(status.get("pid")) in pid and age < 20_000
print(("PASS" if passed else "FAIL") + ": " + json.dumps(status, ensure_ascii=True))
if not passed:
    print("Open X in the foreground before checking: Android may freeze background processes.")
sys.exit(0 if passed else 1)
