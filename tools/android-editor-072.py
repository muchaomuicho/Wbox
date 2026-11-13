"""Isolated API35 checks for native edge-back, editor IME and scrolling.

Run only after the root agent grants emulator-5556 ownership and installs the
current x86_64 debug APK. Synthetic preferences and gesture mode are restored.
"""
import argparse, hashlib, io, json, pathlib, re, subprocess, sys, time
import xml.etree.ElementTree as ET
from PIL import Image

ROOT=pathlib.Path(__file__).resolve().parent.parent
sys.stdout.reconfigure(encoding='utf-8')
ADB=pathlib.Path('C:/Users/hw/AppData/Local/Android/Sdk/platform-tools/adb.exe')
VERSION=re.search(r"versionName '([^']+)'",(ROOT/'android/app/build.gradle').read_text(encoding='utf-8')).group(1)
parser=argparse.ArgumentParser();parser.add_argument('--serial',default='emulator-5556');parser.add_argument('--back-mode',choices=['edge','key'],default='edge');args=parser.parse_args()
if args.serial!='emulator-5556':raise SystemExit('Only the isolated emulator-5556 is supported')
checks=[];screens=[];original=None;failure=None

def adb(*values,data=None,timeout=35):
    result=subprocess.run([str(ADB),'-s',args.serial,*values],input=data,capture_output=True,timeout=timeout)
    if result.returncode:raise RuntimeError(result.stderr.decode(errors='replace'))
    return result.stdout

def nodes():
    adb('shell','uiautomator','dump','/sdcard/wbox-editor-072.xml')
    return list(ET.fromstring(adb('shell','cat','/sdcard/wbox-editor-072.xml')).iter('node'))

def find(label,attr='text'):
    matches=[n for n in nodes() if n.get(attr)==label]
    if not matches:raise AssertionError('Missing '+label)
    return matches[-1]

def bounds(node):return tuple(map(int,re.findall(r'\d+',node.get('bounds'))))
def tap(label,attr='text'):
    x1,y1,x2,y2=bounds(find(label,attr));adb('shell','input','tap',str((x1+x2)//2),str((y1+y2)//2));time.sleep(.25)
def has(label):return any(n.get('text')==label for n in nodes())
def wait(predicate,seconds=10):
    until=time.monotonic()+seconds
    while time.monotonic()<until:
        if predicate():return
        time.sleep(.2)
    raise AssertionError('Timed out waiting for state')
def check(ok,label):
    if not ok:raise AssertionError(label)
    checks.append(label);print('PASS',label,flush=True)
def screenshot(name):
    data=adb('exec-out','screencap','-p');path=ROOT/f'docs/previews/{VERSION}-android-{name}.png';path.parent.mkdir(exist_ok=True,parents=True);path.write_bytes(data);screens.append(str(path.relative_to(ROOT)));return Image.open(io.BytesIO(data)).convert('RGB')
def shot():return Image.open(io.BytesIO(adb('exec-out','screencap','-p'))).convert('RGB')
def ime():return bool(re.search(rb'mInputShown=true|isInputViewShown=true',adb('shell','dumpsys','input_method')))
def edge(side):
    if args.back_mode=='key':adb('shell','input','keyevent','KEYCODE_BACK')
    else:
        x=1 if side=='left' else width-2;to=int(width*.32) if side=='left' else int(width*.68)
        adb('shell','input','swipe',str(x),str(int(height*.52)),str(to),str(int(height*.52)), '280')
    time.sleep(.45)
def prefs():return json.loads(adb('shell','run-as','com.wbox.app','cat','files/profiles.json'))
def write_prefs(data):adb('shell','run-as','com.wbox.app','sh','-c',"'cat > files/profiles.json'",data=data)
def open_editor():
    tap('编辑配置','content-desc');wait(lambda:has('编辑配置'))
    check(not has('概览'),'Full-page editor hides main navigation')

long_content=json.dumps({'outbounds':[{'type':'direct','tag':'DIRECT'}],'route':{'rules':[{'domain':[f'node-{i}.example'],'outbound':'DIRECT'} for i in range(500)]}},indent=2)
profile={'id':'editor-check','name':'Editor · 长配置','core':'sing-box','content':long_content,'url':'','updated':0}
seed=json.dumps({'mode':'config','engine':'sing-box','theme':'light','configs':[profile],'active_config':'editor-check','config_sing-box':long_content}).encode()
overlay='com.android.internal.systemui.navbar.gestural'
overlays=adb('shell','cmd','overlay','list','--user','0').decode()
old_overlay=next((line.split()[-1] for line in overlays.splitlines() if '[x]' in line and 'com.android.internal.systemui.navbar.' in line),'')
try:
    try:original=adb('shell','run-as','com.wbox.app','cat','files/profiles.json')
    except RuntimeError:pass
    adb('shell','cmd','overlay','enable-exclusive','--user','0','--category',overlay)
    adb('shell','am','force-stop','com.wbox.app');write_prefs(seed);adb('shell','am','start','-W','-n','com.wbox.app/.MainActivity');wait(lambda:has('概览'))
    width,height=shot().size
    open_editor();edge('left');check(has('概览') and not has('编辑配置'),'Left screen-edge gesture returns from clean editor' if args.back_mode=='edge' else 'System Back returns from clean editor')
    open_editor();edge('right');check(has('概览') and not has('编辑配置'),'Right screen-edge gesture returns from clean editor' if args.back_mode=='edge' else 'System Back works after reopening editor')
    open_editor();box=bounds(find('配置内容','content-desc'));title=bounds(find('编辑配置'));tap('配置内容','content-desc');wait(ime)
    resized=bounds(find('配置内容','content-desc'))
    check(bounds(find('编辑配置'))==title and resized[3]<box[3]-100,'Keyboard resizes editor without moving toolbar')
    screenshot('editor-keyboard');edge('left');wait(lambda:not ime());check(has('编辑配置'),'First Back closes keyboard and keeps editor')
    tap('配置内容','content-desc');wait(ime);adb('shell','input','text','draft072');edge('right');wait(lambda:not ime());edge('right')
    check(has('放弃未保存的修改？'),'System Back asks before discarding changed text')
    tap('继续编辑');check(has('编辑配置'),'Continue editing keeps the draft')
    edge('left');tap('放弃修改');check(has('概览') and prefs()['configs'][0]['content']==long_content,'Discard returns without changing stored configuration')
    open_editor();x1,y1,x2,y2=bounds(find('配置内容','content-desc'));x=(x1+x2)//2
    before=shot();adb('shell','input','swipe',str(x),str(int(y2-(y2-y1)*.2)),str(x),str(int(y1+(y2-y1)*.2)),'100')
    early=shot();time.sleep(.25);late=shot()
    region=(x1+10,y1+20,x2-10,y2-20)
    digest=lambda im:hashlib.sha256(im.crop(region).tobytes()).hexdigest()
    check(digest(before)!=digest(early),'Fast drag scrolls a long configuration')
    check(digest(early)!=digest(late),'Configuration continues scrolling after finger release')
    screenshot('editor-scroll');edge('right');check(has('概览'),'Fast scrolling does not modify the configuration')
    open_editor();x1,y1,x2,y2=bounds(find('配置内容','content-desc'))
    # The first line is JSON; the second line has a real quoted key to select.
    x=x1+int((x2-x1)*.22);y=y1+100
    adb('shell','input','swipe',str(x),str(y),str(x),str(y),'850');time.sleep(.35)
    check(find('配置内容','content-desc').get('long-clickable')=='true','Native text selection remains enabled')
    screenshot('editor-selection');adb('shell','input','keyevent','KEYCODE_COPY')
    check(prefs()['configs'][0]['content']==long_content,'Long press and copy leave persisted configuration unchanged')
except Exception as error:
    failure=str(error)
    import traceback;traceback.print_exc()
finally:
    adb('shell','am','force-stop','com.wbox.app')
    if original is not None:write_prefs(original)
    else:adb('shell','run-as','com.wbox.app','rm','-f','files/profiles.json')
    adb('shell','rm','-f','/sdcard/wbox-editor-072.xml')
    if old_overlay and old_overlay!=overlay:adb('shell','cmd','overlay','enable-exclusive','--user','0','--category',old_overlay)
    path=ROOT/f'docs/verification/{VERSION}-android-editor.json';path.parent.mkdir(parents=True,exist_ok=True);path.write_text(json.dumps({'backMode':args.back_mode,'edgeGesturesVerified':args.back_mode=='edge' and failure is None,'checks':checks,'screenshots':screens,'failure':failure},ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
if failure:raise SystemExit(1)
