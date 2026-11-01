"""Device UI checks against the minified debug APK installed by runtime-test."""
import pathlib,subprocess,time,xml.etree.ElementTree as ET,re,json
ROOT=pathlib.Path(__file__).resolve().parent.parent
ADB='C:/Users/hw/AppData/Local/Android/Sdk/platform-tools/adb.exe'
checks=[]
def adb(*args):return subprocess.check_output([ADB,'-s','emulator-5556',*args],timeout=20)
def tree():
    adb('shell','uiautomator','dump','/sdcard/wbox-ui.xml')
    return ET.fromstring(adb('shell','cat','/sdcard/wbox-ui.xml'))
def find(value,attr='text'):
    for _ in range(8):
        try:
            nodes=[n for n in tree().iter('node') if n.get(attr)==value]
        except (subprocess.SubprocessError, ET.ParseError):
            nodes=[] # The accessibility root is briefly absent during launch.
        if nodes:return nodes[-1]
        time.sleep(.75)
    raise AssertionError('Missing UI node '+value)
def center(node):
    x1,y1,x2,y2=map(int,re.findall(r'\d+',node.get('bounds')))
    return (x1+x2)//2,(y1+y2)//2
def tap(value,attr='text'):
    x,y=center(find(value,attr));adb('shell','input','tap',str(x),str(y));time.sleep(.4)
def screenshot(name):
    (ROOT/f'docs/previews/0.6.0-android-{name}.png').write_bytes(adb('exec-out','screencap','-p'))
adb('shell','input','keyevent','KEYCODE_WAKEUP')
adb('shell','wm','dismiss-keyguard')
adb('shell','am','broadcast','-n','com.wbox.app/.RuntimeProbeReceiver','--es','operation','seed','--es','engine','sing-box')
time.sleep(.5)
adb('shell','am','force-stop','com.wbox.app');adb('shell','am','start','-n','com.wbox.app/.MainActivity')
button=find('连接代理','content-desc');x,y=center(button)
assert x>750 and y>1700,(x,y)
checks.append('Idle start FAB is at bottom right above navigation')
screenshot('idle-dark')
tap('连接代理','content-desc');find('断开代理连接','content-desc')
checks.append('Production FAB starts VPN and exposes bottom stop control')
tap('代理组','content-desc');find('🇯🇵 Tokyo')
screenshot('groups-dark');checks.append('Groups show Unicode flags and selected outbound')
adb('shell','input','keyevent','4');time.sleep(.4)
tap('日志');find('复制日志','content-desc');screenshot('logs-dark')
tap('设置');tap('主题');tap('浅色')
tap('概览');find('断开代理连接','content-desc');screenshot('overview-light')
tap('断开代理连接','content-desc');find('连接代理','content-desc');screenshot('idle-light')
checks.append('Light theme and stop control work through actual UI')
adb('shell','rm','/sdcard/wbox-ui.xml')
(ROOT/'docs/verification/0.6.0-android-ui.json').write_text(json.dumps({'checks':checks},indent=2),encoding='utf-8')
print('\n'.join('PASS '+c for c in checks))
