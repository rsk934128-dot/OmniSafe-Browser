package com.example.browser

import com.example.data.BrowserMode

object PrivacyScripts {

    data class SpoofedUserAgentProfile(
        val label: String,
        val userAgent: String,
        val platform: String,
    )

    private val surfaceMobileProfiles = listOf(
        SpoofedUserAgentProfile(
            label = "Chrome Android Mobile",
            userAgent = "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36",
            platform = "Linux armv8l",
        ),
        SpoofedUserAgentProfile(
            label = "Firefox Android Mobile",
            userAgent = "Mozilla/5.0 (Android 14; Mobile; rv:131.0) Gecko/131.0 Firefox/131.0",
            platform = "Linux armv8l",
        ),
    )

    private val userAgentProfiles = listOf(
        SpoofedUserAgentProfile(
            label = "Linux Desktop Firefox",
            userAgent = "Mozilla/5.0 (X11; Linux x86_64; rv:128.0) Gecko/20100101 Firefox/128.0",
            platform = "Linux x86_64",
        ),
        SpoofedUserAgentProfile(
            label = "Android Hardened Mobile",
            userAgent = "Mozilla/5.0 (Android 14; Mobile; rv:128.0) Gecko/128.0 Firefox/128.0",
            platform = "Linux armv8l",
        ),
        SpoofedUserAgentProfile(
            label = "Windows Standard",
            userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:128.0) Gecko/20100101 Firefox/128.0",
            platform = "Win32",
        ),
        SpoofedUserAgentProfile(
            label = "macOS Safari-Neutral",
            userAgent = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Safari/537.36",
            platform = "MacIntel",
        ),
    )

    fun profileForSeed(seed: String): SpoofedUserAgentProfile {
        return profileForSeed(seed, BrowserMode.SURFACE)
    }

    fun profileForSeed(seed: String, mode: BrowserMode): SpoofedUserAgentProfile {
        val pool = if (mode == BrowserMode.SURFACE) surfaceMobileProfiles else userAgentProfiles
        val index = (seed.hashCode() and 0x7FFFFFFF) % pool.size
        return pool[index]
    }

    fun buildHardeningScript(
        seed: String,
        enableWebRtcGuard: Boolean,
        enableFingerprintRandomization: Boolean,
        mode: BrowserMode = BrowserMode.SURFACE,
        enableCosmeticAdBlock: Boolean = false,
        enablePopupGuard: Boolean = false,
        enableBackgroundMediaPlayback: Boolean = true,
    ): String {
        if (!enableWebRtcGuard &&
            !enableFingerprintRandomization &&
            !enableCosmeticAdBlock &&
            !enablePopupGuard &&
            !enableBackgroundMediaPlayback
        ) {
            return ""
        }
        val profile = profileForSeed(seed, mode)
        val numericNoise = ((seed.hashCode() and 0xFF) % 5) + 1
        val cores = listOf(2, 4, 8)[(seed.hashCode() and 0x7FFFFFFF) % 3]
        val memoryGb = listOf(4, 8)[(seed.hashCode() and 0x7FFFFFFF) % 2]

        return buildString {
            append("(function() { 'use strict'; ")
            if (enableWebRtcGuard) {
                append(
                    """
                    try {
                      const blockedRtc = function() {
                        throw new DOMException('WebRTC disabled by OnionShield Privacy Guard', 'SecurityError');
                      };
                      Object.defineProperty(window, 'RTCPeerConnection', { value: blockedRtc, configurable: false });
                      Object.defineProperty(window, 'webkitRTCPeerConnection', { value: blockedRtc, configurable: false });
                      Object.defineProperty(window, 'mozRTCPeerConnection', { value: blockedRtc, configurable: false });
                      Object.defineProperty(window, 'RTCDataChannel', { value: undefined, configurable: false });
                      if (navigator.mediaDevices) {
                        navigator.mediaDevices.getUserMedia = function() {
                          return Promise.reject(new DOMException('Media capture blocked by OnionShield', 'NotAllowedError'));
                        };
                        navigator.mediaDevices.enumerateDevices = function() {
                          return Promise.resolve([]);
                        };
                      }
                      navigator.getUserMedia = undefined;
                    } catch (e) {}
                    """.trimIndent()
                )
            }
            if (enableFingerprintRandomization) {
                append(
                    """
                    try {
                      Object.defineProperty(navigator, 'hardwareConcurrency', { get: () => $cores });
                      Object.defineProperty(navigator, 'deviceMemory', { get: () => $memoryGb });
                      Object.defineProperty(navigator, 'platform', { get: () => '${profile.platform}' });
                      Object.defineProperty(navigator, 'doNotTrack', { get: () => '1' });

                      const origToDataURL = HTMLCanvasElement.prototype.toDataURL;
                      HTMLCanvasElement.prototype.toDataURL = function() {
                        try {
                          const ctx = this.getContext('2d');
                          if (ctx && this.width > 0 && this.height > 0) {
                            const img = ctx.getImageData(0, 0, 1, 1);
                            img.data[0] = (img.data[0] + $numericNoise) % 256;
                            ctx.putImageData(img, 0, 0);
                          }
                        } catch (_) {}
                        return origToDataURL.apply(this, arguments);
                      };

                      const origGetParam = WebGLRenderingContext.prototype.getParameter;
                      WebGLRenderingContext.prototype.getParameter = function(param) {
                        if (param === 37445) return 'Generic Privacy Renderer';
                        if (param === 37446) return 'OnionShield Virtual GPU ($seed)';
                        return origGetParam.apply(this, arguments);
                      };
                    } catch (e) {}
                    """.trimIndent()
                )
            }
            if (enableCosmeticAdBlock) {
                append(
                    """
                    try {
                      if (!document.getElementById('os-cosmetic-adblock')) {
                        const style = document.createElement('style');
                        style.id = 'os-cosmetic-adblock';
                        style.textContent = `
                          ins.adsbygoogle,
                          .adsbygoogle,
                          [id^="google_ads_iframe"],
                          [id^="div-gpt-ad"],
                          [data-ad-slot],
                          [data-ad-client],
                          .ad-banner,
                          .ad-container,
                          .ad-wrapper,
                          .sponsored-ad,
                          #taboola-below-article-thumbnails,
                          .taboola-container,
                          .OUTBRAIN,
                          [data-widget-host="outbrain"],
                          iframe[src*="doubleclick.net"],
                          iframe[src*="googlesyndication.com"],
                          iframe[src*="adnxs.com"],
                          iframe[src*="propellerads"],
                          iframe[src*="exoclick"] {
                            display: none !important;
                            visibility: hidden !important;
                            height: 0 !important;
                            min-height: 0 !important;
                          }
                        `;
                        (document.head || document.documentElement).appendChild(style);
                      }
                    } catch (e) {}
                    """.trimIndent()
                )
            }
            if (enablePopupGuard) {
                append(
                    """
                    try {
                      if (!window.__osPopupGuardInstalled) {
                        window.__osPopupGuardInstalled = true;
                        const origOpen = window.open;
                        const adHostRe = /(doubleclick\.net|googlesyndication|googleadservices|popads\.net|popcash\.net|propellerads|adsterra|exoclick|juicyads|trafficjunky|clickadu|admaven|hilltopads)/i;
                        window.open = function(targetUrl) {
                          if (!targetUrl || String(targetUrl) === 'about:blank' || adHostRe.test(String(targetUrl))) {
                            return null;
                          }
                          return origOpen.apply(this, arguments);
                        };
                      }
                    } catch (e) {}
                    """.trimIndent()
                )
            }
            if (enableBackgroundMediaPlayback) {
                append(
                    """
                    try {
                      window.__osBgMediaEnabled = true;
                      if (!window.__osBgMediaGuardInstalled) {
                        window.__osBgMediaGuardInstalled = true;
                        window.__osLastUserTapMs = 0;
                        window.__osNativePaused = false;

                        const forceVisibleProps = function(target) {
                          if (!target) return;
                          try {
                            Object.defineProperty(target, 'hidden', { get: function() { return window.__osBgMediaEnabled ? false : false; }, configurable: true });
                          } catch (_) {}
                          try {
                            Object.defineProperty(target, 'webkitHidden', { get: function() { return false; }, configurable: true });
                          } catch (_) {}
                          try {
                            Object.defineProperty(target, 'mozHidden', { get: function() { return false; }, configurable: true });
                          } catch (_) {}
                          try {
                            Object.defineProperty(target, 'visibilityState', { get: function() { return 'visible'; }, configurable: true });
                          } catch (_) {}
                          try {
                            Object.defineProperty(target, 'webkitVisibilityState', { get: function() { return 'visible'; }, configurable: true });
                          } catch (_) {}
                        };
                        forceVisibleProps(Document.prototype);
                        forceVisibleProps(document);

                        const blockedVisibilityEvents = {
                          visibilitychange: true,
                          webkitvisibilitychange: true,
                          mozvisibilitychange: true,
                          msvisibilitychange: true,
                          pagehide: true,
                          freeze: true
                        };
                        const origAddEventListener = EventTarget.prototype.addEventListener;
                        EventTarget.prototype.addEventListener = function(type, listener, options) {
                          if (window.__osBgMediaEnabled &&
                              typeof type === 'string' &&
                              blockedVisibilityEvents[type.toLowerCase()] &&
                              (this === document || this === window || (this && this.nodeType === 9))) {
                            return;
                          }
                          return origAddEventListener.apply(this, arguments);
                        };
                        try {
                          Object.defineProperty(document, 'onvisibilitychange', { get: function() { return null; }, set: function() {}, configurable: true });
                          Object.defineProperty(window, 'onpagehide', { get: function() { return null; }, set: function() {}, configurable: true });
                        } catch (_) {}

                        const markUserInteraction = function(ev) {
                          if (!ev || ev.isTrusted !== false) {
                            window.__osLastUserTapMs = Date.now();
                          }
                        };
                        ['pointerdown', 'touchstart', 'mousedown', 'click', 'keydown'].forEach(function(evtName) {
                          origAddEventListener.call(document, evtName, markUserInteraction, true);
                        });

                        const hasAnyActiveMedia = function() {
                          const list = document.querySelectorAll('video, audio');
                          for (let i = 0; i < list.length; i++) {
                            const m = list[i];
                            if (!m.paused && !m.ended) return true;
                          }
                          return false;
                        };
                        const notifyPlaying = function() {
                          try {
                            if (window.OnionShieldMediaBridge && window.OnionShieldMediaBridge.onMediaPlaying) {
                              window.OnionShieldMediaBridge.onMediaPlaying(document.title || location.hostname || 'Media');
                            }
                          } catch (_) {}
                        };
                        const notifyStopped = function() {
                          try {
                            if (!hasAnyActiveMedia() && window.OnionShieldMediaBridge && window.OnionShieldMediaBridge.onMediaStopped) {
                              window.OnionShieldMediaBridge.onMediaStopped();
                            }
                          } catch (_) {}
                        };

                        origAddEventListener.call(document, 'play', function(ev) {
                          const el = ev && ev.target;
                          if (el && (el.tagName === 'VIDEO' || el.tagName === 'AUDIO')) {
                            el.__osUserWantsPlay = true;
                            notifyPlaying();
                          }
                        }, true);

                        origAddEventListener.call(document, 'playing', function(ev) {
                          const el = ev && ev.target;
                          if (el && (el.tagName === 'VIDEO' || el.tagName === 'AUDIO')) {
                            el.__osUserWantsPlay = true;
                            notifyPlaying();
                          }
                        }, true);

                        origAddEventListener.call(document, 'ended', function(ev) {
                          const el = ev && ev.target;
                          if (el && (el.tagName === 'VIDEO' || el.tagName === 'AUDIO')) {
                            el.__osUserWantsPlay = false;
                            setTimeout(notifyStopped, 250);
                          }
                        }, true);

                        origAddEventListener.call(document, 'pause', function(ev) {
                          const el = ev && ev.target;
                          if (!el || (el.tagName !== 'VIDEO' && el.tagName !== 'AUDIO')) return;
                          const msSinceTap = Date.now() - (window.__osLastUserTapMs || 0);
                          if (!window.__osBgMediaEnabled || window.__osNativePaused || el.ended || msSinceTap <= 1400) {
                            el.__osUserWantsPlay = false;
                            notifyStopped();
                            return;
                          }
                          if (el.__osUserWantsPlay) {
                            [60, 220, 550].forEach(function(delayMs) {
                              setTimeout(function() {
                                if (window.__osBgMediaEnabled && el.__osUserWantsPlay && el.paused && !el.ended) {
                                  try {
                                    const p = el.play();
                                    if (p && p.catch) p.catch(function() {});
                                  } catch (_) {}
                                }
                              }, delayMs);
                            });
                          }
                        }, true);

                        window.__osBgMediaResumeIfNeeded = function() {
                          if (!window.__osBgMediaEnabled) return;
                          const list = document.querySelectorAll('video, audio');
                          for (let i = 0; i < list.length; i++) {
                            const el = list[i];
                            if (el.__osUserWantsPlay && el.paused && !el.ended) {
                              try {
                                const p = el.play();
                                if (p && p.catch) p.catch(function() {});
                              } catch (_) {}
                            }
                          }
                        };

                        window.__osBgMediaPauseAll = function() {
                          window.__osNativePaused = true;
                          const list = document.querySelectorAll('video, audio');
                          for (let i = 0; i < list.length; i++) {
                            const el = list[i];
                            el.__osUserWantsPlay = false;
                            try { el.pause(); } catch (_) {}
                          }
                          notifyStopped();
                          setTimeout(function() { window.__osNativePaused = false; }, 250);
                        };
                      }
                    } catch (e) {}
                    """.trimIndent()
                )
            } else {
                append(" try { window.__osBgMediaEnabled = false; } catch (e) {} ")
            }
            append(" })();")
        }
    }
}
