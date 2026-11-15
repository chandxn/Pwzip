package com.pwskills.mobile.pwpm02

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TutorSessionPolicyTest {
    @Test
    fun canonicalizesSecureTutorServerAndRejectsCredentialLeakTargets() {
        assertEquals("https://tutor.example", TutorSessionPolicy.canonicalHttpsServer("HTTPS://Tutor.Example:443/"))
        assertEquals("https://tutor.example/base", TutorSessionPolicy.canonicalHttpsServer("https://tutor.example/base/"))
        assertNull(TutorSessionPolicy.canonicalHttpsServer("http://tutor.example"))
        assertNull(TutorSessionPolicy.canonicalHttpsServer("https://user@tutor.example"))
        assertNull(TutorSessionPolicy.canonicalHttpsServer("https://tutor.example/?redirect=elsewhere"))
    }
}
