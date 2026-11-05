"""Capture real app screens through UIAutomator text targets."""
import pathlib
import re
import subprocess
import time
import xml.etree.ElementTree as ET

OUTPUT = pathlib.Path('screenshots')
OUTPUT.mkdir(exist_ok=True)
def adb(*args):
    return subprocess.check_output(['adb',*args])
def tap_text(text):
    for attempt in range(3):
        adb('shell','uiautomator','dump','/sdcard/atlas-window.xml')
        root=ET.fromstring(adb('shell','cat','/sdcard/atlas-window.xml'))
        nodes=[n for n in root.iter('node') if n.attrib.get('text')==text]
        if nodes:
            numbers=list(map(int,re.findall(r'\d+',nodes[-1].attrib['bounds'])))
            adb('shell','input','tap',str((numbers[0]+numbers[2])//2),str((numbers[1]+numbers[3])//2))
            time.sleep(1)
            return
        time.sleep(1)
    raise RuntimeError('Missing navigation target '+text)

adb('install','-r','app/build/outputs/apk/standalone/debug/app-standalone-debug.apk')
adb('shell','wm','density','160')
for width in [320,360,393,432]:
    for scale in ['1.0','1.5']:
        adb('shell','wm','size',f'{width}x900')
        adb('shell','settings','put','system','font_scale',scale)
        adb('shell','am','force-stop','com.lifeatlas.app.newhost')
        adb('shell','am','start','-n','com.lifeatlas.app.newhost/com.lifeatlas.app.MainActivity')
        time.sleep(3)
        for name,label in [('workspace','首页'),('spaces','空间'),('history','记录'),('analytics','分析'),('settings','我的')]:
            tap_text(label)
            (OUTPUT/f'{name}-{width}-{scale}.png').write_bytes(adb('exec-out','screencap','-p'))
adb('shell','settings','put','system','font_scale','1.0')
adb('shell','wm','size','900x432')
adb('shell','am','force-stop','com.lifeatlas.app.newhost')
adb('shell','am','start','-n','com.lifeatlas.app.newhost/com.lifeatlas.app.MainActivity')
time.sleep(3)
(OUTPUT/'workspace-landscape.png').write_bytes(adb('exec-out','screencap','-p'))
adb('pull','/sdcard/Pictures/LifeAtlas',str(OUTPUT/'instrumented'))
adb('shell','wm','size','reset')
adb('shell','wm','density','reset')
