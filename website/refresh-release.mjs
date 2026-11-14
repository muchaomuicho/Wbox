import { writeFile } from 'node:fs/promises';
import { publicRelease } from './worker.js';

const response = await fetch('https://api.github.com/repos/muchaomuicho/Wbox/releases/latest', {
  headers: { Accept: 'application/vnd.github+json', 'User-Agent': 'Wbox-Website' },
  signal: AbortSignal.timeout(15000),
});
if (!response.ok) throw new Error(`GitHub release request failed: ${response.status}`);
const release = publicRelease(await response.json());
for (const suffix of ['-windows-x64-setup.exe', '-android-arm64-v8a.apk', '-source.zip']) {
  if (!release.assets.some(asset => asset.name.endsWith(suffix))) throw new Error(`Missing release asset: ${suffix}`);
}
await writeFile(new URL('./release-fallback.json', import.meta.url), JSON.stringify(release, null, 2) + '\n');
console.log(`Saved public release fallback: ${release.tag_name}`);
