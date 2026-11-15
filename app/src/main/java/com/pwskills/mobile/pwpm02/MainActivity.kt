package com.pwskills.mobile.pwpm02

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebChromeClient
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.content.Intent
import android.net.Uri
import android.webkit.ValueCallback
import android.Manifest
import android.content.pm.PackageManager
import androidx.core.app.ActivityCompat

/**
 * Standalone File: MainActivity.kt
 * Classification: Recovered from PW-PM.apk DEX & Integrated with ActiveLessonState,
 * PwPortalProviderAdapter, NativeDownloadBridge, and PwOfflinePlayer.
 */
class MainActivity : Activity(), View.OnClickListener {
    private var pendingFileCallback: ValueCallback<Array<Uri>>? = null
    private var tutorChromeClient: AlmaWebChromeClient? = null

    lateinit var portalWebView: WebView
    lateinit var playerWebView: WebView
    lateinit var aiTutorWebView: WebView

    private lateinit var tabPortalBtn: Button
    private lateinit var tabPlayerBtn: Button
    private lateinit var tabAiBtn: Button

    private lateinit var repository: DownloadRepository
    private lateinit var providerAdapter: PwPortalProviderAdapter
    private lateinit var downloadBridge: NativeDownloadBridge
    private lateinit var offlinePlayer: PwOfflinePlayer

    private var canonicalLessonState: ActiveLessonState? = null
    private var latestSessionJson: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        repository = DownloadRepository.getInstance(this)
        providerAdapter = PwPortalProviderAdapter(this, repository)
        offlinePlayer = PwOfflinePlayer(this)

        // Restore persisted canonical session if valid (ignoring legacy dashboard noise)
        val cachedJson = getSharedPreferences("pw_pm_v21_session", MODE_PRIVATE)
            .getString("session_json", "") ?: ""
        val parsedState = ActiveLessonState.fromPortalJsonOrNull(cachedJson)
        if (parsedState != null) {
            canonicalLessonState = parsedState
            latestSessionJson = cachedJson
            providerAdapter.registerSyncedLesson(parsedState.toLessonMetadata())
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#070B14"))
        }

        val topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Color.parseColor("#0F172A"))
            gravity = Gravity.CENTER
        }

        tabPortalBtn = createNavButton("★ PW Portal", 1001, "#059669")
        tabPlayerBtn = createNavButton("▶ Course Player", 1002, "#2563EB")
        tabAiBtn = createNavButton("✨ AI Tutor", 1003, "#7C3AED")

        topBar.addView(tabPortalBtn, LinearLayout.LayoutParams(0, dp(44), 1f))
        topBar.addView(tabAiBtn, LinearLayout.LayoutParams(0, dp(44), 1f))

        val contentFrame = FrameLayout(this)
        val contentParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            0,
            1f
        )

        portalWebView = WebView(this)
        playerWebView = WebView(this)
        aiTutorWebView = WebView(this)

        // Register NativeDownloadBridge BEFORE loading player.html
        configureWebView(portalWebView, attachDownloadBridge = false)
        configureWebView(playerWebView, attachDownloadBridge = true)
        configureWebView(aiTutorWebView, attachDownloadBridge = false)

        contentFrame.addView(portalWebView)
        contentFrame.addView(playerWebView)
        contentFrame.addView(aiTutorWebView)

        root.addView(
            topBar,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48))
        )
        root.addView(contentFrame, contentParams)
        setContentView(root)

        portalWebView.loadUrl("https://pwskills.com/dashboard/overview/")
        playerWebView.loadUrl("file:///android_asset/player.html")
        aiTutorWebView.loadUrl("file:///android_asset/ai_tutor.html")

        showTab(1001)
    }

    private fun configureWebView(webView: WebView, attachDownloadBridge: Boolean) {
        val settings = webView.settings
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.databaseEnabled = true
        settings.useWideViewPort = true
        settings.loadWithOverviewMode = true
        settings.mediaPlaybackRequiresUserGesture = false
        settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        settings.allowFileAccess = true
        settings.allowContentAccess = true

        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)

        webView.webViewClient = AlmaWebViewClient(this)
        val chrome = AlmaWebChromeClient(this)
        webView.webChromeClient = chrome
        if (webView === aiTutorWebView) tutorChromeClient = chrome

        if (attachDownloadBridge) {
            downloadBridge = NativeDownloadBridge(
                context = this,
                repository = repository,
                providerAdapter = providerAdapter
            )
            webView.addJavascriptInterface(downloadBridge, "NativeDownloadBridge")
        }
    }

    fun openTutorFilePicker(callback: ValueCallback<Array<Uri>>?, intent: Intent): Boolean {
        pendingFileCallback?.onReceiveValue(null)
        pendingFileCallback = callback
        return try {
            startActivityForResult(intent, 2041)
            true
        } catch (_: Exception) {
            pendingFileCallback = null
            callback?.onReceiveValue(null)
            false
        }
    }

    fun requestTutorMicrophonePermission() {
        ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), 2042)
    }

    @Deprecated("Deprecated in Android but retained for compatibility with the app's minSdk")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 2041) {
            val callback = pendingFileCallback ?: return
            pendingFileCallback = null
            val result = if (resultCode == RESULT_OK) WebChromeClient.FileChooserParams.parseResult(resultCode, data) else null
            callback.onReceiveValue(result)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 2042) tutorChromeClient?.onMicrophonePermissionResult(grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED)
    }

    private fun createNavButton(title: String, id: Int, bgHex: String): Button {
        return Button(this).apply {
            this.id = id
            this.text = title
            this.textSize = 11.5f
            this.setTextColor(Color.WHITE)
            this.setBackgroundColor(Color.parseColor(bgHex))
            this.setOnClickListener(this@MainActivity)
        }
    }

    override fun onClick(v: View?) {
        val id = v?.id ?: return
        showTab(id)
    }


    private var cachedPortalSyncJs: String? = null

    fun injectPortalSyncBridge(targetView: WebView = portalWebView) {
        val script = cachedPortalSyncJs ?: try {
            assets.open("portal_sync_bridge.js").bufferedReader(Charsets.UTF_8).use { it.readText() }
                .also { cachedPortalSyncJs = it }
        } catch (_: Exception) {
            return
        }
        targetView.evaluateJavascript(script, null)
    }

    private fun showTab(tabId: Int) {
        portalWebView.onResume()
        portalWebView.resumeTimers()
        injectPortalSyncBridge(portalWebView)
        portalWebView.visibility = if (tabId == 1001) View.VISIBLE else View.GONE
        playerWebView.visibility = if (tabId == 1002) View.VISIBLE else View.GONE
        aiTutorWebView.visibility = if (tabId == 1003) View.VISIBLE else View.GONE
        pushSessionToChildViews()
    }

    private var lastSyncTimestampMs: Long = 0L
    private var lastSyncSeq: Long = 0L

    fun saveSessionJson(json: String?) {
        if (json.isNullOrBlank()) return
        when (val resolution = ActiveLessonState.resolvePortalSync(json)) {
            is PortalSyncResolution.Confirmed -> {
                val incomingState = resolution.state
                if (incomingState.lastUpdatedAt < lastSyncTimestampMs) return
                if (incomingState.lastUpdatedAt == lastSyncTimestampMs && incomingState.seq < lastSyncSeq) return

                lastSyncTimestampMs = incomingState.lastUpdatedAt
                lastSyncSeq = incomingState.seq
                canonicalLessonState = incomingState
                latestSessionJson = json

                // Register confirmed active lesson in PwPortalProviderAdapter metadata registry
                providerAdapter.registerSyncedLesson(incomingState.toLessonMetadata())
                getSharedPreferences("pw_pm_v21_session", MODE_PRIVATE)
                    .edit()
                    .putString("session_json", json)
                    .apply()
                pushSessionToChildViews()
            }
            is PortalSyncResolution.Unresolved -> {
                if (resolution.lastUpdatedAt < lastSyncTimestampMs) return
                lastSyncTimestampMs = resolution.lastUpdatedAt
                lastSyncSeq = resolution.seq
                // Clear stale lesson context so previous lessonId is never reused!
                canonicalLessonState = null
                latestSessionJson = resolution.toJson().toString()
                getSharedPreferences("pw_pm_v21_session", MODE_PRIVATE)
                    .edit()
                    .remove("session_json")
                    .apply()
                pushSessionToChildViews()
            }
        }
    }

    fun pushSessionToChildViews() {
        if (latestSessionJson.isBlank()) return
        val js = "window.__pwApplySessionState&&window.__pwApplySessionState($latestSessionJson);"
        playerWebView.evaluateJavascript(js, null)
        aiTutorWebView.evaluateJavascript(js, null)
    }

    fun dispatchPortalCommand(cmd: String?) {
        if (cmd.isNullOrBlank()) return
        portalWebView.evaluateJavascript(
            "window.__pwPortalControl&&window.__pwPortalControl('$cmd');",
            null
        )
    }

    override fun onBackPressed() {
        if (portalWebView.visibility == View.VISIBLE && portalWebView.canGoBack()) {
            portalWebView.goBack()
            return
        }
        if (portalWebView.visibility != View.VISIBLE) {
            showTab(1001)
            return
        }
        super.onBackPressed()
    }

    override fun onDestroy() {
        if (::downloadBridge.isInitialized) {
            downloadBridge.shutdown()
        }
        if (::offlinePlayer.isInitialized) {
            offlinePlayer.release()
        }
        super.onDestroy()
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()
}
