package com.example.browser

object PrivacyScripts {

    data class SpoofedUserAgentProfile(
        val label: String,
        val userAgent: String,
        val platform: String,
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
        val index = (seed.hashCode() and 0x7FFFFFFF) % userAgentProfiles.size
        return userAgentProfiles[index]
    }

    fun buildHardeningScript(
        seed: String,
        enableWebRtcGuard: Boolean,
        enableFingerprintRandomization: Boolean,
    ): String {
        if (!enableWebRtcGuard && !enableFingerprintRandomization) return ""
        val profile = profileForSeed(seed)
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
            append(" })();")
        }
    }
}
