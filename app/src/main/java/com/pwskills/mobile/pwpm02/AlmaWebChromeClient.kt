package com.pwskills.mobile.pwpm02

import android.webkit.ConsoleMessage
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.ValueCallback
import android.net.Uri
import android.content.pm.PackageManager
import android.Manifest
import androidx.core.content.ContextCompat

/**
 * Standalone File: AlmaWebChromeClient.kt
 * Classification: Recovered from PW-PM.apk DEX (`Lcom/pwskills/mobile/pwpm02/AlmaWebChromeClient;`).
 */
class AlmaWebChromeClient(private val activity: MainActivity) : WebChromeClient() {
    private var pendingPermission: PermissionRequest? = null

    override fun onShowFileChooser(webView: WebView?, callback: ValueCallback<Array<Uri>>?, params: FileChooserParams?): Boolean {
        val intent = params?.createIntent() ?: return false
        return activity.openTutorFilePicker(callback, intent)
    }

    override fun onPermissionRequest(request: PermissionRequest?) {
        if (request == null) return
        val resources = request.resources ?: emptyArray()
        val allowed = resources.filter { it == PermissionRequest.RESOURCE_PROTECTED_MEDIA_ID }.toMutableList()
        val asksForMic = resources.contains(PermissionRequest.RESOURCE_AUDIO_CAPTURE)
        if (asksForMic && ContextCompat.checkSelfPermission(activity, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            allowed.add(PermissionRequest.RESOURCE_AUDIO_CAPTURE)
        } else if (asksForMic) {
            pendingPermission = request
            activity.requestTutorMicrophonePermission()
            return
        }
        if (allowed.isNotEmpty()) request.grant(allowed.toTypedArray()) else request.deny()
    }

    fun onMicrophonePermissionResult(granted: Boolean) {
        val request = pendingPermission ?: return
        pendingPermission = null
        if (granted && request.resources.contains(PermissionRequest.RESOURCE_AUDIO_CAPTURE)) request.grant(arrayOf(PermissionRequest.RESOURCE_AUDIO_CAPTURE)) else request.deny()
    }

    override fun onConsoleMessage(consoleMessage: ConsoleMessage?): Boolean {
        val msg = consoleMessage?.message() ?: return super.onConsoleMessage(consoleMessage)
        if (msg.startsWith("__PW_SYNC__:") || msg.startsWith("PW_SYNC:")) {
            val payload = msg.removePrefix("__PW_SYNC__:").removePrefix("PW_SYNC:").trim()
            activity.saveSessionJson(payload)
            return true
        }
        if (msg.startsWith("__PW_REQ_SESSION__:")) {
            activity.pushSessionToChildViews()
            return true
        }
        if (msg.startsWith("__PW_CMD__:") || msg.startsWith("PW_CMD:")) {
            val cmd = msg.removePrefix("__PW_CMD__:").removePrefix("PW_CMD:").trim()
            activity.dispatchPortalCommand(cmd)
            return true
        }
        return super.onConsoleMessage(consoleMessage)
    }

    override fun onProgressChanged(view: WebView?, newProgress: Int) {
        super.onProgressChanged(view, newProgress)
    }
}
