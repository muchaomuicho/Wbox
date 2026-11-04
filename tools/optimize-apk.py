"""Recompress APK payloads losslessly before zipalign and release signing."""
import hashlib
import pathlib
import re
import sys
import zipfile

source, target = map(pathlib.Path, sys.argv[1:3])
if source.resolve() == target.resolve():
    raise ValueError('Use a separate output file')
signature = re.compile(r'META-INF/(?:MANIFEST\.MF|[^/]+\.(?:SF|RSA|DSA|EC))$', re.I)
with zipfile.ZipFile(source) as original, zipfile.ZipFile(target, 'w', compresslevel=9) as result:
    payloads = {}
    for entry in original.infolist():
        if signature.fullmatch(entry.filename):
            continue
        data = original.read(entry)
        payloads[entry.filename] = hashlib.sha256(data).digest()
        result.writestr(entry, data, compress_type=entry.compress_type, compresslevel=9)
with zipfile.ZipFile(target) as result:
    assert set(result.namelist()) == set(payloads)
    assert all(hashlib.sha256(result.read(name)).digest() == digest for name, digest in payloads.items())
print(f'Lossless APK recompression saved {source.stat().st_size - target.stat().st_size:,} bytes before signing')
