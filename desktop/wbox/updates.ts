import { createHash } from 'node:crypto';
import { execFile } from 'node:child_process';
import { promisify } from 'node:util';
import { createReadStream } from 'node:fs';
import { mkdir, open, readFile, rename, rm, stat, writeFile } from 'node:fs/promises';
import { basename, dirname, join, resolve } from 'node:path';
import { gt, valid } from 'semver';
import type { DesktopUpdateInfo, DesktopUpdatesState } from '../dashboard/src/app/desktop';

export const RELEASE_API = 'https://api.github.com/repos/muchaomuicho/Wbox/releases/latest';
const RELEASE_BASE = 'https://github.com/muchaomuicho/Wbox/releases/';
const MAX_PACKAGE_SIZE = 1024 * 1024 * 1024;
export interface VerifiedUpdate extends DesktopUpdateInfo { fileName: string; sha256: string }
export interface UpdateSession { info: VerifiedUpdate; phase: 'downloading' | 'ready' | 'installing'; resume: boolean; backendPID?: number }
type Fetch = typeof globalThis.fetch;
type Options = {
  directory: string; version: string; fetch?: Fetch;
  changed(state: DesktopUpdatesState): void;
  install(session: UpdateSession): Promise<void>;
};

export function officialAssetURL(value: unknown, tag: string, name: string): string {
  const expected = `${RELEASE_BASE}download/${tag}/${name}`;
  if (value !== expected) throw new Error('更新安装包地址不属于 Wbox 官方发布。');
  return expected;
}

export function parseRelease(value: any, current: string) {
  if (!value || value.draft !== false || value.prerelease !== false ||
      typeof value.tag_name !== 'string' || !/^v\d+\.\d+\.\d+$/.test(value.tag_name) || !valid(value.tag_name) ||
      value.html_url !== RELEASE_BASE + 'tag/' + value.tag_name || !Array.isArray(value.assets)) {
    throw new Error('GitHub 返回的稳定版信息无效。');
  }
  if (!gt(value.tag_name, current)) return null;
  const version = value.tag_name.slice(1);
  const fileName = `Wbox-${version}-windows-x64-setup.exe`;
  const checksumName = `SHA256SUMS-${version}.txt`;
  const binary = value.assets.filter((asset: any) => asset?.name === fileName);
  const checksums = value.assets.filter((asset: any) => asset?.name === checksumName);
  if (binary.length !== 1 || checksums.length !== 1 || !Number.isSafeInteger(binary[0].size) ||
      binary[0].size < 1 || binary[0].size > MAX_PACKAGE_SIZE ||
      !Number.isSafeInteger(checksums[0].size) || checksums[0].size < 1 || checksums[0].size > 65536) {
    throw new Error('发布缺少有效的 Windows 安装包或 SHA256 校验文件。');
  }
  return {
    info: { versionName: value.tag_name, releaseURL: value.html_url,
      downloadURL: officialAssetURL(binary[0].browser_download_url, value.tag_name, fileName),
      fileName, fileSize: binary[0].size, releaseNotes: String(value.body ?? '').slice(0, 40000), isPrerelease: false },
    checksumURL: officialAssetURL(checksums[0].browser_download_url, value.tag_name, checksumName),
    digest: binary[0].digest,
  };
}

export function parseChecksum(text: string, name: string): string {
  const matches = text.split(/\r?\n/).map(line => /^([a-fA-F0-9]{64})\s+\*?(.+?)\s*$/.exec(line.trim()))
    .filter(match => match?.[2] === name);
  if (matches.length !== 1) throw new Error('SHA256 校验文件中没有唯一的安装包记录。');
  return matches[0]![1].toLowerCase();
}

function trustedDownloadRedirect(value: string) {
  const url = new URL(value);
  return url.protocol === 'https:' && !url.username && !url.password && !url.port &&
    ['github.com', 'release-assets.githubusercontent.com', 'objects.githubusercontent.com', 'github-releases.githubusercontent.com'].includes(url.hostname);
}

export async function request(url: string, fetcher: Fetch, signal: AbortSignal) {
  let next = url;
  for (let redirects = 0; redirects <= 5; redirects++) {
    const response = await fetcher(next, { redirect: 'manual', signal, headers: {
      Accept: next === RELEASE_API ? 'application/vnd.github+json' : 'application/octet-stream',
      'User-Agent': 'Wbox-Updater',
    } });
    if ([301, 302, 303, 307, 308].includes(response.status)) {
      const location = response.headers.get('location');
      await response.body?.cancel();
      if (!location) throw new Error('更新下载重定向无效。');
      next = new URL(location, next).href;
      if (!trustedDownloadRedirect(next)) throw new Error('更新下载被重定向到非官方地址。');
      continue;
    }
    if (!response.ok || !response.body) { await response.body?.cancel(); throw new Error(`获取更新失败（HTTP ${response.status}）。`); }
    return response;
  }
  throw new Error('更新下载重定向次数过多。');
}

async function smallText(response: Response, limit: number) {
  const reader = response.body!.getReader();
  const chunks: Uint8Array[] = [];
  let size = 0;
  try {
    for (;;) {
      const { value, done } = await reader.read();
      if (done) break;
      size += value.length;
      if (size > limit) throw new Error('更新信息超过大小限制。');
      chunks.push(value);
    }
    return Buffer.concat(chunks).toString('utf8');
  } finally { await reader.cancel().catch(() => {}); }
}

export async function atomicJSON(path: string, value: unknown) {
  await writeFile(path + '.tmp', JSON.stringify(value), 'utf8');
  await rename(path + '.tmp', path);
}

export async function verifyPackage(path: string, info: VerifiedUpdate) {
  if ((await stat(path)).size !== info.fileSize) throw new Error('安装包大小不一致，请重新下载。');
  const hash = createHash('sha256');
  for await (const chunk of createReadStream(path)) hash.update(chunk);
  if (hash.digest('hex') !== info.sha256) throw new Error('安装包 SHA256 校验失败，请重新下载。');
}

export async function installerActive(directory: string) {
  try { const file = await open(join(directory, 'install.lock'), 'r+'); await file.close(); }
  catch (error: any) { if (error.code === 'EBUSY') return true; if (error.code !== 'ENOENT') throw error; }
  try {
    const result = JSON.parse(await readFile(join(directory, 'result.json'), 'utf8'));
    if (result.finished === true && ['success', 'cancelled', 'failed'].includes(result.status)) return false;
  } catch {}
  try {
    const record = JSON.parse(await readFile(join(directory, 'installer.json'), 'utf8'));
    if (!Number.isSafeInteger(record.pid) || record.pid < 1 || typeof record.path !== 'string' ||
        !/^\d+$/.test(record.startedAt) || dirname(resolve(record.path)).toLowerCase() !== resolve(directory).toLowerCase() ||
        !/^Wbox-\d+\.\d+\.\d+-windows-x64-setup\.exe$/.test(basename(record.path))) return false;
    // Query once only for an orphaned installer record; a PID alone can belong to a different process after reboot.
    const { stdout } = await promisify(execFile)(join(process.env.SystemRoot || 'C:\\Windows', 'System32/WindowsPowerShell/v1.0/powershell.exe'),
      ['-NoProfile', '-NonInteractive', '-WindowStyle', 'Hidden', '-Command',
        "$p=Get-Process -Id ([int]$env:WBOX_UPDATE_PROCESS_ID) -ErrorAction SilentlyContinue; if($p){ @{path=$p.Path;startedAt=[string]$p.StartTime.ToUniversalTime().Ticks}|ConvertTo-Json -Compress }"],
      { windowsHide: true, timeout: 5000, env: { ...process.env, WBOX_UPDATE_PROCESS_ID: String(record.pid) } });
    const processInfo = JSON.parse(stdout);
    return typeof processInfo.path === 'string' && resolve(processInfo.path).toLowerCase() === resolve(record.path).toLowerCase() && processInfo.startedAt === record.startedAt;
  } catch { /* A completed installer or a reboot releases the process. */ }
  return false;
}

export class WboxUpdates {
  private info: VerifiedUpdate | null = null;
  private session: UpdateSession | null = null;
  private job: Promise<void> | null = null;
  private prefs = { checkUpdateEnabled: false, lastShownVersion: '' };
  private fetcher: Fetch;
  readonly state: DesktopUpdatesState;
  constructor(private options: Options) {
    this.fetcher = options.fetch ?? globalThis.fetch;
    this.state = { supported: true, stableOnly: true, currentVersion: options.version, track: 'stable',
      checkUpdateEnabled: false, prompted: true, info: null, checking: false, downloading: false,
      installing: false, downloadProgress: 0, downloadBytes: 0, downloadSpeed: 0, phase: 'idle' };
  }
  private publish(patch: Partial<DesktopUpdatesState>) {
    Object.assign(this.state, patch);
    this.options.changed({ ...this.state });
  }
  private path(name: string) { return join(this.options.directory, name); }
  private async saveSession() { await atomicJSON(this.path('pending.json'), this.session); }
  async initialize() {
    try {
    await mkdir(this.options.directory, { recursive: true });
    try {
      const prefs = JSON.parse(await readFile(this.path('preferences.json'), 'utf8'));
      this.prefs = { checkUpdateEnabled: prefs.checkUpdateEnabled === true, lastShownVersion: String(prefs.lastShownVersion ?? '') };
    } catch { /* Missing preferences use manual checks. */ }
    this.state.checkUpdateEnabled = this.prefs.checkUpdateEnabled;
    if (await installerActive(this.options.directory)) return false;
    try {
      const session = JSON.parse(await readFile(this.path('pending.json'), 'utf8')) as UpdateSession;
      if (session && ['downloading', 'ready', 'installing'].includes(session.phase) &&
          /^v\d+\.\d+\.\d+$/.test(session.info.versionName) && valid(session.info.versionName) &&
          session.info.fileName === `Wbox-${session.info.versionName.slice(1)}-windows-x64-setup.exe` &&
          session.info.fileSize > 0 && session.info.fileSize <= MAX_PACKAGE_SIZE && /^[a-f0-9]{64}$/.test(session.info.sha256)) {
        officialAssetURL(session.info.downloadURL, session.info.versionName, session.info.fileName);
        this.session = session;
        let result: any;
        try { result = JSON.parse(await readFile(this.path('result.json'), 'utf8')); } catch {}
        if (result?.finished === true || session.phase === 'downloading' || !gt(session.info.versionName, this.options.version)) {
          await this.cleanup();
          this.state.notice = result?.status === 'success' ? '更新安装已完成。' : result?.status === 'cancelled' ? '已取消安装，可以重新检查更新。' : result ? '安装未完成，可以重新检查更新。' : session.phase === 'downloading' ? '上次下载已中断，请重新检查更新。' : undefined;
        } else {
          this.info = session.info;
          this.state.info = session.info;
          this.state.phase = 'available';
          this.state.notice = '上次更新未完成，点击更新可重新校验并安装。';
        }
      }
    } catch { this.state.notice = '上次更新记录无法读取，请重新检查更新。'; }
      return true;
    } catch (error) {
      this.publish({ supported: false, error: '更新功能暂不可用：' + (error instanceof Error ? error.message : String(error)) });
      return true;
    }
  }
  async check() {
    if (!this.state.supported) throw new Error(this.state.error || '更新功能暂不可用。');
    if (this.job || this.state.checking) return this.state.info;
    this.publish({ checking: true, error: undefined, notice: undefined });
    try {
      const signal = AbortSignal.timeout(30000);
      const release = parseRelease(JSON.parse(await smallText(await request(RELEASE_API, this.fetcher, signal), 1024 * 1024)), this.options.version);
      if (!release) { this.info = null; this.publish({ info: null, phase: 'idle', promptUpdate: false }); return null; }
      const sha256 = parseChecksum(await smallText(await request(release.checksumURL, this.fetcher, signal), 65536), release.info.fileName);
      if (release.digest && release.digest !== 'sha256:' + sha256) throw new Error('GitHub 资产摘要与 SHA256 文件不一致。');
      this.info = { ...release.info, sha256 };
      this.publish({ info: this.info, phase: 'available', promptUpdate: this.prefs.lastShownVersion !== this.info.versionName });
      return this.info;
    } catch (error) {
      this.publish({ error: error instanceof Error ? error.message : String(error) });
      throw error;
    } finally { this.publish({ checking: false }); }
  }
  async setCheckUpdateEnabled(value: boolean) {
    if (typeof value !== 'boolean') throw new Error('无效更新设置。');
    this.prefs.checkUpdateEnabled = value;
    await atomicJSON(this.path('preferences.json'), this.prefs);
    this.publish({ checkUpdateEnabled: value });
  }
  async markShown() {
    this.prefs.lastShownVersion = this.info?.versionName ?? '';
    await atomicJSON(this.path('preferences.json'), this.prefs);
    this.publish({ promptUpdate: false });
  }
  start() {
    if (!this.state.supported) throw new Error(this.state.error || '更新功能暂不可用。');
    if (this.job) return 'started' as const;
    if (!this.info || this.state.checking) throw new Error('请先检查更新。');
    const info = this.info;
    this.job = this.downloadAndInstall(info).catch(error => {
      this.publish({ phase: 'error', error: error instanceof Error ? error.message : String(error), downloading: false, installing: false, downloadSpeed: 0 });
    }).finally(() => { this.job = null; });
    return 'started' as const;
  }
  async settled() { await this.job; }
  private async downloadAndInstall(info: VerifiedUpdate) {
    if (await installerActive(this.options.directory)) throw new Error('安装器仍在运行，请先完成或取消安装。');
    this.publish({ downloading: true, phase: 'downloading', downloadProgress: 0, downloadBytes: 0, downloadSpeed: 0, error: undefined, notice: undefined });
    if (this.session && this.session.info.fileName !== info.fileName) await this.cleanup();
    this.session = { info, phase: 'downloading', resume: false };
    await this.saveSession();
    const target = this.path(info.fileName);
    let cached = false;
    try { await verifyPackage(target, info); cached = true; } catch { await rm(target, { force: true }); }
    if (!cached) {
      const controller = new AbortController();
      let idleTimer: ReturnType<typeof setTimeout>;
      const touch = () => { clearTimeout(idleTimer); idleTimer = setTimeout(() => controller.abort(new Error('下载长时间没有响应，请重试。')), 60000); };
      let file: Awaited<ReturnType<typeof open>> | undefined;
      try {
        touch();
        const response = await request(info.downloadURL, this.fetcher, controller.signal);
        const advertised = response.headers.get('content-length');
        if (advertised && Number(advertised) !== info.fileSize) { await response.body!.cancel(); throw new Error('下载大小与发布记录不一致。'); }
        file = await open(target + '.part', 'w');
        const reader = response.body!.getReader();
        let bytes = 0, sampledBytes = 0, sampledAt = Date.now();
        const sample = () => {
          const now = Date.now();
          this.publish({ downloadBytes: bytes, downloadProgress: bytes / info.fileSize, downloadSpeed: (bytes - sampledBytes) * 1000 / Math.max(1, now - sampledAt) });
          sampledBytes = bytes; sampledAt = now;
        };
        const speedTimer = setInterval(sample, 500);
        speedTimer.unref();
        const hash = createHash('sha256');
        try {
          for (;;) {
            const { done, value } = await reader.read();
            if (done) break;
            touch(); bytes += value.length;
            if (bytes > info.fileSize) throw new Error('安装包超过发布记录的大小。');
            hash.update(value);
            let offset = 0;
            while (offset < value.length) {
              const written = await file.write(value, offset, value.length - offset);
              if (written.bytesWritten === 0) throw new Error('无法写入更新安装包。');
              offset += written.bytesWritten;
            }
            if (bytes === info.fileSize) sample();
          }
        } finally { clearInterval(speedTimer); await reader.cancel().catch(() => {}); }
        this.publish({ phase: 'verifying', downloadSpeed: 0 });
        if (bytes !== info.fileSize || hash.digest('hex') !== info.sha256) throw new Error('安装包大小或 SHA256 校验失败，请重试。');
        await file.sync(); await file.close(); file = undefined;
        await rename(target + '.part', target);
      } finally { clearTimeout(idleTimer!); await file?.close(); await rm(target + '.part', { force: true }); }
    }
    this.session.phase = 'ready'; await this.saveSession();
    this.publish({ downloading: false, installing: true, phase: 'installing', downloadProgress: 1, downloadBytes: info.fileSize, downloadSpeed: 0 });
    await this.options.install(this.session);
  }
  private async cleanup() {
    if (!this.session) return;
    // Remove only this updater's validated filenames, never the directory recursively.
    for (const name of [this.session.info.fileName, this.session.info.fileName + '.part']) {
      try { await rm(this.path(name), { force: true }); }
      catch { this.state.notice = '安装包仍被占用，下次启动时会继续清理。'; return; }
    }
    for (const name of ['pending.json', 'result.json', 'ready.json', 'installer.json', 'install.lock']) await rm(this.path(name), { force: true }).catch(() => {});
    this.session = null;
  }
}
