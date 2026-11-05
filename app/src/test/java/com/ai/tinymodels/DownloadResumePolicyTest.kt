package com.ai.tinymodels

import com.ai.tinymodels.domain.usecase.model.DownloadResumePolicy
import com.ai.tinymodels.domain.usecase.model.ResumeAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Byte-range resume rules for the download worker. Pure: the worker supplies a
 * partial-file length and an HTTP status, the policy says what to do.
 */
class DownloadResumePolicyTest {

    // ---- resumeOffset ----

    @Test
    fun `no partial file means start at zero`() {
        assertEquals(0L, DownloadResumePolicy.resumeOffset(partialBytes = 0L, totalBytes = 1000L))
    }

    @Test
    fun `partial file resumes at its current length`() {
        assertEquals(250L, DownloadResumePolicy.resumeOffset(partialBytes = 250L, totalBytes = 1000L))
    }

    @Test
    fun `partial size equal to the remote size restarts instead of resuming`() {
        assertEquals(0L, DownloadResumePolicy.resumeOffset(partialBytes = 1000L, totalBytes = 1000L))
    }

    @Test
    fun `partial larger than the remote size restarts`() {
        assertEquals(0L, DownloadResumePolicy.resumeOffset(partialBytes = 1200L, totalBytes = 1000L))
    }

    @Test
    fun `unknown remote size still resumes from the partial file`() {
        assertEquals(250L, DownloadResumePolicy.resumeOffset(partialBytes = 250L, totalBytes = 0L))
    }

    @Test
    fun `negative partial size is treated as zero`() {
        assertEquals(0L, DownloadResumePolicy.resumeOffset(partialBytes = -5L, totalBytes = 1000L))
    }

    // ---- actionFor ----

    @Test
    fun `206 with a partial file appends`() {
        assertEquals(ResumeAction.APPEND, DownloadResumePolicy.actionFor(offset = 250L, responseCode = 206))
    }

    @Test
    fun `200 with a partial file restarts because the server ignored the range`() {
        assertEquals(ResumeAction.RESTART, DownloadResumePolicy.actionFor(offset = 250L, responseCode = 200))
    }

    @Test
    fun `200 with no partial file restarts from zero`() {
        assertEquals(ResumeAction.RESTART, DownloadResumePolicy.actionFor(offset = 0L, responseCode = 200))
    }

    @Test
    fun `206 with no partial file restarts to avoid an empty append`() {
        assertEquals(ResumeAction.RESTART, DownloadResumePolicy.actionFor(offset = 0L, responseCode = 206))
    }

    @Test
    fun `416 range not satisfiable asks for a fresh request without a range`() {
        // The 416 body is an error page, not file content — it must never be
        // written into the partial file.
        assertEquals(
            ResumeAction.RESTART_WITHOUT_RANGE,
            DownloadResumePolicy.actionFor(offset = 250L, responseCode = 416)
        )
    }

    @Test
    fun `other status codes fail`() {
        assertEquals(ResumeAction.FAIL, DownloadResumePolicy.actionFor(offset = 250L, responseCode = 404))
        assertEquals(ResumeAction.FAIL, DownloadResumePolicy.actionFor(offset = 0L, responseCode = 500))
    }

    // ---- rangeHeader ----

    @Test
    fun `no range header when starting from zero`() {
        assertNull(DownloadResumePolicy.rangeHeader(0L))
    }

    @Test
    fun `range header asks for everything after the partial file`() {
        assertEquals("bytes=250-", DownloadResumePolicy.rangeHeader(250L))
    }
}
