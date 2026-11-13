"""0.7.1 runtime transactions against the isolated API35 emulator and synthetic proxies."""
import argparse,copy,http.server,json,pathlib,re,select,socket,subprocess,threading,time,uuid,xml.etree.ElementTree as ET
ROOT=pathlib.Path(__file__).resolve().parent.parent
ADB='C:/Users/hw/AppData/Local/Android/Sdk/platform-tools/adb.exe'
parser=argparse.ArgumentParser();parser.add_argument('--engine',choices=['mihomo','sing-box']);args=parser.parse_args()
checks=[];failure=None;original=None;mobile_data=None;forwards=[];apply_broadcasts=[]
def adb(*args,data=None,timeout=65):
 r=subprocess.run([ADB,'-s','emulator-5556',*args],input=data,capture_output=True,timeout=timeout)
 if r.returncode:raise RuntimeError(r.stderr.decode(errors='replace'))
 return r.stdout
def op(action):adb('shell','am','broadcast','-n','com.wbox.app/.RuntimeProbeReceiver','--es','operation',action)
def status():
 op('status');time.sleep(.12);return json.loads(adb('shell','run-as','com.wbox.app','cat','files/probe-state.json'))
def prefs():return json.loads(adb('shell','run-as','com.wbox.app','cat','files/profiles.json'))
def write(name,value):adb('shell','run-as','com.wbox.app','sh','-c',"'cat > files/"+name+"'",data=json.dumps(value).encode())
def wait(fn,timeout=65):
 end=time.monotonic()+timeout
 while time.monotonic()<end:
  if fn():return
  time.sleep(.2)
 raise AssertionError('Timed out')
def nodes():
 adb('shell','uiautomator','dump','/sdcard/wbox-071-runtime.xml');return list(ET.fromstring(adb('shell','cat','/sdcard/wbox-071-runtime.xml')).iter('node'))
def tap(label,attr='text',double=False):
 found=[n for n in nodes() if n.get(attr)==label];assert found,'Missing '+label
 n=found[-1];a,b,c,d=map(int,re.findall(r'\d+',n.get('bounds')));x,y=str((a+c)//2),str((b+d)//2)
 adb('shell','input','tap',x,y)
 if double:adb('shell','input','tap',x,y)
def start(p):
 adb('shell','am','force-stop','com.wbox.app');write('profiles.json',p);adb('shell','am','start','-W','-n','com.wbox.app/.MainActivity');wait(lambda:any(n.get('text')=='概览' for n in nodes()))
def check(ok,label):assert ok,label;checks.append(label);print('PASS',label,flush=True)
def begin_apply(next):
 ident=str(uuid.uuid4());write('probe-change.json',{'id':ident,'source':prefs(),'next':next})
 # am broadcast waits for goAsync() to finish, so let the transaction run while
 # the test sends a real UI stop instead of blocking until its 60-second limit.
 apply_broadcasts.append(subprocess.Popen([ADB,'-s','emulator-5556','shell','am','broadcast','-n','com.wbox.app/.RuntimeProbeReceiver','--es','operation','apply-change'],stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL))
 return ident
def finish_apply(ident):
 def ready():
  try:return json.loads(adb('shell','run-as','com.wbox.app','cat','files/probe-change-result.json')).get('id')==ident
  except Exception:return False
 wait(ready);return json.loads(adb('shell','run-as','com.wbox.app','cat','files/probe-change-result.json'))
def apply(next):return finish_apply(begin_apply(next))
proxy_events=[]
class Origin(http.server.BaseHTTPRequestHandler):
 protocol_version='HTTP/1.1'
 def answer(self):
  body=f'WBOX-{self.server.index}'.encode();self.send_response(200);self.send_header('Content-Length',str(len(body)));self.end_headers()
  if self.command!='HEAD':self.wfile.write(body)
  proxy_events.append({'index':self.server.index,'method':self.command,'path':self.path,'response':200})
 do_GET=answer;do_HEAD=answer
 def log_message(self,*args):pass
origins=[http.server.ThreadingHTTPServer(('127.0.0.1',0),Origin) for _ in range(2)]
for i,p in enumerate(origins):p.index=i;threading.Thread(target=p.serve_forever,daemon=True).start()
class Proxy(Origin):
 rbufsize=0
 def do_CONNECT(self):
  if 'latency.example' in self.path:time.sleep(2)
  self.close_connection=True
  try:
   with socket.create_connection(('127.0.0.1',origins[self.server.index].server_port),timeout=10) as upstream:
    self.send_response(200);self.end_headers()
    proxy_events.append({'index':self.server.index,'method':'CONNECT','path':self.path,'response':200})
    # CONNECT is a byte tunnel. Re-entering BaseHTTPRequestHandler after the
    # handshake leaves HTTP connection state/close handling on the proxy leg.
    # A separate origin also handles concurrent Android network probes normally.
    peers={self.connection:upstream,upstream:self.connection};began=time.monotonic();counts={'upload':0,'download':0}
    while True:
     ready,_,_=select.select(list(peers),[],[],10)
     if not ready:return
     for source in ready:
      direction='upload' if source==self.connection else 'download';data=source.recv(65536)
      if not data:
       if self.path.startswith('93.184.'):
        proxy_events.append({'index':self.server.index,'method':'TUNNEL_EOF','direction':direction,'bytes':counts.copy(),'milliseconds':round((time.monotonic()-began)*1000)})
       return
      counts[direction]+=len(data)
      peers[source].sendall(data)
  except (OSError,ValueError) as error:proxy_events.append({'index':self.server.index,'method':'CONNECT','path':self.path,'closed':type(error).__name__})
proxies=[http.server.ThreadingHTTPServer(('0.0.0.0',0),Proxy) for _ in range(2)]
for i,p in enumerate(proxies):p.index=i;threading.Thread(target=p.serve_forever,daemon=True).start()
rule_requested=threading.Event();release_rules=threading.Event()
class SlowRules(http.server.BaseHTTPRequestHandler):
 def do_GET(self):
  rule_requested.set();release_rules.wait(120)
  body=b'payload:\n - DOMAIN,example.invalid\n' if self.path.endswith('.yaml') else b'{"version":3,"rules":[{"domain":["example.invalid"]}]}'
  try:self.send_response(200);self.send_header('Content-Length',str(len(body)));self.end_headers();self.wfile.write(body)
  except (BrokenPipeError,ConnectionResetError):pass
 def log_message(self,*args):pass
slow_rules=http.server.ThreadingHTTPServer(('0.0.0.0',0),SlowRules)
threading.Thread(target=slow_rules.serve_forever,daemon=True).start()
def config(core,index):
 node={'name':'Active','type':'http','server':'10.0.2.2','port':proxies[index].server_port}
 return json.dumps({'proxies':[node],'proxy-groups':[{'name':'Main','type':'select','proxies':['Active']}],'rules':['MATCH,Main']} if core=='mihomo' else {'outbounds':[{'type':'http','tag':'Active','server':'10.0.2.2','server_port':proxies[index].server_port}],'route':{'final':'Active'}})
def forward(index):
 port=int(adb('forward','tcp:0','tcp:10808').decode().strip());forwards.append(port)
 try:
  with socket.create_connection(('127.0.0.1',port),timeout=10) as client:
   client.sendall(b'GET http://93.184.216.34/forward HTTP/1.1\r\nHost: 93.184.216.34\r\nConnection: close\r\n\r\n');body=b''
   while True:
    part=client.recv(8192)
    if not part:break
    body+=part
  check(f'WBOX-{index}'.encode() in body,'Actual VPN core forwards through proxy '+str(index)+'; response='+repr(body[:300]))
 finally:adb('forward','--remove',f'tcp:{port}');forwards.remove(port)
try:
 try:original=adb('shell','run-as','com.wbox.app','cat','files/profiles.json')
 except Exception:pass
 # Synthetic proxy replies cannot satisfy Android's real connectivity probes.
 # Keep this isolated emulator on Wi-Fi so those replies cannot trigger a
 # Wi-Fi/cellular handover and reset a forwarding assertion's live TCP stream.
 mobile_data=adb('shell','settings','get','global','mobile_data').decode().strip()
 if mobile_data=='1':adb('shell','svc','data','disable')
 adb('shell','appops','set','com.wbox.app','ACTIVATE_VPN','allow')
 for core in [args.engine] if args.engine else ['mihomo','sing-box']:
  p={'mode':'config','theme':'light','engine':core,'node_engine':core,'configs':[{'id':'live','name':'Live','core':core,'content':config(core,0),'url':'','updated':1},{'id':'other','name':'Other','core':core,'content':config(core,0),'url':'','updated':1}],'active_config':'live','config_'+core:config(core,0)}
  start(p);op('connect');wait(lambda:status()['running']);forward(0)
  before=status()['startedAt'];n=prefs();n['configs'][0]['content']=config(core,1);n['config_'+core]=config(core,1)
  result=apply(n);check(result['ok'] and status()['startedAt']>before,core+' active config edits immediately reload');forward(1)
  if core=='sing-box':
   for index in [0,1,0,1]:
    n=prefs();n['configs'][0]['content']=config(core,index);n['config_'+core]=config(core,index)
    check(apply(n)['ok'],'sing-box repeated live edit switches to proxy '+str(index));forward(index)
  saved=prefs();n=copy.deepcopy(saved);n['configs'][0]['content']=json.dumps({'proxies':[{'name':'Broken','type':'unsupported-wbox'}],'proxy-groups':[{'name':'Main','type':'select','proxies':['Broken']}],'rules':['MATCH,Main']} if core=='mihomo' else {'outbounds':[{'type':'unsupported-wbox','tag':'Broken'}]});n['config_'+core]=n['configs'][0]['content']
  result=apply(n);check(not result['ok'] and prefs()['configs'][0]['content']==saved['configs'][0]['content'] and status()['running'],core+' failed live config restores saved data and connection');forward(1)
  n=prefs();n['active_config']='other';n['config_'+core]=config(core,0);check(apply(n)['ok'],core+' profile switching applies while connected');forward(0)
  before=status()['startedAt'];n=prefs();n['app_mode']='exclude';n['apps']=['com.android.settings'];check(apply(n)['ok'] and status()['startedAt']>before,core+' per-app VPN changes immediately rebuild routing');forward(0)
  saved=prefs();before_logs=status()['logs'];n=copy.deepcopy(saved);slow=json.loads(config(core,1));rule_requested.clear();release_rules.clear()
  address=f'http://10.0.2.2:{slow_rules.server_port}/{uuid.uuid4()}'
  if core=='mihomo':
   slow['rule-providers']={'Slow':{'type':'http','behavior':'classical','url':address+'.yaml','interval':86400,'proxy':'DIRECT'}}
   slow['rules']=['RULE-SET,Slow,DIRECT','MATCH,Main']
  else:
   slow['outbounds'].append({'type':'direct','tag':'DIRECT'})
   slow['route']['rule_set']=[{'type':'remote','tag':'Slow','format':'source','url':address+'.json','update_interval':'24h','download_detour':'DIRECT'}]
   slow['route']['rules']=[{'rule_set':['Slow'],'action':'route','outbound':'Active'}]
  n['configs'][0 if n['active_config']=='live' else 1]['content']=json.dumps(slow);n['config_'+core]=json.dumps(slow)
  ident=begin_apply(n);wait(lambda:rule_requested.is_set() and status()['reloading'] and status()['starting'])
  began=time.monotonic();tap('取消连接','content-desc');result=finish_apply(ident)
  wait(lambda:not status()['running'] and not status()['starting'] and not status()['reloading'] and not status()['stopping'])
  check(time.monotonic()-began<8 and not result['ok'] and '取消' in result.get('error',''),core+' stopping a pending live reload cancels promptly')
  check(prefs()['configs']==saved['configs'],core+' canceled reload restores the original saved profiles')
  after_logs=status()['logs'];fresh=after_logs[len(before_logs):] if len(after_logs)>=len(before_logs) else after_logs
  check(not any('已连接（' in line for line in fresh),core+' canceled reload never reconnects during rollback')
  release_rules.set();op('connect');wait(lambda:status()['running']);forward(0);check(True,core+' explicit start works after canceled reload')
  op('stop');wait(lambda:not status()['running'])
  p={'mode':'nodes','theme':'light','engine':core,'node_engine':core,'configs':[],'selected':'Node0','test_url':'https://latency.example/check','nodes':[f'http://10.0.2.2:{proxy.server_port}#Node{i}' for i,proxy in enumerate(proxies)]}
  start(p);tap('节点');tap('测试节点 Node1 的延迟','content-desc');time.sleep(.25);check(not status()['running'],core+' offline latency does not enable VPN');tap('连接代理','content-desc');wait(lambda:status()['running']);forward(0);check(True,core+' start remains available during latency')
  tap('测试节点 Node1 的延迟','content-desc');tap('Node1',double=True);wait(lambda:prefs()['selected']=='Node1' and status()['running']);forward(1);check(True,core+' double-tap switches the live node during latency')
  op('stop');wait(lambda:not status()['running'])
except Exception as error:
 failure=str(error)
 try:print('FAILURE STATE',json.dumps(status(),ensure_ascii=False),flush=True)
 except Exception:pass
 print('PROXY EVENTS',json.dumps(proxy_events[-100:]),flush=True)
 import traceback;traceback.print_exc()
finally:
 adb('shell','am','force-stop','com.wbox.app')
 for port in forwards:adb('forward','--remove',f'tcp:{port}')
 if original is not None:adb('shell','run-as','com.wbox.app','sh','-c',"'cat > files/profiles.json'",data=original)
 release_rules.set();slow_rules.shutdown();slow_rules.server_close()
 for process in apply_broadcasts:
  try:process.wait(timeout=5)
  except subprocess.TimeoutExpired:process.terminate();process.wait(timeout=5)
 for proxy in proxies+origins:proxy.shutdown();proxy.server_close()
 if mobile_data=='1':adb('shell','svc','data','enable')
 mobile_restored=mobile_data is None or adb('shell','settings','get','global','mobile_data').decode().strip()==mobile_data
 if not mobile_restored:failure=failure or 'Emulator mobile data setting was not restored'
 report='0.7.1-android-runtime'+('-'+args.engine if args.engine else '')+'.json'
 (ROOT/'docs/verification'/report).write_text(json.dumps({'passed':failure is None,'failure':failure,'checks':checks,'emulator':'API35 x86_64','proxyFixture':'HTTP CONNECT byte tunnels to separate local HTTP origins','forwardingRetries':0,'networkIsolation':{'mode':'Wi-Fi only for synthetic-proxy tests; production network logic unchanged','mobileDataRestored':mobile_restored,'diagnosis':'Synthetic replies made Android connectivity probes reject Wi-Fi and switch to cellular, resetting an in-flight TCP request. This was isolated in the emulator, without adding request retries.','evidence':[{'time':'2026-11-13T04:38:36.663Z','source':'ConnectivityService','event':'Wi-Fi network 100 lost VALIDATED; default network switched to cellular 101 at 04:38:36.664Z.'},{'time':'2026-11-13T04:38:37Z','source':'sing-box','event':'Request 3926590219 failed after 883ms: raw read: connection reset by peer; GET /forward EOF. The core remained running.'},{'time':'2026-11-13T04:38:38.756Z','source':'ConnectivityService','event':'Wi-Fi network 100 regained VALIDATED and became the default again.'}]}},indent=2),encoding='utf-8')
if failure:raise SystemExit(1)
