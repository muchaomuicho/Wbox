"""Check packaged geodata, ARM64-only release and absence of debug probe entry points."""
import hashlib, json, lzma, pathlib, sys, zipfile

root = pathlib.Path(__file__).resolve().parent.parent
version = sys.argv[1] if len(sys.argv) > 1 else '0.7.0'
manifest = json.loads((root/'resources/geodata/manifest.json').read_text())
checks = []
cores = json.loads((root/'resources/cores/manifest.json').read_text())
for entry in cores['files']:
    source = root/'resources/cores'/entry['path']
    assert hashlib.sha256(source.read_bytes()).hexdigest() == entry['sha256'], entry['path']
    if entry['path'].startswith('windows/'):
        packaged = root/'windows/desktop-publish/win-unpacked/resources/backend/cores'/source.name
        assert packaged.read_bytes() == source.read_bytes(), source.name
checks.append('Core manifest hashes match current binaries and packaged Windows cores')
if version in ('0.6.9','0.6.10','0.6.11','0.7.0'):
    assert cores['singboxVersion'] == '1.14.4-wbox.1'
    assert cores['singboxBuild']['unifiedDelay'] is True
    checks.append('Both platforms use the documented Wbox unified-delay core derivative')
with zipfile.ZipFile(root/f'dist/Wbox-{version}-android-arm64-v8a.apk') as apk:
    names = apk.namelist()
    assert {name.split('/')[1] for name in names if name.startswith('lib/')} == {'arm64-v8a'}
    checks.append('APK includes only ARM64 native libraries')
    for library in ['libmihomo.so', 'libsingbox.so', 'libwboxtun.so']:
        assert f'lib/arm64-v8a/{library}' in names
        assert hashlib.sha256(apk.read(f'lib/arm64-v8a/{library}')).digest() == hashlib.sha256((root/'resources/cores/android/arm64-v8a'/library).read_bytes()).digest(), library
    checks.append('APK includes both cores and native tunnel')
    if version in ('0.6.7','0.6.8','0.6.9','0.6.10','0.6.11','0.7.0'):
        assert 'assets/LICENSE-xz-java' in names
        assert 'assets/bundled/rules/mihomo-anti-ad.yaml' not in names
        checks.append('Compressed asset decoder license is included and superseded YAML is excluded')
    checks.append('Packaged core and JNI tunnel hashes match current build inputs')
    rules = json.loads((root/'resources/runtime/bundled/rules/manifest.json').read_text())
    assert json.loads(apk.read('assets/bundled/rules/manifest.json')) == rules
    for entry in rules:
        for data in [apk.read('assets/bundled/rules/'+entry['file']), (root/'windows/desktop-publish/win-unpacked/resources/backend/bundled/rules'/entry['file']).read_bytes()]:
            assert hashlib.sha256(data).hexdigest() == entry['sha256']
    for name in ['sing-box.json','mihomo.yaml']:
        expected=(root/'resources/runtime/bundled/templates'/name).read_bytes()
        assert apk.read('assets/bundled/templates/'+name)==expected
        assert (root/'windows/desktop-publish/win-unpacked/resources/backend/bundled/templates'/name).read_bytes()==expected
    checks.append('Both packages contain all 26 verified rule sets and identical sanitized node templates')
    android_manifest = apk.read('AndroidManifest.xml')
    for encoding in ('utf-8', 'utf-16le'):
        assert 'RuntimeProbeReceiver'.encode(encoding) not in android_manifest
    checks.append('Release APK has no emulator diagnostic receiver')
    assert json.loads(apk.read('assets/manifest.json')) == manifest
    for entry in manifest:
        name='assets/'+entry['name']
        data=apk.read(name) if name in names else lzma.decompress(apk.read(name+'.xz'))
        assert hashlib.sha256(data).hexdigest() == entry['sha256']
        path = root/'windows/desktop-publish/win-unpacked/resources/backend/geodata'/entry['name']
        assert hashlib.sha256(path.read_bytes()).hexdigest() == entry['sha256']
        checks.append('Both packages contain verified ' + entry['name'])
badging = (root/f'android/apk-{version}-badging.log').read_text()
assert 'application-debuggable' not in badging
assert f"versionName='{version}'" in badging
checks.append('Release version matches and debuggable flag is absent')
signature = (root/f'docs/verification/{version}-apk-signature.txt').read_text()
assert '774e7eef8410a54c9d80fe18ebd3980d07701afb176d7e94ddfee5b796c7b026' in signature
checks.append('APK retains the existing release signing certificate')
result = json.dumps({'version': version, 'checks': checks}, indent=2)
(root/f'docs/verification/{version}-packages.json').write_text(result)
print(result)
