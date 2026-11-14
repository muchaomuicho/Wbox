"""Real edge Back, time toggles and card drag on isolated emulator-5556 only.

Requires an enabled SystemUI edge-swipe monitor. Backs up synthetic application
preferences and emulator settings and restores them even on failure.
"""
import hashlib,io,json,pathlib,re,subprocess,sys,time,traceback,zipfile,xml.etree.ElementTree as ET
from PIL import Image

ROOT=pathlib.Path(__file__).resolve().parent.parent
ADB='D:/DevTools/Android/Sdk/platform-tools/adb.exe'
checks=[];observations={};screens=[];failure=None
sys.stdout.reconfigure(encoding='utf-8')
def adb(*args,data=None,timeout=40):
    r=subprocess.run([ADB,'-s','emulator-5556',*args],input=data,capture_output=True,timeout=timeout)
    if r.returncode:raise RuntimeError(r.stderr.decode(errors='replace'))
    return r.stdout
def nodes():
    adb('shell','uiautomator','dump','/sdcard/wbox-interactions075.xml')
    return list(ET.fromstring(adb('shell','cat','/sdcard/wbox-interactions075.xml')).iter('node'))
def has(label,attr='text'):return any(n.get(attr)==label for n in nodes())
def find(label,attr='text'):
    found=[n for n in nodes() if n.get(attr)==label]
    return found[-1] if attr=='text' else found[0]
def bounds(n):return tuple(map(int,re.findall(r'\d+',n.get('bounds',''))))
def tap(label,attr='text'):
    wait(lambda:has(label,attr),'tap '+label)
    l,t,r,b=bounds(find(label,attr));adb('shell','input','tap',str((l+r)//2),str((t+b)//2));time.sleep(.4)
def wait(predicate,label,timeout=25):
    end=time.monotonic()+timeout
    while time.monotonic()<end:
        if predicate():return
        time.sleep(.15)
    raise AssertionError('Timed out '+label)
def check(ok,label,detail=None):
    if not ok:raise AssertionError(label+': '+str(detail))
    if label not in checks:checks.append(label)
    if detail is not None:observations[label]=detail
    print('PASS',label,flush=True)
def save(value):
    data=value if isinstance(value,bytes) else json.dumps(value).encode()
    adb('shell','run-as','com.wbox.app','sh','-c',"'cat > files/profiles.json'",data=data)
def prefs():return json.loads(adb('shell','run-as','com.wbox.app','cat','files/profiles.json'))
content=json.dumps({'outbounds':[{'type':'selector','tag':'Proxy','outbounds':['DIRECT']},{'type':'direct','tag':'DIRECT'}],'route':{'final':'Proxy'}})
def seed(count=3,mode='config'):
    now=int(time.time()*1000)
    usage={'upload':0,'download':30*1024*1024,'total':300*1024*1024*1024,'expire':int(time.time())+18*86400}
    p=[{'id':f'p{i}','name':f'Orion {i+1:02}','core':'sing-box','content':content,'updated':now-180000,'url':f'https://example.invalid/p{i}','subscription':{'usage':usage}} for i in range(count)]
    data={'mode':mode,'engine':'sing-box','node_engine':'sing-box','theme':'light','accent':'pink','app_mode':'exclude','apps':[],
          'configs':p,'active_config':'p2','config_sing-box':content,'nodes':[{'link':'http://127.0.0.1:19283#Orion'}],'selected':'Orion'}
    adb('shell','am','force-stop','com.wbox.app');save(data);adb('shell','am','start','-W','-n','com.wbox.app/.MainActivity');wait(lambda:has('概览'),'main')
def edge(side):
    x=1 if side=='left' else 1078;end=345 if side=='left' else 735
    adb('shell','input','swipe',str(x),'1248',str(end),'1248','280');time.sleep(.5)
def ime():return bool(re.search(rb'mInputShown=true|isInputViewShown=true',adb('shell','dumpsys','input_method')))
def capture(name):
    im=Image.open(io.BytesIO(adb('exec-out','screencap','-p'))).convert('RGB')
    path=ROOT/f'docs/previews/0.7.5-android-{name}.png';im.save(path);screens.append(path.relative_to(ROOT).as_posix());return im
def corner(im):
    top=round(24*420/160)+3
    return all(max(abs(a-b) for a,b in zip(im.getpixel((x,y)),im.getpixel((540,y))))<=3 for x in [2,1077] for y in [top,top+5,top+12])
def native_drag(start,end):
    source=ROOT/'tools/android-gesture/TouchGesture.java'
    cache=ROOT/'.tools/android-gesture';classes=cache/'classes';dex=cache/'dex'
    classes.mkdir(parents=True,exist_ok=True);dex.mkdir(exist_ok=True)
    if not (dex/'classes.dex').exists() or (dex/'classes.dex').stat().st_mtime<source.stat().st_mtime:
        sdk=pathlib.Path(ADB).parent.parent;lib=sdk/'platforms/android-34/android.jar'
        subprocess.run(['D:/DevTools/JDKs/ms-21.0.9/bin/javac.exe','-source','17','-target','17','-cp',str(lib),'-d',str(classes),str(source)],check=True)
        subprocess.run([str(sdk/'build-tools/34.0.0/d8.bat'),'--min-api','26','--lib',str(lib),'--output',str(dex),str(classes/'TouchGesture.class')],check=True)
    jar=cache/'touch-gesture.jar'
    with zipfile.ZipFile(jar,'w') as archive:archive.write(dex/'classes.dex','classes.dex')
    adb('push',str(jar),'/data/local/tmp/wbox-gesture-075.jar')
    adb('shell','CLASSPATH=/data/local/tmp/wbox-gesture-075.jar','app_process','/','TouchGesture',*[str(x) for x in (*start,*end)])
original=adb('shell','run-as','com.wbox.app','cat','files/profiles.json')
hard_ime=adb('shell','settings','get','secure','show_ime_with_hard_keyboard').decode().strip()
appops=adb('shell','appops','get','com.wbox.app','ACTIVATE_VPN').decode().strip()
try:
    check(b'versionName=0.7.5' in adb('shell','dumpsys','package','com.wbox.app'),'Current 0.7.5 debug APK installed')
    observations['apk_sha256']=hashlib.sha256((ROOT/'android/app/build/outputs/apk/debug/app-debug.apk').read_bytes()).hexdigest()
    check(b'[Gesture Monitor] edge-swipe' in adb('shell','dumpsys','input'),'SystemUI has a real edge-swipe monitor')
    observations['gesture_environment']='qemu.hw.mainkeys=0 set temporarily, Android framework restarted; no AVD configuration changed'
    adb('shell','settings','put','secure','show_ime_with_hard_keyboard','1')
    if '--sorting-only' in sys.argv:
        seed()
    elif '--library-only' not in sys.argv:
        seed();tap('设置');tap('分应用代理');wait(lambda:has('代理范围'),'apps');time.sleep(.3)
        check(corner(capture('apps')),'App sheet top corners remain continuous')
        edge('left');check(not has('代理范围') and has('设置'),'Left edge closes app sheet')
        tap('分应用代理');wait(lambda:has('代理范围'),'apps reopen');time.sleep(.3);edge('right');check(not has('代理范围') and has('设置'),'Right edge closes reopened app sheet')
        tap('分应用代理');wait(lambda:has('代理范围'),'apps IME reopen');time.sleep(.3);tap('搜索应用');wait(ime,'app IME');edge('left');wait(lambda:not ime(),'IME dismissed');check(has('代理范围'),'App edge Back hides IME before sheet')
        edge('right');check(not has('代理范围'),'App sheet closes on the next edge Back')
        tap('概览');tap('选择配置','content-desc');wait(lambda:has('Orion 01'),'picker');edge('left');check(has('概览') and not has('选择配置'),'Left edge closes profile picker')
        tap('选择配置','content-desc');wait(lambda:has('Orion 01'),'picker reopen');edge('right');check(not has('选择配置'),'Right edge closes profile picker')
        adb('shell','appops','set','com.wbox.app','ACTIVATE_VPN','allow');tap('连接代理','content-desc');wait(lambda:has('查看连接','content-desc'),'VPN start',45)
        tap('查看连接','content-desc');wait(lambda:has('搜索连接'),'connections');time.sleep(.3);check(corner(capture('connections')),'Connection sheet top corners remain continuous')
        edge('left');check(not has('搜索连接'),'Left edge closes connection sheet')
        tap('查看连接','content-desc');wait(lambda:has('搜索连接'),'connections reopen');edge('right');check(not has('搜索连接'),'Right edge closes connection sheet')
        tap('查看连接','content-desc');tap('搜索连接');wait(ime,'connection IME');edge('right');wait(lambda:not ime(),'connection IME hidden');check(has('搜索连接'),'Connection edge Back hides IME before sheet')
        edge('left');check(not has('搜索连接'),'Connection sheet closes on the next edge Back')
        tap('断开代理连接','content-desc');wait(lambda:has('连接代理','content-desc'),'VPN stop')
    else:
        previous=json.loads((ROOT/'docs/verification/0.7.5-android-interactions.json').read_text())
        assert previous['observations']['apk_sha256']==observations['apk_sha256']
        assert 'Connection sheet closes on the next edge Back' in previous['checks']
        checks[:]=previous['checks'];screens[:]=previous['screenshots'];observations.update(previous['observations'])
        observations['reused_same_apk_prefix']=len(checks)
        seed()
    tap('配置');tree=nodes();expiry=next(n.get('text') for n in tree if re.search(r'\d{4}/\d+/\d+ 到期$',n.get('text','')))
    relative=next(n.get('text') for n in tree if re.fullmatch(r'更新于 \d+ 分钟前',n.get('text','')))
    check(bool(relative),'Updated text defaults to relative time')
    tap(expiry);check(has('· 18 天后到期'),'Expiry switches to remaining days');tap('· 18 天后到期');check(has(expiry),'Expiry switches back to the date')
    tap(relative);absolute=next(n.get('text') for n in nodes() if re.fullmatch(r'更新于 \d{4}/\d+/\d+ \d\d:\d\d:\d\d',n.get('text','')))
    tap(absolute);check(any(re.fullmatch(r'更新于 \d+ 分钟前',n.get('text','')) for n in nodes()),'Updated time switches independently both ways')
    check(prefs()['active_config']=='p2','Time taps preserve the selected configuration')
    # Hold beyond the long-press threshold, then make a deliberately large first
    # move. The shell helper maintains one downTime and a real finger tool type.
    l,t,r,b=bounds(find('Orion 01'));start=((l+r)//2,(t+b)//2)
    l,t,r,b=bounds(find('Orion 03'));end=(start[0],(t+b)//2)
    native_drag(start,end)
    wait(lambda:prefs().get('config_order')==['p1','p2','p0'],'drag order saved')
    check(prefs().get('config_order')==['p1','p2','p0'],'Native long-press drag persists card order')
    observations['native_drag']='finger tool type, one downTime, 900ms hold, first MOVE >35% of the path'
    check(prefs()['active_config']=='p2','Card drag does not select another configuration')
    capture('profiles')
    adb('shell','am','force-stop','com.wbox.app');adb('shell','am','start','-W','-n','com.wbox.app/.MainActivity');wait(lambda:has('概览'),'relaunch');tap('选择配置','content-desc')
    tree=nodes();names=[n.get('text') for n in tree if re.fullmatch('Orion 0[123]',n.get('text',''))]
    check(names==['Orion 02','Orion 03','Orion 01'],'Restart and overview picker keep manual order',names);edge('right')
except Exception as error:
    failure=f'{type(error).__name__}: {error}';traceback.print_exc()
    (ROOT/'.tools/android-interactions-failure-075.png').write_bytes(adb('exec-out','screencap','-p'))
finally:
    adb('shell','am','force-stop','com.wbox.app');save(original)
    if hard_ime=='null':adb('shell','settings','delete','secure','show_ime_with_hard_keyboard')
    else:adb('shell','settings','put','secure','show_ime_with_hard_keyboard',hard_ime)
    match=re.search(r'ACTIVATE_VPN: (\w+)',appops);adb('shell','appops','set','com.wbox.app','ACTIVATE_VPN',match.group(1) if match else 'default')
    adb('shell','rm','-f','/sdcard/wbox-interactions075.xml')
    adb('shell','rm','-f','/data/local/tmp/wbox-gesture-075.jar')
    report='0.7.5-android-sort.json' if '--sorting-only' in sys.argv else '0.7.5-android-interactions.json'
    edges=['Left edge closes app sheet','Right edge closes reopened app sheet','Left edge closes connection sheet','Right edge closes connection sheet']
    (ROOT/'docs/verification'/report).write_text(json.dumps({'checks':checks,'observations':observations,'screenshots':screens,'failure':failure,'preferences_restored':True,'edgeGesturesVerified':failure is None and all(x in checks for x in edges)},ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
if failure:raise SystemExit(1)
