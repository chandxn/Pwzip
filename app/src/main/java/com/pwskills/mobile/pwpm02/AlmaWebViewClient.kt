package com.pwskills.mobile.pwpm02

import android.net.Uri
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient

/**
 * Phase 5 Security: AlmaWebViewClient.kt
 * - Enforces trusted WebView origins: `playerWebView` (which has `@JavascriptInterface NativeDownloadBridge`)
 *   and `aiTutorWebView` are restricted strictly to `file:///android_asset/` and `blob:` URLs. Any attempt
 *   to navigate those child WebViews to an external origin is blocked.
 * - Injects `assets/portal_sync_bridge.js` into `portalWebView` ONLY when the current URL is on a
 *   trusted PW domain (`pwskills.com` or `pw.live`).
 */
class AlmaWebViewClient(private val activity: MainActivity) : WebViewClient() {

    override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
        val uri = request?.url ?: return false
        if (view != null && (view === activity.playerWebView || view === activity.aiTutorWebView)) {
            return !isTrustedLocalAssetUri(uri)
        }
        return false
    }

    override fun onPageFinished(view: WebView?, url: String?) {
        super.onPageFinished(view, url)
        if (view != null && view === activity.portalWebView) {
            if (isTrustedPortalUrl(url)) {
                activity.injectPortalSyncBridge(view)
            }
        } else {
            activity.pushSessionToChildViews()
        }
    }

    override fun doUpdateVisitedHistory(view: WebView?, url: String?, isReload: Boolean) {
        super.doUpdateVisitedHistory(view, url, isReload)
        if (view != null && view === activity.portalWebView && isTrustedPortalUrl(url)) {
            activity.injectPortalSyncBridge(view)
        }
    }

    companion object {
        fun isTrustedLocalAssetUri(uri: Uri?): Boolean {
            if (uri == null) return false
            val scheme = (uri.scheme ?: "").lowercase()
            if (scheme == "blob" || scheme == "about") return true
            if (scheme == "file" && (uri.path ?: "").startsWith("/android_asset/")) return true
            return false
        }

        fun isTrustedPortalUrl(url: String?): Boolean {
            if (url.isNullOrBlank()) return false
            return try {
                val uri = Uri.parse(url)
                val scheme = (uri.scheme ?: "").lowercase()
                val host = (uri.host ?: "").lowercase()
                scheme == "https" && (
                    host == "pwskills.com" ||
                    host.endsWith(".pwskills.com") ||
                    host == "pw.live" ||
                    host.endsWith(".pw.live")
                )
            } catch (_: Exception) {
                false
            }
        }
    }
}
