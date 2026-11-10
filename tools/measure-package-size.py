"""Measure package contents and Windows allocated bytes after optional LZX compression."""
import ctypes
import hashlib
import json
import pathlib
import subprocess
import sys
import zipfile

root = pathlib.Path(__file__).resolve().parent.parent
version = '0.6.7'
package = root / 'windows/desktop-publish/win-unpacked'
files = [p for p in package.rglob('*') if p.is_file()]
get_size = ctypes.WinDLL('kernel32', use_last_error=True).GetCompressedFileSizeW
get_size.argtypes = [ctypes.c_wchar_p, ctypes.POINTER(ctypes.c_ulong)]
get_size.restype = ctypes.c_ulong

def allocated(path):
    high = ctypes.c_ulong()
    ctypes.set_last_error(0)
    low = get_size(str(path), ctypes.byref(high))
    if low == 0xffffffff and ctypes.get_last_error():
        raise ctypes.WinError(ctypes.get_last_error())
    return (high.value << 32) | low

before = sum(allocated(p) for p in files)
hashes = {p: hashlib.sha256(p.read_bytes()).digest() for p in files}
if '--compress' in sys.argv:
    # This command operates only on the resolved packaged application directory.
    subprocess.run(['compact.exe', '/C', '/S', '/I', '/Q', '/EXE:LZX', '*'],
                   cwd=package, check=True, stdout=subprocess.DEVNULL)
assert all(hashlib.sha256(p.read_bytes()).digest() == value for p, value in hashes.items())
result = {'windows': {'logicalBytes': sum(p.stat().st_size for p in files),
                     'beforeAllocatedBytes': before,
                     'afterAllocatedBytes': sum(allocated(p) for p in files),
                     'fileHashesUnchanged': True}, 'android': {}}
for v in ['0.6.6', version]:
    apk = root / f'dist/Wbox-{v}-android-arm64-v8a.apk'
    if apk.exists():
        with zipfile.ZipFile(apk) as archive:
            native = sum(p.file_size for p in archive.infolist() if p.filename.startswith('lib/'))
        result['android'][v] = {'apkBytes': apk.stat().st_size, 'extractedNativeBytes': native,
                               'apkPlusNativeBytes': apk.stat().st_size + native}
result['android']['note'] = 'APK plus extracted native libraries; excludes ART, runtime data and filesystem overhead; not a phone storage measurement.'
destination = root / f'docs/verification/{version}-size.json'
destination.write_text(json.dumps(result, indent=2) + '\n', encoding='utf-8')
print(json.dumps(result, indent=2))
