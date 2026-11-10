"""0.6.10 regression; isolated Wbox_API35 emulator, debug APK only."""
import sys,http.server,json,pathlib,re,select,socket,ssl,subprocess,threading,time,xml.etree.ElementTree as ET
ROOT=pathlib.Path(__file__).resolve().parent.parent
ADB='C:/Users/hw/AppData/Local/Android/Sdk/platform-tools/adb.exe'
checks=[];timing={};requests={};failure=None;slow=False
def adb(*args,data=None,timeout=40):
    r=subprocess.run([ADB,'-s','emulator-5556',*args],input=data,capture_output=True,timeout=timeout)
    if r.returncode:raise RuntimeError(r.stderr.decode(errors='replace'))
    return r.stdout
def tree():
    adb('shell','uiautomator','dump','/sdcard/wbox-regressions-0610.xml')
    return ET.fromstring(adb('shell','cat','/sdcard/wbox-regressions-0610.xml'))
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
def screenshot(name):(ROOT/f'docs/previews/0.6.10-android-{name}.png').write_bytes(adb('exec-out','screencap','-p'))
def seed(config,extra=None):
    adb('shell','am','force-stop','com.wbox.app')
    content=json.dumps(config)
    p={'mode':'config','engine':'sing-box','theme':'light','test_url':f'https://10.0.2.2:{origin.server_port}/','active_config':'regression','config_sing-box':content,'configs':[{'id':'regression','name':'Regression','core':'sing-box','content':content}]}
    p.update(extra or {});save(p)
    adb('shell','am','start','-W','-n','com.wbox.app/.MainActivity');wait(lambda:'概览' in labels())
def fill(index,value):
    edits=[n for n in nodes() if n.get('class')=='android.widget.EditText'];tap_node(edits[index])
    adb('shell','input','keycombination','113','29');adb('shell','input','text',value)
    adb('shell','input','keyevent','KEYCODE_BACK')

def delay_node(name,ui=None):
    return next(n for n in (ui if ui is not None else nodes()) if n.get('content-desc','').startswith('测试节点 '+name+' 的延迟'))
def tap_delay(name):tap_node(delay_node(name))
def delay_text(name,ui=None):
    ui=ui if ui is not None else nodes()
    x1,y1,x2,y2=map(int,re.findall(r'\d+',delay_node(name,ui).get('bounds')))
    return ' '.join(n.get('text','') for n in ui if n.get('text') and (lambda b:x1<=b[0] and y1<=b[1] and b[2]<=x2 and b[3]<=y2)(list(map(int,re.findall(r'\d+',n.get('bounds'))))))
class QuietServer(http.server.ThreadingHTTPServer):
    def handle_error(self,request,client_address):pass
class Origin(http.server.BaseHTTPRequestHandler):
    protocol_version='HTTP/1.1'
    def do_HEAD(self):
        gate.wait(4)
        time.sleep(.06)
        try:self.send_response(204);self.send_header('Content-Length','0');self.end_headers()
        except OSError:pass
    def log_message(self,*args):pass
class Proxy(http.server.BaseHTTPRequestHandler):
    def do_CONNECT(self):
        hits[self.server.node]+=1
        upstream=socket.create_connection(('127.0.0.1',getattr(self.server,'origin_port',origin.server_port)))
        try:
            self.send_response(200);self.end_headers()
            while True:
                ready,_,_=select.select([self.connection,upstream],[],[],10)
                if not ready:return
                for source in ready:
                    chunk=source.recv(65536)
                    if not chunk:return
                    (upstream if source is self.connection else self.connection).sendall(chunk)
        except OSError:pass
        finally:upstream.close()
    def log_message(self,*args):pass
origin=QuietServer(('0.0.0.0',0),Origin)
tls=ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER);tls.load_cert_chain(ROOT/'.tools/regressions-069-cert.pem',ROOT/'.tools/regressions-069-key.pem')
origin.socket=tls.wrap_socket(origin.socket,server_side=True)
proxies=[QuietServer(('0.0.0.0',0),Proxy) for _ in range(2)]
for i,p in enumerate(proxies):p.node=i
hits=[0,0];gate=threading.Event();gate.set()
for server in [origin,*proxies]:threading.Thread(target=server.serve_forever,daemon=True).start()
cert=(ROOT/'.tools/regressions-069-cert.pem').read_text()
config={'certificate':{'certificate':[cert]},'outbounds':[{'type':'selector','tag':'Main','outbounds':['A','B','DIRECT','Nested','SshNode','VlessNode']},{'type':'selector','tag':'Nested','outbounds':['A','B']},*[{'type':'http','tag':name,'server':'10.0.2.2','server_port':p.server_port} for name,p in zip(['A','B'],proxies)],{'type':'direct','tag':'DIRECT'},{'type':'ssh','tag':'SshNode','server':'127.0.0.1','server_port':9,'user':'fixture','password':'fixture'},{'type':'vless','tag':'VlessNode','server':'127.0.0.1','server_port':9,'uuid':'00000000-0000-0000-0000-000000000001'}]}
try:
    adb('shell','appops','set','com.wbox.app','ACTIVATE_VPN','allow')
    if '--mihomo-only' in sys.argv:
        checks.extend(json.loads((ROOT/'docs/verification/0.6.10-android-runtime.json').read_text())['checks'][:9])
    else:
        seed(config);tap('代理');wait(lambda:'Main' in labels())
        before=labels();check(all(v in before for v in ['HTTP','SSH','VLESS','Direct','Selector']),'Offline protocol labels are uppercase while Direct and Selector keep their spelling')
        before_selection=prefs()['configs'][0].get('group_selections',{})
        gate.clear();tap_delay('B');ui=nodes()
        check(not any(n.get('text')=='测试中' for n in ui),'Pending single-node test has no testing text')
        check(hits[0]==0 and hits[1]==1,'Clicking B latency starts only B, without testing A')
        screenshot('single-spinner');gate.set()
        wait(lambda:bool(re.search(r'[1-9]\d* ms',delay_text('B'))))
        check(prefs()['configs'][0].get('group_selections',{})==before_selection,'Tapping the latency region does not select its card')
        after=labels();check(all(v in after for v in ['HTTP','SSH','VLESS','Direct','Selector']),'Protocol and group labels remain stable after the core catalog refresh')
        count=hits[1];tap_delay('B');wait(lambda:hits[1]>count);wait(lambda:bool(re.search(r'[1-9]\d* ms',delay_text('B'))));check(hits[0]==0,'Clicking a measured latency retests only that node')
        counts=hits.copy();tap_delay('Nested');wait(lambda:hits[0]>counts[0]);wait(lambda:bool(re.search(r'[1-9]\d* ms',delay_text('Nested'))));check(hits[1]==counts[1],'Nested group card tests only its selected leaf')
        tap_delay('SshNode');wait(lambda:'Timeout' in delay_text('SshNode'));tap_delay('SshNode');wait(lambda:'Timeout' in delay_text('SshNode'));check(True,'Failed-node latency remains clickable for retry')
        op('connect');wait(lambda:status()['running']);counts=hits.copy();tap_delay('B');wait(lambda:hits[1]>counts[1]);wait(lambda:bool(re.search(r'[1-9]\d* ms',delay_text('B'))));check(hits[0]==counts[0] and status()['running'],'Connected single-node test uses the running core and preserves the VPN')
        screenshot('single-result');op('stop');wait(lambda:not status()['running'])
    # Keep mihomo fixture on local HTTP, avoiding any external certificate dependency.
    plain_origin=QuietServer(('0.0.0.0',0),Origin)
    threading.Thread(target=plain_origin.serve_forever,daemon=True).start()
    plain=[QuietServer(('0.0.0.0',0),Proxy) for _ in range(2)]
    for i,p in enumerate(plain):p.node=i;p.origin_port=plain_origin.server_port;threading.Thread(target=p.serve_forever,daemon=True).start()
    mihomo={'proxies':[{'type':'http','name':name,'server':'10.0.2.2','port':p.server_port} for name,p in zip(['A','B'],plain)],'proxy-groups':[{'name':'Main','type':'select','proxies':['A','B','Nested','DIRECT']},{'name':'Nested','type':'fallback','proxies':['A','B'],'url':'http://example.test/','interval':0,'lazy':True}],'rules':['MATCH,Main']}
    seed(mihomo,{'engine':'mihomo','config_mihomo':json.dumps(mihomo),'test_url':'http://example.test/','configs':[{'id':'regression','name':'Regression','core':'mihomo','content':json.dumps(mihomo)}]});tap('代理');wait(lambda:'Main' in labels());check('Fallback' in labels(),'Fallback group label preserves its original capitalization')
    # Fallback starts autonomous health checks; isolate user requests with selectors.
    mihomo['proxy-groups'][1]={'name':'Nested','type':'select','proxies':['A','B']}
    seed(mihomo,{'engine':'mihomo','config_mihomo':json.dumps(mihomo),'test_url':'http://example.test/','configs':[{'id':'regression','name':'Regression','core':'mihomo','content':json.dumps(mihomo)}]});tap('代理');wait(lambda:'Main' in labels())
    hits[:]=[0,0];tap_delay('B');wait(lambda:bool(re.search(r'[1-9]\d* ms',delay_text('B'))));check(hits[1]>=1 and hits[0]==0,'Mihomo card also tests only the tapped node')
    # The node-workspace list uses the same latency control.
    link=f'http://10.0.2.2:{plain[0].server_port}#ListNode'
    seed(mihomo,{'mode':'nodes','engine':'mihomo','node_engine':'mihomo','test_url':'http://example.test/','nodes':[link],'selected':'ListNode'});tap('节点');tap_delay('ListNode');wait(lambda:bool(re.search(r'[1-9]\d* ms',delay_text('ListNode'))));check(True,'Node-workspace list latency supports a single-node tap')
except Exception as err:
    failure=str(err);screenshot('failure');raise
finally:
    gate.set();adb('shell','am','force-stop','com.wbox.app')
    for server in [origin,*proxies,*globals().get('plain',[]),*([plain_origin] if 'plain_origin' in globals() else [])]:server.shutdown();server.server_close()
    (ROOT/'docs/verification/0.6.10-android-runtime.json').write_text(json.dumps({'passed':failure is None,'failure':failure,'checks':checks},indent=2),encoding='utf-8')
