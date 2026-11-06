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
def tap_text(text, editor=False):
    for attempt in range(3):
        adb('shell','uiautomator','dump','/sdcard/atlas-window.xml')
        root=ET.fromstring(adb('shell','cat','/sdcard/atlas-window.xml'))
        nodes=[n for n in root.iter('node') if (
            n.attrib.get('class')=='android.widget.EditText' if editor else n.attrib.get('text')==text
        )]
        if nodes:
            bounds=[list(map(int,re.findall(r'\d+',n.attrib['bounds']))) for n in nodes]
            numbers=max(bounds,key=lambda box:box[1])
            adb('shell','input','tap',str((numbers[0]+numbers[2])//2),str((numbers[1]+numbers[3])//2))
            time.sleep(1)
            return
        time.sleep(1)
    raise RuntimeError('Missing navigation target '+text)

adb('install','-r','app/build/outputs/apk/standalone/debug/app-standalone-debug.apk')
adb('shell','wm','density','160')
for width in [320,360,393,432,600,840]:
    for scale in ['1.0','1.3','1.5']:
        adb('shell','wm','size',f'{width}x900')
        adb('shell','settings','put','system','font_scale',scale)
        adb('shell','am','force-stop','com.lifeatlas.app.newhost.test')
        adb('shell','am','start','-n','com.lifeatlas.app.newhost.test/com.lifeatlas.app.MainActivity')
        time.sleep(3)
        for name,label in [('workspace','输入'),('history','时间轴'),('analytics','分析'),('settings','我的')]:
            tap_text(label)
            (OUTPUT/f'{name}-{width}-{scale}.png').write_bytes(adb('exec-out','screencap','-p'))
            if name=='workspace' and width in [320,393] and scale=='1.5':
                tap_text('输入',editor=True)
                adb('shell','input','text','LifeAtlas')
                time.sleep(1)
                (OUTPUT/f'keyboard-{width}-{scale}.png').write_bytes(adb('exec-out','screencap','-p'))
                adb('shell','input','keyevent','4')
                time.sleep(1)
adb('shell','settings','put','system','font_scale','1.0')
adb('shell','wm','size','900x432')
adb('shell','am','force-stop','com.lifeatlas.app.newhost.test')
adb('shell','am','start','-n','com.lifeatlas.app.newhost.test/com.lifeatlas.app.MainActivity')
time.sleep(3)
(OUTPUT/'workspace-landscape.png').write_bytes(adb('exec-out','screencap','-p'))
adb('pull','/sdcard/Pictures/LifeAtlas',str(OUTPUT/'instrumented'))
adb('shell','wm','size','reset')
adb('shell','wm','density','reset')
