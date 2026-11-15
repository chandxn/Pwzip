package com.pwskills.mobile.pwpm02

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PwPmDualModeUnitTest {

    @Test
    fun testDashboardAndProgressTextReturnActiveLessonUnresolved() {
        val dashboardJson = """{"hasActiveLesson":true,"courseId":"dashboard","lessonId":"overview","lessonTitle":"Days Streak"}"""
        val progressJson = """{"hasActiveLesson":true,"courseId":"c101","lessonId":"50% completed","lessonTitle":"Basics of computer fundamentals"}"""
        val ratioJson = """{"hasActiveLesson":true,"courseId":"c101","lessonId":"686f5b9f7fc92f2957b2fd7f","lessonTitle":"4/12 lessons"}"""
        val moduleCountJson = """{"hasActiveLesson":true,"courseId":"c101","lessonId":"686f5b9f7fc92f2957b2fd7f","lessonTitle":"8 Modules"}"""

        val res1 = ActiveLessonState.resolvePortalSync(dashboardJson)
        val res2 = ActiveLessonState.resolvePortalSync(progressJson)
        val res3 = ActiveLessonState.resolvePortalSync(ratioJson)

        assertTrue(res1 is PortalSyncResolution.Unresolved)
        assertTrue(res2 is PortalSyncResolution.Unresolved)
        assertTrue(res3 is PortalSyncResolution.Unresolved)
        assertTrue(ActiveLessonState.resolvePortalSync(moduleCountJson) is PortalSyncResolution.Unresolved)
        assertEquals("ACTIVE_LESSON_UNRESOLVED", (res2 as PortalSyncResolution.Unresolved).status)
    }

    @Test
    fun testNewLessonWithUnresolvedIdReturnsActiveLessonUnresolved() {
        val unresolvedIdJson = """
            {
              "seq": 20,
              "lessonId": "",
              "lessonTitle": "Introduction to Operating Systems",
              "source": "HTMLMediaElement.loadedmetadata",
              "lastUpdatedAt": 1763046600000
            }
        """.trimIndent()
        val res = ActiveLessonState.resolvePortalSync(unresolvedIdJson)
        assertTrue(res is PortalSyncResolution.Unresolved)
        val unresolved = res as PortalSyncResolution.Unresolved
        assertEquals("ACTIVE_LESSON_UNRESOLVED", unresolved.status)
        assertEquals("LESSON_ID_UNRESOLVED", unresolved.reason)
        assertEquals("Introduction to Operating Systems", unresolved.observedLessonTitle)
        assertNull(ActiveLessonState.fromPortalJsonOrNull(unresolvedIdJson))
    }

    @Test
    fun testConfirmedLessonAndHttpsWithoutExplicitEntitlementBlocked() {
        val confirmedJson = """
            {
              "seq": 21,
              "lessonId": "686f5b9f7fc92f2957b2fd7f",
              "lessonTitle": "Basics of computer fundamentals",
              "courseId": "685c3776926220a1869c18a3",
              "approvedDownloadUrl": "https://cdn.pwskills.com/lecture.mp4",
              "mediaFormat": "PROGRESSIVE_MP4"
            }
        """.trimIndent()
        val res = ActiveLessonState.resolvePortalSync(confirmedJson)
        assertTrue(res is PortalSyncResolution.Confirmed)
        val state = (res as PortalSyncResolution.Confirmed).state
        assertEquals("686f5b9f7fc92f2957b2fd7f", state.lessonId)
        assertEquals(ProviderAuthStatus.PROVIDER_OFFLINE_ACCESS_UNAVAILABLE, state.toLessonMetadata().providerAuthStatus)
    }

    @Test
    fun testProviderAdapterBlocksArbitraryUnentitledUrl() {
        val state = ActiveLessonState(
            seq = 1L,
            lessonId = "686f5b9f7fc92f2957b2fd7f",
            lessonTitle = "Basics of computer fundamentals",
            courseId = "685c3776926220a1869c18a3",
            courseTitle = "Decode Data Science with ML 1.0",
            moduleId = "mod_cs_01",
            moduleTitle = "Computer Fundamentals",
            source = "HTMLMediaElement.timeupdate",
            lastUpdatedAt = 1763046500000L,
            explicitSharedExportEntitled = false,
            entitlementSource = null,
            approvedDirectUrl = "https://cdn.pwskills.com/lecture.mp4"
        )
        val meta = state.toLessonMetadata()
        assertEquals(ProviderAuthStatus.PROVIDER_OFFLINE_ACCESS_UNAVAILABLE, meta.providerAuthStatus)
        assertFalse(meta.explicitSharedExportEntitled)
    }

    @Test
    fun testDownloadWorkerSafeFileNameValidation() {
        assertTrue(DownloadWorker.isSafeFileName("Basics_of_Computer_Fundamentals_720p.mp4"))
        assertTrue(DownloadWorker.isSafeFileName("Lecture_Notes.pdf"))
        assertFalse(DownloadWorker.isSafeFileName("../etc/passwd"))
        assertFalse(DownloadWorker.isSafeFileName("manifest.m3u8"))
    }

    @Test
    fun testTransientHttpErrorsRetryButPermanentClientErrorsDoNot() {
        assertTrue(DownloadWorker.isRetryableHttpStatus(408))
        assertTrue(DownloadWorker.isRetryableHttpStatus(429))
        assertTrue(DownloadWorker.isRetryableHttpStatus(503))
        assertFalse(DownloadWorker.isRetryableHttpStatus(404))
        assertFalse(DownloadWorker.isRetryableHttpStatus(401))
    }

    @Test
    fun testDownloadStateTransitionsRejectInvalidCompletionAndAllowRecovery() {
        fun item(status: DownloadStatus) = DownloadItem(
            requestId = "id", sourceUrl = "", fileName = "clip.mp4", mimeType = "video/mp4",
            status = status, bytesDownloaded = 0, totalBytes = 0, progress = 0,
            destinationUri = null, errorMessage = null, createdAt = 1, updatedAt = 1
        )
        assertTrue(item(DownloadStatus.DOWNLOADING).canTransitionTo(DownloadStatus.VERIFYING))
        assertTrue(item(DownloadStatus.VERIFYING).canTransitionTo(DownloadStatus.COMPLETED))
        assertFalse(item(DownloadStatus.DOWNLOADING).canTransitionTo(DownloadStatus.COMPLETED))
        assertTrue(item(DownloadStatus.FAILED).canTransitionTo(DownloadStatus.QUEUED))
        assertTrue(item(DownloadStatus.COMPLETED).canTransitionTo(DownloadStatus.FAILED))
    }

    @Test
    fun testFileContainerVerificationSupportsOnlyMp4WebmAndPdfSignatures() {
        val mp4 = ByteArray(16).also { "ftyp".toByteArray().copyInto(it, 4) }
        val webm = byteArrayOf(0x1A, 0x45, 0xDF.toByte(), 0xA3.toByte(), 0, 0, 0, 0)
        val pdf = "%PDF-1.7".toByteArray()
        val broken = "<!doctype".toByteArray()
        assertNull(DownloadedMediaVerifier.validateHeaderBytes(mp4, mp4.size, false, "clip.mp4"))
        assertNull(DownloadedMediaVerifier.validateHeaderBytes(webm, webm.size, false, "clip.webm"))
        assertNull(DownloadedMediaVerifier.validateHeaderBytes(pdf, pdf.size, true, "notes.pdf"))
        assertNotNull(DownloadedMediaVerifier.validateHeaderBytes(broken, broken.size, false, "clip.mp4"))
        assertNotNull(DownloadedMediaVerifier.validateHeaderBytes(mp4, mp4.size, false, "clip.m3u8"))
    }

    @Test
    fun testNativeDownloadJsonDoesNotExposeProviderUrlOrDestinationPath() {
        val json = DownloadItem(
            requestId = "id", sourceUrl = "https://cdn.example/signed?token=secret", fileName = "clip.mp4",
            mimeType = "video/mp4", status = DownloadStatus.COMPLETED, bytesDownloaded = 100,
            totalBytes = 100, progress = 100, destinationUri = "file:///data/user/0/private/clip.mp4",
            errorMessage = null, createdAt = 1, updatedAt = 1
        ).toJson().toString()
        assertFalse(json.contains("sourceUrl"))
        assertFalse(json.contains("cdn.example"))
        assertFalse(json.contains("destinationUri"))
        assertFalse(json.contains("/data/user"))
        assertTrue(json.contains("hasPlayableFile"))
    }
}
