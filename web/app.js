(() => {
  'use strict';

  const TRACKING_PARAMS = [
    'utm_source', 'utm_medium', 'utm_campaign', 'utm_term', 'utm_content',
    'fbclid', 'gclid', 'dclid', 'msclkid', 'yclid', 'mc_cid', 'mc_eid',
    'igshid', 'ref_src', 'ref_url', '_hsenc', '_hsmi', 'si'
  ];

  const SEARCH_ENGINES = {
    ddg: {
      surface: 'https://duckduckgo.com/?q=',
      onion: 'https://duckduckgogg42xjoc72x3sjasowoarfbgcmvfimaftt6twagswzczad.onion/?q='
    },
    startpage: {
      surface: 'https://www.startpage.com/do/search?query=',
      onion: 'https://www.startpage.com/do/search?query='
    },
    brave: {
      surface: 'https://search.brave.com/search?q=',
      onion: 'https://search.brave4u7jddbv7cyviptqjc7jusxh72uik7zt6adtckl5f4nwy2v72qd.onion/search?q='
    }
  };

  let currentMode = 'surface';
  let sanitizedUrl = '';
  let deferredPrompt = null;

  const btnSurfaceMode = document.getElementById('btnSurfaceMode');
  const btnTorMode = document.getElementById('btnTorMode');
  const modeBadge = document.getElementById('modeBadge');
  const modeBadgeText = document.getElementById('modeBadgeText');
  const onionAlert = document.getElementById('onionAlert');
  const searchForm = document.getElementById('searchForm');
  const searchEngine = document.getElementById('searchEngine');
  const omniboxInput = document.getElementById('omniboxInput');
  const auditResults = document.getElementById('auditResults');
  const btnRunAudit = document.getElementById('btnRunAudit');
  const dirtyUrlInput = document.getElementById('dirtyUrlInput');
  const cleanUrlOutput = document.getElementById('cleanUrlOutput');
  const btnCleanUrl = document.getElementById('btnCleanUrl');
  const btnCopyClean = document.getElementById('btnCopyClean');
  const btnOpenClean = document.getElementById('btnOpenClean');
  const githubRepoInput = document.getElementById('githubRepoInput');
  const btnFetchRelease = document.getElementById('btnFetchRelease');
  const releaseStatus = document.getElementById('releaseStatus');
  const btnInstallPwa = document.getElementById('btnInstallPwa');

  function setMode(mode) {
    currentMode = mode;
    const isTor = mode === 'tor';
    document.documentElement.style.setProperty('--accent', isTor ? 'var(--amethyst)' : 'var(--cyan)');
    btnSurfaceMode.className = isTor ? 'mode-btn' : 'mode-btn active-surface';
    btnTorMode.className = isTor ? 'mode-btn active-tor' : 'mode-btn';
    modeBadge.className = isTor ? 'hero-badge tor' : 'hero-badge';
    modeBadgeText.textContent = isTor
      ? 'TOR .ONION MODE (REQUIRES ANDROID APK / ORBOT)'
      : 'SURFACE WEB PRIVATE LAUNCHER';
    onionAlert.classList.toggle('hidden', !isTor);
  }

  btnSurfaceMode.addEventListener('click', () => setMode('surface'));
  btnTorMode.addEventListener('click', () => setMode('tor'));

  searchForm.addEventListener('submit', (e) => {
    e.preventDefault();
    const raw = omniboxInput.value.trim();
    if (!raw) return;

    const lower = raw.toLowerCase();
    if (lower.startsWith('javascript:') || lower.startsWith('file:') || lower.startsWith('data:')) {
      return;
    }

    const looksLikeDomain = !raw.includes(' ') && (raw.includes('.') || lower.startsWith('http://') || lower.startsWith('https://'));
    let target;
    if (looksLikeDomain) {
      if (lower.includes('.onion')) {
        setMode('tor');
        target = lower.startsWith('http') ? raw : `http://${raw}`;
        alert('Detected .onion address. Standard browsers cannot resolve .onion without Tor. Open this URL inside OnionShield Android (with Orbot) or Tor Browser:\n\n' + target);
        return;
      }
      target = lower.startsWith('http://') ? `https://${raw.slice(7)}` : (lower.startsWith('https://') ? raw : `https://${raw}`);
    } else {
      const engine = SEARCH_ENGINES[searchEngine.value] || SEARCH_ENGINES.ddg;
      target = `${engine.surface}${encodeURIComponent(raw)}`;
    }
    window.open(target, '_blank', 'noopener,noreferrer');
  });

  function sanitizeUrlString(raw) {
    const trimmed = raw.trim();
    if (!trimmed) return '';
    let candidate = trimmed;
    if (!/^https?:\/\//i.test(candidate)) {
      candidate = `https://${candidate}`;
    }
    const parsed = new URL(candidate);
    if (parsed.protocol === 'http:' && !parsed.hostname.endsWith('.onion') && parsed.hostname !== 'localhost') {
      parsed.protocol = 'https:';
    }
    TRACKING_PARAMS.forEach((param) => parsed.searchParams.delete(param));
    return parsed.toString();
  }

  btnCleanUrl.addEventListener('click', () => {
    try {
      sanitizedUrl = sanitizeUrlString(dirtyUrlInput.value);
      cleanUrlOutput.textContent = sanitizedUrl || 'Please enter a valid URL.';
    } catch {
      sanitizedUrl = '';
      cleanUrlOutput.textContent = 'Invalid URL format.';
    }
  });

  btnCopyClean.addEventListener('click', async () => {
    if (!sanitizedUrl) return;
    await navigator.clipboard.writeText(sanitizedUrl);
    btnCopyClean.textContent = 'Copied!';
    setTimeout(() => { btnCopyClean.textContent = 'Copy'; }, 1500);
  });

  btnOpenClean.addEventListener('click', () => {
    if (!sanitizedUrl) return;
    window.open(sanitizedUrl, '_blank', 'noopener,noreferrer');
  });

  function computeCanvasHash() {
    try {
      const canvas = document.createElement('canvas');
      canvas.width = 180;
      canvas.height = 40;
      const ctx = canvas.getContext('2d');
      ctx.fillStyle = '#0B0F19';
      ctx.fillRect(0, 0, 180, 40);
      ctx.fillStyle = '#00E5D1';
      ctx.font = '14px monospace';
      ctx.fillText('OnionShield-Audit', 10, 24);
      const data = canvas.toDataURL();
      let hash = 0;
      for (let i = 0; i < data.length; i++) {
        hash = ((hash << 5) - hash + data.charCodeAt(i)) | 0;
      }
      return (hash >>> 0).toString(16).toUpperCase();
    } catch {
      return 'Blocked / Protected';
    }
  }

  function runPrivacyAudit() {
    const isHttps = window.location.protocol === 'https:';
    const hasRtc = Boolean(window.RTCPeerConnection || window.webkitRTCPeerConnection);
    const dnt = navigator.doNotTrack === '1' ? 'Enabled (1)' : 'Not Set';
    const cores = navigator.hardwareConcurrency || 'Hidden';
    const canvasHash = computeCanvasHash();

    let webglRenderer = 'Unavailable';
    try {
      const gl = document.createElement('canvas').getContext('webgl');
      if (gl) {
        const dbg = gl.getExtension('WEBGL_debug_renderer_info');
        webglRenderer = dbg ? gl.getParameter(dbg.UNMASKED_RENDERER_WEBGL) : 'Masked';
      }
    } catch {
      webglRenderer = 'Blocked';
    }

    const rows = [
      { label: 'Transport Security', val: isHttps ? 'HTTPS Active' : 'Insecure HTTP', good: isHttps },
      { label: 'WebRTC API Exposure', val: hasRtc ? 'Exposed in Browser' : 'Blocked / Absent', good: !hasRtc },
      { label: 'Do Not Track Header', val: dnt, good: navigator.doNotTrack === '1' },
      { label: 'Canvas Fingerprint', val: `#${canvasHash}`, good: false },
      { label: 'WebGL Renderer', val: String(webglRenderer), good: webglRenderer === 'Blocked' || webglRenderer === 'Masked' },
      { label: 'CPU Concurrency', val: `${cores} threads`, good: cores === 'Hidden' },
    ];

    auditResults.innerHTML = '';
    rows.forEach((row) => {
      const div = document.createElement('div');
      div.className = 'audit-item';
      div.innerHTML = `
        <span>${row.label}</span>
        <span class="audit-val ${row.good ? 'tag-good' : 'tag-warn'}">${row.val}</span>
      `;
      auditResults.appendChild(div);
    });
  }

  btnRunAudit.addEventListener('click', runPrivacyAudit);

  const savedRepo = localStorage.getItem('onionshield_github_repo');
  if (savedRepo) {
    githubRepoInput.value = savedRepo;
  }

  btnFetchRelease.addEventListener('click', async () => {
    const repo = githubRepoInput.value.trim().replace(/^https?:\/\/github\.com\//i, '').replace(/\/$/, '');
    if (!repo.includes('/')) {
      releaseStatus.textContent = 'Please enter a valid GitHub repository in owner/repo format.';
      return;
    }
    localStorage.setItem('onionshield_github_repo', repo);
    releaseStatus.textContent = `Fetching latest release from github.com/${repo}…`;

    try {
      const res = await fetch(`https://api.github.com/repos/${repo}/releases/latest`);
      if (!res.ok) {
        throw new Error(`HTTP ${res.status} — ensure a release tag (e.g. v1.0.0) has been published.`);
      }
      const data = await res.json();
      const apkAsset = (data.assets || []).find((a) => a.name.endsWith('.apk'));
      const shaAsset = (data.assets || []).find((a) => a.name.endsWith('.sha256'));

      if (!apkAsset) {
        releaseStatus.innerHTML = `Release <strong>${data.tag_name}</strong> found, but no <code>.apk</code> asset is attached yet.`;
        return;
      }

      releaseStatus.innerHTML = `
        <div><strong>Latest Release:</strong> ${data.tag_name} (${(apkAsset.size / (1024 * 1024)).toFixed(2)} MB)</div>
        <a class="apk-download-btn" href="${apkAsset.browser_download_url}" rel="noopener">Download ${apkAsset.name}</a>
        ${shaAsset ? `<a class="secondary-btn" style="display:inline-block;text-decoration:none;margin-top:10px;" href="${shaAsset.browser_download_url}" rel="noopener">SHA-256</a>` : ''}
      `;
    } catch (err) {
      releaseStatus.textContent = `Could not fetch release: ${err.message}`;
    }
  });

  window.addEventListener('beforeinstallprompt', (e) => {
    e.preventDefault();
    deferredPrompt = e;
    btnInstallPwa.classList.remove('hidden');
  });

  btnInstallPwa.addEventListener('click', async () => {
    if (!deferredPrompt) return;
    deferredPrompt.prompt();
    await deferredPrompt.userChoice;
    deferredPrompt = null;
    btnInstallPwa.classList.add('hidden');
  });

  if ('serviceWorker' in navigator) {
    navigator.serviceWorker.register('/sw.js').catch(() => {});
  }

  runPrivacyAudit();
})();
