"""Device regression: launch XBlocker, preview back from theme to settings.
Requires Python Pillow and an attached, unlocked Android device. Keeps output local.
"""
import argparse,io,json,subprocess,time
import numpy as np
from pathlib import Path
from PIL import Image,ImageChops,ImageStat
parser=argparse.ArgumentParser(); parser.add_argument("--label",default="check"); args=parser.parse_args()
adb=["D:/Android/Sdk/platform-tools/adb.exe","-s","3B164L002P800000"]
out=Path("artifacts/ui-device");out.mkdir(parents=True,exist_ok=True)
def run(*args): return subprocess.run(adb+list(args),capture_output=True,check=True).stdout
def tap(x,y): run("shell","input","tap",str(x),str(y));time.sleep(.5)
def shot(name):
    b=run("exec-out","screencap","-p");(out/(args.label+"-"+name+".png")).write_bytes(b)
    return Image.open(io.BytesIO(b)).convert("RGB")
def check_screen(text):
    run("shell","uiautomator","dump","/data/local/tmp/xblocker-test.xml")
    xml=run("shell","cat","/data/local/tmp/xblocker-test.xml").decode()
    assert 'package="io.github.bileizhen.xblocker"' in xml and ('text="'+text+'"') in xml, "Unexpected foreground page; device was being used"
run("shell","am","force-stop","io.github.bileizhen.xblocker")
run("shell","am","start","-W","-n","io.github.bileizhen.xblocker/.ui.MainActivity");time.sleep(1.8)
def tap_until(x,y,text,tries=4):
    for _ in range(tries):
        tap(x,y)
        try: check_screen(text); return
        except AssertionError: continue
    raise AssertionError("tap_until failed: "+text)
tap_until(1037,2656,"主题设置")
base=shot("target")
tap_until(300,600,"启用 Monet 颜色")
p=subprocess.Popen(adb+["shell","input","swipe","1","1300","800","1300","2400"],stdout=subprocess.DEVNULL)
time.sleep(1.7);mid=shot("gesture");p.wait();time.sleep(.9)
shot("result");check_screen("主题设置")
# The destination slides in lockstep from the edge during the gesture, so align
# the revealed left strip against the settled target at the best shift instead
# of expecting a static copy; a blank window background fails the variance gate.
g=np.asarray(mid.convert("RGB"),dtype=np.int16)
b=np.asarray(base.convert("RGB"),dtype=np.int16)
strip=g[400:1800,12:55]
strip_std=float(strip.std())
shifts=range(0,b.shape[1]-55,12)
# The miuix transition dims the destination while it is revealed, so compare
# brightness-invariant structure: subtract each strip's mean before diffing.
def scaled_err(dx):
    c=b[400:1800,12+dx:55+dx]
    scale=float(strip.mean()/max(c.mean(),1.0))
    return np.abs(strip-c*scale).mean(),scale
best_dx,(best_err,best_scale)=min(((dx,scaled_err(dx)) for dx in shifts),key=lambda e:e[1][0])
best_err=float(best_err); best_scale=round(float(best_scale),2)
result={"left_strip_std":round(strip_std,1),"best_shift_px":best_dx,
        "aligned_error":round(best_err,2),"dim_scale":best_scale,
        "pass":bool(best_err<18 and strip_std>8 and best_dx<=340 and best_scale<=1.05)}
(out/(args.label+"-result.json")).write_text(json.dumps(result))
print(json.dumps(result));raise SystemExit(0 if result["pass"] else 1)
