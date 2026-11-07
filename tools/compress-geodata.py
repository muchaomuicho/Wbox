"""Create Android-only XZ assets with a bounded 1 MiB decoder dictionary."""
import hashlib
import json
import lzma
import pathlib
import sys

root = pathlib.Path(__file__).resolve().parent.parent
source = root / 'resources/geodata'
output = pathlib.Path(sys.argv[1])
output.mkdir(parents=True, exist_ok=True)
manifest = json.loads((source / 'manifest.json').read_text(encoding='utf-8'))
for entry in manifest:
    data = (source / entry['name']).read_bytes()
    assert hashlib.sha256(data).hexdigest() == entry['sha256']
    target = output / (entry['name'] + '.xz')
    if not target.exists() or lzma.decompress(target.read_bytes()) != data:
        compressed = lzma.compress(data, format=lzma.FORMAT_XZ,
            filters=[{'id': lzma.FILTER_LZMA2, 'dict_size': 1 << 20, 'preset': 7}])
        assert lzma.decompress(compressed) == data
        target.write_bytes(compressed)
(output / 'manifest.json').write_bytes((source / 'manifest.json').read_bytes())
print('Verified three losslessly compressed offline databases')
