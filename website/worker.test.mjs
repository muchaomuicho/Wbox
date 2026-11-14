import { test } from 'node:test';
import assert from 'node:assert/strict';
import worker, { publicRelease } from './worker.js';
import fallback from './release-fallback.json' with { type: 'json' };

test('release endpoint caches public metadata and falls back during GitHub outages', async () => {
  const originalFetch = globalThis.fetch;
  const savedCaches = globalThis.caches;
  let cached;
  let calls = 0;
  const pending = [];
  const ctx = { waitUntil(promise) { pending.push(promise); } };
  globalThis.caches = { default: {
    async match() { return cached?.clone(); },
    async put(key, response) { cached = response; },
  } };
  const request = new Request('https://wbox.wucnebssss.hidns.co/api/release');
  try {
    globalThis.fetch = async () => { calls++; return Response.json({ ...fallback, internal: 'excluded' }); };
    const first = await worker.fetch(request, {}, ctx);
    assert.deepEqual(await first.json(), publicRelease(fallback));
    await Promise.all(pending);
    const second = await worker.fetch(request, {}, ctx);
    assert.equal(second.status, 200);
    assert.equal(calls, 1);
    const head = await worker.fetch(new Request(request.url, { method: 'HEAD' }), {}, ctx);
    assert.equal(await head.text(), '');
    assert.equal(head.headers.get('content-type'), 'application/json; charset=utf-8');
    cached = undefined;
    globalThis.fetch = async () => { throw new Error('offline'); };
    const offline = await worker.fetch(request, {}, ctx);
    assert.equal(offline.headers.get('X-Wbox-Release'), 'fallback');
    assert.equal((await offline.json()).tag_name, fallback.tag_name);
    assert.equal((await worker.fetch(new Request(request.url, { method: 'POST' }), {}, ctx)).status, 405);
    assert.equal((await worker.fetch(new Request('https://example.com/api/unknown'), {}, ctx)).status, 404);
    const asset = await worker.fetch(new Request('https://example.com/styles.css'), {
      ASSETS: { fetch: () => new Response('asset') },
    }, ctx);
    assert.equal(await asset.text(), 'asset');
  } finally {
    globalThis.fetch = originalFetch;
    globalThis.caches = savedCaches;
  }
});

test('only public releases and repository download links are returned', () => {
  assert.throws(() => publicRelease({ ...fallback, draft: true }));
  assert.throws(() => publicRelease({ ...fallback, prerelease: true }));
  assert.throws(() => publicRelease({ ...fallback, html_url: 'https://example.com' }));
  const filtered = publicRelease({ ...fallback, assets: [{ name: 'bad', browser_download_url: 'javascript:alert(1)' }] });
  assert.deepEqual(filtered.assets, []);
});
