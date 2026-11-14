"""Test Android selections during real latency probes on emulator-5556 only.

Synthetic mihomo HTTP and sing-box HTTPS fixtures use no public endpoints or system trust changes.
The test restores the emulator's application data and keeps the device running.
"""
import hashlib, http.server, json, pathlib, re, select, socket, subprocess, threading, time, ssl, os, sys
import xml.etree.ElementTree as ET

ROOT=pathlib.Path(__file__).resolve().parent.parent
ADB=os.environ.get('WBOX_ADB','D:/DevTools/Android/Sdk/platform-tools/adb.exe')
checks=[];shots=[];observations={};failure=None;delay_seconds=.05;hits=0;engine='mihomo';points={}
hold=threading.Event();hold.set()
provider_version=0;provider_requests=0;proxy_hits=[0,0]
def adb(*args,data=None,timeout=15):
    result=subprocess.run([ADB,'-s','emulator-5556',*args],input=data,capture_output=True,timeout=timeout)
    if result.returncode:raise RuntimeError(result.stderr.decode(errors='replace') or 'ADB command failed: '+' '.join(args))
    return result.stdout
def nodes():
    for attempt in range(2):
        output=adb('shell','uiautomator','dump','--compressed','/sdcard/wbox-latency074.xml')
        if b'UI hierchary dumped' not in output and b'UI hierarchy dumped' not in output:
            time.sleep(.25);continue
        return list(ET.fromstring(adb('shell','cat','/sdcard/wbox-latency074.xml')).iter('node'))
    return []
def box(node):return tuple(map(int,re.findall(r'\d+',node.get('bounds',''))))
def tap_xy(bounds):
    left,top,right,bottom=bounds;adb('shell','input','tap',str((left+right)//2),str((top+bottom)//2))
def tap(label,attribute='text'):
    if (attribute,label) in points:
        tap_xy(points[attribute,label]);return
    found=[n for n in nodes() if n.get(attribute)==label]
    if not found:raise AssertionError('Missing '+label)
    tap_xy(box(found[-1] if attribute=='text' else found[0]))
def select_profile(label):
    tap('概览');time.sleep(.3);tap('选择配置','content-desc');time.sleep(.5);tap(label);time.sleep(.3)
def wait(predicate,label,timeout=30):
    deadline=time.monotonic()+timeout
    while time.monotonic()<deadline:
        if predicate():return
        time.sleep(.1)
    raise AssertionError('Timed out '+label)
def check(ok,label):
    if not ok:raise AssertionError(label)
    checks.append(engine+' '+label);print('PASS',engine,label,flush=True)
def prefs():return json.loads(adb('shell','run-as','com.wbox.app','cat','files/profiles.json'))
def save(data):
    value=data if isinstance(data,bytes) else json.dumps(data).encode()
    adb('shell','run-as','com.wbox.app','mkdir','-p','files')
    adb('shell','run-as','com.wbox.app','sh','-c',"'cat > files/profiles.json'",data=value)
def seed(data):
    points.clear()
    adb('shell','am','force-stop','com.wbox.app');save(data)
    if data.get('mode')=='config' and len(data.get('configs',[]))==2:
        for profile in data['configs']:
            folder='files/runtime/'+data['engine']+'/'+profile['id']
            adb('shell','run-as','com.wbox.app','mkdir','-p',folder)
            filename='custom.json' if data['engine']=='sing-box' else 'rules.yaml'
            content=b'{"version":3,"rules":[{"domain":["fixture.invalid"]}]}' if data['engine']=='sing-box' else b'payload:\n - cached.example\n'
            adb('shell','run-as','com.wbox.app','sh','-c',"'cat > "+folder+'/'+filename+"'",data=content)
    adb('shell','am','start','-W','-n','com.wbox.app/.MainActivity')
    wait(lambda:any(n.get('text')=='概览' for n in nodes()),'main page',timeout=60)
    for n in nodes():
        text=n.get('text');desc=n.get('content-desc')
        if text in ['概览','代理','节点']:points['text',text]=box(n)
        if desc in ['选择配置','连接代理']:points['content-desc',desc]=box(n)
    if data.get('mode')=='config' and len(data.get('configs',[]))==2:
        tap('选择配置','content-desc');time.sleep(.5)
        for n in nodes():
            if n.get('text') in ['Probe one','Probe two']:points['text',n.get('text')]=box(n)
        adb('shell','input','keyevent','4');time.sleep(1)
def probe(action):adb('shell','am','broadcast','-n','com.wbox.app/.RuntimeProbeReceiver','--es','operation',action)
def has_file(path):
    try:adb('shell','run-as','com.wbox.app','test','-f',path);return True
    except RuntimeError:return False
def status():
    adb('shell','run-as','com.wbox.app','rm','-f','files/probe-state.json');probe('status')
    wait(lambda:has_file('files/probe-state.json'),'status response')
    return json.loads(adb('shell','run-as','com.wbox.app','cat','files/probe-state.json'))
def stopped():
    value=status();return not any(value[key] for key in ['running','starting','stopping'])
def active_probe():
    result=adb('shell','run-as','com.wbox.app','ls','-A','cache/latency').decode().split()
    return set(result)
def photo(name):
    path=ROOT/f'docs/previews/0.7.4-android-latency-{engine}-{name}.png'
    path.write_bytes(adb('exec-out','screencap','-p'));shots.append(path.relative_to(ROOT).as_posix())
class Origin(http.server.BaseHTTPRequestHandler):
    protocol_version='HTTP/1.1'
    def handle(self):
        try:super().handle()
        except OSError:pass
    def respond(self):
        global hits,provider_requests
        if self.path=='/provider':
            provider_requests+=1
            body=json.dumps({'proxies':[{'name':'A','type':'http','server':'10.0.2.2','port':device_port(proxy if provider_version==0 else other_proxy)}]}).encode()
            self.send_response(200);self.send_header('Content-Length',str(len(body)));self.end_headers();self.wfile.write(body);return
        hits+=1;hold.wait(25);time.sleep(delay_seconds)
        try:self.send_response(204);self.send_header('Content-Length','0');self.end_headers()
        except OSError:pass
    do_HEAD=respond;do_GET=respond
    def log_message(self,*values):pass
class Proxy(http.server.BaseHTTPRequestHandler):
    protocol_version='HTTP/1.1'
    index=0
    def forward(self):
        proxy_hits[self.index]+=1
        upstream=socket.create_connection(('127.0.0.1',origin.server_port))
        try:
            upstream.sendall(f'{self.command} /probe HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n'.encode())
            while chunk:=upstream.recv(65536):self.connection.sendall(chunk)
            self.close_connection=True
        except OSError:pass
        finally:upstream.close()
    do_HEAD=forward;do_GET=forward
    def do_CONNECT(self):
        proxy_hits[self.index]+=1
        target=origin if self.path.endswith(':80') else tls_origin
        upstream=socket.create_connection(('127.0.0.1',target.server_port))
        try:
            self.connection.sendall(b'HTTP/1.1 200 Connection Established\r\n\r\n')
            while True:
                ready,_,_=select.select([self.connection,upstream],[],[],10)
                if not ready:break
                for source in ready:
                    chunk=source.recv(65536)
                    if not chunk:return
                    (upstream if source is self.connection else self.connection).sendall(chunk)
        except OSError:pass
        finally:upstream.close();self.close_connection=True
    def log_message(self,*values):pass
origin=http.server.ThreadingHTTPServer(('0.0.0.0',0),Origin)
tls_origin=http.server.ThreadingHTTPServer(('0.0.0.0',0),Origin)
tls_context=ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
tls_context.load_cert_chain(ROOT/'tools/fixtures/latency-localhost-cert.pem',ROOT/'tools/fixtures/latency-localhost-key.pem')
tls_origin.socket=tls_context.wrap_socket(tls_origin.socket,server_side=True)
proxy=http.server.ThreadingHTTPServer(('0.0.0.0',0),Proxy)
class OtherProxy(Proxy):index=1
other_proxy=http.server.ThreadingHTTPServer(('0.0.0.0',0),OtherProxy)
for server in [origin,tls_origin,proxy,other_proxy]:threading.Thread(target=server.serve_forever,daemon=True).start()
def device_port(server):return server.server_port
observations['fixture_transport']='Emulator 10.0.2.2 host alias; local synthetic servers only'
observations['adb_version']=adb('version').decode().strip()

def config(group='Main'):
    names=[f'N{i:02}' for i in range(128)]
    if engine=='sing-box':return json.dumps({'certificate':{'certificate':[(ROOT/'tools/fixtures/latency-localhost-cert.pem').read_text()]},'outbounds':[{'type':'selector','tag':group,'outbounds':names}]+[{'type':'http','tag':name,'server':'10.0.2.2','server_port':device_port(proxy)} for name in names],'route':{'final':group,'rule_set':[{'tag':'known','type':'remote','format':'binary','url':'https://anti-ad.net/anti-ad-sing-box.srs'},{'tag':'custom','type':'local','format':'source','path':'custom.json'}]}})
    return json.dumps({'mixed-port':7890,'proxies':[{'name':name,'type':'http','server':'10.0.2.2','port':device_port(proxy)} for name in names],
        'proxy-groups':[{'name':group,'type':'select','proxies':names}],
        'rule-providers':{'known':{'type':'http','behavior':'domain','format':'yaml','url':'https://anti-ad.net/clash.yaml','path':'rules.yaml'}},'rules':[f'MATCH,{group}']})
def configs_seed():
    one,two=config(),config('Other')
    return {'mode':'config','engine':engine,'theme':'light','app_mode':'all','test_url':'http://93.184.216.34/probe' if engine=='mihomo' else f'https://localhost:{device_port(tls_origin)}/probe',
        'configs':[{'id':'one','name':'Probe one','core':engine,'content':one},{'id':'two','name':'Probe two','core':engine,'content':two}],
        'active_config':'one','config_'+engine:one}
def begin():
    global delay_seconds
    hold.clear();delay_seconds=.05
    points['content-desc','延迟测试']=box(next(n for n in nodes() if n.get('content-desc')=='延迟测试'))
    previous=hits;tap('延迟测试','content-desc')
    wait(lambda:hits>previous,'synthetic origin receives probe')
    running=active_probe();check(len(running)==1,'Configuration test owns one isolated core')
    return running
def finish():
    global delay_seconds
    hold.set();delay_seconds=.05;wait(lambda:not active_probe(),'probe completes and cleans up',timeout=35);time.sleep(.5)
def results():return [n.get('text') for n in nodes() if re.fullmatch(r'\d+ ms',n.get('text',''))]
try:original=adb('shell','run-as','com.wbox.app','cat','files/profiles.json')
except RuntimeError:original=None
if original is not None:(ROOT/'.tools/android-latency-074-original.json').write_bytes(original)
original_appops=adb('shell','appops','get','com.wbox.app','ACTIVATE_VPN').decode().strip()
try:
    transport=adb('shell','toybox','nc','-w','3','10.0.2.2',str(device_port(proxy)),data=b'GET http://93.184.216.34/probe HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n')
    check(b'204' in transport,'Emulator host alias reaches the synthetic proxy and origin')
    apk=ROOT/'android/app/build/outputs/apk/debug/app-debug.apk'
    check(b'versionName=0.7.4' in adb('shell','dumpsys','package','com.wbox.app'),'Final 0.7.4 debug APK is installed')
    observations['apk_sha256']=hashlib.sha256(apk.read_bytes()).hexdigest()
    observations['resource_fixtures']=['sing-box bundled rule before custom source file','mihomo cached YAML rule localized to bundled MRS']
    installed_path=adb('shell','pm','path','com.wbox.app').decode().strip().removeprefix('package:')
    observations['installed_apk_sha256']=hashlib.sha256(adb('exec-out','cat',installed_path)).hexdigest()
    check(observations['installed_apk_sha256']==observations['apk_sha256'],'Installed APK hash matches the verified debug build')
    tail='--tail' in sys.argv
    if tail:
        previous=json.loads((ROOT/'docs/verification/0.7.4-android-latency-selection.json').read_text(encoding='utf-8'))
        assert previous['observations']['apk_sha256']==observations['apk_sha256']
        for core in ['mihomo','sing-box']:assert core+' Explicit cancel removes the latency core without starting VPN' in previous['checks']
        checks=previous['checks']+checks;shots=[path for path in previous['screenshots'] if not path.endswith('-failure.png')]
        observations['reused_same_apk_checks']=len(previous['checks']);observations['full_engine_run']=previous['observations'].get('full_engine_run',previous['observations'])
    adb('shell','appops','set','com.wbox.app','ACTIVATE_VPN','allow')
    for engine in ([] if tail else ['mihomo','sing-box']):
        seed(configs_seed());tap('代理');wait(lambda:any(n.get('text')=='Main' for n in nodes()),'proxy group')
        current=nodes();point=box(next(n for n in current if n.get('text')=='N01'))
        running=begin();tap_xy(point)
        wait(lambda:prefs()['configs'][0].get('group_selections',{}).get('Main')=='N01','offline group selection persists')
        check(active_probe()==running,'Offline node selection keeps the original latency core alive')
        finish();check(bool(results()),'Offline probe results remain visible after node selection');photo('offline-selected')
        running=begin();select_profile('Probe two')
        wait(lambda:prefs().get('active_config')=='two','overview selects second profile')
        check(active_probe()==running,'Overview profile selection leaves the original configuration probe running')
        tap('代理');finish();check(not results(),'Old probe delays do not appear in the newly selected profile')
        select_profile('Probe one');tap('代理')
        wait(lambda:bool(results()),'returning to original configuration restores results')
        check(prefs().get('active_config')=='one','Completed probe does not restore an old profile selection');photo('profile-return')
        running=begin();tap('概览');time.sleep(.3);tap('连接代理','content-desc')
        wait(lambda:status()['running'],'VPN starts alongside probe',timeout=40)
        check(active_probe()==running,'Connecting the VPN does not cancel the isolated latency test')
        finish();tap('代理');check(bool(results()),'Results remain available after the VPN starts')
        current=nodes();point=box(next(n for n in current if n.get('text')=='N02'))
        running=begin();tap_xy(point)
        wait(lambda:prefs()['configs'][0].get('group_selections',{}).get('Main')=='N02','live node selection')
        check(active_probe()==running and status()['running'],'Live group selection keeps the probe and VPN running')
        finish();check(bool(results()),'Live selection retains completed delay results');photo('connected-selected')
        running=begin();select_profile('Probe two')
        wait(lambda:prefs().get('active_config')=='two' and status()['running'],'live profile reload',timeout=45)
        check(active_probe()==running,'Live configuration reload preserves the isolated original probe')
        finish();check(prefs().get('active_config')=='two','Finishing old probe leaves the new live configuration selected')
        tap('代理');current=nodes();points['content-desc','断开代理连接']=box(next(n for n in current if n.get('content-desc')=='断开代理连接'))
        begin();tap('断开代理连接','content-desc')
        wait(lambda:not active_probe() and stopped(),'UI stop cancels the active probe and VPN')
        check(True,'Stopping from the UI cancels both connection and latency')
        select_profile('Probe one');tap('代理')
        begin();tap('延迟测试','content-desc');wait(lambda:not active_probe(),'explicit cancellation cleans probe')
        check(not status()['running'],'Explicit cancel removes the latency core without starting VPN')
    engine='mihomo'
    hold.set()
    # Cached providers are kept on the original endpoint after the remote source changes.
    provider_config=json.dumps({'proxy-providers':{'Remote':{'type':'http','url':f'http://10.0.2.2:{device_port(origin)}/provider','interval':3600}},'proxy-groups':[{'name':'Main','type':'select','use':['Remote']}],'rules':['MATCH,Main']})
    provider_seed=configs_seed();provider_seed.update(configs=[{'id':'provider','name':'Provider fixture','core':'mihomo','content':provider_config}],active_config='provider',config_mihomo=provider_config)
    seed(provider_seed);probe('connect');wait(lambda:status()['running'],'provider configuration VPN starts',40)
    tap('代理');wait(lambda:any(n.get('text')=='A' for n in nodes()),'provider node appears')
    baseline_requests=provider_requests;baseline_hits=proxy_hits.copy();provider_version=1
    tap('延迟测试','content-desc');wait(lambda:proxy_hits[0]>baseline_hits[0],'provider test uses cached endpoint')
    wait(lambda:not active_probe(),'provider test cleans up')
    check(bool(results()),'Provider-native healthcheck produces a positive result')
    check(proxy_hits[1]==baseline_hits[1] and provider_requests==baseline_requests,'Provider snapshot never fetches or tests the changed same-name remote endpoint')
    check(status()['running'],'Provider test preserves the current VPN');photo('provider-cached')
    observations['provider']={'requests_during_test':provider_requests-baseline_requests,'proxy_hits_during_test':[proxy_hits[i]-baseline_hits[i] for i in range(2)]}
    probe('stop');wait(stopped,'provider VPN stops')
    # Standalone probes also keep running while the selected node changes.
    standalone={'mode':'nodes','engine':'mihomo','node_engine':'mihomo','theme':'light','app_mode':'all',
        'test_url':'http://93.184.216.34/probe','configs':[],
        'nodes':[{'link':f'http://10.0.2.2:{device_port(proxy)}#Node{i}'} for i in range(4)],'selected':'Node0'}
    seed(standalone);tap('节点');wait(lambda:any(n.get('text')=='Node1' for n in nodes()),'standalone list')
    current=nodes();menu=[n for n in current if n.get('content-desc')=='节点操作'][1];point=box(menu)
    tap_xy(point);time.sleep(.5);points['text','设为当前节点']=box(next(n for n in nodes() if n.get('text')=='设为当前节点'))
    adb('shell','input','keyevent','4');time.sleep(.3)
    hold.clear();delay_seconds=.05;before=hits;tap('测试延迟','content-desc');wait(lambda:hits>before,'standalone test begins')
    first=active_probe();tap_xy(point);time.sleep(.5);tap('设为当前节点');wait(lambda:prefs().get('selected')=='Node1','standalone selection')
    check(bool(active_probe()),'Standalone node selection continues its batch')
    finish();wait(lambda:len(results())==4 and not active_probe(),'all four standalone results',timeout=35)
    check(True,'Standalone tests complete and retain all four delay values');photo('standalone-selected')
except Exception as error:
    failure=repr(error);import traceback;traceback.print_exc();photo('failure')
finally:
    hold.set();delay_seconds=.05
    observations['origin_hits']=hits;observations['proxy_hits']=proxy_hits
    adb('shell','am','force-stop','com.wbox.app')
    if original is not None:save(original)
    else:adb('shell','run-as','com.wbox.app','rm','-f','files/profiles.json')
    adb('shell','rm','-f','/sdcard/wbox-latency074.xml')
    match=re.search(r'ACTIVATE_VPN: (\w+)',original_appops)
    adb('shell','appops','set','com.wbox.app','ACTIVATE_VPN',match.group(1) if match else 'default')
    for server in [origin,tls_origin,proxy,other_proxy]:server.shutdown();server.server_close()
    restored=(adb('shell','run-as','com.wbox.app','cat','files/profiles.json')==original) if original is not None else not has_file('files/profiles.json')
    (ROOT/'docs/verification/0.7.4-android-latency-selection.json').write_text(json.dumps({'passed':failure is None and restored,'checks':checks,'observations':observations,'screenshots':list(dict.fromkeys(shots)),'failure':failure,'preferences_restored':restored,'vpn_appops_restored':original_appops,'engines':['mihomo','sing-box']},ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
if failure is not None:raise SystemExit(1)
