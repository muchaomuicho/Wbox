"""Verify sheet-content edge stretching on isolated emulator-5556.

Records actual rendered frames, rather than checking layout after settling.
--baseline observes 0.7.5; the default checks 0.7.6. Synthetic profiles and
emulator preferences are restored. PyAV may be installed into .tools/video-076.
"""
import json, pathlib, re, subprocess, sys, time, traceback
import xml.etree.ElementTree as ET
import numpy as np
from PIL import Image, ImageDraw

ROOT = pathlib.Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ROOT / '.tools/video-076'))
import av

sys.stdout.reconfigure(encoding='utf-8')
ADB = 'D:/DevTools/Android/Sdk/platform-tools/adb.exe'
SERIAL = 'emulator-5556'
BASELINE = '--baseline' in sys.argv
VERSION = '0.7.5' if BASELINE else '0.7.6'
SUFFIX = 'baseline' if BASELINE else 'final'
checks, observations, videos = [], {}, []
failure = None


def adb(*args, data=None, timeout=40):
    result = subprocess.run([ADB, '-s', SERIAL, *args], input=data,
                            capture_output=True, timeout=timeout)
    if result.returncode:
        raise RuntimeError(result.stderr.decode(errors='replace'))
    return result.stdout


def nodes():
    adb('shell', 'uiautomator', 'dump', '/sdcard/wbox-boundaries076.xml')
    return list(ET.fromstring(adb('shell', 'cat', '/sdcard/wbox-boundaries076.xml')).iter('node'))


def bounds(node):
    return tuple(map(int, re.findall(r'\d+', node.get('bounds', ''))))


def has(label, attr='text'):
    return any(node.get(attr) == label for node in nodes())


def find(label, attr='text'):
    return next(node for node in nodes() if node.get(attr) == label)


def tap(label, attr='text'):
    left, top, right, bottom = bounds(find(label, attr))
    adb('shell', 'input', 'tap', str((left + right) // 2), str((top + bottom) // 2))
    time.sleep(.4)


def swipe(start, end, duration=70):
    adb('shell', 'input', 'swipe', *map(str, (*start, *end, duration)))


def wait(predicate, label, timeout=25):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        if predicate():
            return
        time.sleep(.2)
    raise AssertionError('Timed out: ' + label)


def check(ok, label, detail=None):
    if not ok:
        raise AssertionError(label + ': ' + str(detail))
    checks.append(label)
    if detail is not None:
        observations[label] = detail
    print('PASS', label, flush=True)


def save(data):
    if not isinstance(data, bytes):
        data = json.dumps(data).encode()
    adb('shell', 'run-as', 'com.wbox.app', 'sh', '-c', "'cat > files/profiles.json'", data=data)


def seed():
    content = json.dumps({'outbounds': [{'type': 'selector', 'tag': 'Proxy', 'outbounds': ['DIRECT']},
                                       {'type': 'direct', 'tag': 'DIRECT'}], 'route': {'final': 'Proxy'}})
    data = {'mode': 'config', 'engine': 'sing-box', 'node_engine': 'sing-box',
            'theme': 'light', 'accent': 'blue', 'app_mode': 'exclude', 'apps': [],
            'configs': [{'id': f'p{i}', 'name': f'Orion {i + 1:02}', 'core': 'sing-box',
                         'content': content} for i in range(28)],
            'active_config': 'p0', 'config_sing-box': content}
    adb('shell', 'am', 'force-stop', 'com.wbox.app')
    save(data)
    adb('shell', 'am', 'start', '-W', '-n', 'com.wbox.app/.MainActivity')
    wait(lambda: has('概览'), 'main')


def open_apps(filtered=False):
    seed()
    tap('设置')
    tap('分应用代理')
    wait(lambda: has('代理范围'), 'apps')
    if filtered:
        tap('搜索应用')
        adb('shell', 'input', 'text', 'com.android.providers')
        adb('shell', 'input', 'keyevent', '4')
        time.sleep(.5)


def scroll_bottom(label, attr='text', attempts=12):
    for _ in range(attempts):
        swipe((540, 2050), (540, 850), 180)
        time.sleep(.25)
        if has(label, attr):
            # Bring the final row completely into view and let edge animation settle.
            swipe((540, 2050), (540, 850), 180)
            time.sleep(1)
            return
    raise AssertionError('Cannot reach final row: ' + label)


def record(label, gesture):
    remote = '/sdcard/wbox-boundaries076.mp4'
    path = ROOT / f'.tools/boundaries-{label}-{SUFFIX}-076.mp4'
    process = subprocess.Popen([ADB, '-s', SERIAL, 'shell', 'screenrecord', '--size', '720x1600',
                                '--bit-rate', '6000000', '--time-limit', '6', remote],
                               stdout=subprocess.PIPE, stderr=subprocess.PIPE)
    try:
        time.sleep(1.6)
        gesture()
        _, error = process.communicate(timeout=15)
        if process.returncode:
            raise RuntimeError(error.decode(errors='replace'))
    finally:
        if process.poll() is None:
            process.terminate()
            process.wait(timeout=5)
    adb('pull', remote, str(path))
    videos.append(path.relative_to(ROOT).as_posix())
    frames = [(float(frame.time), frame.to_ndarray(format='rgb24'))
              for frame in av.open(str(path)).decode(video=0)]
    if len(frames) < 3:
        raise AssertionError('Insufficient rendered frames: ' + label)
    return frames


def track(frames, rectangle, radius=35):
    # Match the rendered artwork; Compose layout coordinates do not show stretch.
    scale = frames[0][1].shape[1] / 1080
    left, top, right, bottom = [round(value * scale) for value in rectangle]
    first = frames[0][1]
    width, height = right - left, bottom - top
    if width < 15 or height < 15:
        raise AssertionError('Tracking region is clipped')
    template = first[top:bottom, left:right:2].astype(np.int16)
    samples = []
    for seconds, frame in frames:
        low, high = max(0, top - radius), min(frame.shape[0] - height, top + radius)
        errors = [float(np.mean(np.abs(frame[y:y + height, left:right:2].astype(np.int16) - template)))
                  for y in range(low, high + 1)]
        offset = int(np.argmin(errors)) + low - top
        samples.append({'seconds': round(seconds, 4), 'offset_video_px': offset,
                        'pixel_error': round(min(errors), 3)})
    return {'max_displacement_video_px': max(abs(sample['offset_video_px']) for sample in samples),
            'video_scale': scale, 'samples': samples}


def edge_record(label, title, row_label, row_attr='text'):
    title_rectangle = bounds(find(title))
    row_rectangle = bounds(find(row_label, row_attr))
    frames = record(label, lambda: swipe((540, 2050), (540, 900), 70))
    header = track(frames, title_rectangle, 80)
    row = track(frames, row_rectangle)
    observations[label] = {'header': header, 'content': row, 'gesture': [540, 2050, 540, 900, 70]}
    check(header['max_displacement_video_px'] <= 1, label + ' keeps the fixed sheet header stationary', header['max_displacement_video_px'])
    if BASELINE:
        check(row['max_displacement_video_px'] >= 3, label + ' reproduces rendered content stretch', row['max_displacement_video_px'])
    else:
        check(row['max_displacement_video_px'] <= 1, label + ' stops content at the edge without rendered rebound', row['max_displacement_video_px'])
    # Retain one synthetic side-by-side frame comparison as review evidence.
    worst = max(range(len(row['samples'])), key=lambda i: abs(row['samples'][i]['offset_video_px']))
    preview = Image.new('RGB', (2160, 1660), 'white')
    for column, index in enumerate([0, worst, len(frames) - 1]):
        preview.paste(Image.fromarray(frames[index][1]), (column * 720, 60))
        ImageDraw.Draw(preview).text((column * 720 + 20, 15),
                                   f"t={frames[index][0]:.3f}s, shift={row['samples'][index]['offset_video_px']}px", fill='black')
    destination = ROOT / (f'.tools/boundaries-{label}-baseline-076.png' if BASELINE else
                          f'docs/previews/0.7.6-android-{label}-boundary.png')
    preview.save(destination)


def top_record():
    open_apps()
    initial_first_row = bounds(find('2 Button Navigation Bar'))
    # Start inside the list, then return to its first row through inertia.
    # The finger travel is shorter than the initial content displacement, so
    # any later header movement cannot be an intentional drag of the sheet.
    swipe((540, 1800), (540, 1450), 500)
    time.sleep(.8)
    frames = record('apps-top', lambda: swipe((540, 1100), (540, 1400), 70))
    check(has('代理范围'), 'Fast return to the app list top does not dismiss the sheet')
    title_rectangle = bounds(find('分应用代理'))
    row_rectangle = bounds(find('Android Accessibility Suite'))
    # Reference the settled final frame; the regular journey toward the top
    # has negative displacement, while positive displacement is overshoot.
    reference = [frames[-1]] + frames
    header = track(reference, title_rectangle, 120)
    row = track(reference, row_rectangle, 400)
    header['samples'] = header['samples'][1:]
    row['samples'] = row['samples'][1:]
    overshoot = max(sample['offset_video_px'] for sample in row['samples'])
    observations['apps-top'] = {'header': header, 'content': row,
                                'overshoot_video_px': overshoot,
                                'gesture': [540, 1100, 540, 1400, 70]}
    worst = max(range(len(header['samples'])), key=lambda i: abs(header['samples'][i]['offset_video_px']))
    preview = Image.new('RGB', (2160, 1660), 'white')
    for column, index in enumerate([0, worst, len(frames) - 1]):
        preview.paste(Image.fromarray(frames[index][1]), (column * 720, 60))
        ImageDraw.Draw(preview).text((column * 720 + 20, 15),
                                   f"t={frames[index][0]:.3f}s, header shift={header['samples'][index]['offset_video_px']}px", fill='black')
    destination = ROOT / ('.tools/boundaries-apps-top-baseline-076.png' if BASELINE else
                          'docs/previews/0.7.6-android-apps-top-boundary.png')
    preview.save(destination)
    if BASELINE:
        observations['apps-top']['header_rebound_observed'] = header['max_displacement_video_px'] > 1
    else:
        check(header['max_displacement_video_px'] <= 1,
              'Returning content to the top keeps the sheet header stationary', header['max_displacement_video_px'])
    if not BASELINE:
        check(overshoot <= 1, 'Returning content stops at the top without rebound', overshoot)
        travel = -min(sample['offset_video_px'] for sample in row['samples'])
        check(travel > 300 * row['video_scale'] + 2,
              'Normal content inertia continues beyond the finger travel', travel)
    check(has('2 Button Navigation Bar'), 'Fast reverse scroll returns to the first app row')
    final_first_row = bounds(find('2 Button Navigation Bar'))
    check(abs(final_first_row[1] - initial_first_row[1]) <= 2,
          'Reverse fling reaches the actual top boundary',
          {'initial_first_row': initial_first_row, 'final_first_row': final_first_row})


def direct_boundary_record(label, start, end):
    open_apps()
    title_rectangle = bounds(find('分应用代理'))
    frames = record(label, lambda: swipe(start, end, 70))
    header = track(frames, title_rectangle, 120)
    observations[label] = {'header': header, 'gesture': [*start, *end, 70]}
    check(has('代理范围'), label + ' retains the app sheet')
    if not BASELINE:
        check(header['max_displacement_video_px'] <= 1,
              label + ' stops the sheet at its expanded boundary', header['max_displacement_video_px'])


original = adb('shell', 'run-as', 'com.wbox.app', 'cat', 'files/profiles.json')
hard_ime = adb('shell', 'settings', 'get', 'secure', 'show_ime_with_hard_keyboard').decode().strip()
appops = adb('shell', 'appops', 'get', 'com.wbox.app', 'ACTIVATE_VPN').decode().strip()
try:
    check(f'versionName={VERSION}'.encode() in adb('shell', 'dumpsys', 'package', 'com.wbox.app'), 'Expected debug version installed: ' + VERSION)
    installed_path = adb('shell', 'pm', 'path', 'com.wbox.app').decode().strip().splitlines()[0].removeprefix('package:')
    observations['installed_apk_sha256'] = adb('shell', 'sha256sum', installed_path).decode().split()[0]
    observations['environment'] = {'serial': SERIAL, 'api': 35, 'recording': '720x1600 rendered H264 frames'}
    if not BASELINE:
        comparisons = {}
        baseline_path = ROOT / '.tools/android-boundaries-baseline-076.json'
        null_only_path = ROOT / '.tools/apps-top-final-076.json'
        if baseline_path.exists():
            baseline_data = json.loads(baseline_path.read_text(encoding='utf-8'))
            comparisons['baseline_075'] = {key: baseline_data['observations'][key]
                                           for key in ['apps-top', 'apps-bottom', 'picker-bottom']}
        if null_only_path.exists():
            comparisons['overscroll_disabled_only_top'] = json.loads(null_only_path.read_text(encoding='utf-8'))['apps-top']
        direct_path = ROOT / '.tools/direct-drag-null-076.json'
        if direct_path.exists():
            comparisons['overscroll_disabled_only_direct_drag'] = json.loads(direct_path.read_text(encoding='utf-8'))
        observations['rendered_before_after_comparisons'] = comparisons
    adb('shell', 'settings', 'put', 'secure', 'show_ime_with_hard_keyboard', '1')

    top_record()
    direct_boundary_record('title-up', (540, 290), (540, 90))
    direct_boundary_record('handle-up', (540, 126), (540, 65))

    open_apps(filtered=True)
    scroll_bottom('应用图标 · User Dictionary', 'content-desc')
    check(has('应用图标 · User Dictionary', 'content-desc'), 'App list scrolls normally to its final filtered row')
    edge_record('apps-bottom', '分应用代理', '应用图标 · User Dictionary', 'content-desc')

    seed()
    tap('选择配置', 'content-desc')
    scroll_bottom('Orion 28')
    check(has('Orion 28'), 'Long profile picker scrolls normally to its last profile')
    edge_record('picker-bottom', '选择配置', 'Orion 28')

    # A normal downward drag from the handle must still dismiss the sheet.
    left, top, right, bottom = bounds(find('Drag handle', 'content-desc'))
    frames = record('handle-close', lambda: swipe(((left + right) // 2, (top + bottom) // 2), (540, 2200), 220))
    check(not has('选择配置') and has('概览'), 'Fast handle drag still closes the profile picker')
    observations['handle_drag_frames'] = len(frames)

    open_apps()
    tap('搜索应用')
    wait(lambda: bool(re.search(rb'mInputShown=true|isInputViewShown=true', adb('shell', 'dumpsys', 'input_method'))), 'IME appears')
    adb('shell', 'input', 'keyevent', '4')
    time.sleep(.5)
    check(has('代理范围'), 'System Back hides the app keyboard before dismissing the sheet')
    adb('shell', 'input', 'keyevent', '4')
    time.sleep(.5)
    check(not has('代理范围') and has('设置'), 'Next System Back closes the app sheet')

    seed()
    adb('shell', 'appops', 'set', 'com.wbox.app', 'ACTIVATE_VPN', 'allow')
    tap('连接代理', 'content-desc')
    wait(lambda: has('查看连接', 'content-desc'), 'Synthetic VPN starts', 45)
    tap('查看连接', 'content-desc')
    wait(lambda: has('搜索连接'), 'Connection sheet')
    title_rectangle = bounds(find('连接'))
    frames = record('connections-empty', lambda: swipe((540, 1800), (540, 950), 70))
    stationary = track(frames, title_rectangle, 80)
    observations['connections-empty'] = stationary
    check(stationary['max_displacement_video_px'] <= 1, 'Connection sheet stays stable during fast content swipe', stationary['max_displacement_video_px'])
    adb('shell', 'input', 'keyevent', '4')
    time.sleep(.4)
    check(not has('搜索连接'), 'System Back closes the connection sheet')
    tap('断开代理连接', 'content-desc')
    wait(lambda: has('连接代理', 'content-desc'), 'Synthetic VPN stops')
except Exception as error:
    failure = f'{type(error).__name__}: {error}'
    traceback.print_exc()
finally:
    adb('shell', 'am', 'force-stop', 'com.wbox.app')
    save(original)
    if hard_ime == 'null':
        adb('shell', 'settings', 'delete', 'secure', 'show_ime_with_hard_keyboard')
    else:
        adb('shell', 'settings', 'put', 'secure', 'show_ime_with_hard_keyboard', hard_ime)
    match = re.search(r'ACTIVATE_VPN: (\w+)', appops)
    adb('shell', 'appops', 'set', 'com.wbox.app', 'ACTIVATE_VPN', match.group(1) if match else 'default')
    profiles_restored = adb('shell', 'run-as', 'com.wbox.app', 'cat', 'files/profiles.json') == original
    ime_restored = adb('shell', 'settings', 'get', 'secure', 'show_ime_with_hard_keyboard').decode().strip() == hard_ime
    if not profiles_restored or not ime_restored:
        failure = (failure + '; ' if failure else '') + 'Emulator preferences were not restored'
    adb('shell', 'rm', '-f', '/sdcard/wbox-boundaries076.xml', '/sdcard/wbox-boundaries076.mp4')
    output = ROOT / ('.tools/android-boundaries-baseline-076.json' if BASELINE else 'docs/verification/0.7.6-android-boundaries.json')
    output.write_text(json.dumps({'version': VERSION, 'checks': checks, 'observations': observations,
                                  'failure': failure, 'preferences_restored': profiles_restored,
                                  'ime_setting_restored': ime_restored,
                                  'temporary_videos': videos}, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
if failure:
    raise SystemExit(1)
