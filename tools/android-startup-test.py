"""Slow remote-rule startup regression on the project-owned emulator only."""
import argparse, http.server, json, pathlib, subprocess, threading, time

ROOT = pathlib.Path(__file__).resolve().parent.parent
ADB = 'C:/Users/hw/AppData/Local/Android/Sdk/platform-tools/adb.exe'
parser = argparse.ArgumentParser()
parser.add_argument('--baseline', action='store_true')
args = parser.parse_args()
results = {'baseline': args.baseline, 'checks': []}

def adb(*argv, data=None, timeout=30):
    p = subprocess.run([ADB, '-s', 'emulator-5556', *argv], input=data,
                       stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=timeout)
    if p.returncode:
        raise RuntimeError(p.stderr.decode(errors='replace') + p.stdout.decode(errors='replace'))
    return p.stdout

def probe(op):
    adb('shell', 'am', 'broadcast', '-n', 'com.wbox.app/.RuntimeProbeReceiver', '--es', 'operation', op)

def state():
    probe('status')
    time.sleep(.15)
    return json.loads(adb('shell', 'run-as', 'com.wbox.app', 'cat', 'files/probe-state.json'))

def wait(predicate, timeout=40):
    end = time.monotonic() + timeout
    while time.monotonic() < end:
        value = state()
        if predicate(value):
            return value
        time.sleep(.25)
    raise AssertionError(value)

requests = []
class Rules(http.server.BaseHTTPRequestHandler):
    def do_GET(self):
        requests.append(self.path)
        time.sleep(12 if self.path == '/slow.json' else 40)
        body = b'{"version":3,"rules":[{"domain_suffix":["example.test"]}]}'
        try:
            self.send_response(200)
            self.send_header('Content-Length', str(len(body)))
            self.end_headers()
            self.wfile.write(body)
        except (BrokenPipeError, ConnectionResetError, ConnectionAbortedError):
            pass
    def log_message(self, *args):
        pass

server = http.server.ThreadingHTTPServer(('127.0.0.1', 0), Rules)
threading.Thread(target=server.serve_forever, daemon=True).start()
port = server.server_port

def seed(path, invalid=False):
    adb('shell', 'am', 'force-stop', 'com.wbox.app')
    config = {'log': {'level': 'info'}, 'outbounds': [{'type': 'direct', 'tag': 'DIRECT'}],
              'route': {'final': 'DIRECT', 'rule_set': [{'type': 'remote', 'tag': 'slow-rule',
                        'format': 'source', 'url': f'http://127.0.0.1:{port}/{path}.json',
                        'download_detour': 'DIRECT'}],
                        'rules': [{'rule_set': 'slow-rule', 'outbound': 'DIRECT'}]}}
    if invalid:
        config['route']['final'] = 'missing-outbound'
    content = json.dumps(config)
    profile_id = 'startup-' + str(time.time_ns())
    prefs = {'mode': 'config', 'engine': 'sing-box', 'theme': 'dark',
             'config_sing-box': content, 'active_config': profile_id,
             'configs': [{'id': profile_id, 'name': 'Startup regression', 'core': 'sing-box', 'content': content}]}
    adb('shell', 'run-as', 'com.wbox.app', 'mkdir', '-p', 'files')
    adb('shell', 'run-as', 'com.wbox.app', 'sh', '-c', "'cat > files/profiles.json'", data=json.dumps(prefs).encode())
    adb('shell', 'am', 'start', '-n', 'com.wbox.app/.MainActivity')
    time.sleep(.5)

try:
    adb('wait-for-device')
    adb('install', '-r', str(ROOT/'android/app/build/outputs/apk/debug/app-debug.apk'), timeout=120)
    adb('shell', 'pm', 'grant', 'com.wbox.app', 'android.permission.POST_NOTIFICATIONS')
    adb('shell', 'appops', 'set', 'com.wbox.app', 'ACTIVATE_VPN', 'allow')
    adb('reverse', f'tcp:{port}', f'tcp:{port}')
    seed('slow')
    began = time.monotonic()
    probe('connect')
    finished = wait(lambda s: s['running'] or bool(s['error']))
    elapsed = time.monotonic() - began
    results['slow'] = {'elapsed': elapsed, 'state': finished, 'requests': list(requests)}
    assert '/slow.json' in requests, 'Core did not request the slow rule set'
    if args.baseline:
        assert not finished['running'] and '超时' in finished['error'] and elapsed < 12, results
        results['checks'].append('Old APK killed slow startup before rule response')
    else:
        assert finished['running'] and elapsed >= 12 and not finished['error'], results
        assert any('permission denied' in x for x in finished['logs']), 'Expected nonfatal package warning'
        results['checks'].append('Slow rule download succeeds despite packages.xml warning')
        probe('stop')
        wait(lambda s: not s['running'] and not s['starting'])
        requests.clear()
        began = time.monotonic()
        probe('connect')
        cached = wait(lambda s: s['running'] or bool(s['error']))
        results['cached'] = {'elapsed': time.monotonic()-began, 'requests': list(requests), 'state': cached}
        assert cached['running'] and not requests, results['cached']
        results['checks'].append('Reconnect reuses persistent rule cache')
        probe('stop')
        wait(lambda s: not s['running'] and not s['starting'])
        seed('cancel')
        probe('connect')
        wait(lambda s: s['starting'])
        time.sleep(1)
        began = time.monotonic()
        probe('stop')
        cancelled = wait(lambda s: not s['running'] and not s['starting'] and s['status']=='未连接', timeout=8)
        results['cancelled'] = {'elapsed': time.monotonic()-began, 'state': cancelled}
        time.sleep(2)
        assert not state()['running'] and not cancelled['error'], results['cancelled']
        results['checks'].append('Stop during initialization cancels without reporting failure')
        seed('invalid', invalid=True)
        began = time.monotonic()
        probe('connect')
        failed = wait(lambda s: bool(s['error']), timeout=8)
        results['invalid'] = {'elapsed': time.monotonic()-began, 'state': failed}
        assert not failed['running'] and '超时' not in failed['error'], results['invalid']
        assert any('missing-outbound' in x for x in failed['logs']), results['invalid']
        results['checks'].append('Actual core error exits promptly with original diagnostic')
finally:
    try:
        probe('stop')
        adb('reverse', '--remove', f'tcp:{port}')
    finally:
        server.shutdown()
        server.server_close()
        suffix = 'baseline' if args.baseline else 'fixed'
        (ROOT/f'docs/verification/0.6.2-android-startup-{suffix}.json').write_text(json.dumps(results,ensure_ascii=False,indent=2),encoding='utf-8')
print(json.dumps({'checks': results['checks']}, ensure_ascii=True))
