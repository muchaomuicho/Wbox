"""Wbox 0.6.11 integration against the isolated API35 emulator and local fixtures."""
import http.server,json,pathlib,re,socket,subprocess,threading,time,xml.etree.ElementTree as ET
ROOT=pathlib.Path(__file__).resolve().parent.parent
ADB='C:/Users/hw/AppData/Local/Android/Sdk/platform-tools/adb.exe'
checks=[];failure=None;hits=[0,0];header='upload=1024;download=2048;total=107374182400;expire=1893456000';response_status=200
def adb(*args,data=None,timeout=40):
 r=subprocess.run([ADB,'-s','emulator-5556',*args],input=data,capture_output=True,timeout=timeout)
 if r.returncode:raise RuntimeError(r.stderr.decode(errors='replace'))
 return r.stdout
def nodes():
 adb('shell','uiautomator','dump','/sdcard/wbox-0611.xml')
 return list(ET.fromstring(adb('shell','cat','/sdcard/wbox-0611.xml')).iter('node'))
def labels():return [n.get('text','') for n in nodes()]
def point(n):
 a,b,c,d=map(int,re.findall(r'\d+',n.get('bounds')));return str((a+c)//2),str((b+d)//2)
def tap(label,attr='text'):
 for _ in range(5):
  found=[n for n in nodes() if n.get(attr)==label]
  if found:adb('shell','input','tap',*point(found[-1]));return
 raise AssertionError('Missing '+label)
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
def start(p):
 adb('shell','am','force-stop','com.wbox.app');save(p);adb('shell','am','start','-W','-n','com.wbox.app/.MainActivity');wait(lambda:'概览' in labels())
def screenshot(name):(ROOT/f'docs/previews/0.6.11-android-{name}.png').write_bytes(adb('exec-out','screencap','-p'))
def op(action):adb('shell','am','broadcast','-n','com.wbox.app/.RuntimeProbeReceiver','--es','operation',action)
def status():
 op('status');time.sleep(.15);return json.loads(adb('shell','run-as','com.wbox.app','cat','files/probe-state.json'))
def fill(index,value):
 n=[n for n in nodes() if n.get('class')=='android.widget.EditText'][index]
 adb('shell','input','tap',*point(n));adb('shell','input','keycombination','113','29');adb('shell','input','text',value);adb('shell','input','keyevent','KEYCODE_BACK')
def scroll():adb('shell','input','swipe','480','1600','480','650','350')
class Source(http.server.BaseHTTPRequestHandler):
 def do_GET(self):
  self.send_response(response_status)
  if header:self.send_header('Subscription-Userinfo',header)
  self.end_headers();self.wfile.write(json.dumps({'proxies':[],'proxy-groups':[{'name':'Main','type':'select','proxies':['DIRECT']}],'rules':['MATCH,Main']}).encode())
 def log_message(self,*args):pass
class Proxy(http.server.BaseHTTPRequestHandler):
 protocol_version='HTTP/1.1'
 def do_CONNECT(self):
  self.send_response(200);self.end_headers();self.close_connection=False
 def answer(self):
  hits[self.server.index]+=1;time.sleep(.06)
  body=f'WBOX-{self.server.index}'.encode();self.send_response(200);self.send_header('Content-Length',str(len(body)));self.end_headers()
  if self.command!='HEAD':self.wfile.write(body)
 do_GET=answer;do_HEAD=answer
 def log_message(self,*args):pass
source=http.server.ThreadingHTTPServer(('0.0.0.0',0),Source)
proxies=[http.server.ThreadingHTTPServer(('0.0.0.0',0),Proxy) for _ in range(2)]
for i,p in enumerate(proxies):p.index=i
for server in [source,*proxies]:threading.Thread(target=server.serve_forever,daemon=True).start()
url=f'http://10.0.2.2:{source.server_port}/config'
try:
 adb('shell','appops','set','com.wbox.app','ACTIVATE_VPN','allow')
 start({'mode':'config','theme':'light','configs':[]})
 tap('配置');tap('添加配置','content-desc');fill(0,'TrafficPlan');fill(1,url);tap('添加');wait(lambda:len(prefs().get('configs',[]))==1)
 p=prefs();u=p['configs'][0]['subscription']['usage'];check(u['total']==107374182400 and u['download']==2048,'Android URL import stores subscription headers')
 wait(lambda:any('100 GB' in l for l in labels()));check(True,'Configuration card displays traffic quota');screenshot('subscriptions')
 tap('配置操作','content-desc');wait(lambda:'编辑订阅' in labels())
 check(any(n.get('class')=='android.widget.EditText' for n in nodes()) and '编辑配置' in labels(),'Overflow opens a full editor with subscription and config actions')
 screenshot('editor');tap('编辑配置');wait(lambda:'编辑配置' in labels());check(any(n.get('content-desc')=='配置内容' for n in nodes()),'Configuration code editor is reachable from subscription page');tap('返回','content-desc')
 header='upload=4096;download=8192;total=107374182400;expire=0'
 for _ in range(3):
  if '立即更新' in labels():break
  scroll()
 tap('立即更新');wait(lambda:prefs()['configs'][0]['subscription']['usage']['download']==8192);check(True,'Manual update refreshes subscription traffic')
 response_status=401;tap('立即更新');time.sleep(2);check(prefs()['configs'][0]['subscription']['usage']['download']==8192,'Download failure preserves quota and configuration');response_status=200
 header='';tap('立即更新');wait(lambda:'usage' not in prefs()['configs'][0]['subscription']);check(True,'Missing response header removes stale traffic');tap('返回','content-desc')
 # Use a fresh, isolated node library for each core.
 links=[f'http://10.0.2.2:{p.server_port}#Node{i}' for i,p in enumerate(proxies)]
 for core in ['mihomo','sing-box']:
  p={'mode':'nodes','engine':core,'node_engine':core,'theme':'light','ipv6':False,'nodes':links,'selected':'Node0','test_url':'http://93.184.216.34/check','configs':[]}
  start(p);tap('节点');check('Selector' not in labels() and '代理' not in labels(),core+' node workspace uses the independent node list')
  if core=='mihomo':
   before=hits.copy();tap('测试节点 Node1 的延迟','content-desc');wait(lambda:hits[1]>before[1]);wait(lambda:any('ms' in l for l in labels()));check(hits[0]==before[0] and prefs()['selected']=='Node0','Single-node latency click leaves selection unchanged')
  for index in [1,0]:
   n=next(n for n in nodes() if n.get('text')==f'Node{index}');x,y=point(n)
   adb('shell','input','tap',x,y);adb('shell','input','tap',x,y)
   wait(lambda:status()['running'] and prefs()['selected']==f'Node{index}',60)
   # Reach the app's loopback mixed listener through adb, preserving the VPN.
   forward=int(adb('forward','tcp:0','tcp:10808').decode().strip())
   with socket.create_connection(('127.0.0.1',forward),timeout=8) as client:
    client.sendall(b'GET http://93.184.216.34/forward HTTP/1.1\r\nHost: 93.184.216.34\r\nConnection: close\r\n\r\n');body=b''
    while True:
     chunk=client.recv(8192)
     if not chunk:break
     body+=chunk
   adb('forward','--remove',f'tcp:{forward}')
   check(f'WBOX-{index}'.encode() in body,core+f' double-tap forwards through Node{index} only')
  tun=adb('shell','ip','-6','addr','show').decode();check('fdfe:dcba:9876::1' in tun,core+' VPN enables IPv6 despite legacy false preference')
  screenshot('nodes-'+core);op('stop');wait(lambda:not status()['running'])
  tap('设置');check('IPv6' not in labels(),'IPv6 switch is absent for '+core)
except Exception as e:
 failure=str(e);screenshot('failure');raise
finally:
 adb('shell','am','force-stop','com.wbox.app')
 for server in [source,*proxies]:server.shutdown();server.server_close()
 (ROOT/'docs/verification/0.6.11-android-runtime.json').write_text(json.dumps({'passed':failure is None,'failure':failure,'checks':checks},indent=2),encoding='utf-8')
