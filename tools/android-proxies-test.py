"""Exercise permanent proxy tabs and offline core tests on Wbox_API35 only."""
import http.server
import json
import pathlib
import re
import subprocess
import threading
import time
import xml.etree.ElementTree as ET

ROOT = pathlib.Path(__file__).resolve().parent.parent
ADB = 'C:/Users/hw/AppData/Local/Android/Sdk/platform-tools/adb.exe'
checks = []
slow = False

def adb(*args, data=None, timeout=30):
    result = subprocess.run([ADB, '-s', 'emulator-5556', *args], input=data,
                            capture_output=True, timeout=timeout)
    if result.returncode:
        raise RuntimeError(result.stderr.decode(errors='replace'))
    return result.stdout

def tree():
    adb('shell', 'uiautomator', 'dump', '/sdcard/wbox-proxies-067.xml')
    return ET.fromstring(adb('shell', 'cat', '/sdcard/wbox-proxies-067.xml'))

def text_values():
    return [n.get('text', '') for n in tree().iter('node')]

def tap(label, attr='text'):
    for _ in range(8):
        nodes = [n for n in tree().iter('node') if n.get(attr) == label]
        if nodes:
            x1, y1, x2, y2 = map(int, re.findall(r'\d+', nodes[0].get('bounds')))
            adb('shell', 'input', 'tap', str((x1+x2)//2), str((y1+y2)//2))
            return ((x1+x2)//2, (y1+y2)//2)
        time.sleep(.25)
    raise AssertionError('Missing UI control: ' + label)

def wait(predicate, timeout=35):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        if predicate():
            return
        time.sleep(.2)
    raise AssertionError('Emulator did not reach expected proxy state')

def state():
    adb('shell', 'am', 'broadcast', '-n', 'com.wbox.app/.RuntimeProbeReceiver', '--es', 'operation', 'status')
    time.sleep(.1)
    return json.loads(adb('shell', 'run-as', 'com.wbox.app', 'cat', 'files/probe-state.json'))

def seed(engine, config, url):
    adb('shell', 'am', 'force-stop', 'com.wbox.app')
    content = json.dumps(config, ensure_ascii=False)
    prefs = {'mode': 'config', 'engine': engine, 'theme': 'dark', 'test_url': url,
             'active_config': 'proxy-regression', 'config_'+engine: content,
             'configs': [{'id': 'proxy-regression', 'name': 'Proxy regression', 'core': engine, 'content': content}]}
    adb('shell', 'run-as', 'com.wbox.app', 'mkdir', '-p', 'files')
    adb('shell', 'run-as', 'com.wbox.app', 'sh', '-c', "'cat > files/profiles.json'", data=json.dumps(prefs).encode())
    adb('shell', 'am', 'start', '-n', 'com.wbox.app/.MainActivity')
    tap('代理')

class Server(http.server.BaseHTTPRequestHandler):
    def do_GET(self):
        time.sleep(12 if slow else .08)
        try:
            self.send_response(204)
            self.end_headers()
        except OSError:
            pass
    def log_message(self, *args):
        pass

server = http.server.ThreadingHTTPServer(('0.0.0.0', 0), Server)
threading.Thread(target=server.serve_forever, daemon=True).start()
try:
    config = {'proxies': [{'name': '失败节点', 'type': 'http', 'server': '127.0.0.1', 'port': 9}],
              'proxy-groups': [{'name': 'Z 外层', 'type': 'select', 'proxies': ['A 内层', '失败节点']},
                               {'name': 'A 内层', 'type': 'select', 'proxies': ['DIRECT']}],
              'tun': {'enable': True}, 'dns': {'enable': True, 'listen': '127.0.0.1:53'}, 'rules': ['MATCH,DIRECT']}
    seed('mihomo', config, f'http://10.0.2.2:{server.server_port}/')
    labels = text_values()
    assert labels.index('Z 外层') < labels.index('A 内层')
    assert not state()['running']
    checks.append('Proxy tabs exist while disconnected and retain configuration order')
    tap('延迟测试', 'content-desc')
    wait(lambda: 'Timeout' in text_values())
    values = text_values()
    assert any(re.fullmatch(r'[1-9]\d* ms', value) for value in values), values
    assert not state()['running']
    wait(lambda: not adb('shell', 'run-as', 'com.wbox.app', 'ls', '-A', 'cache/latency').strip())
    checks.append('Mihomo resolves a nested group to measured HTTP delay and shows Timeout for failure without VPN')
    (ROOT/'docs/previews/0.6.7-android-proxies.png').write_bytes(adb('exec-out', 'screencap', '-p'))
    slow = True
    point = tap('延迟测试', 'content-desc')
    time.sleep(.25)
    adb('shell', 'input', 'tap', str(point[0]), str(point[1]))
    wait(lambda: not any(n.get('content-desc')=='取消测试' for n in tree().iter('node')))
    wait(lambda: not adb('shell', 'run-as', 'com.wbox.app', 'ls', '-A', 'cache/latency').strip())
    checks.append('Cancel releases the temporary core and files')
    sing = {'outbounds': [{'type': 'selector', 'tag': 'Z 外层', 'outbounds': ['A 内层', '失败节点']},
                          {'type': 'selector', 'tag': 'A 内层', 'outbounds': ['失败节点']},
                          {'type': 'http', 'tag': '失败节点', 'server': '127.0.0.1', 'server_port': 9}]}
    seed('sing-box', sing, 'https://www.apple.com/library/test/success.html')
    tap('延迟测试', 'content-desc')
    wait(lambda: 'Timeout' in text_values())
    wait(lambda: not adb('shell', 'run-as', 'com.wbox.app', 'ls', '-A', 'cache/latency').strip())
    assert not state()['running']
    checks.append('Sing-box nested-node failures display Timeout and finish without VPN or leftover files')
finally:
    adb('shell', 'am', 'force-stop', 'com.wbox.app')
    server.shutdown()
    server.server_close()
    (ROOT/'docs/verification/0.6.7-android-proxies.json').write_text(json.dumps({'checks': checks}, indent=2), encoding='utf-8')
print(json.dumps({'checks': checks}, indent=2))
