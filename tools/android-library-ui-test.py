"""Configuration/subscription UI checks on the isolated Wbox API35 emulator.

Run after installing the current x86_64 debug APK. Screenshots and the report
use the Android versionName. Existing emulator preferences are restored.
"""
import argparse, collections, http.server, io, json, pathlib, re, subprocess, threading, time
import xml.etree.ElementTree as ET
from PIL import Image

ROOT=pathlib.Path(__file__).resolve().parent.parent
ADB=pathlib.Path('C:/Users/hw/AppData/Local/Android/Sdk/platform-tools/adb.exe')
VERSION=re.search(r"versionName '([^']+)'",(ROOT/'android/app/build.gradle').read_text()).group(1)
parser=argparse.ArgumentParser()
parser.add_argument('--serial',default='emulator-5556')
args=parser.parse_args()
if args.serial!='emulator-5556':
    raise SystemExit('This check only supports the isolated emulator-5556.')
checks=[]
images=[]
failure=None
hits=collections.Counter()

def adb(*values,data=None,timeout=40):
    r=subprocess.run([str(ADB),'-s',args.serial,*values],input=data,capture_output=True,timeout=timeout)
    if r.returncode:
        raise RuntimeError(r.stderr.decode(errors='replace'))
    return r.stdout

def nodes():
    adb('shell','uiautomator','dump','/sdcard/wbox-library.xml')
    return list(ET.fromstring(adb('shell','cat','/sdcard/wbox-library.xml')).iter('node'))

def labels():
    return [n.get('text','') for n in nodes()]

def has_text(label):
    return any(label in value for value in labels())

def text_order(first,second):
    values=labels()
    return next(i for i,s in enumerate(values) if first in s)<next(i for i,s in enumerate(values) if second in s)

def tap(label,attr='text'):
    matches=[n for n in nodes() if n.get(attr)==label]
    if not matches:
        raise AssertionError('Missing control '+label)
    target=matches[-1] if attr=='text' and label in ['概览','配置','节点','日志','设置','订阅'] else matches[0]
    x1,y1,x2,y2=map(int,re.findall(r'\d+',target.get('bounds')))
    adb('shell','input','tap',str((x1+x2)//2),str((y1+y2)//2))

def wait(predicate,timeout=30):
    until=time.monotonic()+timeout
    while time.monotonic()<until:
        if predicate():
            return
        time.sleep(.2)
    raise AssertionError('Timed out')

def check(ok,label):
    if not ok:
        raise AssertionError(label)
    checks.append(label)
    print('PASS',label,flush=True)

def prefs():
    return json.loads(adb('shell','run-as','com.wbox.app','cat','files/profiles.json'))

def write_prefs(value):
    adb('shell','run-as','com.wbox.app','sh','-c',"'cat > files/profiles.json'",data=json.dumps(value).encode())

def start(value):
    adb('shell','am','force-stop','com.wbox.app')
    write_prefs(value)
    adb('shell','am','start','-W','-n','com.wbox.app/.MainActivity')
    wait(lambda:'概览' in labels())

def screenshot(name,dark=False,verify_bar=True):
    time.sleep(.5)
    data=adb('exec-out','screencap','-p')
    path=ROOT/f'docs/previews/{VERSION}-android-{name}.png'
    path.parent.mkdir(parents=True,exist_ok=True)
    path.write_bytes(data)
    images.append(str(path.relative_to(ROOT)))
    if verify_bar:
        im=Image.open(io.BytesIO(data)).convert('RGB')
        expected=(22,25,32) if dark else (243,244,248)
        pixels=[im.getpixel((x,2)) for x in [2,im.width//2,im.width-3]]
        check(all(max(abs(v-e) for v,e in zip(pixel,expected))<=8 for pixel in pixels),name+' status bar shares the page surface')

def content():
    return json.dumps({'outbounds':[{'type':'selector','tag':'Proxy','outbounds':['DIRECT']},{'type':'direct','tag':'DIRECT'}]})

class Source(http.server.BaseHTTPRequestHandler):
    def do_GET(self):
        hits[self.path]+=1
        self.send_response(200)
        self.send_header('Subscription-Userinfo','upload=1048576; download=2147483648; total=322122547200; expire=1893456000')
        self.end_headers()
        self.wfile.write(content().encode() if self.path!='/nodes' else b'http://10.0.2.2:19283#Tokyo\nhttp://10.0.2.2:19284#London')
    def log_message(self,*values):
        pass

server=http.server.ThreadingHTTPServer(('0.0.0.0',0),Source)
threading.Thread(target=server.serve_forever,daemon=True).start()
url=f'http://10.0.2.2:{server.server_port}'
try:
    original=adb('shell','run-as','com.wbox.app','cat','files/profiles.json')
except RuntimeError:
    original=None
original_font=adb('shell','settings','get','system','font_scale').decode().strip()
original_size=adb('shell','wm','size').decode()
original_density=adb('shell','wm','density').decode()

def restore_display():
    for kind,value in [('size',original_size),('density',original_density)]:
        match=re.search(r'Override (?:size|density): ([\dx]+)',value)
        adb('shell','wm',kind,match.group(1) if match else 'reset')
    if original_font=='null':
        adb('shell','settings','delete','system','font_scale')
    else:
        adb('shell','settings','put','system','font_scale',original_font)

def config_seed(dark=False):
    now=int(time.time()*1000)
    profiles=[]
    for index,(key,name) in enumerate([('orion','Orion · 出行'),('atlas','Atlas · 主订阅'),('local','本地规则')]):
        p={'id':key,'name':name,'core':'sing-box','content':content(),'url':'' if key=='local' else url+'/'+key,'updated':now-index*86400000}
        if key!='local':
            p['subscription']={'name':name,'auto_update':False,'update_minutes':1440,'last_success':p['updated'],'usage':{'upload':1048576,'download':2147483648*(index+1),'total':322122547200,'expire':1893456000}}
        profiles.append(p)
    return {'mode':'config','engine':'sing-box','theme':'dark' if dark else 'light','configs':profiles,'active_config':'atlas','config_sing-box':content()}

try:
    start(config_seed())
    tap('配置')
    wait(lambda:has_text('Orion · 出行'))
    check(any('300 GB' in s for s in labels()) and any('剩余' in s for s in labels()),'Configuration cards show quota and remaining traffic')
    screenshot('profiles-light')
    tap('添加配置','content-desc')
    check(all(s in labels() for s in ['从链接或文本导入','从文件导入','从剪贴板导入']),'Profile toolbar exposes link, file and clipboard imports')
    tap('从文件导入')
    check('com.wbox.app' not in adb('shell','dumpsys','activity','activities').decode().split('mResumedActivity:')[-1].split('\n')[0],'File import opens the system document picker')
    adb('shell','input','keyevent','4')
    wait(lambda:has_text('Orion · 出行'))
    tap('配置操作','content-desc')
    check(all(s in labels() for s in ['编辑','预览','更多','导出文件','删除']) and '同步' not in labels(),'Configuration menu retains actions and removes duplicate sync')
    screenshot('profiles-menu',verify_bar=False)
    tap('预览')
    wait(lambda:'预览配置' in labels())
    check(any(n.get('content-desc')=='配置预览' for n in nodes()),'Preview opens the selectable read-only configuration')
    screenshot('config-preview')
    tap('返回','content-desc')
    tap('配置操作','content-desc')
    tap('更多')
    wait(lambda:'编辑订阅' in labels())
    screenshot('subscription-editor')
    tap('返回','content-desc')
    tap('配置操作','content-desc')
    tap('编辑')
    wait(lambda:'编辑配置' in labels())
    screenshot('config-editor')
    tap('返回','content-desc')
    tap('排序订阅','content-desc')
    tap('名称')
    wait(lambda:prefs().get('config_sort')=='name')
    check(text_order('Atlas · 主订阅','Orion · 出行'),'Name sorting changes configuration order')
    tap('更新全部','content-desc')
    wait(lambda:hits['/atlas']>0 and hits['/orion']>0)
    wait(lambda:all(p.get('subscription',{}).get('usage',{}).get('download')==2147483648 for p in prefs()['configs'] if p.get('url')))
    check(True,'Update all synchronizes both remote configurations')
    p=prefs()
    start(p)
    tap('配置')
    check(prefs().get('config_sort')=='name' and text_order('Atlas · 主订阅','Orion · 出行'),'Configuration sort persists across restart')
    start(config_seed(True))
    tap('配置')
    screenshot('profiles-dark',dark=True)
    adb('shell','settings','put','system','font_scale','1.5')
    start(config_seed(True))
    tap('配置')
    check(has_text('剩余') and has_text('300 GB'),'Quota and remaining traffic remain available at 150% font scale')
    screenshot('profiles-large-font',dark=True)
    adb('shell','settings','put','system','font_scale','1.0')
    adb('shell','wm','size','1600x2560')
    adb('shell','wm','density','240')
    start(config_seed())
    tap('配置')
    positions={n.get('text'):tuple(map(int,re.findall(r'\d+',n.get('bounds')))) for n in nodes() if n.get('text') in ['Orion · 出行','Atlas · 主订阅','本地规则']}
    check(len(positions)==3 and len({p[0] for p in positions.values()})>1,'Tablet configurations use an adaptive multi-column grid')
    screenshot('profiles-tablet')
    restore_display()
    start(config_seed(True))
    tap('配置')
    tap('配置操作','content-desc')
    tap('更多')
    wait(lambda:'编辑订阅' in labels())
    screenshot('subscription-editor-dark',dark=True)
    p={'mode':'nodes','engine':'mihomo','node_engine':'mihomo','theme':'light','nodes':[{'link':'http://10.0.2.2:19283#Tokyo','source':url+'/nodes'},{'link':'http://10.0.2.2:19284#London','source':url+'/nodes'},'http://10.0.2.2:19285#Manual'],'selected':'Tokyo','configs':[],
       'subscriptions':{url+'/nodes':[]},'subscription_settings':{url+'/nodes':{'name':'Atlas · 节点订阅','auto_update':True,'update_minutes':1440,'last_success':int(time.time()*1000),'usage':{'upload':1048576,'download':4294967296,'total':322122547200,'expire':0}}}}
    start(p)
    tap('节点')
    tap('订阅')
    wait(lambda:has_text('Atlas · 节点订阅'))
    screenshot('node-subscriptions')
    tap('订阅操作','content-desc')
    screenshot('node-subscription-menu',verify_bar=False)
    tap('预览')
    check(has_text('Tokyo') and has_text('London') and not has_text('Manual'),'Subscription preview filters the node workspace to its source')
    screenshot('nodes')
except Exception as error:
    failure=str(error)
    import traceback
    traceback.print_exc()
    print('FAIL',failure,flush=True)
finally:
    try:
        adb('shell','am','force-stop','com.wbox.app')
        if original is not None:
            adb('shell','run-as','com.wbox.app','sh','-c',"'cat > files/profiles.json'",data=original)
        else:
            adb('shell','run-as','com.wbox.app','rm','-f','files/profiles.json')
    finally:
        restore_display()
        server.shutdown()
        report=ROOT/f'docs/verification/{VERSION}-android-library.json'
        report.parent.mkdir(parents=True,exist_ok=True)
        report.write_text(json.dumps({'version':VERSION,'emulator':args.serial,'checks':checks,'screenshots':images,'failure':failure},ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
if failure:
    raise SystemExit(1)
