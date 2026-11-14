"""Exercise node selection with real UI taps on isolated emulator-5556.

Installs the x86_64 debug APK, serves two synthetic local proxies, restores
application preferences/runtime files and VPN permission, then closes the AVD.
"""
import hashlib
import http.server
import http.client
import io
import json
import os
import pathlib
import re
import select
import shutil
import socket
import ssl
import subprocess
import sys
import tarfile
import threading
import time
import traceback
import xml.etree.ElementTree as ET

ROOT=pathlib.Path(__file__).resolve().parent.parent
ADB=os.environ.get('WBOX_ADB','D:/DevTools/Android/Sdk/platform-tools/adb.exe')
APK=pathlib.Path(os.environ.get('WBOX_ANDROID_APK',ROOT/'android/app/build/outputs/apk/debug/app-debug.apk')).resolve()
DATA=ROOT/f'.tools/android-nodes-077-{os.getpid()}'
XML='/sdcard/wbox-nodes077.xml'
checks=[];screens=[];observations={};failure=None;engine='setup';servers=[]
original_files={};original_runtime={};original_appops=None;original_mainkeys=None;installed=False;restored=False;forward_port=None
hits=[0,0];latency_hits=[0,0];proxy_requests=[];gate=threading.Event();gate.set()
began=time.time()
sys.stdout.reconfigure(encoding='utf-8')

def adb(*args,data=None,timeout=30):
    # A Windows adb shell may otherwise allocate a PTY, which rewrites CR/LF
    # and corrupts piped HTTP bodies or binary archive input.
    if args and args[0]=='shell':args=('shell','-T',*args[1:])
    result=subprocess.run([ADB,'-s','emulator-5556',*map(str,args)],input=data,capture_output=True,timeout=timeout)
    if result.returncode:raise RuntimeError(result.stderr.decode(errors='replace') or 'ADB failed: '+' '.join(map(str,args)))
    return result.stdout

def wait(predicate,label,timeout=35):
    end=time.monotonic()+timeout
    while time.monotonic()<end:
        if predicate():return
        time.sleep(.12)
    raise AssertionError('Timed out: '+label)

def check(ok,label,detail=None):
    label=engine+' '+label
    if not ok:raise AssertionError(label+(': '+str(detail) if detail is not None else ''))
    checks.append(label)
    if detail is not None:observations[label]=detail
    print('PASS',label,flush=True)

def exists(path,kind='f'):
    try:adb('shell','run-as','com.wbox.app','test','-'+kind,path);return True
    except RuntimeError:return False

def read(path):return adb('exec-out','run-as','com.wbox.app','cat',path)

def write(path,data):
    if not isinstance(data,bytes):data=json.dumps(data).encode()
    adb('shell','run-as','com.wbox.app','mkdir','-p',str(pathlib.PurePosixPath(path).parent))
    adb('shell','run-as','com.wbox.app','sh','-c',"'cat > "+path+"'",data=data)

def prefs():return json.loads(read('files/profiles.json'))

def nodes():
    for _ in range(3):
        output=adb('shell','uiautomator','dump','--compressed',XML)
        if b'dumped' in output:return list(ET.fromstring(adb('shell','cat',XML)).iter('node'))
        time.sleep(.2)
    raise AssertionError('UI hierarchy unavailable')

def bounds(node):return tuple(map(int,re.findall(r'\d+',node.get('bounds',''))))

def find(label,attr='text',items=None):
    found=[node for node in (nodes() if items is None else items) if node.get(attr)==label]
    if not found:raise AssertionError('Missing UI label '+label)
    return found[-1] if attr=='text' else found[0]

def tap_box(rect):
    left,top,right,bottom=rect
    adb('shell','input','tap',(left+right)//2,(top+bottom)//2)

def tap(label,attr='text'):
    tap_box(bounds(find(label,attr)))
    time.sleep(.25)

def status():
    adb('shell','run-as','com.wbox.app','rm','-f','files/probe-state.json')
    adb('shell','am','broadcast','-n','com.wbox.app/.RuntimeProbeReceiver','--es','operation','status')
    wait(lambda:exists('files/probe-state.json'),'runtime status')
    return json.loads(read('files/probe-state.json'))

def is_stopped(value):return not any(value[key] for key in ('running','starting','stopping','reloading'))

def active_probes():
    if not exists('cache/latency','d'):return set()
    return set(adb('shell','run-as','com.wbox.app','ls','-A','cache/latency').decode().split())

def launch():
    adb('shell','am','start','-W','-n','com.wbox.app/.MainActivity')
    wait(lambda:any(n.get('text')=='概览' for n in nodes()),'main page',60)

def seed():
    adb('shell','am','force-stop','com.wbox.app')
    write('files/profiles.json',{'mode':'nodes','engine':engine,'node_engine':engine,'theme':'light','accent':'blue',
          'app_mode':'all','configs':[],'selected':'Node0',
          'test_url':'http://93.184.216.34/latency' if engine=='mihomo' else f'https://93.184.216.34:{tls.server_port}/latency',
          'nodes':[{'link':f'http://10.0.2.2:{proxy.server_port}#Node{index}'} for index,proxy in enumerate(proxies)]})
    launch();tap('节点')
    wait(lambda:any(n.get('text')=='Node1' for n in nodes()),'node library')

def photo(name):
    path=ROOT/f'docs/previews/0.7.7-android-nodes-{name}.png'
    path.parent.mkdir(parents=True,exist_ok=True)
    path.write_bytes(adb('exec-out','screencap','-p'))
    screens.append(path.relative_to(ROOT).as_posix())

class Origin(http.server.BaseHTTPRequestHandler):
    protocol_version='HTTP/1.1'
    index=0
    def handle(self):
        try:super().handle()
        except OSError:pass
    def do_GET(self):
        try:
            latency='/latency' in self.path
            if latency:latency_hits[self.index]+=1;gate.wait(20)
            body=f'WBOX-{self.index}'.encode()
            self.send_response(200);self.send_header('Content-Length',str(len(body)));self.send_header('Connection','close');self.end_headers();self.wfile.write(body)
        except OSError:pass
        self.close_connection=True
    do_HEAD=do_GET
    def log_message(self,*_):pass

class Proxy(Origin):
    def do_GET(self):
        hits[self.index]+=1
        proxy_requests.append([self.index,self.command,self.path])
        super().do_GET()
    do_HEAD=do_GET
    def do_CONNECT(self):
        hits[self.index]+=1
        proxy_requests.append([self.index,self.command,self.path])
        latency=self.path==f'93.184.216.34:{tls.server_port}'
        if not latency and self.path!='93.184.216.34:80':
            self.connection.sendall(b'HTTP/1.1 502 Bad Gateway\r\nConnection: close\r\nContent-Length: 0\r\n\r\n');self.close_connection=True;return
        if latency:latency_hits[self.index]+=1;gate.wait(20)
        upstream=None
        try:
            upstream=socket.create_connection(('127.0.0.1',(tls if latency else origins[self.index]).server_port))
            self.connection.sendall(b'HTTP/1.1 200 Connection Established\r\n\r\n')
            while True:
                ready,_,_=select.select([self.connection,upstream],[],[],5)
                if not ready:break
                for source in ready:
                    chunk=source.recv(65536)
                    if not chunk:return
                    (upstream if source is self.connection else self.connection).sendall(chunk)
        except OSError:pass
        finally:
            if upstream:upstream.close()
            self.close_connection=True

def server(handler,tls_context=None):
    result=http.server.ThreadingHTTPServer(('0.0.0.0',0),handler)
    result.daemon_threads=True
    if tls_context:result.socket=tls_context.wrap_socket(result.socket,server_side=True)
    servers.append(result);threading.Thread(target=result.serve_forever,daemon=True).start()
    return result

def forward(index,label):
    client=http.client.HTTPConnection('127.0.0.1',forward_port,timeout=8)
    try:
        client.request('GET','http://93.184.216.34/forward',headers={'Host':'93.184.216.34','Connection':'close'})
        response=client.getresponse();body=response.read()
        check(response.status==200 and body==f'WBOX-{index}'.encode(),label,{'status':response.status,'body':body.decode(errors='replace')})
    finally:client.close()

def menu(index=0):
    current=nodes();buttons=[n for n in current if n.get('content-desc')=='节点操作'];rect=bounds(buttons[index])
    tap_box(rect);time.sleep(.3)
    current=nodes();actions=[find(label,items=current) for label in ('连接此节点','设为当前节点','测试延迟','编辑节点','克隆为手动节点','移除节点')]
    action_boxes=[bounds(n) for n in actions]
    content=(min(b[0] for b in action_boxes),min(b[1] for b in action_boxes),max(b[2] for b in action_boxes),max(b[3] for b in action_boxes))
    size=tuple(map(int,re.search(r'(\d+)x(\d+)',adb('shell','wm','size').decode()).groups()))
    check(0<content[2]-content[0]<size[0]*.8 and abs(content[1]-rect[3])<size[1]*.12 and content[3]-content[1]<size[1]*.65,
          'Node actions form a compact menu beside their button',{'button':rect,'menu_text':content,'screen':size})

def edge_back(side):
    width,height=tuple(map(int,re.search(r'(\d+)x(\d+)',adb('shell','wm','size').decode()).groups()))
    start=1 if side=='left' else width-2;end=width//3 if side=='left' else width*2//3
    adb('shell','input','swipe',start,height//2,end,height//2,'280');time.sleep(.5)

try:
    DATA.mkdir(parents=True,exist_ok=False)
    def booted():
        try:return adb('shell','getprop','sys.boot_completed',timeout=5).strip()==b'1'
        except (RuntimeError,subprocess.TimeoutExpired):return False
    wait(booted,'emulator boot',180)
    check(b'emulator-5556' in adb('get-serialno'),'Only the isolated emulator is targeted')
    if b'[Gesture Monitor] edge-swipe' not in adb('shell','dumpsys','input'):
        original_mainkeys=adb('shell','getprop','qemu.hw.mainkeys').decode().strip()
        adb('root');adb('wait-for-device',timeout=30)
        adb('shell','setprop','qemu.hw.mainkeys','0')
        adb('shell','stop');adb('shell','start')
        wait(booted,'Android framework boot',120)
        wait(lambda:b'[Gesture Monitor] edge-swipe' in adb('shell','dumpsys','input'),'native edge-swipe monitor',60)
        observations['gesture_setup']='Temporarily set qemu.hw.mainkeys=0 and restart Android framework; AVD files untouched'
    check(b'[Gesture Monitor] edge-swipe' in adb('shell','dumpsys','input'),'Native SystemUI edge-swipe monitor is enabled')
    check(APK.is_file(),'Debug APK exists')
    if b'package:' in adb('shell','pm','path','com.wbox.app'):
        adb('shell','am','force-stop','com.wbox.app')
        for path in ['files/profiles.json','files/probe-state.json','files/probe-error.txt']:
            original_files[path]=read(path) if exists(path) else None
        for path in ['files/runtime/mihomo/nodes','files/runtime/sing-box/nodes']:
            original_runtime[path]=adb('exec-out','run-as','com.wbox.app','tar','cf','-',path) if exists(path,'d') else None
        original_appops=adb('shell','appops','get','com.wbox.app','ACTIVATE_VPN').decode().strip()
    for index,(path,content) in enumerate(original_files.items()):
        if content is not None:(DATA/f'original-{index}.bin').write_bytes(content)
    for index,content in enumerate(original_runtime.values()):
        if content is not None:
            with tarfile.open(fileobj=io.BytesIO(content)) as archive:archive.getmembers()
            (DATA/f'runtime-{index}.tar').write_bytes(content)
    output=adb('install','-r','-t',APK,timeout=180)
    installed=True
    check(b'Success' in output,'Debug APK installs successfully')
    check(b'versionName=0.7.7' in adb('shell','dumpsys','package','com.wbox.app'),'Version 0.7.7 is installed')
    observations['apk_sha256']=hashlib.sha256(APK.read_bytes()).hexdigest()
    package=adb('shell','pm','path','com.wbox.app').decode().strip().removeprefix('package:')
    observations['installed_apk_sha256']=hashlib.sha256(adb('exec-out','cat',package,timeout=90)).hexdigest()
    check(observations['apk_sha256']==observations['installed_apk_sha256'],'Installed APK exactly matches the tested build')
    forward_port=int(adb('forward','tcp:0','tcp:10808').strip())
    adb('shell','appops','set','com.wbox.app','ACTIVATE_VPN','allow')
    context=ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
    context.load_cert_chain(ROOT/'tools/fixtures/latency-localhost-cert.pem',ROOT/'tools/fixtures/latency-localhost-key.pem')
    tls=server(Origin,context)
    origins=[server(type(f'Origin{i}',(Origin,),{'index':i})) for i in range(2)]
    proxies=[server(type(f'Proxy{i}',(Proxy,),{'index':i})) for i in range(2)]
    observations['fixture_transport']='Local synthetic proxies via emulator 10.0.2.2; no public endpoint or trust changes'
    transport=adb('shell','toybox','nc','-w','3','10.0.2.2',proxies[0].server_port,data=b'GET http://93.184.216.34/forward HTTP/1.1\r\nHost: 93.184.216.34\r\nConnection: close\r\n\r\n')
    check(transport.endswith(b'WBOX-0'),'Raw non-PTY HTTP reaches the local proxy unchanged')
    for engine in ['mihomo','sing-box']:
        seed();tap('Node1')
        wait(lambda:prefs().get('selected')=='Node1','single-tap selection')
        value=status();check(is_stopped(value) and not any('开始启动' in row for row in value['logs']),'Single tap persists Node1 while staying disconnected')
        adb('shell','am','force-stop','com.wbox.app');launch();tap('节点')
        check(prefs().get('selected')=='Node1' and is_stopped(status()),'The selected node survives application restart without connecting')
        menu(0);check(prefs().get('selected')=='Node1' and is_stopped(status()),'Opening another node menu does not select or connect it')
        if engine=='mihomo':photo('menu')
        adb('shell','input','keyevent','4');time.sleep(.3)
        check(not any(n.get('text')=='连接此节点' for n in nodes()),'Back closes the floating node menu')
        for side in ['left','right']:
            menu(0);edge_back(side)
            check(not any(n.get('text')=='连接此节点' for n in nodes()),side.capitalize()+' edge Back closes the floating node menu')
        tap('连接代理','content-desc');wait(lambda:status()['running'],'explicit VPN start',50)
        forward(1,'Explicit start forwards through the saved Node1')
        tap('Node0');wait(lambda:prefs().get('selected')=='Node0' and status()['running'],'live single-tap switch',50)
        forward(0,'Single-tap live selection reconnects traffic through Node0')
        previous=status()['startedAt'];tap('Node0');time.sleep(.5)
        check(status()['startedAt']==previous and status()['running'],'Tapping the current node keeps the existing connection')
        if engine=='mihomo':
            current=nodes();target=bounds(find('Node1',items=current));test=bounds(find('测试延迟','content-desc',current))
            gate.clear();previous_hits=sum(latency_hits);tap_box(test)
            wait(lambda:sum(latency_hits)>previous_hits and bool(active_probes()),'real node latency starts')
            initial=active_probes();tap_box(target)
            # Observe the batch immediately after the tap. Waiting for the
            # complete VPN reload first can outlast the probe's five-second
            # timeout, which is normal completion rather than cancellation.
            check(bool(initial.intersection(active_probes())),'A UI node tap keeps the original latency batch running')
            wait(lambda:prefs().get('selected')=='Node1' and status()['running'],'selection during latency',45)
            forward(1,'The node selected during latency carries the new traffic')
            gate.set();wait(lambda:not active_probes(),'latency completion',40)
            results=[n.get('text') for n in nodes() if re.fullmatch(r'\d+ ms|Timeout',n.get('text',''))]
            # A deliberately held request may reach its five-second timeout;
            # it must still commit a terminal result instead of being canceled.
            check(len(results)==2 and prefs().get('selected')=='Node1','Both held latency requests finish and preserve the latest selection',results)
            previous_hits=sum(latency_hits);tap('测试延迟','content-desc')
            wait(lambda:sum(latency_hits)>previous_hits,'fresh latency request')
            wait(lambda:not active_probes(),'fresh latency completion',30)
            results=[n.get('text') for n in nodes() if re.fullmatch(r'\d+ ms',n.get('text',''))]
            check(len(results)==2,'Both nodes remeasure successfully once the gate is released',results)
            photo('connected')
        tap('断开代理连接','content-desc');wait(lambda:is_stopped(status()),'explicit UI stop')
        tap('Node1' if prefs().get('selected')=='Node0' else 'Node0')
        check(is_stopped(status()),'Choosing a node after UI stop stays disconnected')
    check(not active_probes(),'All transient latency directories are cleaned')
except Exception as error:
    failure=repr(error);traceback.print_exc()
    if installed:
        try:observations['failure_status']=status();photo('failure')
        except Exception:pass
finally:
    gate.set()
    observations['proxy_hits']=hits;observations['latency_hits']=latency_hits;observations['proxy_requests']=proxy_requests[-40:]
    try:
        if installed:
            adb('shell','am','force-stop','com.wbox.app')
            for path in ['files/profiles.json','files/probe-state.json','files/probe-error.txt']:
                if original_files.get(path) is not None:write(path,original_files[path])
                else:adb('shell','run-as','com.wbox.app','rm','-f',path)
            for index,path in enumerate(['files/runtime/mihomo/nodes','files/runtime/sing-box/nodes']):
                assert path.startswith('files/runtime/') and path.endswith('/nodes')
                adb('shell','run-as','com.wbox.app','rm','-rf',path)
                if original_runtime.get(path) is not None:
                    remote=f'/data/local/tmp/wbox-nodes077-{os.getpid()}-{index}.tar'
                    adb('push',DATA/f'runtime-{index}.tar',remote,timeout=60)
                    adb('shell','run-as','com.wbox.app','tar','xf',remote,timeout=60)
                    adb('shell','rm','-f',remote)
                    with tarfile.open(fileobj=io.BytesIO(original_runtime[path])) as archive:
                        for member in archive.getmembers():
                            if member.isfile():
                                assert member.name.startswith(path+'/')
                                assert read(member.name)==archive.extractfile(member).read(),'Runtime restore mismatch: '+member.name
            match=re.search(r'ACTIVATE_VPN: (\w+)',original_appops or '')
            expected_mode=match.group(1) if match else 'default'
            adb('shell','appops','set','com.wbox.app','ACTIVATE_VPN',expected_mode)
            restored_appops=adb('shell','appops','get','com.wbox.app','ACTIVATE_VPN').decode()
            restored_match=re.search(r'ACTIVATE_VPN: (\w+)',restored_appops)
            observations['vpn_appops_restored']=(restored_match.group(1) if restored_match else 'default')==expected_mode
            assert observations['vpn_appops_restored'],'VPN permission restore mismatch'
            observations['runtime_files_restored']=True
            restored=all(read(path)==content if content is not None else not exists(path) for path,content in original_files.items())
        adb('shell','rm','-f',XML)
    except Exception as error:failure=failure or 'Restore failed: '+repr(error);traceback.print_exc()
    for item in servers:item.shutdown();item.server_close()
    try:
        if forward_port is not None:adb('forward','--remove',f'tcp:{forward_port}')
        if original_mainkeys is not None:adb('shell',"setprop qemu.hw.mainkeys '"+original_mainkeys+"'")
        adb('emu','kill')
        observations['emulator_closed']=True
    except Exception as error:failure=failure or 'Emulator close failed: '+repr(error)
    try:
        assert DATA.resolve().parent==(ROOT/'.tools').resolve() and DATA.name==f'android-nodes-077-{os.getpid()}'
        if DATA.exists() and (restored or not installed):shutil.rmtree(DATA)
        observations['temporary_host_files_removed']=not DATA.exists()
        if DATA.exists():observations['preserved_restore_backup']=str(DATA)
    except Exception as error:failure=failure or 'Cleanup failed: '+repr(error)
    observations['duration_seconds']=round(time.time()-began,1)
    if failure is None and restored:
        old_failure=ROOT/'docs/previews/0.7.7-android-nodes-failure.png'
        old_failure.unlink(missing_ok=True)
    result={'passed':failure is None and restored,'checks':checks,'observations':observations,'screenshots':screens,'failure':failure,
            'preferences_restored':restored,'original_vpn_appops':original_appops,'engines':['mihomo','sing-box']}
    (ROOT/'docs/verification/0.7.7-android-nodes.json').write_text(json.dumps(result,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
if failure is not None:raise SystemExit(1)
