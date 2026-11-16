package com.pwskills.mobile.pwpm02

import org.json.JSONObject

/**
 * Fix B & E: Canonical ActiveLessonState & PortalSyncResolution (`ActiveLessonState.kt`)
 *
 * Enforces:
 * 1. Both a validated provider `lessonId` (`isValidProviderIdentifier`) AND a validated
 *    `lessonTitle` (`!isBlockedValue`) must be confirmed; otherwise returns
 *    `PortalSyncResolution.Unresolved(status = "ACTIVE_LESSON_UNRESOLVED", ...)`.
 * 2. Never reuses a previous lesson's ID or context when a new lesson is selected with an unresolved ID.
 * 3. Never derives a `lessonId` from course progress text ("75% completed", "4/12 lessons") or title slugs.
 * 4. Prevents stale events (`lastUpdatedAt` / `seq`) from overwriting newer state.
 */
sealed class PortalSyncResolution {
    data class Confirmed(val state: ActiveLessonState) : PortalSyncResolution()
    data class Unresolved(
        val seq: Long,
        val lastUpdatedAt: Long,
        val reason: String,
        val observedLessonTitle: String? = null
    ) : PortalSyncResolution() {
        val status: String = "ACTIVE_LESSON_UNRESOLVED"
        fun toJson(): JSONObject = JSONObject()
            .put("hasActiveLesson", false)
            .put("status", status)
            .put("connectionState", status)
            .put("seq", seq)
            .put("lastUpdatedAt", lastUpdatedAt)
            .put("reason", reason)
            .put("observedLessonTitle", observedLessonTitle ?: JSONObject.NULL)
    }
}

data class ActiveLessonState(
    val seq: Long,
    val lessonId: String,
    val lessonTitle: String,
    val courseId: String,
    val courseTitle: String,
    val moduleId: String,
    val moduleTitle: String,
    val source: String,
    val lastUpdatedAt: Long,
    val currentPosition: Double = 0.0,
    val duration: Double = 0.0,
    val playbackState: String = "PAUSED",
    val mediaType: String = "WIDEVINE_PROTECTED_STREAMING",
    val explicitSharedExportEntitled: Boolean = false,
    val explicitAppOfflineEntitled: Boolean = false,
    val explicitPersistentDrmEntitled: Boolean = false,
    val entitlementSource: String? = null,
    val approvedDirectUrl: String? = null,
    val adaptiveManifestUrl: String? = null,
    val offlineLicenseUrl: String? = null
) {
    val registryKey: String
        get() = lessonId

    fun toLessonMetadata(): LessonMetadata {
        val authStatus = when {
            explicitSharedExportEntitled && !entitlementSource.isNullOrBlank() ->
                ProviderAuthStatus.AUTHORIZED_SHARED_EXPORT
            explicitPersistentDrmEntitled && !entitlementSource.isNullOrBlank() ->
                ProviderAuthStatus.AUTHORIZED_PERSISTENT_DRM
            explicitAppOfflineEntitled && !entitlementSource.isNullOrBlank() ->
                ProviderAuthStatus.AUTHORIZED_APP_OFFLINE
            else -> ProviderAuthStatus.PROVIDER_OFFLINE_ACCESS_UNAVAILABLE
        }
        return LessonMetadata(
            lessonId = lessonId,
            lessonTitle = lessonTitle,
            courseId = courseId,
            courseTitle = courseTitle,
            moduleId = moduleId,
            moduleTitle = moduleTitle,
            mediaType = mediaType,
            durationSec = duration,
            source = source,
            lastUpdatedAt = lastUpdatedAt,
            providerAuthStatus = authStatus,
            explicitSharedExportEntitled = explicitSharedExportEntitled,
            explicitAppOfflineEntitled = explicitAppOfflineEntitled,
            explicitPersistentDrmEntitled = explicitPersistentDrmEntitled,
            entitlementSource = entitlementSource,
            approvedDirectUrl = approvedDirectUrl,
            adaptiveManifestUrl = adaptiveManifestUrl,
            offlineLicenseUrl = offlineLicenseUrl,
            persistentLicenseAllowed = explicitPersistentDrmEntitled
        )
    }

    fun toJson(): JSONObject = JSONObject()
        .put("hasActiveLesson", true)
        .put("status", "ACTIVE_LESSON_CONFIRMED")
        .put("connectionState", "CONNECTED")
        .put("seq", seq)
        .put("lessonId", lessonId)
        .put("lessonTitle", lessonTitle)
        .put("courseId", courseId)
        .put("courseTitle", courseTitle)
        .put("moduleId", moduleId)
        .put("moduleTitle", moduleTitle)
        .put("source", source)
        .put("lastUpdatedAt", lastUpdatedAt)
        .put("currentPosition", currentPosition)
        .put("duration", duration)
        .put("playbackState", playbackState)
        .put("mediaFormat", mediaType)
        .put("explicitSharedExportEntitled", explicitSharedExportEntitled)
        .put("explicitAppOfflineEntitled", explicitAppOfflineEntitled)
        .put("explicitPersistentDrmEntitled", explicitPersistentDrmEntitled)
        .put("entitlementSource", entitlementSource ?: JSONObject.NULL)

    companion object {
        private val BLOCKED_TOKENS = listOf(
            "days streak", "day streak", "streak", "dashboard", "overview",
            "learn skills that matter", "leaderboard", "my courses", "all courses",
            "enrolled courses", "continue learning", "start learning", "my batches",
            "in progress", "completed", "watched", "xp", "coins", "certificates",
            "course progress", "overall progress", "your progress", "module progress",
            "active_lesson_unresolved", "unresolved"
        )

        private val PROGRESS_REGEX = Regex(
            "^(\\d+%(\\s*(complete|completed|watched|done))?|\\d+\\s*(/|of)\\s*\\d+(\\s*(lessons?|lectures?|modules?|videos?|chapters?|completed|done))?|\\d+\\s*(modules?|lessons?|lectures?|videos?|chapters?|hours?|mins?|minutes?)|\\d{1,2}:\\d{2}(:\\d{2})?(\\s*/\\s*\\d{1,2}:\\d{2}(:\\d{2})?)?)$",
            RegexOption.IGNORE_CASE
        )

        private val VALID_ID_REGEX = Regex("^[A-Za-z0-9_-]{3,128}$")

        fun isBlockedValue(raw: String?): Boolean {
            if (raw.isNullOrBlank()) return true
            val trimmed = raw.trim()
            if (trimmed.length < 3) return true
            val lower = trimmed.lowercase()
            if (BLOCKED_TOKENS.any { lower == it || lower.contains(it) }) return true
            if (PROGRESS_REGEX.matches(trimmed)) return true
            return false
        }

        /**
         * Validates that a provider ID is genuine and NEVER derived from course progress text,
         * percentages, ratios, timestamps, route keywords, or synthetic `lesson_` slugs.
         */
        fun isValidProviderIdentifier(rawId: String?): Boolean {
            if (rawId.isNullOrBlank()) return false
            val trimmed = rawId.trim()
            if (!VALID_ID_REGEX.matches(trimmed)) return false
            if (PROGRESS_REGEX.matches(trimmed)) return false
            val lower = trimmed.lowercase()
            if (lower in setOf(
                    "dashboard", "overview", "home", "login", "profile", "courses",
                    "batches", "study", "learn", "watch", "lecture", "lesson", "video",
                    "null", "undefined", "none", "unknown", "unresolved", "in_progress",
                    "completed", "watched"
                )
            ) {
                return false
            }
            if (lower.startsWith("lesson_") || lower.contains("streak") ||
                lower.contains("progress") || lower.contains("complete")
            ) {
                return false
            }
            return true
        }

        fun resolvePortalSync(jsonStr: String?): PortalSyncResolution {
            val now = System.currentTimeMillis()
            if (jsonStr.isNullOrBlank()) {
                return PortalSyncResolution.Unresolved(now, now, "EMPTY_SYNC_PAYLOAD")
            }
            return try {
                val obj = JSONObject(jsonStr)
                val updatedAt = obj.optLong("lastUpdatedAt", obj.optLong("updatedAtMs", now))
                val seq = obj.optLong("seq", updatedAt)

                if (obj.optString("status", "") == "ACTIVE_LESSON_UNRESOLVED" ||
                    obj.optString("connectionState", "") == "ACTIVE_LESSON_UNRESOLVED" ||
                    !obj.optBoolean("hasActiveLesson", true)
                ) {
                    val reason = obj.optString("reason", "ACTIVE_LESSON_UNRESOLVED")
                    val obsTitle = obj.optString("observedLessonTitle", "").trim().ifEmpty { null }
                    return PortalSyncResolution.Unresolved(seq, updatedAt, reason, obsTitle)
                }

                val rawTitle = obj.optString("lessonTitle", "").trim()
                if (isBlockedValue(rawTitle)) {
                    return PortalSyncResolution.Unresolved(seq, updatedAt, "LESSON_TITLE_INVALID_OR_BLOCKED")
                }

                val rawLessonId = obj.optString("lessonId", "").trim()
                if (!isValidProviderIdentifier(rawLessonId)) {
                    // Requirement B: If lessonId cannot be confirmed (or is progress text), return ACTIVE_LESSON_UNRESOLVED!
                    return PortalSyncResolution.Unresolved(seq, updatedAt, "LESSON_ID_UNRESOLVED", rawTitle)
                }

                val rawCourseId = obj.optString("courseId", "").trim()
                val cleanCourseId = if (isValidProviderIdentifier(rawCourseId)) rawCourseId else ""

                val rawCourseTitle = obj.optString("courseTitle", obj.optString("courseName", "")).trim()
                val cleanCourseTitle = if (isBlockedValue(rawCourseTitle)) "" else rawCourseTitle

                val rawModuleId = obj.optString("moduleId", "").trim()
                val cleanModuleId = if (isValidProviderIdentifier(rawModuleId)) rawModuleId else ""

                val rawModuleTitle = obj.optString("moduleTitle", obj.optString("moduleName", "")).trim()
                val cleanModuleTitle = if (isBlockedValue(rawModuleTitle)) "" else rawModuleTitle

                val entSource = obj.optString("entitlementSource", "").trim().ifEmpty { null }
                val sharedEnt = obj.optBoolean("explicitSharedExportEntitled", false) && entSource != null
                val offlineEnt = obj.optBoolean("explicitAppOfflineEntitled", false) && entSource != null
                val drmEnt = obj.optBoolean("explicitPersistentDrmEntitled", false) &&
                        obj.optBoolean("persistentLicenseAllowed", false) && entSource != null

                PortalSyncResolution.Confirmed(
                    ActiveLessonState(
                        seq = seq,
                        lessonId = rawLessonId,
                        lessonTitle = rawTitle,
                        courseId = cleanCourseId,
                        courseTitle = cleanCourseTitle,
                        moduleId = cleanModuleId,
                        moduleTitle = cleanModuleTitle,
                        source = obj.optString("source", obj.optString("positionSource", "HTMLMediaElement")),
                        lastUpdatedAt = updatedAt,
                        currentPosition = obj.optDouble("currentPosition", 0.0),
                        duration = obj.optDouble("duration", 0.0),
                        playbackState = obj.optString("playbackState", "PAUSED"),
                        mediaType = obj.optString("mediaFormat", "WIDEVINE_PROTECTED_STREAMING"),
                        explicitSharedExportEntitled = sharedEnt,
                        explicitAppOfflineEntitled = offlineEnt,
                        explicitPersistentDrmEntitled = drmEnt,
                        entitlementSource = entSource,
                        approvedDirectUrl = obj.optString("approvedDownloadUrl", "").trim().ifEmpty { null },
                        adaptiveManifestUrl = obj.optString("adaptiveManifestUrl", "").trim().ifEmpty { null },
                        offlineLicenseUrl = obj.optString("offlineLicenseUrl", "").trim().ifEmpty { null }
                    )
                )
            } catch (_: Exception) {
                PortalSyncResolution.Unresolved(now, now, "INVALID_JSON")
            }
        }

        /**
         * A transient empty/unresolved portal sync must not erase a confirmed lesson.
         * Clear the confirmed lesson only when the portal has actually exposed a
         * different lesson title. This prevents tab switches/brief DOM gaps from
         * wiping the canonical state shared with Downloads and AI Tutor.
         */
        fun shouldRetainConfirmedStateOnUnresolved(
            current: ActiveLessonState?,
            unresolved: PortalSyncResolution.Unresolved
        ): Boolean {
            if (current == null) return false
            val observed = unresolved.observedLessonTitle?.trim().orEmpty()
            return observed.isEmpty() || observed.equals(current.lessonTitle, ignoreCase = true)
        }

        fun fromPortalJsonOrNull(jsonStr: String?): ActiveLessonState? {
            return when (val res = resolvePortalSync(jsonStr)) {
                is PortalSyncResolution.Confirmed -> res.state
                is PortalSyncResolution.Unresolved -> null
            }
        }
    }
}
