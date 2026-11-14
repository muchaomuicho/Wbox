(() => {
  'use strict';

  const root = document.documentElement;
  const latestURL = 'https://github.com/muchaomuicho/Wbox/releases/latest';
  const darkPreference = window.matchMedia('(prefers-color-scheme: dark)');
  const reducedMotion = window.matchMedia('(prefers-reduced-motion: reduce)');
  const select = (selector) => document.querySelector(selector);
  const selectAll = (selector) => [...document.querySelectorAll(selector)];
  const readPreference = (key, allowed, fallback) => {
    try { const value = localStorage.getItem(key); return allowed.includes(value) ? value : fallback; }
    catch { return fallback; }
  };
  const savePreference = (key, value) => { try { localStorage.setItem(key, value); } catch { /* Storage is optional. */ } };

  // The site has its own preferences; these never touch the client's settings.
  let theme = readPreference('wbox-site-theme', ['system', 'light', 'dark'], 'system');
  let accent = readPreference('wbox-site-accent', ['mono', 'blue', 'pink'], 'blue');
  const themeButton = select('#theme-toggle');
  const themeMenu = select('#theme-menu');
  function applyTheme(value) {
    theme = value;
    if (value === 'system') delete root.dataset.theme;
    else root.dataset.theme = value;
    selectAll('[data-theme-choice]').forEach((button) => button.setAttribute('aria-checked', String(button.dataset.themeChoice === value)));
    themeButton.querySelector('use').setAttribute('href', value === 'system' ? '#i-theme' : value === 'dark' ? '#i-moon' : '#i-sun');
    const label = { system: '跟随系统', light: '浅色', dark: '深色' }[value];
    themeButton.setAttribute('aria-label', `页面外观：${label}，点击切换`);
    const isDark = value === 'dark' || (value === 'system' && darkPreference.matches);
    select('meta[name="theme-color"]').setAttribute('content', isDark ? '#1c221f' : '#f5f3ed');
  }
  function closeTheme(restoreFocus = false) {
    themeMenu.hidden = true;
    themeButton.setAttribute('aria-expanded', 'false');
    if (restoreFocus) themeButton.focus();
  }
  themeButton.addEventListener('click', () => {
    const open = themeMenu.hidden;
    themeMenu.hidden = !open;
    themeButton.setAttribute('aria-expanded', String(open));
    if (open) themeMenu.querySelector('[aria-checked="true"]').focus();
  });
  selectAll('[data-theme-choice]').forEach((button) => button.addEventListener('click', () => {
    applyTheme(button.dataset.themeChoice);
    savePreference('wbox-site-theme', theme);
    closeTheme(true);
  }));
  themeMenu.addEventListener('keydown', (event) => {
    if (!['ArrowDown', 'ArrowUp', 'Home', 'End'].includes(event.key)) return;
    event.preventDefault();
    const buttons = [...themeMenu.querySelectorAll('button')];
    const current = buttons.indexOf(document.activeElement);
    const index = event.key === 'Home' ? 0 : event.key === 'End' ? buttons.length - 1 : (current + (event.key === 'ArrowDown' ? 1 : -1) + buttons.length) % buttons.length;
    buttons[index].focus();
  });
  document.addEventListener('click', (event) => { if (!event.target.closest('.theme-control')) closeTheme(); });
  darkPreference.addEventListener('change', () => { if (theme === 'system') applyTheme('system'); });
  applyTheme(theme);

  function applyAccent(value) {
    accent = value;
    root.dataset.accent = value;
    selectAll('[data-accent]').forEach((button) => {
      if (button.tagName === 'BUTTON') button.setAttribute('aria-pressed', String(button.dataset.accent === value));
    });
    select('#color-hint').textContent = `正在预览${{ mono: '黑白', blue: '雾蓝', pink: '柔粉' }[value]}配色`;
  }
  selectAll('button[data-accent]').forEach((button) => button.addEventListener('click', () => {
    applyAccent(button.dataset.accent);
    savePreference('wbox-site-accent', accent);
  }));
  applyAccent(accent);

  const mobileButton = select('#mobile-menu-toggle');
  const mobileNav = select('#mobile-nav');
  function setMobileMenu(open, restoreFocus = false) {
    mobileNav.hidden = !open;
    mobileButton.setAttribute('aria-expanded', String(open));
    mobileButton.setAttribute('aria-label', open ? '关闭导航' : '打开导航');
    mobileButton.querySelector('use').setAttribute('href', open ? '#i-close' : '#i-menu');
    if (restoreFocus) mobileButton.focus();
  }
  mobileButton.addEventListener('click', () => setMobileMenu(mobileNav.hidden));
  mobileNav.addEventListener('click', (event) => { if (event.target.closest('a')) setMobileMenu(false); });
  document.addEventListener('keydown', (event) => {
    if (event.key !== 'Escape') return;
    if (!themeMenu.hidden) closeTheme(true);
    else if (!mobileNav.hidden) setMobileMenu(false, true);
  });
  window.matchMedia('(min-width: 561px)').addEventListener('change', (event) => { if (event.matches) setMobileMenu(false); });

  let platform = /Android/i.test(navigator.userAgent) ? 'android' : 'windows';
  let assets = {};
  function applyPlatform(value) {
    platform = value;
    selectAll('[data-platform]').forEach((button) => {
      if (button.tagName === 'BUTTON') button.setAttribute('aria-pressed', String(button.dataset.platform === value));
    });
    select('#showcase').dataset.platform = value;
    select('#hero-download-label').textContent = value === 'windows' ? '下载 Windows 版' : '下载 Android 版';
    select('#hero-platform-icon use').setAttribute('href', value === 'windows' ? '#i-windows' : '#i-android');
    const link = select('#hero-download');
    link.href = assets[value]?.url || '#download';
    if (assets[value]) { link.target = '_blank'; link.rel = 'noopener noreferrer'; }
    else { link.removeAttribute('target'); link.removeAttribute('rel'); }
  }
  selectAll('button[data-platform]').forEach((button) => button.addEventListener('click', () => applyPlatform(button.dataset.platform)));
  applyPlatform(platform);

  const workspaceTabs = selectAll('[data-workspace]');
  const workspaceImage = select('#workspace-image');
  const workspaceData = {
    config: { image: '/assets/windows-configurations.webp', alt: 'Windows 配置工作区的配置卡片和订阅信息', caption: 'CONFIGURATION WORKSPACE' },
    nodes: { image: '/assets/windows-nodes.webp', alt: 'Windows 节点工作区的节点列表与右键操作菜单', caption: 'NODE WORKSPACE' },
  };
  function applyWorkspace(value, focus = false) {
    workspaceTabs.forEach((button) => {
      const selected = button.dataset.workspace === value;
      button.setAttribute('aria-selected', String(selected));
      button.tabIndex = selected ? 0 : -1;
      select(`#workspace-${button.dataset.workspace}`).hidden = !selected;
      if (selected && focus) button.focus();
    });
    const data = workspaceData[value];
    workspaceImage.classList.remove('changing');
    workspaceImage.src = data.image;
    workspaceImage.alt = data.alt;
    select('#workspace-caption').textContent = data.caption;
    if (!reducedMotion.matches) requestAnimationFrame(() => workspaceImage.classList.add('changing'));
  }
  workspaceTabs.forEach((button, index) => {
    button.addEventListener('click', () => applyWorkspace(button.dataset.workspace));
    button.addEventListener('keydown', (event) => {
      if (!['ArrowLeft', 'ArrowRight', 'Home', 'End'].includes(event.key)) return;
      event.preventDefault();
      const next = event.key === 'Home' ? 0 : event.key === 'End' ? workspaceTabs.length - 1 : (index + (event.key === 'ArrowRight' ? 1 : -1) + workspaceTabs.length) % workspaceTabs.length;
      applyWorkspace(workspaceTabs[next].dataset.workspace, true);
    });
  });

  // Native details remains usable without JavaScript and on assistive devices.
  const questions = selectAll('.faq-list details');
  questions.forEach((question) => question.addEventListener('toggle', () => {
    if (question.open) questions.forEach((other) => { if (other !== question) other.open = false; });
  }));

  const reveals = selectAll('.reveal');
  if ('IntersectionObserver' in window && !reducedMotion.matches) {
    const observer = new IntersectionObserver((entries) => {
      entries.forEach((entry) => {
        if (entry.isIntersecting) { entry.target.classList.add('visible'); observer.unobserve(entry.target); }
      });
    }, { threshold: .07, rootMargin: '0px 0px -20px 0px' });
    root.classList.add('js');
    reveals.forEach((element) => observer.observe(element));
    reducedMotion.addEventListener('change', (event) => {
      if (event.matches) { observer.disconnect(); reveals.forEach((element) => element.classList.add('visible')); }
    });
  }

  function githubURL(value, asset = false) {
    if (typeof value !== 'string') return null;
    try {
      const url = new URL(value);
      const prefix = asset ? '/muchaomuicho/Wbox/releases/download/' : '/muchaomuicho/Wbox/releases/';
      return url.protocol === 'https:' && url.hostname === 'github.com' && !url.username && !url.password && url.pathname.startsWith(prefix) ? url.href : null;
    } catch { return null; }
  }
  function fileSize(size) {
    return Number.isFinite(size) && size > 0 ? `${(size / (1024 * 1024)).toFixed(1)} MB` : '';
  }
  async function loadRelease() {
    const controller = new AbortController();
    const timeout = setTimeout(() => controller.abort(), 6500);
    try {
      const response = await fetch('/api/release', { headers: { Accept: 'application/json' }, signal: controller.signal, credentials: 'same-origin' });
      if (!response.ok) throw new Error('Release unavailable');
      const release = await response.json();
      if (!release || typeof release.tag_name !== 'string' || !release.tag_name.trim() || !Array.isArray(release.assets)) throw new Error('Invalid release');
      const version = release.tag_name.trim().slice(0, 64);
      const releaseURL = githubURL(release.html_url) || latestURL;
      selectAll('[data-release-link]').forEach((link) => { link.href = releaseURL; });
      select('[data-release-badge]').textContent = `${version} 已发布 · 查看更新`;
      const date = new Date(release.published_at);
      const published = Number.isNaN(date.getTime()) ? '最新稳定版本' : `${date.getFullYear()}.${String(date.getMonth() + 1).padStart(2, '0')}.${String(date.getDate()).padStart(2, '0')}`;
      select('#release-status').textContent = `${version} · ${published}`;
      const candidates = release.assets.filter((asset) => asset && typeof asset.name === 'string' && githubURL(asset.browser_download_url, true));
      const findAsset = (pattern) => candidates.find((asset) => pattern.test(asset.name));
      const binaries = {
        windows: findAsset(/windows.*x64.*\.exe$/i),
        android: findAsset(/android.*(?:arm64|aarch64).*\.apk$/i),
      };
      for (const [name, asset] of Object.entries(binaries)) {
        if (!asset) continue;
        const url = githubURL(asset.browser_download_url, true);
        assets[name] = { url, name: asset.name };
        select(`[data-download="${name}"]`).href = url;
        const extension = name === 'windows' ? 'EXE' : 'APK';
        select(`[data-asset-meta="${name}"]`).textContent = [version, extension, fileSize(Number(asset.size))].filter(Boolean).join(' · ');
      }
      const checksum = findAsset(/^SHA256.*\.txt$/i);
      selectAll('[data-checksum-link]').forEach((link) => { link.href = checksum ? githubURL(checksum.browser_download_url, true) : releaseURL; });
      applyPlatform(platform);
      if (!binaries.windows || !binaries.android) {
        select('#release-fallback').textContent = '部分安装包信息暂不可用；对应下载按钮会打开 GitHub 发布页，可在那里选择安装包。';
        select('#release-fallback').hidden = false;
      }
    } catch {
      select('#release-status').textContent = '最新版本请查看 GitHub 发布页';
      select('#release-fallback').hidden = false;
    } finally { clearTimeout(timeout); }
  }
  loadRelease();
})();
