"""Verify 0.7.3 appearance and subscription updates on isolated emulator-5556.

Uses synthetic loopback HTTP sources. Restores app preferences and display
settings; keeps the emulator running. Does not contact real subscriptions.
"""
import argparse
import collections
import hashlib
import http.server
import io
import json
import pathlib
import re
import subprocess
import threading
import time
import traceback
import xml.etree.ElementTree as ET
from PIL import Image, ImageChops

ROOT = pathlib.Path(__file__).resolve().parent.parent
ADB = pathlib.Path('D:/DevTools/Android/Sdk/platform-tools/adb.exe')
APK = ROOT / 'android/app/build/outputs/apk/debug/app-debug.apk'
VERSION = '0.7.3'
parser = argparse.ArgumentParser()
parser.add_argument('--phase', choices=['all', 'appearance', 'updates', 'feedback'], default='all')
args = parser.parse_args()
checks, images, observations = [], [], {}
report_path = ROOT / f'docs/verification/{VERSION}-android-appearance.json'
reused_appearance = False
if args.phase in ['updates', 'feedback'] and report_path.exists():
    previous = json.loads(report_path.read_text(encoding='utf-8'))
    previous_checks = previous.get('checks', [])
    if ('Connections entry appears only after start and disappears after stop' in previous_checks
            and previous.get('observations', {}).get('apk_sha256') == hashlib.sha256(APK.read_bytes()).hexdigest()):
        excluded = ('Installs ',) if args.phase == 'feedback' else ('config ', 'nodes ', 'Installs ')
        checks = [label for label in previous_checks if not label.startswith(excluded)]
        observations = {key: value for key, value in previous.get('observations', {}).items() if key in checks}
        excluded_images = ['-failure'] if args.phase == 'feedback' else ['-config-menu', '-nodes-menu', '-updating', '-failure']
        images = [path for path in previous.get('screenshots', []) if not any(word in path for word in excluded_images)]
        reused_appearance = True
failure = None
plans = collections.defaultdict(collections.deque)
hits = collections.Counter()
gate_lock = threading.Lock()
gates = []


def adb(*values, data=None, timeout=40):
    result = subprocess.run([str(ADB), '-s', 'emulator-5556', *values], input=data,
                            capture_output=True, timeout=timeout)
    if result.returncode:
        raise RuntimeError(result.stderr.decode(errors='replace'))
    return result.stdout


def nodes():
    adb('shell', 'uiautomator', 'dump', '/sdcard/wbox-appearance.xml')
    return list(ET.fromstring(adb('shell', 'cat', '/sdcard/wbox-appearance.xml')).iter('node'))


def bounds(node):
    return tuple(map(int, re.findall(r'\d+', node.get('bounds', ''))))


def find(label, attr='text', tree=None):
    tree = nodes() if tree is None else tree
    matches = [n for n in tree if n.get(attr) == label]
    if not matches and attr == 'text':
        matches = [n for n in tree if label in n.get('text', '').splitlines()]
    if not matches:
        raise AssertionError('Missing control: ' + label)
    return matches[-1] if attr == 'text' else matches[0]


def tap_at(box):
    x1, y1, x2, y2 = box
    adb('shell', 'input', 'tap', str((x1 + x2) // 2), str((y1 + y2) // 2))


def tap(label, attr='text', tree=None):
    tap_at(bounds(find(label, attr, tree)))


def wait(predicate, label, timeout=25):
    until = time.monotonic() + timeout
    while time.monotonic() < until:
        if predicate():
            return
        time.sleep(.15)
    raise AssertionError('Timed out: ' + label)


def check(ok, label, detail=None):
    if not ok:
        raise AssertionError(label + (': ' + str(detail) if detail is not None else ''))
    checks.append(label)
    if detail is not None:
        observations[label] = detail
    print('PASS', label, flush=True)


def prefs():
    return json.loads(adb('shell', 'run-as', 'com.wbox.app', 'cat', 'files/profiles.json'))


def write_prefs(value):
    data = value if isinstance(value, bytes) else json.dumps(value).encode()
    adb('shell', 'run-as', 'com.wbox.app', 'sh', '-c', "'cat > files/profiles.json'", data=data)


def start(value):
    adb('shell', 'am', 'force-stop', 'com.wbox.app')
    write_prefs(value)
    adb('shell', 'am', 'start', '-W', '-n', 'com.wbox.app/.MainActivity')
    wait(lambda: any(n.get('text') == '概览' for n in nodes()), 'app launch')


def capture():
    return Image.open(io.BytesIO(adb('exec-out', 'screencap', '-p'))).convert('RGB')


def save_image(name, image=None):
    path = ROOT / f'docs/previews/{VERSION}-android-{name}.png'
    path.parent.mkdir(parents=True, exist_ok=True)
    (capture() if image is None else image).save(path)
    relative = path.relative_to(ROOT).as_posix()
    if relative not in images:
        images.append(relative)
    return path


def content(marker='original'):
    return json.dumps({'log': {'level': 'warn', 'timestamp': True},
                       'outbounds': [{'type': 'selector', 'tag': 'Proxy', 'outbounds': ['DIRECT']},
                                     {'type': 'direct', 'tag': 'DIRECT'}],
                       'route': {'final': 'Proxy'}, 'experimental': {}}, separators=(',', ':')) + '\n' * (len(marker) % 4)


class Gate:
    def __init__(self, status=200, body=None):
        self.status, self.body = status, body
        self.started, self.release = threading.Event(), threading.Event()
        self.timed_out = False
        gates.append(self)


def queue(path, status=200, body=None):
    gate = Gate(status, body)
    with gate_lock:
        plans[path].append(gate)
    return gate


class Source(http.server.BaseHTTPRequestHandler):
    def do_GET(self):
        with gate_lock:
            hits[self.path] += 1
            gate = plans[self.path].popleft() if plans[self.path] else None
        if gate:
            gate.started.set()
            if not gate.release.wait(25):
                gate.timed_out = True
        status = gate.status if gate else 200
        self.send_response(status)
        self.send_header('Subscription-Userinfo', f'upload=1048576; download=2147483648; total=322122547200; expire={int(time.time()) + 18 * 86400}')
        self.end_headers()
        default = ('http://10.0.2.2:19283#Tokyo' if self.path.startswith('/nodes') else content('downloaded'))
        body = gate.body if gate and gate.body is not None else default
        try:
            self.wfile.write(body.encode())
        except (BrokenPipeError, ConnectionResetError):
            pass

    def log_message(self, *values):
        pass


server = http.server.ThreadingHTTPServer(('0.0.0.0', 0), Source)
threading.Thread(target=server.serve_forever, daemon=True).start()
base = f'http://10.0.2.2:{server.server_port}'


def usage():
    return {'upload': 1048576, 'download': 2147483648, 'total': 322122547200,
            'expire': int(time.time()) + 18 * 86400}


def config_seed(accent='blue', theme='light'):
    now = int(time.time() * 1000)
    configs = []
    for key, name in [('orion', 'Orion · 出行'), ('atlas', 'Atlas · 主订阅')]:
        configs.append({'id': key, 'name': name, 'core': 'sing-box', 'content': content(),
                        'url': base + '/' + key, 'updated': now,
                        'subscription': {'name': name, 'auto_update': False, 'update_minutes': 1440,
                                         'last_success': now, 'usage': usage()}})
    return {'mode': 'config', 'engine': 'sing-box', 'theme': theme, 'accent': accent,
            'configs': configs, 'active_config': 'orion', 'config_sing-box': content()}


def node_seed():
    now = int(time.time() * 1000)
    addresses = [base + '/nodes-a', base + '/nodes-b']
    return {'mode': 'nodes', 'engine': 'mihomo', 'node_engine': 'mihomo', 'theme': 'light', 'accent': 'blue',
            'nodes': [{'link': f'http://10.0.2.2:{19283 + i}#' + name, 'source': address}
                      for i, (address, name) in enumerate(zip(addresses, ['Tokyo', 'London']))],
            'selected': 'Tokyo', 'configs': [], 'subscriptions': {address: [] for address in addresses},
            'subscription_settings': {address: {'name': name, 'auto_update': False, 'update_minutes': 1440,
                                               'last_success': now, 'usage': usage()}
                                      for address, name in zip(addresses, ['Orion · 节点订阅', 'Atlas · 节点订阅'])}}


def library(kind='config'):
    tap('配置' if kind == 'config' else '节点')
    if kind == 'nodes':
        tap('订阅')
    return nodes()


def is_busy(label, tree):
    return find(label, 'content-desc', tree).get('enabled') == 'false'


def assert_states(expected, label):
    tree = nodes()
    actual = {name: is_busy(name, tree) for name in expected}
    check(actual == expected, label, actual)


def wait_idle(*labels):
    wait(lambda: all(not is_busy(label, tree) for label in labels) if (tree := nodes()) else False,
         'update controls become available')


def changed_pixels(first, second, box):
    difference = ImageChops.difference(first.crop(box), second.crop(box))
    return sum(max(pixel) > 8 for pixel in difference.getdata())


def appearance_checks():
    start(config_seed())
    expected = {('blue', 'light'): (243, 244, 248), ('blue', 'dark'): (22, 25, 32),
                ('pink', 'light'): (248, 243, 246), ('pink', 'dark'): (30, 24, 28),
                ('default', 'light'): (250, 250, 250), ('default', 'dark'): (31, 31, 31)}
    for theme in ['light', 'dark']:
        for accent, label in [('blue', '雾蓝'), ('pink', '柔粉'), ('default', '黑白')]:
            tap('设置')
            tap('主题')
            tap('浅色' if theme == 'light' else '深色')
            tap(label)
            wait(lambda: prefs().get('accent') == accent and prefs().get('theme') == theme, 'theme saved')
            current = prefs()
            start(current)
            tree = library()
            check(prefs().get('accent') == accent and prefs().get('theme') == theme,
                  f'{accent}/{theme} selected through UI and persists after restart')
            frame = capture()
            pixels = [frame.getpixel((x, 2)) for x in [2, frame.width // 2, frame.width - 3]]
            check(all(max(abs(a - b) for a, b in zip(pixel, expected[accent, theme])) <= 3 for pixel in pixels),
                  f'{accent}/{theme} page and system bar use the selected palette', pixels)
            check(any('300 GB' in n.get('text', '') and '18 天后到期' in n.get('text', '') for n in tree),
                  f'{accent}/{theme} expiry and traffic share one text node')
            save_image(f'{accent}-{theme}', frame)
            if (accent, theme) in [('pink', 'light'), ('default', 'dark')]:
                tap('设置')
                save_image(f'{accent}-{theme}-settings')
    adb('shell', 'settings', 'put', 'system', 'font_scale', '1.5')
    start(config_seed('pink'))
    tap('设置')
    tree = nodes()
    chip_boxes = [bounds(find(label, tree=tree)) for label in ['黑白', '雾蓝', '柔粉']]
    check(all(box[2] > box[0] and box[3] > box[1] for box in chip_boxes), 'All three palette chips remain visible at font scale 1.5', chip_boxes)
    save_image('appearance-large-font-settings')
    tree = library()
    check(any('300 GB' in n.get('text', '') and '18 天后到期' in n.get('text', '') for n in tree),
          'Traffic and expiry remain readable as one text at font scale 1.5')
    save_image('appearance-large-font')
    adb('shell', 'settings', 'put', 'system', 'font_scale', '1.0')
    start(config_seed())
    boxes = {}
    for page in ['概览', '配置', '日志', '设置']:
        tap(page)
        tree = nodes()
        fabs = [n for n in tree if n.get('content-desc') == '连接代理']
        check(len(fabs) == 1 and not any(n.get('content-desc') == '查看连接' for n in tree),
              f'{page} has one floating start control and no stopped connections entry')
        boxes[page] = bounds(fabs[0])
    check(len(set(boxes.values())) == 1, 'Stopped pages use the same floating start position and size', boxes)
    library()
    tap('连接代理', 'content-desc')
    tree = nodes()
    if any(n.get('package') == 'com.android.vpndialogs' for n in tree):
        candidates = [n for n in tree if n.get('resource-id') == 'android:id/button1']
        check(bool(candidates), 'Isolated emulator VPN permission dialog has its confirmation action')
        tap_at(bounds(candidates[0]))
    wait(lambda: any(n.get('content-desc') == '查看连接' for n in nodes()), 'synthetic direct VPN starts', timeout=35)
    tree = nodes()
    check(not any(n.get('content-desc') == '连接代理' for n in tree), 'Running configuration page replaces floating start with connections and stop controls')
    save_image('appearance-connected')
    tap('断开代理连接', 'content-desc', tree)
    wait(lambda: any(n.get('content-desc') == '连接代理' for n in nodes()), 'VPN stops')
    check(True, 'Connections entry appears only after start and disappears after stop')


def update_checks(kind):
    start(config_seed() if kind == 'config' else node_seed())
    tree = library(kind)
    name_a = '更新订阅 · Orion · ' + ('出行' if kind == 'config' else '节点订阅')
    name_b = '更新订阅 · Atlas · ' + ('主订阅' if kind == 'config' else '节点订阅')
    all_name = '更新全部'
    path_a, path_b = ('/orion', '/atlas') if kind == 'config' else ('/nodes-a', '/nodes-b')
    box_a, box_b, box_all = [bounds(find(label, 'content-desc', tree)) for label in [name_a, name_b, all_name]]
    tap('配置操作' if kind == 'config' else '订阅操作', 'content-desc', tree)
    menu = nodes()
    check(not any(n.get('text') == '同步' for n in menu) and any(n.get('text') == '编辑' for n in menu),
          f'{kind} three-dot menu removes duplicate sync and retains editing')
    save_image('appearance-' + kind + '-menu')
    # Dismiss through its outside-click action. A raw API35 KEYCODE_BACK may be
    # handled by the activity before this Compose popup receives it.
    tap_at((20, box_b[3] + 400, 40, box_b[3] + 420))
    # Wait for the popup window to finish closing before delivering the next tap.
    tree = nodes()
    box_a, box_b, box_all = [bounds(find(label, 'content-desc', tree)) for label in [name_a, name_b, all_name]]
    idle = capture()
    single = queue(path_a)
    before = hits[path_a]
    clicked = time.monotonic()
    tap_at(box_a)
    check(single.started.wait(5), f'{kind} single-card update starts its download')
    first = capture()
    feedback_ms = round((time.monotonic() - clicked) * 1000)
    time.sleep(.17)
    second = capture()
    time.sleep(.17)
    third = capture()
    delta = [changed_pixels(first, second, box_a), changed_pixels(second, third, box_a)]
    check(changed_pixels(idle, first, box_a) > 15 and min(delta) > 15,
          f'{kind} slow update immediately shows an animated ring', {'feedback_observed_ms': feedback_ms, 'frame_changed_pixels': delta})
    check(changed_pixels(first, third, box_b) == 0, f'{kind} unrelated card remains visually still')
    tap_at(box_a)
    tap_at(box_a)
    assert_states({name_a: True, name_b: False, all_name: False}, f'{kind} only the requested card is disabled during its slow update')
    check(hits[path_a] == before + 1, f'{kind} repeated clicks do not duplicate the download')
    save_image('appearance-' + kind + '-updating', second)
    single.release.set()
    wait_idle(name_a, name_b, all_name)
    check(not single.timed_out, f'{kind} synthetic slow request finishes before network timeout')

    # A failed HTTP response must not replace valid configuration or nodes.
    old = prefs()
    bad = queue(path_a, 400, 'Synthetic bad request')
    tap_at(box_a)
    check(bad.started.wait(5), f'{kind} HTTP 400 request reaches the controlled source')
    bad.release.set()
    time.sleep(.35)
    error_tree = nodes()
    check(any('400' in n.get('text', '') for n in error_tree), f'{kind} HTTP 400 displays a visible failure message',
          [n.get('text') for n in error_tree if '400' in n.get('text', '')])
    save_image('appearance-' + kind + '-http400')
    wait_idle(name_a)
    after = prefs()
    if kind == 'config':
        before_data = next(p['content'] for p in old['configs'] if p['id'] == 'orion')
        after_data = next(p['content'] for p in after['configs'] if p['id'] == 'orion')
    else:
        before_data, after_data = old['nodes'], after['nodes']
    check(before_data == after_data, f'{kind} HTTP 400 keeps the previous valid data')
    retry = queue(path_a)
    tap_at(box_a)
    check(retry.started.wait(5), f'{kind} failed source can be retried immediately')
    retry.release.set()
    wait_idle(name_a)

    # In a sequential batch, queued sources and the top-level control stay busy.
    batch_a, batch_b = queue(path_a), queue(path_b)
    counts = (hits[path_a], hits[path_b])
    tap_at(box_all)
    check(batch_a.started.wait(5), f'{kind} batch starts first source')
    assert_states({name_a: True, name_b: True, all_name: True}, f'{kind} batch marks current and queued sources busy')
    tap_at(box_all)
    batch_a.release.set()
    check(batch_b.started.wait(5), f'{kind} batch advances to second source')
    time.sleep(.5)
    assert_states({name_a: False, name_b: True, all_name: True}, f'{kind} batch stays active until the last source completes')

    # Re-enter A after its batch slot ends; old batch cleanup must not own new A.
    new_a = queue(path_a)
    tap_at(box_a)
    check(new_a.started.wait(5), f'{kind} completed source can update again while another batch source is pending')
    batch_b.release.set()
    time.sleep(.5)
    assert_states({name_a: True, name_b: False, all_name: False}, f'{kind} finishing old batch preserves the new single-source busy state')
    tap_at(box_a)
    check(hits[path_a] == counts[0] + 2 and hits[path_b] == counts[1] + 1,
          f'{kind} overlapping batch and individual updates never duplicate active downloads')
    new_a.release.set()
    wait_idle(name_a, name_b, all_name)
    check(not any(g.timed_out for g in [batch_a, batch_b, new_a]), f'{kind} batch interleaving completed within network timeout')


def settings_previews():
    for accent, theme, scale, name in [('pink', 'light', '1.0', 'pink-light-settings'),
                                     ('default', 'dark', '1.0', 'default-dark-settings'),
                                     ('pink', 'light', '1.5', 'appearance-large-font-settings')]:
        adb('shell', 'settings', 'put', 'system', 'font_scale', scale)
        start(config_seed(accent, theme))
        tap('设置')
        wait(lambda: all(any(n.get('text') == label for n in tree) for label in ['黑白', '雾蓝', '柔粉'])
             if (tree := nodes()) else False, 'settings palette controls appear')
        save_image(name)
    check(True, 'Settings previews are captured only after all three palette controls appear')


def feedback_previews():
    for kind in ['config', 'nodes']:
        start(config_seed() if kind == 'config' else node_seed())
        tree = library(kind)
        label = '更新订阅 · Orion · ' + ('出行' if kind == 'config' else '节点订阅')
        gate = queue('/orion' if kind == 'config' else '/nodes-a', 400, 'Synthetic bad request')
        tap(label, 'content-desc', tree)
        check(gate.started.wait(5), f'{kind} feedback preview reaches controlled HTTP 400 source')
        gate.release.set()
        wait(lambda: any('400' in n.get('text', '') for n in nodes()), 'visible HTTP 400 feedback')
        save_image('appearance-' + kind + '-http400')
    check(True, 'Failure feedback previews are captured after the HTTP 400 message appears')


try:
    original = adb('shell', 'run-as', 'com.wbox.app', 'cat', 'files/profiles.json')
except RuntimeError:
    original = None
original_font = adb('shell', 'settings', 'get', 'system', 'font_scale').decode().strip()
original_size = adb('shell', 'wm', 'size').decode()
original_density = adb('shell', 'wm', 'density').decode()

try:
    installed = adb('install', '-r', str(APK), timeout=120).decode().strip()
    check('Success' in installed, 'Installs current debug APK before verification')
    observations['apk_sha256'] = hashlib.sha256(APK.read_bytes()).hexdigest()
    adb('shell', 'settings', 'put', 'system', 'font_scale', '1.0')
    if args.phase in ['all', 'appearance']:
        appearance_checks()
    if args.phase in ['all', 'updates']:
        update_checks('config')
        update_checks('nodes')
        settings_previews()
    if args.phase == 'feedback':
        feedback_previews()
except Exception as error:
    failure = str(error)
    traceback.print_exc()
    print('FAIL', failure, flush=True)
    save_image('appearance-failure')
finally:
    for gate in gates:
        gate.release.set()
    adb('shell', 'am', 'force-stop', 'com.wbox.app')
    if original is not None:
        write_prefs(original)
    else:
        adb('shell', 'run-as', 'com.wbox.app', 'rm', '-f', 'files/profiles.json')
    for kind, value in [('size', original_size), ('density', original_density)]:
        match = re.search(r'Override (?:size|density): ([\dx]+)', value)
        adb('shell', 'wm', kind, match.group(1) if match else 'reset')
    if original_font == 'null':
        adb('shell', 'settings', 'delete', 'system', 'font_scale')
    else:
        adb('shell', 'settings', 'put', 'system', 'font_scale', original_font)
    adb('shell', 'rm', '-f', '/sdcard/wbox-appearance.xml')
    server.shutdown()
    report = report_path
    report.parent.mkdir(parents=True, exist_ok=True)
    verified_phases = [phase for phase, marker in [('appearance', 'Connections entry appears only after start and disappears after stop'),
                      ('config-updates', 'config batch interleaving completed within network timeout'),
                      ('nodes-updates', 'nodes batch interleaving completed within network timeout')]
                      if marker in checks]
    report.write_text(json.dumps({'version': VERSION, 'phase': args.phase, 'verified_phases': verified_phases,
                                  'appearance_reused_from_same_apk': reused_appearance, 'emulator': 'emulator-5556',
                                  'checks': checks, 'observations': observations, 'screenshots': images,
                                  'failure': failure, 'preferences_and_display_restored': True},
                                 ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
if failure:
    raise SystemExit(1)
