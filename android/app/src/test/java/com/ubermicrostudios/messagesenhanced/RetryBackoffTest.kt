package com.ubermicrostudios.messagesenhanced

import com.ubermicrostudios.messagesenhanced.data.model.LinkPreviewDto
import com.ubermicrostudios.messagesenhanced.util.ImageRetryBackoff
import com.ubermicrostudios.messagesenhanced.util.ImageRetrySchedule
import com.ubermicrostudios.messagesenhanced.util.PreviewBackoff
import com.ubermicrostudios.messagesenhanced.util.PreviewFetch
import com.ubermicrostudios.messagesenhanced.util.PreviewFetchDecision
import com.ubermicrostudios.messagesenhanced.util.PreviewRetries
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Response
import java.io.IOException

class RetryBackoffTest {

    @Test
    fun transientFailuresBackOffAndCapAtThirtyMinutes() {
        val retries = PreviewRetries(clock = { 0L })
        val url = "https://example.test/post/1"
        val expected = longArrayOf(30_000L, 120_000L, 600_000L, 1_800_000L, 1_800_000L)
        var now = 0L
        for (delay in expected) {
            assertFalse(retries.blocked(url, now))
            val entry = retries.fail(url, 502, empty = false, now = now)
            assertEquals(delay, entry.retryAt - now)
            assertTrue(retries.blocked(url, now + delay - 1))
            assertFalse(retries.blocked(url, now + delay))
            now += delay
        }
        assertTrue(PreviewBackoff.delayMs(100, 503) <= PreviewBackoff.CAP_MS)
        assertEquals(PreviewBackoff.CAP_MS, PreviewBackoff.delayMs(100, null))
    }

    @Test
    fun clientErrorsWaitLongerThanTimeoutsAndRateLimits() {
        assertTrue(PreviewBackoff.isClientStatus(400))
        assertTrue(PreviewBackoff.isClientStatus(401))
        assertTrue(PreviewBackoff.isClientStatus(403))
        assertTrue(PreviewBackoff.isClientStatus(404))
        assertTrue(PreviewBackoff.isClientStatus(410))
        assertFalse(PreviewBackoff.isClientStatus(408))
        assertFalse(PreviewBackoff.isClientStatus(429))
        assertFalse(PreviewBackoff.isClientStatus(502))
        assertFalse(PreviewBackoff.isClientStatus(503))
        assertFalse(PreviewBackoff.isClientStatus(null))

        val retries = PreviewRetries(clock = { 0L })
        val missing = retries.fail("https://example.test/missing", 404, empty = false, now = 0L)
        val limited = retries.fail("https://example.test/limited", 429, empty = false, now = 0L)
        val timeout = retries.fail("https://example.test/slow", 408, empty = false, now = 0L)
        val empty = retries.fail("https://example.test/plain", 200, empty = true, now = 0L)
        assertEquals(600_000L, missing.retryAt)
        assertEquals(30_000L, limited.retryAt)
        assertEquals(30_000L, timeout.retryAt)
        assertEquals(600_000L, empty.retryAt)
        assertEquals(PreviewBackoff.CAP_MS, retries.fail("https://example.test/missing", 404, empty = false, now = 0L).retryAt)
    }

    @Test
    fun socialPreviewFailuresExpireAndSuccessesStay() {
        val x = "https://example.test/x/status/1"
        val youtube = "https://example.test/watch?v=abc"
        val retries = PreviewRetries(clock = { 0L })
        for (url in listOf(x, youtube)) {
            val decision = PreviewFetchDecision.classify(preview = null, error = http(502))
            val miss = decision as PreviewFetch.Miss
            assertEquals(502, miss.status)
            assertFalse(miss.empty)
            retries.fail(url, miss.status, miss.empty, now = 0L)
            assertTrue(retries.blocked(url, now = 29_000L))
            assertFalse("shown again after expiry", retries.blocked(url, now = 30_000L))
        }

        val fallback = LinkPreviewDto(url = youtube, title = "YouTube", isVideo = true)
        assertEquals(PreviewFetch.Hit(30_000L), PreviewFetchDecision.classify(fallback, null))
        val titled = LinkPreviewDto(url = youtube, title = "A talk", description = "Channel", isVideo = true, domain = "youtube.com")
        assertEquals(PreviewFetch.Hit(null), PreviewFetchDecision.classify(titled, null))
        val post = LinkPreviewDto(url = x, title = "Post", domain = "x.com", imageUrl = "/api/link-preview-image?url=1")
        assertEquals(PreviewFetch.Hit(null), PreviewFetchDecision.classify(post, null))

        val network = PreviewFetchDecision.classify(null, IOException("synthetic outage"))
        assertEquals(PreviewFetch.Miss(null, false), network)
        assertEquals(30_000L, PreviewBackoff.delayMs(0, (network as PreviewFetch.Miss).status, network.empty))
    }

    @Test
    fun httpStatusIsReadFromTheFailure() {
        assertEquals(502, PreviewBackoff.httpStatus(http(502)))
        assertEquals(503, PreviewBackoff.httpStatus(RuntimeException(http(503))))
        assertNull(PreviewBackoff.httpStatus(IOException("down")))
        assertNull(PreviewBackoff.httpStatus(null))
    }

    @Test
    fun releaseMakesFailuresDueWithoutResettingAttempts() {
        var now = 0L
        val retries = PreviewRetries(clock = { now })
        val url = "https://example.test/post/1"
        retries.fail(url, 502, empty = false, now = 0L)
        assertEquals(1, retries.entry(url)!!.attempts)
        assertTrue(retries.blocked(url, now = 1_000L))
        retries.releaseAll(now = 1_000L)
        assertFalse(retries.blocked(url, now = 1_000L))
        assertEquals(1, retries.entry(url)!!.attempts)
        val next = retries.fail(url, 502, empty = false, now = 1_000L)
        assertEquals(2, next.attempts)
        assertEquals(1_000L + 120_000L, next.retryAt)
        retries.clear(url)
        assertNull(retries.entry(url))
        assertFalse(retries.blocked(url))
    }

    @Test
    fun failureLogDropsTheOldestPastTheCap() {
        val retries = PreviewRetries(clock = { 0L })
        repeat(PreviewRetries.MAX + 1) { index ->
            retries.fail("https://example.test/$index", 502, empty = false, now = 0L)
        }
        assertNull(retries.entry("https://example.test/0"))
        assertNotNull(retries.entry("https://example.test/${PreviewRetries.MAX}"))
    }

    @Test
    fun imageRetriesWaitThenRequireATapUntilReleased() {
        var now = 0L
        val schedule = ImageRetrySchedule(clock = { now })
        val url = "https://example.test/api/media/m1"
        assertTrue(schedule.onBind(url).fetch)
        assertEquals(2_000L, schedule.onFailure(url, now))
        val waiting = schedule.onBind(url, now = 1_000L)
        assertFalse(waiting.fetch)
        assertEquals(1_000L, waiting.waitMs)
        assertTrue(waiting.failed)

        now = 2_000L
        assertTrue(schedule.onBind(url, now).fetch)
        assertEquals(10_000L, schedule.onFailure(url, now))
        assertEquals(30_000L, schedule.onFailure(url, now = 12_000L))
        assertNull(schedule.onFailure(url, now = 42_000L))
        val tapped = schedule.onBind(url, now = 42_000L)
        assertFalse(tapped.fetch)
        assertEquals(Long.MAX_VALUE, tapped.waitMs)

        schedule.release(url, now = 50_000L)
        assertTrue(schedule.onBind(url, now = 50_000L).fetch)
        schedule.onFailure(url, now = 50_000L)
        assertFalse(schedule.onBind(url, now = 50_000L).fetch)
        schedule.releaseAll(now = 60_000L)
        assertTrue(schedule.onBind(url, now = 60_000L).fetch)
        schedule.onSuccess(url)
        assertFalse(schedule.isFailed(url))
        assertTrue(schedule.onBind(url, now = 60_000L).fetch)
    }

    @Test
    fun imageErrorsAreNotKeptForMediaOrPreviewPictures() {
        assertEquals(longArrayOf(2_000L, 10_000L, 30_000L).toList(), ImageRetryBackoff.AUTO_MS.toList())
        assertNull(ImageRetryBackoff.autoDelayMs(3))
        assertTrue(ImageRetryBackoff.forgetOnError("https://example.test/api/media/m1"))
        assertTrue(ImageRetryBackoff.forgetOnError("https://example.test/api/link-preview-image?url=https%3A%2F%2Fexample.test%2Fa"))
        assertFalse(ImageRetryBackoff.forgetOnError("https://example.test/api/avatar?phone=15555550100"))
        assertFalse(ImageRetryBackoff.forgetOnError("file:///cache/pending/a.png"))
    }

    private fun http(code: Int): HttpException {
        val raw = okhttp3.Response.Builder()
            .code(code)
            .message("synthetic")
            .protocol(Protocol.HTTP_1_1)
            .request(
                okhttp3.Request.Builder()
                    .url("https://example.test/api/link-preview?url=https%3A%2F%2Fexample.test%2Fa")
                    .build(),
            )
            .build()
        return HttpException(Response.error<String>("{}".toResponseBody("application/json".toMediaType()), raw))
    }
}
