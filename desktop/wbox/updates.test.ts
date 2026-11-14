import test from 'node:test';
import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { mkdtemp, readFile, rm, writeFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { WboxUpdates, RELEASE_API, atomicJSON, parseChecksum, parseRelease, request, verifyPackage, type VerifiedUpdate } from './updates';

const bytes = Buffer.from('synthetic installer, never executed');
const sha256 = createHash('sha256').update(bytes).digest('hex');
const tag = 'v0.7.10';
const fileName = 'Wbox-0.7.10-windows-x64-setup.exe';
const base = 'https://github.com/muchaomuicho/Wbox/releases/';
const release = () => ({ tag_name: tag, draft: false, prerelease: false, html_url: base + 'tag/' + tag, body: 'Changes', assets: [
  { name: fileName, size: bytes.length, browser_download_url: base + `download/${tag}/${fileName}`, digest: 'sha256:' + sha256 },
  { name: 'SHA256SUMS-0.7.10.txt', size: 120, browser_download_url: base + `download/${tag}/SHA256SUMS-0.7.10.txt` },
] });
const info: VerifiedUpdate = { ...parseRelease(release(), '0.7.9')!.info, sha256 };

test('stable version comparison and release trust boundaries', () => {
  assert.equal(parseRelease(release(), '0.7.9')?.info.versionName, 'v0.7.10');
  assert.equal(parseRelease(release(), '0.7.10'), null);
  assert.equal(parseRelease(release(), '0.8.0'), null);
  for (const mutation of [
    (r: any) => { r.draft = true; }, (r: any) => { r.prerelease = true; },
    (r: any) => { r.tag_name = 'v0.7.11-beta.1'; }, (r: any) => { r.html_url = 'https://example.com'; },
    (r: any) => { r.assets[0].size = 0; }, (r: any) => { r.assets[0].size = 1.5; },
    (r: any) => { r.assets[0].browser_download_url = 'https://github.com/attacker/Wbox/releases/file.exe'; },
    (r: any) => { r.assets[0].browser_download_url += '?source=other'; },
    (r: any) => { r.assets[0].name = 'Wbox-0.7.10-windows-arm64-setup.exe'; },
    (r: any) => { r.assets.push(r.assets[0]); }, (r: any) => { r.assets.pop(); },
  ]) { const value = release(); mutation(value); assert.throws(() => parseRelease(value, '0.7.9')); }
  assert.equal(parseChecksum(`${sha256} *${fileName}\r\n`, fileName), sha256);
  assert.throws(() => parseChecksum(`${sha256} other.exe`, fileName));
  assert.throws(() => parseChecksum(`${sha256} ${fileName}\n${sha256} ${fileName}`, fileName));
});

test('asset requests allow GitHub CDN redirects but reject external and downgraded redirects', async () => {
  for (const target of ['https://example.com/file', 'http://github.com/file', 'https://user@github.com/file', 'https://github.com:444/file']) {
    const fetcher = (async () => new Response(null, { status: 302, headers: { location: target } })) as typeof fetch;
    await assert.rejects(request(info.downloadURL, fetcher, new AbortController().signal));
  }
  let requests = 0;
  const fetcher = (async () => ++requests === 1 ? new Response(null, { status: 302, headers: { location: 'https://release-assets.githubusercontent.com/github-production-release-asset/file' } }) : new Response(bytes)) as typeof fetch;
  assert.equal((await request(info.downloadURL, fetcher, new AbortController().signal)).status, 200);
  assert.equal(requests, 2);
});

async function fixture(run: (directory: string) => Promise<void>) {
  const directory = await mkdtemp(join(tmpdir(), 'wbox-updater-test-'));
  try { await run(directory); } finally { assert.ok(directory.startsWith(join(tmpdir(), 'wbox-updater-test-'))); await rm(directory, { recursive: true, force: true }); }
}
function fakeFetch(body: Buffer = bytes, headers: Record<string, string> = {}) {
  const requests: string[] = [];
  const fetcher = (async (url: any) => {
    requests.push(String(url));
    if (url === RELEASE_API) return Response.json(release());
    if (String(url).endsWith('.txt')) return new Response(`${sha256}  ${fileName}`);
    return new Response(new Uint8Array(body), { headers });
  }) as typeof fetch;
  return { fetcher, requests };
}

test('checking never downloads; one main-process job survives absent renderer listeners', async () => fixture(async directory => {
  const fake = fakeFetch(); let installs = 0;
  const manager = new WboxUpdates({ directory, version: '0.7.9', fetch: fake.fetcher, changed() {}, install: async session => {
    installs++; assert.equal(session.phase, 'ready'); await verifyPackage(join(directory, fileName), session.info);
  } });
  await manager.initialize(); await manager.check();
  assert.equal(fake.requests.length, 2); assert.equal(manager.state.promptUpdate, true);
  await manager.markShown(); assert.equal(manager.state.promptUpdate, false);
  manager.start(); manager.start(); await manager.settled();
  assert.equal(installs, 1); assert.equal(fake.requests.length, 3);
  assert.equal(manager.state.downloadProgress, 1); assert.equal(manager.state.downloadBytes, bytes.length);
  assert.deepEqual(await readFile(join(directory, fileName)), bytes);
}));

test('size, digest and content-length mismatches never launch an installer', async () => {
  for (const fake of [fakeFetch(Buffer.from('truncated')), fakeFetch(Buffer.alloc(bytes.length, 42)), fakeFetch(bytes, { 'content-length': '999' })]) {
    await fixture(async directory => {
      let installs = 0;
      const manager = new WboxUpdates({ directory, version: '0.7.9', fetch: fake.fetcher, changed() {}, install: async () => { installs++; } });
      await manager.initialize(); await manager.check(); manager.start(); await manager.settled();
      assert.equal(installs, 0); assert.equal(manager.state.phase, 'error');
      await assert.rejects(readFile(join(directory, fileName))); await assert.rejects(readFile(join(directory, fileName + '.part')));
    });
  }
});

test('a failed handoff keeps the verified package retryable without re-downloading', async () => fixture(async directory => {
  const fake = fakeFetch(); let installs = 0;
  const manager = new WboxUpdates({ directory, version: '0.7.9', fetch: fake.fetcher, changed() {}, install: async () => {
    if (++installs === 1) throw new Error('synthetic handoff failure');
  } });
  await manager.initialize(); await manager.check(); manager.start(); await manager.settled();
  assert.equal(manager.state.error, 'synthetic handoff failure');
  manager.start(); await manager.settled();
  assert.equal(installs, 2); assert.equal(fake.requests.length, 3);
}));

test('restart recovers ready work, clears interrupted downloads and completed installer packages', async () => {
  for (const phase of ['ready', 'downloading', 'installing'] as const) await fixture(async directory => {
    await atomicJSON(join(directory, 'pending.json'), { info, phase, resume: false });
    await writeFile(join(directory, fileName), bytes); await writeFile(join(directory, fileName + '.part'), 'partial');
    if (phase === 'installing') await atomicJSON(join(directory, 'result.json'), { status: 'cancelled', exitCode: 2, finished: true });
    const manager = new WboxUpdates({ directory, version: '0.7.9', changed() {}, install: async () => {} });
    assert.equal(await manager.initialize(), true);
    if (phase === 'ready') { assert.equal(manager.state.info?.versionName, tag); assert.deepEqual(await readFile(join(directory, fileName)), bytes); }
    else { assert.equal(manager.state.info, null); await assert.rejects(readFile(join(directory, fileName))); await assert.rejects(readFile(join(directory, fileName + '.part'))); }
  });
});

test('persisted hostile filenames cannot escape the owned update directory', async () => fixture(async directory => {
  const outside = join(directory, 'keep.txt'); await writeFile(outside, 'keep');
  await atomicJSON(join(directory, 'pending.json'), { info: { ...info, fileName: '../keep.txt' }, phase: 'downloading' });
  const manager = new WboxUpdates({ directory, version: '0.7.9', changed() {}, install: async () => {} });
  await manager.initialize(); assert.equal(await readFile(outside, 'utf8'), 'keep');
}));

test('an unavailable updater directory cannot prevent normal application startup', async () => fixture(async directory => {
  const invalidDirectory = join(directory, 'not-a-directory'); await writeFile(invalidDirectory, 'file');
  const manager = new WboxUpdates({ directory: invalidDirectory, version: '0.7.9', changed() {}, install: async () => {} });
  assert.equal(await manager.initialize(), true);
  assert.equal(manager.state.supported, false); assert.match(manager.state.error!, /更新功能暂不可用/);
  await assert.rejects(manager.check()); assert.throws(() => manager.start());
}));

test('download speed returns to zero during a stall and the sampler stops after completion', async () => fixture(async directory => {
  let feed!: ReadableStreamDefaultController<Uint8Array>;
  const states: number[] = [];
  const fake = fakeFetch();
  const manager = new WboxUpdates({ directory, version: '0.7.9', changed: state => { states.push(state.downloadSpeed ?? 0); }, install: async () => {},
    fetch: (async (url: any, init: any) => String(url).endsWith('.exe') ? new Response(new ReadableStream<Uint8Array>({ start(controller) { feed = controller; controller.enqueue(new Uint8Array(bytes.subarray(0, 5))); } })) : fake.fetcher(url, init)) as typeof fetch });
  await manager.initialize(); await manager.check(); manager.start();
  await new Promise(resolve => setTimeout(resolve, 1200));
  assert.ok(states.some(speed => speed > 0)); assert.equal(manager.state.downloadSpeed, 0);
  feed.enqueue(new Uint8Array(bytes.subarray(5))); feed.close(); await manager.settled();
  const count = states.length;
  await new Promise(resolve => setTimeout(resolve, 650));
  assert.equal(states.length, count); assert.equal(manager.state.downloadSpeed, 0);
}));
