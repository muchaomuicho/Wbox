"""Actual Android form import while a VPN is running; no private subscription data."""
import http.server, json, pathlib, re, subprocess, threading, time, xml.etree.ElementTree as ET
ROOT=pathlib.Path(__file__).resolve().parent.parent
ADB='C:/Users/hw/AppData/Local/Android/Sdk/platform-tools/adb.exe'
def adb(*args,data=None):
    return subprocess.check_output([ADB,'-s','emulator-5556',*args],input=data,timeout=30)
def probe(action):adb('shell','am','broadcast','-n','com.wbox.app/.RuntimeProbeReceiver','--es','operation',action)
def stored():return json.loads(adb('shell','run-as','com.wbox.app','cat','files/profiles.json'))
def state():
    probe('status');time.sleep(.2)
    return json.loads(adb('shell','run-as','com.wbox.app','cat','files/probe-state.json'))
def tree():
    adb('shell','uiautomator','dump','/sdcard/wbox-import-ui.xml')
    return ET.fromstring(adb('shell','cat','/sdcard/wbox-import-ui.xml'))
def find(value,attr='text'):
    for _ in range(10):
        rows=[n for n in tree().iter('node') if n.get(attr)==value]
        if rows:return rows[-1]
        time.sleep(.5)
    raise AssertionError('Missing '+value)
def tap_node(node):
    x,y,r,b=map(int,re.findall(r'\d+',node.get('bounds')))
    adb('shell','input','tap',str((x+r)//2),str((y+b)//2));time.sleep(.3)
def tap(value,attr='text'):tap_node(find(value,attr))
def input_text(value):adb('shell','input','text',value)
agents=[]
prefix='proxies:\n  - name: "'
payload=(prefix+'x'*(1023-len(prefix))+'🇯🇵 Tokyo"\n    type: ss\n    server: example.test\n    port: 443\n    cipher: aes-128-gcm\n    password: synthetic\nrules: ["MATCH,DIRECT"]\n').encode()
class Handler(http.server.BaseHTTPRequestHandler):
    def do_GET(self):
        agents.append(self.headers.get('User-Agent',''))
        body=payload if self.path=='/config' else b'WBOX_OK'
        self.send_response(200);self.send_header('Content-Length',str(len(body)));self.end_headers();self.wfile.write(body)
    def log_message(self,*args):pass
server=http.server.ThreadingHTTPServer(('0.0.0.0',0),Handler)
threading.Thread(target=server.serve_forever,daemon=True).start()
port=server.server_port
checks=[]
try:
    probe('stop');time.sleep(.5);probe('seed');time.sleep(1)
    adb('shell','am','force-stop','com.wbox.app');adb('shell','am','start','-n','com.wbox.app/.MainActivity')
    adb('reverse',f'tcp:{port}',f'tcp:{port}')
    tap('连接代理','content-desc');find('断开代理连接','content-desc')
    before=stored();logs=state()['logs'];starts=sum('已连接（' in line for line in logs)
    tap('配置');tap('添加配置','content-desc')
    edits=[n for n in tree().iter('node') if n.get('class')=='android.widget.EditText']
    assert len(edits)==2
    tap_node(edits[0]);input_text('Added')
    # Opening the IME moves the bottom sheet; use fresh accessibility bounds.
    edits=[n for n in tree().iter('node') if n.get('class')=='android.widget.EditText']
    tap_node(edits[-1]);input_text(f'http://127.0.0.1:{port}/config')
    adb('shell','input','keyevent','4');time.sleep(.3);tap('添加');find('Added')
    after=stored();runtime=state()
    assert len(after['configs'])==2 and after['configs'][-1]['name']=='Added'
    assert after['active_config']==before['active_config'] and after['engine']==before['engine']
    assert runtime['running'] and sum('已连接（' in line for line in runtime['logs'])==starts
    assert 'clash.meta/1.19.33' in agents
    assert '🇯🇵' in after['configs'][-1]['content']
    checks.append('Remote YAML with boundary-split flag imports through actual Android form while connected')
    checks.append('Mihomo UA, active sing-box config, running session and Unicode content preserved')
    response=adb('shell','toybox','nc','-w','5','10.0.2.2',str(port),data=b'GET /ok HTTP/1.0\r\nHost: test\r\n\r\n')
    assert b'WBOX_OK' in response,repr(response)
    checks.append('VPN continues forwarding after adding a config')
    (ROOT/'docs/previews/0.6.2-android-live-import.png').write_bytes(adb('exec-out','screencap','-p'))
finally:
    probe('stop');adb('reverse','--remove',f'tcp:{port}');server.shutdown();server.server_close()
    (ROOT/'docs/verification/0.6.2-android-import.json').write_text(json.dumps({'checks':checks},indent=2),encoding='utf-8')
print(json.dumps({'checks':checks}))
