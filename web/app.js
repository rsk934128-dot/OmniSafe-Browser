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
  const apkQrContainer = document.getElementById('apkQrContainer');
  const apkQrCanvas = document.getElementById('apkQrCanvas');
  const apkQrUrlLabel = document.getElementById('apkQrUrlLabel');
  const btnCopyApkLink = document.getElementById('btnCopyApkLink');
  const btnDownloadQr = document.getElementById('btnDownloadQr');
  const btnInstallPwa = document.getElementById('btnInstallPwa');
  let latestApkUrl = '';
  let latestReleaseTag = 'latest';

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

  const DEFAULT_GITHUB_REPO = 'rsk934128-dot/OmniSafe-Browser';
  const savedRepo = localStorage.getItem('onionshield_github_repo') || DEFAULT_GITHUB_REPO;
  githubRepoInput.value = savedRepo;

  async function fetchLatestRelease(rawRepo, savePreference = false) {
    const repo = String(rawRepo || '')
      .trim()
      .replace(/^https?:\/\/github\.com\//i, '')
      .replace(/\/$/, '');

    if (!repo.includes('/')) {
      releaseStatus.textContent = 'Please enter a valid GitHub repository in owner/repo format.';
      apkQrContainer.classList.add('hidden');
      return;
    }

    if (savePreference) {
      localStorage.setItem('onionshield_github_repo', repo);
    }

    releaseStatus.textContent = `Fetching latest release from github.com/${repo}…`;
    apkQrContainer.classList.add('hidden');

    try {
      const res = await fetch(`https://api.github.com/repos/${repo}/releases/latest`);
      if (!res.ok) {
        if (res.status === 404) {
          throw new Error('No published GitHub Release found yet. Trigger "Release Android APK" in GitHub Actions or push a tag (e.g. v1.0.0).');
        }
        throw new Error(`HTTP ${res.status} — could not query GitHub Releases.`);
      }

      const data = await res.json();
      const apkAsset = (data.assets || []).find((a) => a.name && a.name.endsWith('.apk'));
      const shaAsset = (data.assets || []).find((a) => a.name && a.name.endsWith('.sha256'));

      if (!apkAsset) {
        releaseStatus.textContent = `Release ${data.tag_name || 'latest'} found on ${repo}, but no .apk asset is attached yet.`;
        return;
      }

      latestApkUrl = apkAsset.browser_download_url;
      latestReleaseTag = data.tag_name || 'latest';

      releaseStatus.textContent = '';

      const metaDiv = document.createElement('div');
      const strong = document.createElement('strong');
      strong.textContent = 'Latest Release: ';
      metaDiv.appendChild(strong);
      metaDiv.appendChild(
        document.createTextNode(`${latestReleaseTag} (${(apkAsset.size / (1024 * 1024)).toFixed(2)} MB)`)
      );
      releaseStatus.appendChild(metaDiv);

      const apkLink = document.createElement('a');
      apkLink.className = 'apk-download-btn';
      apkLink.href = latestApkUrl;
      apkLink.rel = 'noopener noreferrer';
      apkLink.textContent = `Download ${apkAsset.name}`;
      releaseStatus.appendChild(apkLink);

      if (shaAsset) {
        const shaLink = document.createElement('a');
        shaLink.className = 'secondary-btn';
        shaLink.style.display = 'inline-block';
        shaLink.style.textDecoration = 'none';
        shaLink.style.marginTop = '10px';
        shaLink.href = shaAsset.browser_download_url;
        shaLink.rel = 'noopener noreferrer';
        shaLink.textContent = 'SHA-256';
        releaseStatus.appendChild(shaLink);
      }

      if (window.OnionShieldQR && apkQrCanvas) {
        window.OnionShieldQR.renderToCanvas(apkQrCanvas, latestApkUrl, {
          size: 220,
          quietModules: 4,
          darkColor: '#0B0F19',
          lightColor: '#FFFFFF',
        });
        apkQrUrlLabel.textContent = latestApkUrl;
        apkQrContainer.classList.remove('hidden');
      }
    } catch (err) {
      releaseStatus.textContent = `Could not fetch release: ${err.message}`;
    }
  }

  btnFetchRelease.addEventListener('click', () => {
    fetchLatestRelease(githubRepoInput.value, true);
  });

  btnCopyApkLink.addEventListener('click', async () => {
    if (!latestApkUrl) return;
    await navigator.clipboard.writeText(latestApkUrl);
    btnCopyApkLink.textContent = 'Copied!';
    setTimeout(() => { btnCopyApkLink.textContent = 'Copy APK Link'; }, 1500);
  });

  btnDownloadQr.addEventListener('click', () => {
    if (!apkQrCanvas || !latestApkUrl) return;
    const link = document.createElement('a');
    link.download = `OnionShield-${latestReleaseTag}-apk-qr.png`;
    link.href = apkQrCanvas.toDataURL('image/png');
    link.click();
  });

  const pwaInstallGuide = document.getElementById('pwaInstallGuide');
  const pwaInstallGuideText = document.getElementById('pwaInstallGuideText');
  const btnGuideInstallNow = document.getElementById('btnGuideInstallNow');

  function setInstallGuideVisible(visible, promptReady = false) {
    if (!pwaInstallGuide) return;
    pwaInstallGuide.classList.toggle('prompt-ready', promptReady);
    pwaInstallGuide.classList.toggle('is-visible', visible);
    pwaInstallGuide.setAttribute('aria-hidden', visible ? 'false' : 'true');
  }

  async function triggerPwaInstallPrompt() {
    if (!deferredPrompt) return;
    deferredPrompt.prompt();
    const choice = await deferredPrompt.userChoice;
    deferredPrompt = null;
    if (btnGuideInstallNow) {
      btnGuideInstallNow.classList.add('hidden');
    }
    if (choice && choice.outcome === 'accepted') {
      if (btnInstallPwa) {
        btnInstallPwa.textContent = 'Web App Installed';
      }
      setInstallGuideVisible(false, false);
    } else {
      setInstallGuideVisible(false, false);
    }
  }

  if (window.matchMedia && window.matchMedia('(display-mode: standalone)').matches && btnInstallPwa) {
    btnInstallPwa.textContent = 'Web App Installed';
  }

  window.addEventListener('beforeinstallprompt', (e) => {
    e.preventDefault();
    deferredPrompt = e;
    if (btnInstallPwa) {
      btnInstallPwa.textContent = 'Install Web App';
    }
    if (pwaInstallGuideText) {
      pwaInstallGuideText.innerHTML =
        '<strong>Install OnionShield Web App:</strong> আপনার ডিভাইসের Home Screen-এ যুক্ত করে অ্যাপের মতো দ্রুত ব্যবহার করুন।';
    }
    if (btnGuideInstallNow) {
      btnGuideInstallNow.classList.remove('hidden');
    }
    requestAnimationFrame(() => {
      setInstallGuideVisible(true, true);
    });
  });

  window.addEventListener('appinstalled', () => {
    deferredPrompt = null;
    if (btnInstallPwa) {
      btnInstallPwa.textContent = 'Web App Installed';
    }
    if (btnGuideInstallNow) {
      btnGuideInstallNow.classList.add('hidden');
    }
    setInstallGuideVisible(false, false);
  });

  if (btnGuideInstallNow) {
    btnGuideInstallNow.addEventListener('click', triggerPwaInstallPrompt);
  }

  if (btnInstallPwa) {
    btnInstallPwa.addEventListener('click', async () => {
      if (deferredPrompt) {
        await triggerPwaInstallPrompt();
        return;
      }
      if (pwaInstallGuide) {
        const nextVisible = !pwaInstallGuide.classList.contains('is-visible');
        setInstallGuideVisible(nextVisible, false);
      }
    });
  }

  if ('serviceWorker' in navigator) {
    navigator.serviceWorker.register('/sw.js').catch(() => {});
  }

  runPrivacyAudit();
  fetchLatestRelease(savedRepo, false);
})();
