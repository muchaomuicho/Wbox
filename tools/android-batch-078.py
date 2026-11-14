"""0.7.8 menu and batch-latency UI checks on isolated emulator-5556 only.

Local gated HTTP proxies keep queued targets observable. The harness restores
the emulator preferences, removes only its temporary data and closes the AVD.
"""
import hashlib, http.server, io, json, os, pathlib, re, select, socket, ssl
import subprocess, sys, tarfile, threading, time, traceback
import xml.etree.ElementTree as ET

ROOT=pathlib.Path(__file__).resolve().parent.parent
ADB=os.environ.get('WBOX_ADB','D:/DevTools/Android/Sdk/platform-tools/adb.exe')
APK=ROOT/'android/app/build/outputs/apk/debug/app-debug.apk'
DATA=ROOT/f'.tools/android-batch-078-{os.getpid()}'
XML='/sdcard/wbox-batch078.xml'
checks=[];shots=[];observations={};failure=None;phase='setup';servers=[]
original_files={};original_runtime={};initial_probes=set();installed=False;restored=False
gates=[threading.Event() for _ in range(6)];hits=[0]*6
for gate in gates:gate.set()
started=time.time()
sys.stdout.reconfigure(encoding='utf-8')

def adb(*args,data=None,timeout=30):
    if args and args[0]=='shell':args=('shell','-T',*args[1:])
    p=subprocess.run([ADB,'-s','emulator-5556',*map(str,args)],input=data,capture_output=True,timeout=timeout)
    if p.returncode:raise RuntimeError(p.stderr.decode(errors='replace') or 'ADB failed: '+' '.join(map(str,args)))
    return p.stdout

def wait(ready,label,timeout=35):
    deadline=time.monotonic()+timeout
    while time.monotonic()<deadline:
        if ready():return
        time.sleep(.12)
    raise AssertionError('Timed out: '+label)

def booted():
    try:return adb('shell','getprop','sys.boot_completed').strip()==b'1'
    except (RuntimeError,subprocess.TimeoutExpired):return False

def check(ok,label,detail=None):
    label=phase+' '+label
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

def tree():
    for _ in range(3):
        output=adb('shell','uiautomator','dump','--compressed',XML)
        if b'dumped' in output:return ET.fromstring(adb('shell','cat',XML))
        time.sleep(.2)
    raise AssertionError('UI hierarchy unavailable')

def box(node):return tuple(map(int,re.findall(r'\d+',node.get('bounds',''))))

def find(label,attr='text',root=None):
    found=[n for n in (tree() if root is None else root).iter('node') if n.get(attr)==label]
    if not found:raise AssertionError('Missing UI label '+label)
    return found[-1] if attr=='text' else found[0]

def tap_box(rect):
    x1,y1,x2,y2=rect;adb('shell','input','tap',(x1+x2)//2,(y1+y2)//2)

def tap(label,attr='text'):
    tap_box(box(find(label,attr)));time.sleep(.2)

def probes():
    if not exists('cache/latency','d'):return set()
    return set(adb('shell','run-as','com.wbox.app','ls','-A','cache/latency').decode().split())

def current_probes():return probes()-initial_probes

def photo(name):
    path=ROOT/f'docs/previews/0.7.8-android-{name}.png'
    path.parent.mkdir(parents=True,exist_ok=True);path.write_bytes(adb('exec-out','screencap','-p'))
    shots.append(path.relative_to(ROOT).as_posix())

def delay_values(root=None):
    root=tree() if root is None else root
    texts=[n for n in root.iter('node') if re.fullmatch(r'\d+ ms|Timeout|未测试',n.get('text',''))]
    result={}
    for n in root.iter('node'):
        match=re.search(r'测试节点 (.+?) 的延迟',n.get('content-desc',''))
        if match:
            # Compose can expose the visible Text as an accessibility sibling
            # of its semantic action, so associate it by screen bounds.
            x1,y1,x2,y2=box(n)
            values=[]
            for text in texts:
                left,top,right,bottom=box(text)
                if x1<=(left+right)/2<=x2 and y1<=(top+bottom)/2<=y2:values.append(text.get('text'))
            result[match.group(1)]=values[0] if values else None
    return result

class Origin(http.server.BaseHTTPRequestHandler):
    protocol_version='HTTP/1.1'
    def do_GET(self):
        try:
            self.send_response(204);self.send_header('Content-Length','0');self.send_header('Connection','close');self.end_headers()
        except OSError:pass
        self.close_connection=True
    do_HEAD=do_GET
    def log_message(self,*args):pass

class Proxy(Origin):
    index=0
    def do_GET(self):
        hits[self.index]+=1;gates[self.index].wait(8);super().do_GET()
    do_HEAD=do_GET
    def do_CONNECT(self):
        hits[self.index]+=1;gates[self.index].wait(8)
        target=tls if self.path.endswith(':'+str(tls.server_port)) else origin
        upstream=None
        try:
            upstream=socket.create_connection(('127.0.0.1',target.server_port))
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
    result=http.server.ThreadingHTTPServer(('0.0.0.0',0),handler);result.daemon_threads=True
    if tls_context:result.socket=tls_context.wrap_socket(result.socket,server_side=True)
    servers.append(result);threading.Thread(target=result.serve_forever,daemon=True).start();return result

def seed(engine,workspace):
    adb('shell','am','force-stop','com.wbox.app')
    names=[f'N{i}' for i in range(6)]
    data={'mode':workspace,'engine':engine,'node_engine':engine,'theme':'light','accent':'blue','app_mode':'all',
          'test_url':'http://93.184.216.34/latency' if engine=='mihomo' else f'https://localhost:{tls.server_port}/latency'}
    if workspace=='nodes':
        data.update({'configs':[],'selected':'N0','nodes':[{'link':f'http://10.0.2.2:{proxies[i].server_port}#{name}'} for i,name in enumerate(names[:3])]})
    else:
        if engine=='mihomo':
            config={'proxies':[{'name':name,'type':'http','server':'10.0.2.2','port':proxies[i].server_port} for i,name in enumerate(names)],
                    'proxy-groups':[{'name':'Main','type':'select','proxies':names[:5]},{'name':'Other','type':'select','proxies':names[5:]}],'rules':['MATCH,Main']}
        else:
            config={'certificate':{'certificate':[(ROOT/'tools/fixtures/latency-localhost-cert.pem').read_text()]},
                    'outbounds':[{'type':'selector','tag':'Main','outbounds':names[:5]},{'type':'selector','tag':'Other','outbounds':names[5:]}]+
                        [{'type':'http','tag':name,'server':'10.0.2.2','server_port':proxies[i].server_port} for i,name in enumerate(names)],'route':{'final':'Main'}}
        content=json.dumps(config);data.update({'active_config':'batch078','config_'+engine:content,'configs':[{'id':'batch078','name':'Batch feedback','core':engine,'content':content}]})
    write('files/profiles.json',data)
    adb('shell','am','start','-W','-n','com.wbox.app/.MainActivity')
    tap('节点' if workspace=='nodes' else '代理')
    root=tree();return box(find('测试延迟' if workspace=='nodes' else '延迟测试','content-desc',root))

def batch(engine,workspace):
    global phase
    phase=engine+' '+workspace
    button=seed(engine,workspace);targets=[f'N{i}' for i in range(3 if workspace=='nodes' else 5)]
    if workspace=='nodes':
        before=json.loads(read('files/profiles.json'))['selected']
        tap('节点操作','content-desc');root=tree();texts=[n.get('text') for n in root.iter('node')]
        check(all(v in texts for v in ('编辑节点','克隆为手动节点','移除节点')) and all(v not in texts for v in ('连接此节点','设为当前节点','测试延迟')),'Menu contains exactly the three retained actions')
        check(json.loads(read('files/profiles.json'))['selected']==before,'Menu does not alter the current node')
        photo('nodes-menu');adb('shell','input','keyevent','4')
        check(not any(n.get('text')=='克隆为手动节点' for n in tree().iter('node')),'Native Back closes the compact menu')
    tap_box(button)
    wait(lambda:bool(current_probes()),'warmup probe starts')
    wait(lambda:not current_probes(),'warmup completes',45)
    values=delay_values();check(all(re.fullmatch(r'\d+ ms',values.get(n) or '') for n in targets),'All targets have old numeric delays before retesting',values)
    for i in range(len(targets)):gates[i].clear()
    previous=sum(hits);tap_box(button)
    wait(lambda:sum(hits)>previous and bool(current_probes()),'gated probe starts')
    photo(f'batch-{engine}-{workspace}-pending')
    values=delay_values();check(all(n in values and values[n] is None for n in targets),'Every batch target clears its old number while queued or running',values)
    gates[0].set()
    values=delay_values();check(re.fullmatch(r'\d+ ms',values.get('N0') or '') is not None and any(values.get(n) is None for n in targets[1:]),'A completed target shows a new result while remaining targets stay pending',values)
    photo(f'batch-{engine}-{workspace}-partial')
    for gate in gates:gate.set()
    wait(lambda:not current_probes(),'batch completes',35)
    values=delay_values();check(all(re.fullmatch(r'\d+ ms|Timeout',values.get(n) or '') for n in targets),'Every target reaches a terminal result',values)
    if workspace=='config':
        tap('Other');values=delay_values();check(values.get('N5') in (None,'未测试') and hits[5]==0,'The other group is not included in the batch',values)
        tap('Main')
    for i in range(len(targets)):gates[i].clear()
    previous=sum(hits);tap_box(button)
    wait(lambda:sum(hits)>previous and bool(current_probes()),'cancelled probe starts')
    tap_box(button)
    for gate in gates:gate.set()
    wait(lambda:not current_probes(),'cancel cleans the probe',30)
    values=delay_values();check(all(n in values and values[n] in (None,'未测试') for n in targets),'Cancel clears pending indicators without restoring stale delays',values)
    photo(f'batch-{engine}-{workspace}-cancelled')

try:
    DATA.mkdir(parents=True,exist_ok=False)
    wait(booted,'emulator boot',120)
    check(adb('get-serialno').strip()==b'emulator-5556','Only the isolated emulator is targeted')
    adb('shell','am','force-stop','com.wbox.app')
    for path in ['files/profiles.json','files/probe-state.json','files/probe-error.txt']:
        original_files[path]=read(path) if exists(path) else None
    for index,path in enumerate(['files/runtime/mihomo/nodes','files/runtime/sing-box/nodes']):
        original_runtime[path]=adb('exec-out','run-as','com.wbox.app','tar','cf','-',path) if exists(path,'d') else None
        if original_runtime[path] is not None:(DATA/f'runtime-{index}.tar').write_bytes(original_runtime[path])
    for index,content in enumerate(original_files.values()):
        if content is not None:(DATA/f'original-{index}.bin').write_bytes(content)
    initial_probes=probes()
    check(b'Success' in adb('install','-r','-t',APK,timeout=180),'Debug APK installs successfully');installed=True
    check(b'versionName=0.7.8' in adb('shell','dumpsys','package','com.wbox.app'),'Version 0.7.8 is installed')
    observations['apk_sha256']=hashlib.sha256(APK.read_bytes()).hexdigest()
    context=ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER);context.load_cert_chain(ROOT/'tools/fixtures/latency-localhost-cert.pem',ROOT/'tools/fixtures/latency-localhost-key.pem')
    origin=server(Origin);tls=server(Origin,context);proxies=[server(type(f'Proxy{i}',(Proxy,),{'index':i})) for i in range(6)]
    observations['fixture']='Local synthetic gated proxies via 10.0.2.2; no public requests, VPN start, or system trust changes.'
    batch('mihomo','nodes');batch('mihomo','config');batch('sing-box','config')
except Exception as error:
    failure=repr(error);traceback.print_exc()
    if installed:
        try:
            photo('batch-failure');root=tree();observations['failure_ui']=delay_values(root)
            (ROOT/'.tools/android-batch-078-failure.xml').write_bytes(ET.tostring(root))
        except Exception:pass
finally:
    for gate in gates:gate.set()
    try:
        if installed:
            adb('shell','am','force-stop','com.wbox.app')
            for path,content in original_files.items():
                if content is None:adb('shell','run-as','com.wbox.app','rm','-f',path)
                else:write(path,content)
            for index,(path,content) in enumerate(original_runtime.items()):
                if content is not None:
                    with tarfile.open(fileobj=io.BytesIO(content)) as archive:
                        for entry in archive.getmembers():
                            if entry.isfile():assert read(entry.name)==archive.extractfile(entry).read(),'Runtime unexpectedly changed: '+entry.name
            for name in current_probes():
                assert re.fullmatch(r'[0-9a-f-]+',name);adb('shell','run-as','com.wbox.app','rm','-rf','cache/latency/'+name)
            restored=all(read(path)==content if content is not None else not exists(path) for path,content in original_files.items())
            observations['runtime_unchanged']=True
        adb('shell','rm','-f',XML)
    except Exception as error:failure=failure or 'Restore failed: '+repr(error);traceback.print_exc()
    for item in servers:item.shutdown();item.server_close()
    try:adb('emu','kill');observations['emulator_closed']=True
    except Exception as error:failure=failure or 'Emulator close failed: '+repr(error)
    if DATA.exists() and (restored or not installed):
        for item in DATA.iterdir():
            assert item.is_file() and not item.is_symlink();item.unlink()
        DATA.rmdir()
    observations['temporary_host_files_removed']=not DATA.exists()
    observations['proxy_hits']=hits;observations['duration_seconds']=round(time.time()-started,1)
    result={'passed':failure is None and restored,'checks':checks,'observations':observations,'screenshots':shots,'failure':failure,'preferences_restored':restored}
    (ROOT/'docs/verification/0.7.8-android-batch.json').write_text(json.dumps(result,ensure_ascii=False,indent=2)+'\n',encoding='utf-8',newline='\n')
if failure is not None:raise SystemExit(1)
