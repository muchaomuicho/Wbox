"""Compile the pinned domain list to MRS without dropping any rule entries."""
import hashlib
import json
import pathlib
import subprocess

root = pathlib.Path(__file__).resolve().parent.parent
directory = root / 'resources/runtime/bundled/rules'
manifest_path = directory / 'manifest.json'
manifest = json.loads(manifest_path.read_text(encoding='utf-8'))
entry = next(e for e in manifest if e['engine'] == 'mihomo' and e['tag'] == 'anti-ad')
source = directory / 'mihomo-anti-ad.yaml'
source_hash = hashlib.sha256(source.read_bytes()).hexdigest()
assert source_hash == entry.get('sourceSha256', entry['sha256']), 'Pinned source rule hash changed'
target = directory / 'mihomo-anti-ad.mrs'
if not target.exists() or hashlib.sha256(target.read_bytes()).hexdigest() != entry['sha256']:
    subprocess.run([str(root / 'resources/cores/windows/mihomo.exe'), 'convert-ruleset',
                    'domain', 'yaml', str(source), str(target)], check=True)
entry.update(sourceFormat='yaml', sourceSha256=source_hash, format='mrs', file=target.name,
             size=target.stat().st_size, sha256=hashlib.sha256(target.read_bytes()).hexdigest())
manifest_path.write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
print(f'Pinned advertising rules: {source.stat().st_size:,} -> {target.stat().st_size:,} bytes')
