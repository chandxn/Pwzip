package com.pwskills.mobile.pwpm02

import android.content.Context
import android.os.Environment
import org.json.JSONObject

/**
 * Fix 2: Provider Integration Adapter (`PwPortalProviderAdapter.kt`)
 *
 * CRITICAL SECURITY & ENTITLEMENT RULE:
 * - An `https://` URL prefix validates transport security ONLY, NOT provider download entitlement.
 * - `AUTHORIZED_SHARED_EXPORT`, `AUTHORIZED_APP_OFFLINE`, and `AUTHORIZED_PERSISTENT_DRM` are
 *   assigned ONLY when an explicit provider entitlement flag (`explicitSharedExportEntitled`,
 *   `explicitAppOfflineEntitled`, or `explicitPersistentDrmEntitled`) and a verified
 *   `entitlementSource` from the authenticated PW Portal confirm permission for that exact
 *   lesson and media type.
 * - Whenever explicit provider entitlement is absent or unverified, this adapter returns
 *   `ProviderAuthStatus.PROVIDER_OFFLINE_ACCESS_UNAVAILABLE` and grants zero download options.
 */
enum class DownloadMode {
    SHARED_EXPORT,
    APP_OFFLINE
}

enum class ProviderAuthStatus {
    AUTHORIZED_SHARED_EXPORT,
    AUTHORIZED_APP_OFFLINE,
    AUTHORIZED_PERSISTENT_DRM,
    PROVIDER_OFFLINE_ACCESS_UNAVAILABLE
}

data class LessonMetadata(
    val lessonId: String,
    val lessonTitle: String,
    val courseId: String,
    val courseTitle: String,
    val moduleId: String,
    val moduleTitle: String,
    val mediaType: String,
    val durationSec: Double,
    val source: String,
    val lastUpdatedAt: Long,
    val providerAuthStatus: ProviderAuthStatus = ProviderAuthStatus.PROVIDER_OFFLINE_ACCESS_UNAVAILABLE,
    val explicitSharedExportEntitled: Boolean = false,
    val explicitAppOfflineEntitled: Boolean = false,
    val explicitPersistentDrmEntitled: Boolean = false,
    val entitlementSource: String? = null,
    val approvedDirectUrl: String? = null,
    val adaptiveManifestUrl: String? = null,
    val offlineLicenseUrl: String? = null,
    val persistentLicenseAllowed: Boolean = false,
    val attachments: List<AttachmentOption> = emptyList()
) {
    /**
     * Registry lookup key: uses genuine provider `lessonId` when exposed; falls back to
     * `courseId + ":" + lessonTitle` only for internal map indexing without inventing fake IDs.
     */
    val registryKey: String
        get() = if (lessonId.isNotBlank()) lessonId else "${courseId}:${lessonTitle}"

    fun toJson(): JSONObject = JSONObject()
        .put("lessonId", lessonId.ifBlank { JSONObject.NULL })
        .put("lessonTitle", lessonTitle)
        .put("courseId", courseId.ifBlank { JSONObject.NULL })
        .put("courseTitle", courseTitle.ifBlank { JSONObject.NULL })
        .put("moduleId", moduleId.ifBlank { JSONObject.NULL })
        .put("moduleTitle", moduleTitle.ifBlank { JSONObject.NULL })
        .put("mediaType", mediaType)
        .put("durationSec", durationSec)
        .put("source", source)
        .put("lastUpdatedAt", lastUpdatedAt)
        .put("providerAuthStatus", providerAuthStatus.name)
        .put("explicitSharedExportEntitled", explicitSharedExportEntitled)
        .put("explicitAppOfflineEntitled", explicitAppOfflineEntitled)
        .put("explicitPersistentDrmEntitled", explicitPersistentDrmEntitled)
        .put("entitlementSource", entitlementSource ?: JSONObject.NULL)
        .put("approvedDirectUrl", approvedDirectUrl ?: JSONObject.NULL)
        .put("adaptiveManifestUrl", adaptiveManifestUrl ?: JSONObject.NULL)
        .put("offlineLicenseUrl", offlineLicenseUrl ?: JSONObject.NULL)
        .put("persistentLicenseAllowed", persistentLicenseAllowed)
}

data class AttachmentOption(
    val attachmentId: String,
    val title: String,
    val httpsUrl: String,
    val mimeType: String = "application/pdf",
    val explicitDownloadEntitled: Boolean = false,
    val entitlementSource: String? = null
)

data class DownloadOption(
    val optionId: String,
    val lessonId: String,
    val quality: String,
    val downloadMode: DownloadMode,
    val mediaType: String,
    val providerAuthStatus: ProviderAuthStatus,
    val entitlementSource: String,
    val sourceUrl: String,
    val targetDestination: String,
    val estimatedBytes: Long
) {
    fun toJson(): JSONObject = JSONObject()
        .put("optionId", optionId)
        .put("lessonId", lessonId)
        .put("quality", quality)
        .put("downloadMode", downloadMode.name)
        .put("mediaType", mediaType)
        .put("providerAuthStatus", providerAuthStatus.name)
        .put("entitlementSource", entitlementSource)
        .put("sourceUrl", sourceUrl)
        .put("targetDestination", targetDestination)
        .put("estimatedBytes", estimatedBytes)
}

data class OfflineLicenseConfiguration(
    val lessonId: String,
    val drmScheme: String = "WIDEVINE",
    val licenseServerUrl: String,
    val persistentLicenseAllowed: Boolean,
    val entitlementSource: String,
    val requestHeaders: Map<String, String> = emptyMap(),
    val licenseDurationSec: Long = 172_800L,
    val playbackDurationSec: Long = 172_800L
) {
    fun toJson(): JSONObject = JSONObject()
        .put("lessonId", lessonId)
        .put("drmScheme", drmScheme)
        .put("licenseServerUrl", licenseServerUrl)
        .put("persistentLicenseAllowed", persistentLicenseAllowed)
        .put("entitlementSource", entitlementSource)
        .put("licenseDurationSec", licenseDurationSec)
        .put("playbackDurationSec", playbackDurationSec)
}

data class OfflineAccessGrant(
    val granted: Boolean,
    val downloadId: String?,
    val lessonId: String,
    val quality: String,
    val downloadMode: DownloadMode?,
    val providerAuthStatus: ProviderAuthStatus,
    val sourceUrl: String?,
    val destinationPath: String?,
    val licenseConfig: OfflineLicenseConfiguration?,
    val failureReason: String?
) {
    fun toJson(): JSONObject = JSONObject()
        .put("granted", granted)
        .put("downloadId", downloadId ?: JSONObject.NULL)
        .put("lessonId", lessonId)
        .put("quality", quality)
        .put("downloadMode", downloadMode?.name ?: JSONObject.NULL)
        .put("providerAuthStatus", providerAuthStatus.name)
        .put("sourceUrl", sourceUrl ?: JSONObject.NULL)
        .put("destinationPath", destinationPath ?: JSONObject.NULL)
        .put("licenseConfig", licenseConfig?.toJson() ?: JSONObject.NULL)
        .put("failureReason", failureReason ?: JSONObject.NULL)
}

class PwPortalProviderAdapter(
    private val context: Context,
    private val repository: DownloadRepository = DownloadRepository.getInstance(context),
    private val licenseStorage: PwLicenseStorage = PwLicenseStorage(context)
) {
    private val lessonRegistry = LinkedHashMap<String, LessonMetadata>()

    @Synchronized
    fun registerSyncedLesson(meta: LessonMetadata) {
        if (meta.lessonTitle.isBlank()) return
        lessonRegistry[meta.registryKey] = meta
        if (meta.lessonId.isNotBlank()) {
            lessonRegistry[meta.lessonId] = meta
        }
    }

    /**
     * 1. getLessonMetadata(lessonId)
     */
    @Synchronized
    fun getLessonMetadata(lessonId: String): LessonMetadata? {
        return lessonRegistry[lessonId]
    }

    /**
     * 2. getDownloadOptions(lessonId)
     * NEVER grants AUTHORIZED_SHARED_EXPORT or AUTHORIZED_APP_OFFLINE merely because a URL starts with https://.
     * Requires both explicit provider entitlement (`explicit*Entitled == true` + non-blank `entitlementSource`)
     * AND a valid `https://` transport URL.
     */
    @Synchronized
    fun getDownloadOptions(lessonId: String): List<DownloadOption> {
        val meta = lessonRegistry[lessonId] ?: return emptyList()
        val entSource = meta.entitlementSource
        val options = mutableListOf<DownloadOption>()

        // Path A (SHARED_EXPORT): Requires explicitSharedExportEntitled == true + non-blank entitlementSource + HTTPS URL
        val directUrl = meta.approvedDirectUrl
        if (meta.explicitSharedExportEntitled &&
            !entSource.isNullOrBlank() &&
            meta.providerAuthStatus == ProviderAuthStatus.AUTHORIZED_SHARED_EXPORT &&
            !directUrl.isNullOrBlank() &&
            directUrl.startsWith("https://", ignoreCase = true)
        ) {
            listOf("1080p", "720p", "480p").forEach { q ->
                options.add(
                    DownloadOption(
                        optionId = "${meta.registryKey}_shared_${q}",
                        lessonId = meta.lessonId,
                        quality = q,
                        downloadMode = DownloadMode.SHARED_EXPORT,
                        mediaType = "PROGRESSIVE_MP4",
                        providerAuthStatus = ProviderAuthStatus.AUTHORIZED_SHARED_EXPORT,
                        entitlementSource = entSource,
                        sourceUrl = directUrl,
                        targetDestination = "Documents/PW Triple/PW-PM/Videos/",
                        estimatedBytes = -1L
                    )
                )
            }
        }

        // Path B (APP_OFFLINE): Requires explicitAppOfflineEntitled or explicitPersistentDrmEntitled + non-blank entitlementSource + HTTPS manifest
        val manifestUrl = meta.adaptiveManifestUrl
        if (!manifestUrl.isNullOrBlank() && manifestUrl.startsWith("https://", ignoreCase = true) && !entSource.isNullOrBlank()) {
            val isWidevine = meta.mediaType.contains("WIDEVINE", ignoreCase = true)
            if (!isWidevine &&
                meta.explicitAppOfflineEntitled &&
                meta.providerAuthStatus == ProviderAuthStatus.AUTHORIZED_APP_OFFLINE
            ) {
                listOf("720p", "480p").forEach { q ->
                    options.add(
                        DownloadOption(
                            optionId = "${meta.registryKey}_app_offline_${q}",
                            lessonId = meta.lessonId,
                            quality = q,
                            downloadMode = DownloadMode.APP_OFFLINE,
                            mediaType = meta.mediaType,
                            providerAuthStatus = ProviderAuthStatus.AUTHORIZED_APP_OFFLINE,
                            entitlementSource = entSource,
                            sourceUrl = manifestUrl,
                            targetDestination = "App Internal Media3 SimpleCache (pw_offline_downloads/)",
                            estimatedBytes = -1L
                        )
                    )
                }
            } else if (isWidevine &&
                meta.explicitPersistentDrmEntitled &&
                meta.persistentLicenseAllowed &&
                meta.providerAuthStatus == ProviderAuthStatus.AUTHORIZED_PERSISTENT_DRM &&
                !meta.offlineLicenseUrl.isNullOrBlank() &&
                meta.offlineLicenseUrl.startsWith("https://", ignoreCase = true)
            ) {
                options.add(
                    DownloadOption(
                        optionId = "${meta.registryKey}_drm_offline_720p",
                        lessonId = meta.lessonId,
                        quality = "720p",
                        downloadMode = DownloadMode.APP_OFFLINE,
                        mediaType = "WIDEVINE_PROTECTED_STREAMING",
                        providerAuthStatus = ProviderAuthStatus.AUTHORIZED_PERSISTENT_DRM,
                        entitlementSource = entSource,
                        sourceUrl = manifestUrl,
                        targetDestination = "Encrypted App Internal Media3 SimpleCache (pw_offline_downloads/)",
                        estimatedBytes = -1L
                    )
                )
            }
        }

        // Path A (SHARED_EXPORT PDF Attachments): Requires att.explicitDownloadEntitled == true + HTTPS URL
        meta.attachments.forEach { att ->
            val attEntSource = att.entitlementSource ?: entSource
            if (att.explicitDownloadEntitled &&
                !attEntSource.isNullOrBlank() &&
                att.httpsUrl.startsWith("https://", ignoreCase = true)
            ) {
                options.add(
                    DownloadOption(
                        optionId = "${meta.registryKey}_att_${att.attachmentId}",
                        lessonId = meta.lessonId,
                        quality = "Document",
                        downloadMode = DownloadMode.SHARED_EXPORT,
                        mediaType = "PDF_ATTACHMENT",
                        providerAuthStatus = ProviderAuthStatus.AUTHORIZED_SHARED_EXPORT,
                        entitlementSource = attEntSource,
                        sourceUrl = att.httpsUrl,
                        targetDestination = "Documents/PW Triple/PW-PM/Files/",
                        estimatedBytes = -1L
                    )
                )
            }
        }

        return options
    }


    /**
     * Phase 5 Security: Prevents arbitrary URL downloads through NativeDownloadBridge.
     * Returns true ONLY if `url` matches an explicitly provider-entitled DownloadOption
     * registered in `lessonRegistry` for the specified `DownloadMode`.
     */
    @Synchronized
    fun isUrlAuthorizedForMode(url: String, mode: DownloadMode): Boolean {
        val cleanUrl = url.trim()
        if (!cleanUrl.startsWith("https://", ignoreCase = true)) return false
        for (lessonId in lessonRegistry.keys) {
            val opts = getDownloadOptions(lessonId)
            if (opts.any { it.downloadMode == mode && it.sourceUrl == cleanUrl }) {
                return true
            }
        }
        return false
    }

    /**
     * 3. requestOfflineAccess(lessonId, quality)
     */
    @Synchronized
    fun requestOfflineAccess(lessonId: String, quality: String): OfflineAccessGrant {
        val meta = lessonRegistry[lessonId]
            ?: return OfflineAccessGrant(
                granted = false,
                downloadId = null,
                lessonId = lessonId,
                quality = quality,
                downloadMode = null,
                providerAuthStatus = ProviderAuthStatus.PROVIDER_OFFLINE_ACCESS_UNAVAILABLE,
                sourceUrl = null,
                destinationPath = null,
                licenseConfig = null,
                failureReason = "PROVIDER_OFFLINE_ACCESS_UNAVAILABLE: No registered provider metadata for lesson '$lessonId'."
            )

        val usableBytes = Environment.getDataDirectory().usableSpace
        if (usableBytes < 25L * 1024L * 1024L) {
            return OfflineAccessGrant(
                granted = false,
                downloadId = null,
                lessonId = lessonId,
                quality = quality,
                downloadMode = null,
                providerAuthStatus = ProviderAuthStatus.PROVIDER_OFFLINE_ACCESS_UNAVAILABLE,
                sourceUrl = null,
                destinationPath = null,
                licenseConfig = null,
                failureReason = "INSUFFICIENT_STORAGE: Only ${usableBytes / (1024 * 1024)} MB available."
            )
        }

        val options = getDownloadOptions(lessonId)
        val matchingOption = options.firstOrNull {
            it.quality.equals(quality, ignoreCase = true)
        } ?: options.firstOrNull()

        if (matchingOption == null) {
            return OfflineAccessGrant(
                granted = false,
                downloadId = null,
                lessonId = lessonId,
                quality = quality,
                downloadMode = null,
                providerAuthStatus = ProviderAuthStatus.PROVIDER_OFFLINE_ACCESS_UNAVAILABLE,
                sourceUrl = null,
                destinationPath = null,
                licenseConfig = null,
                failureReason = "PROVIDER_OFFLINE_ACCESS_UNAVAILABLE: Provider has not confirmed download entitlement for '${meta.lessonTitle}' (HTTPS alone does not grant offline entitlement)."
            )
        }

        val licConfig = getOfflineLicenseConfiguration(lessonId)
        val downloadId = "dl_${meta.registryKey}_${matchingOption.quality}_${System.currentTimeMillis()}"
        return OfflineAccessGrant(
            granted = true,
            downloadId = downloadId,
            lessonId = meta.lessonId,
            quality = matchingOption.quality,
            downloadMode = matchingOption.downloadMode,
            providerAuthStatus = matchingOption.providerAuthStatus,
            sourceUrl = matchingOption.sourceUrl,
            destinationPath = matchingOption.targetDestination,
            licenseConfig = licConfig,
            failureReason = null
        )
    }

    /**
     * 4. getOfflineLicenseConfiguration(lessonId)
     */
    @Synchronized
    fun getOfflineLicenseConfiguration(lessonId: String): OfflineLicenseConfiguration? {
        val meta = lessonRegistry[lessonId] ?: return null
        val entSource = meta.entitlementSource
        if (!meta.explicitPersistentDrmEntitled ||
            !meta.persistentLicenseAllowed ||
            entSource.isNullOrBlank() ||
            meta.offlineLicenseUrl.isNullOrBlank() ||
            !meta.offlineLicenseUrl.startsWith("https://", ignoreCase = true)
        ) {
            return null
        }
        return OfflineLicenseConfiguration(
            lessonId = meta.lessonId,
            drmScheme = "WIDEVINE",
            licenseServerUrl = meta.offlineLicenseUrl,
            persistentLicenseAllowed = true,
            entitlementSource = entSource
        )
    }

    /**
     * 5. getDownloadStatus(downloadId)
     */
    fun getDownloadStatus(downloadId: String): DownloadItem? {
        return repository.getAllDownloads().firstOrNull { it.requestId == downloadId }
    }

    /**
     * 6. releaseOfflineAccess(downloadId)
     */
    fun releaseOfflineAccess(downloadId: String): Boolean {
        val item = getDownloadStatus(downloadId)
        if (item != null) {
            licenseStorage.removeKeySetId(item.requestId)
        }
        return repository.cancelDownload(downloadId)
    }
}
