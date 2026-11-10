"""0.6.9 regression; isolated Wbox_API35 emulator, debug APK only."""
import http.server,json,pathlib,re,select,socket,ssl,subprocess,threading,time,xml.etree.ElementTree as ET
ROOT=pathlib.Path(__file__).resolve().parent.parent
ADB='C:/Users/hw/AppData/Local/Android/Sdk/platform-tools/adb.exe'
checks=[];timing={};requests={};failure=None;slow=False
def adb(*args,data=None,timeout=40):
    r=subprocess.run([ADB,'-s','emulator-5556',*args],input=data,capture_output=True,timeout=timeout)
    if r.returncode:raise RuntimeError(r.stderr.decode(errors='replace'))
    return r.stdout
def tree():
    adb('shell','uiautomator','dump','/sdcard/wbox-regressions-069.xml')
    return ET.fromstring(adb('shell','cat','/sdcard/wbox-regressions-069.xml'))
def nodes():return list(tree().iter('node'))
def labels():return [n.get('text','') for n in nodes()]
def point(n):
    a,b,c,d=map(int,re.findall(r'\d+',n.get('bounds')));return str((a+c)//2),str((b+d)//2)
def tap_node(n):adb('shell','input','tap',*point(n))
def tap(label,attr='text'):
    for _ in range(5):
        hits=[n for n in nodes() if n.get(attr)==label]
        if hits:tap_node(hits[-1]);return point(hits[-1])
        time.sleep(.2)
    raise AssertionError('Missing control '+label)
def wait(fn,timeout=40):
    end=time.monotonic()+timeout
    while time.monotonic()<end:
        if fn():return
        time.sleep(.2)
    raise AssertionError('Timed out')
def check(ok,label):
    assert ok,label
    checks.append(label);print('PASS',label,flush=True)
def prefs():return json.loads(adb('shell','run-as','com.wbox.app','cat','files/profiles.json'))
def save(p):adb('shell','run-as','com.wbox.app','sh','-c',"'cat > files/profiles.json'",data=json.dumps(p).encode())
def op(action):adb('shell','am','broadcast','-n','com.wbox.app/.RuntimeProbeReceiver','--es','operation',action)
def status():
    op('status');time.sleep(.15)
    return json.loads(adb('shell','run-as','com.wbox.app','cat','files/probe-state.json'))
def screenshot(name):(ROOT/f'docs/previews/0.6.9-android-{name}.png').write_bytes(adb('exec-out','screencap','-p'))
def seed(config,extra=None):
    adb('shell','am','force-stop','com.wbox.app')
    content=json.dumps(config)
    p={'mode':'config','engine':'sing-box','theme':'light','test_url':f'https://localhost:{origin.server_port}/','active_config':'regression','config_sing-box':content,'configs':[{'id':'regression','name':'Regression','core':'sing-box','content':content}]}
    p.update(extra or {});save(p)
    adb('shell','am','start','-W','-n','com.wbox.app/.MainActivity');wait(lambda:'概览' in labels())
def fill(index,value):
    edits=[n for n in nodes() if n.get('class')=='android.widget.EditText'];tap_node(edits[index])
    adb('shell','input','keycombination','113','29');adb('shell','input','text',value)
    adb('shell','input','keyevent','KEYCODE_BACK')
class Origin(http.server.BaseHTTPRequestHandler):
    protocol_version='HTTP/1.1'
    def do_HEAD(self):
        time.sleep(12 if slow else .06)
        try:self.send_response(204);self.send_header('Content-Length','0');self.end_headers()
        except OSError:pass
    def log_message(self,*args):pass
class Proxy(http.server.BaseHTTPRequestHandler):
    def do_CONNECT(self):
        time.sleep(.3)
        upstream=socket.create_connection(('127.0.0.1',origin.server_port))
        try:
            self.send_response(200);self.end_headers()
            while True:
                ready,_,_=select.select([self.connection,upstream],[],[],15)
                if not ready:return
                for source in ready:
                    chunk=source.recv(65536)
                    if not chunk:return
                    (upstream if source is self.connection else self.connection).sendall(chunk)
        except OSError:pass
        finally:upstream.close()
    def log_message(self,*args):pass
class Subscription(http.server.BaseHTTPRequestHandler):
    def do_GET(self):
        requests[self.path]=requests.get(self.path,0)+1
        self.send_response(200);self.end_headers();self.wfile.write(json.dumps(config|{'log':{'level':'warn'}}).encode())
    def log_message(self,*args):pass
origin=http.server.ThreadingHTTPServer(('0.0.0.0',0),Origin)
tls=ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER);tls.load_cert_chain(ROOT/'.tools/regressions-069-cert.pem',ROOT/'.tools/regressions-069-key.pem')
origin.socket=tls.wrap_socket(origin.socket,server_side=True)
proxy=http.server.ThreadingHTTPServer(('0.0.0.0',0),Proxy)
subs=http.server.ThreadingHTTPServer(('0.0.0.0',0),Subscription)
for server in [origin,proxy,subs]:threading.Thread(target=server.serve_forever,daemon=True).start()
base=f'http://10.0.2.2:{subs.server_port}'
cert=(ROOT/'.tools/regressions-069-cert.pem').read_text()
config={'certificate':{'certificate':[cert]},'outbounds':[{'type':'selector','tag':n,'outbounds':['A','B']} for n in ['Main','Media','AI']]+[{'type':'http','tag':n,'server':'10.0.2.2','server_port':proxy.server_port} for n in ['A','B']]}
try:
    adb('shell','appops','set','com.wbox.app','ACTIVATE_VPN','allow')
    seed(config);tap('代理');wait(lambda:'Main' in labels())
    ui=nodes();check(not any(n.get('text')=='未测试' for n in ui) and sum(n.get('content-desc')=='未测试' for n in ui)==2,'Untested cards show two lightning icons without placeholder text')
    cards=[n for n in ui if n.get('clickable')=='true' and any(c.get('text')=='A' for c in n.iter('node'))]
    a,b,c,d=map(int,re.findall(r'\d+',cards[-1].get('bounds')))
    density=int(re.search(r'(?:Override|Physical) density: (\d+)',adb('shell','wm','density').decode()).group(1))/160
    timing['cardHeightDp']=round((d-b)/density,1);check(timing['cardHeightDp']<130,'Compact Android proxy cards stay below 130 dp')
    screenshot('proxies');tap('选择代理组','content-desc');check('策略组' in labels(),'Compact group bottom sheet opens');screenshot('group-picker');tap('Media');check('策略组' not in labels(),'Choosing a group closes the sheet');tap('Main')
    tap('B');wait(lambda:prefs()['configs'][0].get('group_selections',{}).get('Main')=='B');check(not status()['running'],'Offline selection persists without VPN')
    tap('延迟测试','content-desc');wait(lambda:any(re.fullmatch(r'[1-9]\d* ms',v) for v in labels()))
    timing['singboxHttpsMs']=[int(v.split()[0]) for v in labels() if re.fullmatch(r'[1-9]\d* ms',v)]
    check(len(timing['singboxHttpsMs'])==2 and all(45<=n<250 for n in timing['singboxHttpsMs']),'Android sing-box HTTPS second-request timing excludes 300 ms setup')
    slow=True;xy=tap('延迟测试','content-desc');time.sleep(.4);adb('shell','input','tap',*xy)
    wait(lambda:not any(n.get('content-desc')=='取消测试' for n in nodes()));wait(lambda:not adb('shell','run-as','com.wbox.app','ls','-A','cache/latency').strip());check(not status()['running'],'Cancel stops the temporary core and removes its files');slow=False
    op('connect');wait(lambda:status()['running']);tap('概览');check(not any(n.get('content-desc') in ['代理组','选择代理组'] for n in nodes()),'Connected status bar has no duplicate proxy shortcut');screenshot('connected');op('stop');wait(lambda:not status()['running'])
    content=json.dumps(config);remote=[{'id':'regression','name':'Remote','core':'sing-box','content':content,'url':base+'/one'},{'id':'disabled','name':'Disabled','core':'sing-box','content':content,'url':base+'/two'}]
    seed(config,{'configs':remote});tap('配置');tap('配置操作','content-desc');tap('编辑订阅')
    # The last card is Disabled; edit it and leave the other source disabled.
    fill(0,'Edited');fill(1,base+'/edited');fill(2,'15');tap_node(next(n for n in nodes() if n.get('checkable')=='true'));screenshot('subscription');tap('保存')
    wait(lambda:any(c.get('name')=='Edited' for c in prefs()['configs']));edited=next(c for c in prefs()['configs'] if c['name']=='Edited')
    check(edited['url']==base+'/edited' and edited['subscription']['auto_update'] and edited['subscription']['update_minutes']==15,'Remote subscription editor saves name, URL, independent switch and interval')
    # Hide the activity and force a due periodic tick using only emulator data.
    adb('shell','input','keyevent','KEYCODE_HOME');p=prefs();next(c for c in p['configs'] if c['name']=='Edited')['subscription']['last_attempt']=0;save(p)
    before=requests.get('/edited',0);adb('shell','cmd','jobscheduler','run','-f','com.wbox.app','2008');wait(lambda:requests.get('/edited',0)>before)
    wait(lambda:json.loads(next(c for c in prefs()['configs'] if c['name']=='Edited')['content']).get('log',{}).get('level')=='warn')
    check(requests.get('/one',0)==0 and requests.get('/two',0)==0,'Hidden-activity system job refreshes only the enabled due subscription')
    before=requests.get('/edited',0);adb('shell','cmd','jobscheduler','run','-f','com.wbox.app','2008');time.sleep(1);check(requests.get('/edited',0)==before,'Repeated background tick respects the per-source interval')
    url=base+'/nodes';link='socks5://127.0.0.1:1234#Node'
    seed(config,{'mode':'nodes','node_engine':'sing-box','subscriptions':{url:link},'nodes':[{'link':link,'source':url}]});tap('节点');tap('订阅');tap('编辑');fill(0,'NodeSource');fill(1,base+'/nodes-new');fill(2,'30');tap('保存')
    wait(lambda:base+'/nodes-new' in prefs().get('subscription_settings',{}));p=prefs();check(p['subscription_settings'][base+'/nodes-new']['name']=='NodeSource' and p['nodes'][0]['source']==base+'/nodes-new','Node subscription editor changes source URL while preserving nodes')
    tap('移除');wait(lambda:not prefs().get('subscriptions'));check(not prefs().get('subscription_settings'),'Removing node subscription also removes its scheduling metadata')
except Exception as err:
    failure=str(err);screenshot('failure');raise
finally:
    adb('shell','am','force-stop','com.wbox.app')
    for server in [origin,proxy,subs]:server.shutdown();server.server_close()
    (ROOT/'docs/verification/0.6.9-android-runtime.json').write_text(json.dumps({'passed':failure is None,'failure':failure,'checks':checks,'timing':timing},indent=2),encoding='utf-8')
