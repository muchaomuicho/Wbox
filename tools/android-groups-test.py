"""Large-group UI and native connection-owner checks on Wbox_API35 only."""
import http.server,json,pathlib,re,socket,subprocess,threading,time,xml.etree.ElementTree as ET
ROOT=pathlib.Path(__file__).resolve().parent.parent
ADB='C:/Users/hw/AppData/Local/Android/Sdk/platform-tools/adb.exe'
results={'checks':[],'opensMs':[]}
def adb(*args,data=None,timeout=30):
    p=subprocess.run([ADB,'-s','emulator-5556',*args],input=data,stdout=subprocess.PIPE,stderr=subprocess.PIPE,timeout=timeout)
    if p.returncode:raise RuntimeError(p.stderr.decode(errors='replace'))
    return p.stdout
def probe(op,engine='mihomo'):
    adb('shell','am','broadcast','-n','com.wbox.app/.RuntimeProbeReceiver','--es','operation',op,'--es','engine',engine)
def state():
    probe('status');time.sleep(.1)
    return json.loads(adb('shell','run-as','com.wbox.app','cat','files/probe-state.json'))
def wait(ready,timeout=35):
    end=time.monotonic()+timeout
    while time.monotonic()<end:
        value=state()
        if ready(value):return value
        if value['error']:raise AssertionError(value['error'])
        time.sleep(.2)
    raise AssertionError('Service did not settle')
def tree():
    adb('shell','uiautomator','dump','/sdcard/wbox-groups-test.xml')
    return ET.fromstring(adb('shell','cat','/sdcard/wbox-groups-test.xml'))
def find(value,attr='content-desc'):
    for _ in range(6):
        for n in tree().iter('node'):
            if n.get(attr)==value:return n
        time.sleep(.3)
    raise AssertionError('UI entry missing: '+value)
def tap(value,attr='content-desc'):
    node=find(value,attr);x1,y1,x2,y2=map(int,re.findall(r'\d+',node.get('bounds')))
    adb('shell','input','tap',str((x1+x2)//2),str((y1+y2)//2))
def screenshot(name):
    (ROOT/f'docs/previews/0.6.4-android-{name}.png').write_bytes(adb('exec-out','screencap','-p'))
class Stream(http.server.BaseHTTPRequestHandler):
    def do_GET(self):
        self.send_response(200);self.end_headers()
        try:
            for _ in range(240):self.wfile.write(b'Wbox owner test\n');self.wfile.flush();time.sleep(.25)
        except (BrokenPipeError,ConnectionResetError,ConnectionAbortedError):pass
    def log_message(self,*args):pass
server=http.server.ThreadingHTTPServer(('0.0.0.0',19284),Stream)
threading.Thread(target=server.serve_forever,daemon=True).start()
udp=socket.socket(socket.AF_INET,socket.SOCK_DGRAM);udp.bind(('0.0.0.0',19285));udp.settimeout(.5)
def echo():
    while True:
        try:body,peer=udp.recvfrom(2048);udp.sendto(body,peer)
        except socket.timeout:continue
        except OSError:return
threading.Thread(target=echo,daemon=True).start()
clients=[]
try:
    adb('install','-r',str(ROOT/'android/app/build/outputs/apk/debug/app-debug.apk'),timeout=120)
    adb('shell','pm','grant','com.wbox.app','android.permission.POST_NOTIFICATIONS')
    adb('shell','appops','set','com.wbox.app','ACTIVATE_VPN','allow')
    for engine in ('sing-box','mihomo'):
        probe('seed',engine);adb('shell','am','force-stop','com.wbox.app');adb('shell','am','start','-n','com.wbox.app/.MainActivity')
        probe('connect',engine);wait(lambda s:s['running'])
        for protocol,port in [('tcp','19284'),('udp','19285')]:
            args=['shell','toybox','nc']+(['-u'] if protocol=='udp' else [])+['10.0.2.2',port]
            p=subprocess.Popen([ADB,'-s','emulator-5556',*args],stdin=subprocess.PIPE,stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL)
            clients.append(p);p.stdin.write(b'GET / HTTP/1.0\r\nHost: test\r\n\r\n' if protocol=='tcp' else b'owner');p.stdin.flush()
        time.sleep(1)
        matched=[]
        for _ in range(5):
            probe('details');time.sleep(.2)
            detail=json.loads(adb('shell','run-as','com.wbox.app','cat','files/probe-details.json'))
            matched=[r for r in detail['connections'] if r['destination'].endswith((':19284',':19285'))]
            if {r['network'] for r in matched if r['package']=='com.android.shell'}=={'TCP','UDP'}:break
            time.sleep(.5)
        assert {r['network'] for r in matched if r['package']=='com.android.shell'}=={'TCP','UDP'},detail
        results['checks'].append(engine+' TCP and UDP retain their real originating app')
        for p in clients:p.terminate();p.wait(timeout=5)
        clients=[];probe('stop');wait(lambda s:not s['running'] and not s['starting'])
    count=2000
    names=[f'Node {i:04}' for i in range(count)]
    config=json.dumps({'proxies':[{'name':n,'type':'ss','server':'127.0.0.1','port':9,'cipher':'aes-128-gcm','password':'synthetic'} for n in names],
        'proxy-groups':[{'name':tag,'type':'select','proxies':names} for tag in ['Stress','header','nodes','0']], 'rules':['MATCH,DIRECT']})
    prefs={'mode':'config','engine':'mihomo','theme':'dark','config_mihomo':config,'active_config':'stress',
           'configs':[{'id':'stress','name':'2,000 nodes','core':'mihomo','content':config}]}
    adb('shell','am','force-stop','com.wbox.app')
    adb('shell','run-as','com.wbox.app','sh','-c',"'cat > files/profiles.json'",data=json.dumps(prefs).encode())
    adb('shell','am','start','-n','com.wbox.app/.MainActivity');probe('connect');wait(lambda s:s['running'])
    pid=adb('shell','pidof','com.wbox.app').strip()
    for i in range(6):
        began=time.monotonic();tap('代理组');find('Node 0000','text')
        results['opensMs'].append(round((time.monotonic()-began)*1000))
        assert adb('shell','pidof','com.wbox.app').strip()==pid
        if i==0:screenshot('large-group-dark')
        if i==1:
            tap('展开或收起全部');screenshot('group-dots-dark');tap('展开或收起全部')
        adb('shell','input','keyevent','4');find('断开代理连接')
    screenshot('running-dark')
    results['checks'].append('2,000-node configuration opens groups repeatedly with stable process PID')
    results['checks'].append('Large groups collapse to canvas indicators and expand without a crash')
finally:
    for p in clients:p.terminate()
    probe('stop');server.shutdown();server.server_close();udp.close()
    (ROOT/'docs/verification/0.6.4-android-groups.json').write_text(json.dumps(results,indent=2))
print(json.dumps(results,indent=2))
