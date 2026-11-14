import fallback from './release-fallback.json' with { type: 'json' };

const RELEASE_API = 'https://api.github.com/repos/muchaomuicho/Wbox/releases/latest';
const RELEASE_BASE = 'https://github.com/muchaomuicho/Wbox/releases/';
const headers = {
  'Content-Type': 'application/json; charset=utf-8',
  'Cache-Control': 'public, max-age=60, s-maxage=300',
  'X-Content-Type-Options': 'nosniff',
};

export function publicRelease(release) {
  if (!/^v\d+\.\d+\.\d+$/.test(release?.tag_name) ||
      !release.html_url?.startsWith(RELEASE_BASE + 'tag/') ||
      release.draft || release.prerelease || !Array.isArray(release.assets)) {
    throw new Error('Invalid public release');
  }
  return {
    tag_name: release.tag_name,
    published_at: release.published_at,
    html_url: release.html_url,
    body: String(release.body ?? '').slice(0, 20000),
    assets: release.assets
      .filter(asset => asset.browser_download_url?.startsWith(RELEASE_BASE + 'download/'))
      .map(({ name, size, browser_download_url }) => ({ name, size, browser_download_url })),
  };
}

export default {
  async fetch(request, env, ctx) {
    const url = new URL(request.url);
    if (!url.pathname.startsWith('/api/')) return env.ASSETS.fetch(request);
    if (url.pathname !== '/api/release') return new Response('Not found', { status: 404 });
    if (request.method !== 'GET' && request.method !== 'HEAD') {
      return new Response('Method not allowed', { status: 405, headers: { Allow: 'GET, HEAD' } });
    }
    const key = new Request(new URL('/api/release', url.origin));
    const cache = caches.default;
    let response = await cache.match(key);
    if (!response) {
      try {
        const upstream = await fetch(RELEASE_API, {
          headers: {
            Accept: 'application/vnd.github+json',
            'User-Agent': 'Wbox-Website',
            'X-GitHub-Api-Version': '2022-11-28',
          },
          signal: AbortSignal.timeout(6000),
        });
        if (!upstream.ok) throw new Error('Release request failed');
        response = Response.json(publicRelease(await upstream.json()), { headers });
        ctx.waitUntil(cache.put(key, response.clone()));
      } catch {
        response = Response.json(publicRelease(fallback), {
          headers: { ...headers, 'Cache-Control': 'public, max-age=30', 'X-Wbox-Release': 'fallback' },
        });
      }
    }
    return request.method === 'HEAD' ? new Response(null, { status: response.status, headers: response.headers }) : response;
  },
};
