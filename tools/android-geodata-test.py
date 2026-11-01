"""Offline GEOIP/GEOSITE startup on the project-owned Android emulator."""
import json, pathlib, subprocess, time

ROOT = pathlib.Path(__file__).resolve().parent.parent
ADB = 'C:/Users/hw/AppData/Local/Android/Sdk/platform-tools/adb.exe'
checks = []
timings = {}

def adb(*args, data=None, timeout=30):
    result = subprocess.run([ADB, '-s', 'emulator-5556', *args], input=data,
                            stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=timeout)
    if result.returncode:
        raise RuntimeError(result.stderr.decode(errors='replace'))
    return result.stdout

def probe(op):
    adb('shell', 'am', 'broadcast', '-n', 'com.wbox.app/.RuntimeProbeReceiver', '--es', 'operation', op)

def state():
    probe('status')
    time.sleep(.15)
    return json.loads(adb('shell', 'run-as', 'com.wbox.app', 'cat', 'files/probe-state.json'))

def wait(predicate, timeout=35):
    end = time.monotonic() + timeout
    while time.monotonic() < end:
        value = state()
        if predicate(value):
            return value
        time.sleep(.25)
    raise AssertionError('Emulator state did not settle: ' + value['status'])

def seed(content, profile='offline-geodata'):
    adb('shell', 'am', 'force-stop', 'com.wbox.app')
    prefs = {'mode': 'config', 'engine': 'mihomo', 'theme': 'dark', 'config_mihomo': content,
             'active_config': profile, 'configs': [{'id': profile, 'name': 'Offline startup test',
                 'core': 'mihomo', 'content': content}]}
    adb('shell', 'run-as', 'com.wbox.app', 'mkdir', '-p', 'files')
    adb('shell', 'run-as', 'com.wbox.app', 'sh', '-c', "'cat > files/profiles.json'", data=json.dumps(prefs).encode())
    adb('shell', 'am', 'start', '-n', 'com.wbox.app/.MainActivity')
    time.sleep(.5)

def start(label):
    began = time.monotonic()
    probe('connect')
    value = wait(lambda s: s['running'] or bool(s['error']))
    timings[label] = round(time.monotonic() - began, 2)
    assert value['running'] and not value['error'], label + ': ' + value['error']
    assert not any('start download' in line or 'invalid, remove' in line for line in value['logs']), label
    probe('stop')
    wait(lambda s: not s['running'] and not s['starting'])
    checks.append(label)

wifi = adb('shell', 'settings', 'get', 'global', 'wifi_on').strip()
mobile = adb('shell', 'settings', 'get', 'global', 'mobile_data').strip()
try:
    adb('install', '-r', str(ROOT / 'android/app/build/outputs/apk/debug/app-debug.apk'), timeout=120)
    adb('shell', 'pm', 'grant', 'com.wbox.app', 'android.permission.POST_NOTIFICATIONS')
    adb('shell', 'appops', 'set', 'com.wbox.app', 'ACTIVATE_VPN', 'allow')
    adb('shell', 'svc', 'wifi', 'disable')
    adb('shell', 'svc', 'data', 'disable')
    assert adb('shell', 'settings', 'get', 'global', 'wifi_on').strip() == b'0'
    assert adb('shell', 'settings', 'get', 'global', 'mobile_data').strip() == b'0'
    content = "proxies: []\ngeodata-mode: false\nrules: ['GEOIP,CN,DIRECT', 'GEOSITE,gfw,DIRECT', 'MATCH,DIRECT']\n"
    seed(content)
    start('MMDB and GEOSITE start without Wi-Fi or mobile data')
    target = 'files/runtime/mihomo/offline-geodata/geoip.metadb'
    initial_hash = adb('shell', 'run-as', 'com.wbox.app', 'sha256sum', target).split()[0].decode()
    expected = json.loads((ROOT/'resources/geodata/manifest.json').read_text())[0]['sha256']
    assert initial_hash == expected
    adb('shell', 'run-as', 'com.wbox.app', 'sh', '-c', "'echo partial > " + target + "'")
    seed(content)
    start('Truncated MMDB is repaired offline before core startup')
    assert adb('shell', 'run-as', 'com.wbox.app', 'sha256sum', target).split()[0].decode() == expected
    seed(content.replace('geodata-mode: false', 'geodata-mode: true'))
    start('DAT GEOIP mode also starts offline')
finally:
    probe('stop')
    adb('shell', 'svc', 'wifi', 'enable' if wifi == b'1' else 'disable')
    adb('shell', 'svc', 'data', 'enable' if mobile == b'1' else 'disable')
    adb('shell', 'am', 'force-stop', 'com.wbox.app')

output = ROOT/'docs/verification/0.6.3-android-geodata.json'
output.write_text(json.dumps({'checks': checks, 'seconds': timings, 'emulator': 'Android 15 x86_64, minified debug'}, indent=2))
print(json.dumps({'checks': checks, 'seconds': timings}, indent=2))
