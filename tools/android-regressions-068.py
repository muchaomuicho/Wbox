"""UI, dual-core selector and background-job regression on Wbox_API35 only."""
import http.server,json,pathlib,re,subprocess,threading,time,xml.etree.ElementTree as ET
ROOT=pathlib.Path(__file__).resolve().parent.parent
ADB='C:/Users/hw/AppData/Local/Android/Sdk/platform-tools/adb.exe'
checks=[]
def adb(*args,data=None,timeout=30):
    r=subprocess.run([ADB,'-s','emulator-5556',*args],input=data,capture_output=True,timeout=timeout)
    if r.returncode:raise RuntimeError(r.stderr.decode(errors='replace'))
    return r.stdout
def tree():
    adb('shell','uiautomator','dump','/sdcard/wbox-regressions-068.xml')
    return ET.fromstring(adb('shell','cat','/sdcard/wbox-regressions-068.xml'))
def tap(text,attribute='text'):
    for _ in range(5):
        for n in reversed(list(tree().iter('node'))):
            if n.get(attribute)==text:
                a,b,c,d=map(int,re.findall(r'\d+',n.get('bounds')))
                adb('shell','input','tap',str((a+c)//2),str((b+d)//2));return
        time.sleep(.2)
    raise AssertionError('Missing control '+text)
def wait(fn,timeout=35):
    end=time.monotonic()+timeout
    while time.monotonic()<end:
        if fn():return
        time.sleep(.25)
    raise AssertionError('Timed out')
def prefs():return json.loads(adb('shell','run-as','com.wbox.app','cat','files/profiles.json'))
def op(action):adb('shell','am','broadcast','-n','com.wbox.app/.RuntimeProbeReceiver','--es','operation',action)
def status():
    op('status');time.sleep(.15)
    return json.loads(adb('shell','run-as','com.wbox.app','cat','files/probe-state.json'))
def check(ok,label):
    assert ok,label
    checks.append(label);print('PASS',label,flush=True)
def seed(engine,config,extra=None):
    adb('shell','am','force-stop','com.wbox.app')
    content=json.dumps(config)
    p={'mode':'config','engine':engine,'theme':'light','active_config':'regression','config_'+engine:content,
       'configs':[{'id':'regression','name':'Regression','core':engine,'content':content}]}
    p.update(extra or {})
    adb('shell','run-as','com.wbox.app','mkdir','-p','files')
    adb('shell','run-as','com.wbox.app','sh','-c',"'cat > files/profiles.json'",data=json.dumps(p).encode())
    adb('shell','am','start','-W','-n','com.wbox.app/.MainActivity')
    wait(lambda:any(n.get('text')=='概览' for n in tree().iter('node')))
    tap('代理')
    wait(lambda:any(n.get('text')=='Main' for n in tree().iter('node')))
class Server(http.server.BaseHTTPRequestHandler):
    def do_GET(self):
        self.send_response(200);self.send_header('Content-Type','application/json');self.end_headers();self.wfile.write(subscription.encode())
    def log_message(self,*args):pass
subscription=''
failure=None
server=http.server.ThreadingHTTPServer(('0.0.0.0',0),Server)
threading.Thread(target=server.serve_forever,daemon=True).start()
try:
    adb('shell','appops','set','com.wbox.app','ACTIVATE_VPN','allow')
    for engine in ['mihomo','sing-box']:
        if engine=='mihomo':
            config={'proxies':[{'name':n,'type':'http','server':'127.0.0.1','port':9} for n in ['A','B']],
                    'proxy-groups':[{'name':n,'type':'select','proxies':['A','B']} for n in ['Main','Media','AI']],'rules':['MATCH,Main'],'unified-delay':False}
        else:
            config={'outbounds':[{'type':'selector','tag':n,'outbounds':['A','B']} for n in ['Main','Media','AI']]+[{'type':'direct','tag':n} for n in ['A','B']]}
        seed(engine,config)
        tap('B');wait(lambda:prefs()['configs'][0].get('group_selections',{}).get('Main')=='B')
        check(not status()['running'],engine+' offline card selection is persisted without a VPN')
        tap('选择代理组','content-desc')
        check(any(n.get('text')=='策略组' for n in tree().iter('node')),'Group picker opens a bottom sheet')
        if engine=='mihomo':(ROOT/'docs/previews/0.6.8-android-group-picker.png').write_bytes(adb('exec-out','screencap','-p'))
        # The tab and sheet both contain Media; choose the last occurrence.
        matches=[n for n in tree().iter('node') if n.get('text')=='Media'];a,b,c,d=map(int,re.findall(r'\d+',matches[-1].get('bounds')));adb('shell','input','tap',str((a+c)//2),str((b+d)//2))
        check(not any(n.get('text')=='策略组' for n in tree().iter('node')),'Choosing a sheet chip closes the sheet')
        tap('Main');op('connect');wait(lambda:status()['running'])
        # Query the authenticated controller from inside the app in a debug-only probe.
        op('groups');time.sleep(.2);groups=json.loads(adb('shell','run-as','com.wbox.app','cat','files/probe-groups.json'))
        check(groups['Main']['now']=='B',engine+' startup restores the saved selector')
        tap('A');wait(lambda:prefs()['configs'][0]['group_selections']['Main']=='A')
        op('groups');time.sleep(.2);groups=json.loads(adb('shell','run-as','com.wbox.app','cat','files/probe-groups.json'))
        check(groups['Main']['now']=='A',engine+' card switches the running core')
        op('stop');wait(lambda:not status()['running'])
    subscription=json.dumps(config|{'log':{'level':'warn'}})
    url=f'http://10.0.2.2:{server.server_port}/subscription'
    content=json.dumps(config)
    # Keep startup catch-up from racing the explicit background-only assertion.
    seed('sing-box',config,{'subscription_auto_update':True,'subscription_update_minutes':15,'subscription_last_attempt':int(time.time()*1000),'configs':[{'id':'regression','name':'Remote','core':'sing-box','content':content,'url':url}]})
    adb('shell','input','keyevent','KEYCODE_HOME')
    hidden=prefs();hidden['subscription_last_attempt']=0
    check(not hidden.get('subscription_last_success',0),'Subscription has not refreshed before hiding the activity')
    adb('shell','run-as','com.wbox.app','sh','-c',"'cat > files/profiles.json'",data=json.dumps(hidden).encode())
    adb('shell','cmd','jobscheduler','run','-f','com.wbox.app','2008')
    wait(lambda:prefs().get('subscription_last_success',0)>0)
    check(json.loads(prefs()['configs'][0]['content']).get('log',{}).get('level')=='warn','System background job updates remote configuration with the activity hidden')
    check(prefs()['configs'][0]['name']=='Remote','Background refresh preserves the configured name')
except Exception as error:
    failure=str(error)
    try:
        (ROOT/'.tools/android-regressions-068-failure.xml').write_bytes(ET.tostring(tree(),encoding='utf-8'))
        (ROOT/'.tools/android-regressions-068-failure.png').write_bytes(adb('exec-out','screencap','-p'))
    except Exception as capture_error:
        print('Could not capture failure:',capture_error,flush=True)
    raise
finally:
    op('stop');adb('shell','am','force-stop','com.wbox.app');server.shutdown();server.server_close()
    (ROOT/'docs/verification/0.6.8-android-runtime.json').write_text(json.dumps({'passed':failure is None,'failure':failure,'checks':checks},indent=2))
