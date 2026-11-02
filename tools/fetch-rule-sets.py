"""Retrieve the exact pinned rule artifacts; never silently refresh a release snapshot."""
import concurrent.futures, hashlib, json, pathlib, subprocess, tempfile
ROOT=pathlib.Path(__file__).resolve().parent.parent
DEST=ROOT/'resources/runtime/bundled/rules'

def fetch(entry):
    target=DEST/entry['file']
    def valid(path):return path.is_file() and hashlib.sha256(path.read_bytes()).hexdigest()==entry['sha256']
    if valid(target):return entry['file']+' verified'
    with tempfile.TemporaryDirectory(prefix='wbox-rule-',dir=DEST) as tmp:
        path=pathlib.Path(tmp)/entry['file']
        subprocess.run(['curl.exe','-fL','--connect-timeout','15','--max-time','90',entry['url'],'-o',str(path)],check=True,stdout=subprocess.DEVNULL)
        if not valid(path):raise RuntimeError(entry['file']+': upstream content changed; update the manifest only after review')
        path.replace(target)
    return entry['file']+' downloaded and verified'

if __name__=='__main__':
    entries=json.loads((DEST/'manifest.json').read_text(encoding='utf-8'))
    with concurrent.futures.ThreadPoolExecutor(max_workers=4) as pool:
        for result in pool.map(fetch,entries):print(result)
