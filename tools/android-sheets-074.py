"""Verify modal sheet corners and app icons on isolated emulator-5556.

Uses synthetic profiles and installed emulator system apps. Restores application
preferences and font scale. Never operates on a phone or real subscription.
"""
import hashlib, io, json, pathlib, re, subprocess, time, traceback, sys
import xml.etree.ElementTree as ET
from PIL import Image

ROOT=pathlib.Path(__file__).resolve().parent.parent
ADB='D:/DevTools/Android/Sdk/platform-tools/adb.exe'
checks=[]; shots=[]; observations={}; failure=None
def adb(*args,data=None,timeout=40):
    r=subprocess.run([ADB,'-s','emulator-5556',*args],input=data,capture_output=True,timeout=timeout)
    if r.returncode:raise RuntimeError(r.stderr.decode(errors='replace'))
    return r.stdout
def nodes():
    adb('shell','uiautomator','dump','/sdcard/wbox-sheets074.xml')
    return list(ET.fromstring(adb('shell','cat','/sdcard/wbox-sheets074.xml')).iter('node'))
def bounds(n):return tuple(map(int,re.findall(r'\d+',n.get('bounds',''))))
def tap(label,attr='text'):
    found=[n for n in nodes() if n.get(attr)==label]
    if not found:raise AssertionError('Missing '+label)
    l,t,r,b=bounds(found[-1] if attr=='text' else found[0]);adb('shell','input','tap',str((l+r)//2),str((t+b)//2))
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
def seed(theme='light',accent='blue',count=2,mode='config',app_mode='include'):
    content=json.dumps({'outbounds':[{'type':'selector','tag':'Proxy','outbounds':['DIRECT']},{'type':'direct','tag':'DIRECT'}],'route':{'final':'Proxy'}})
    data={'mode':mode,'engine':'sing-box','node_engine':'sing-box','theme':theme,'accent':accent,
          'app_mode':app_mode,'apps':[],'configs':[{'id':f'p{i}','name':f'Orion {i+1:02}','core':'sing-box','content':content} for i in range(count)],
          'active_config':'p0','config_sing-box':content,'nodes':[{'link':'http://127.0.0.1:19283#Orion'}],'selected':'Orion'}
    adb('shell','am','force-stop','com.wbox.app');save(data)
    adb('shell','am','start','-W','-n','com.wbox.app/.MainActivity')
    wait(lambda:any(n.get('text')=='概览' for n in nodes()),'main page')
def capture(name):
    image=Image.open(io.BytesIO(adb('exec-out','screencap','-p'))).convert('RGB')
    path=ROOT/f'docs/previews/0.7.4-android-{name}.png';image.save(path)
    if path.relative_to(ROOT).as_posix() not in shots:shots.append(path.relative_to(ROOT).as_posix())
    return image
def docked(name,image):
    # STATUS_BAR_HEIGHT is 24 dp on this fixture; inspect several rows below it.
    density=int(re.findall(rb'\d+',adb('shell','wm','density'))[-1])/160
    top=round(24*density)+3
    samples=[[image.getpixel((x,y)) for x in [2,image.width//2,image.width-3]] for y in [top,top+5,top+12]]
    check(all(max(max(abs(a-b) for a,b in zip(p,row[1])) for p in row)<=3 for row in samples),name+' top corners join the sheet surface',{'rows':samples,'top_y':top})
def apps():
    tap('设置');tap('分应用代理')
    wait(lambda:any(n.get('content-desc','').startswith('应用图标 · ') for n in nodes()),'application icons')
def back():adb('shell','input','keyevent','4');time.sleep(.25)
original=None
try:original=adb('shell','run-as','com.wbox.app','cat','files/profiles.json')
except RuntimeError:pass
font=adb('shell','settings','get','system','font_scale').decode().strip()
hard_ime=adb('shell','settings','get','secure','show_ime_with_hard_keyboard').decode().strip()
appops=adb('shell','appops','get','com.wbox.app','ACTIVATE_VPN').decode().strip()
try:
    check(b'versionName=0.7.4' in adb('shell','dumpsys','package','com.wbox.app'),'Final 0.7.4 debug APK installed')
    observations['apk_sha256']=hashlib.sha256((ROOT/'android/app/build/outputs/apk/debug/app-debug.apk').read_bytes()).hexdigest()
    adb('shell','settings','put','system','font_scale','1.0')
    adb('shell','settings','put','secure','show_ime_with_hard_keyboard','1')
    if '--tail' not in sys.argv:
        themes=[('light','blue','apps-light'),('dark','blue','apps-dark'),('light','pink','apps-pink')]
        for theme,accent,name in (themes[-1:] if '--quick' in sys.argv else themes):
            seed(theme,accent);apps();time.sleep(.4);image=capture(name);docked(name,image)
            tree=nodes();icons=[n for n in tree if n.get('content-desc','').startswith('应用图标 · ')]
            check(len(icons)>=3,name+' shows real application icons',len(icons))
            l,t,r,b=bounds(icons[0]);crop=image.crop((l,t,r,b));colors=crop.getcolors(crop.width*crop.height)
            check(colors is not None and len(colors)>12,name+' icon pixels contain application artwork',len(colors or []))
        tree=nodes();pkg=next(n.get('text') for n in tree if n.get('text','').startswith('com.android.') and n.get('text')!='com.android.systemui')
        tap(pkg);tap('搜索应用');wait(lambda:bool(re.search(rb'mInputShown=true|isInputViewShown=true',adb('shell','dumpsys','input_method'))),'search keyboard opens');adb('shell','input','text',pkg)
        wait(lambda:sum(n.get('text')==pkg for n in nodes())==2,'filtered package remains visible')
        check(sum(n.get('content-desc','').startswith('应用图标 · ') for n in nodes())==1,'Search filters icons with application rows')
        back();check(any(n.get('text')=='代理范围' for n in nodes()),'Back closes the keyboard before the app sheet')
        tap('保存');wait(lambda:prefs().get('apps')==[pkg],'selected application saved')
        check(prefs().get('app_mode')=='include','Application selection and proxy range persist')
        seed('light','blue',30);tap('选择配置','content-desc');wait(lambda:any(n.get('text')=='Orion 02' for n in nodes()),'profile sheet appears');time.sleep(.4);image=capture('profile-picker');docked('Profile picker',image)
        for _ in range(4):
            adb('shell','input','swipe','540','1800','540','400','180');time.sleep(.4)
            if any(n.get('text')=='Orion 30' for n in nodes()):break
        check(any(n.get('text')=='Orion 30' for n in nodes()),'Long profile picker scrolls to later profiles')
        back();wait(lambda:any(n.get('text')=='概览' for n in nodes()),'profile picker dismisses')
        seed('light','pink',app_mode='all');adb('shell','appops','set','com.wbox.app','ACTIVATE_VPN','allow');tap('连接代理','content-desc')
        wait(lambda:any(n.get('content-desc')=='查看连接' for n in nodes()),'synthetic VPN start',40)
        tap('查看连接','content-desc');wait(lambda:any(n.get('text')=='搜索连接' for n in nodes()),'connection sheet appears');time.sleep(.4);image=capture('connections-pink');docked('Connections',image)
        check(any(n.get('text')=='搜索连接' for n in nodes()),'Connections search is available in full sheet')
        back();tap('断开代理连接','content-desc');wait(lambda:any(n.get('content-desc')=='连接代理' for n in nodes()),'VPN stop')
        adb('shell','settings','put','system','font_scale','1.5');seed();apps();image=capture('apps-large-font');docked('Large-font apps',image)
        check(any(n.get('text')=='保存' for n in nodes()),'Large font preserves app sheet save control')
    else:
        previous=json.loads((ROOT/'docs/verification/0.7.4-android-sheets.json').read_text())
        if previous['observations'].get('apk_sha256')!=observations['apk_sha256']:raise AssertionError('Cannot reuse checks from another APK')
        checks[:]=previous['checks'];observations.update(previous['observations']);shots[:]=[p for p in previous['screenshots'] if 'failure' not in p]
        observations['reused_same_apk_checks']=len(checks)
    adb('shell','settings','put','system','font_scale','1.0');seed(mode='nodes');tap('节点');wait(lambda:any(n.get('content-desc')=='节点操作' for n in nodes()),'node list appears');tap('节点操作','content-desc');wait(lambda:any(n.get('text')=='连接此节点' for n in nodes()),'node actions appear');time.sleep(.4)
    image=capture('node-actions');tree=nodes();title=next(n for n in tree if n.get('text')=='连接此节点');l,t,r,b=bounds(title)
    # The action sheet remains below the top and has a curved edge; it is not full height.
    check(t>image.height//3,'Short node action sheet retains its lower position',{'first_action_top':t})
    surface=image.getpixel((image.width//2,t-15))
    curved=[y for y in range(image.height//3,t-30) if image.getpixel((image.width//2,y))==surface and image.getpixel((2,y))!=surface and image.getpixel((image.width-3,y))!=surface]
    check(bool(curved),'Short node action sheet keeps a rounded top edge',{'first_curved_row':curved[0] if curved else None})
except Exception as error:
    failure=str(error);traceback.print_exc();capture('sheets-failure')
finally:
    adb('shell','am','force-stop','com.wbox.app')
    if original is not None:save(original)
    else:adb('shell','run-as','com.wbox.app','rm','-f','files/profiles.json')
    if font=='null':adb('shell','settings','delete','system','font_scale')
    else:adb('shell','settings','put','system','font_scale',font)
    if hard_ime=='null':adb('shell','settings','delete','secure','show_ime_with_hard_keyboard')
    else:adb('shell','settings','put','secure','show_ime_with_hard_keyboard',hard_ime)
    match=re.search(r'ACTIVATE_VPN: (\w+)',appops)
    adb('shell','appops','set','com.wbox.app','ACTIVATE_VPN',match.group(1) if match else 'default')
    adb('shell','rm','-f','/sdcard/wbox-sheets074.xml')
    (ROOT/'docs/verification/0.7.4-android-sheets.json').write_text(json.dumps({'checks':checks,'observations':observations,'screenshots':shots,'failure':failure,'preferences_restored':True,'font_scale_restored':font,'vpn_appops_restored':appops,'edge_swipe':'not verified; API35 fixture lacks edge listener'},ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
if failure:raise SystemExit(1)
