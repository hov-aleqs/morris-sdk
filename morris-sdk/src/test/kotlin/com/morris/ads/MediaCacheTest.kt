package com.morris.ads

import com.morris.ads.media.MediaCache
import java.io.File
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Кэш ролика. Главное требование не «быстро», а «никогда не отдать обрубок»:
 * недокачанный файл, лежащий как готовый, проигрался бы наполовину, и мы
 * отчитались бы о показе, которого не было.
 */
class MediaCacheTest {

    @get:Rule val temp = TemporaryFolder()

    private lateinit var server: MockWebServer
    private lateinit var dir: File
    private lateinit var cache: MediaCache

    @Before
    fun setUp() {
        server = MockWebServer().apply { start() }
        dir = temp.newFolder("media")
        cache = MediaCache(
            dir,
            OkHttpClient.Builder()
                .connectTimeout(2, TimeUnit.SECONDS)
                .readTimeout(2, TimeUnit.SECONDS)
                .build(),
        )
    }

    @After fun tearDown() { server.shutdown() }

    private fun body(size: Int): Buffer = Buffer().write(ByteArray(size) { it.toByte() })

    @Test
    fun `файл скачивается и лежит целиком`() {
        server.enqueue(MockResponse().setResponseCode(200).setBody(body(4096)))

        val f = cache.fetch(server.url("/v.mp4").toString())

        assertNotNull(f)
        assertEquals(4096L, f!!.length())
        assertFalse("временных огрызков остаться не должно", dir.list()!!.any { it.endsWith(".part") })
    }

    @Test
    fun `повторный запрос берётся с диска, а не из сети`() {
        server.enqueue(MockResponse().setResponseCode(200).setBody(body(1024)))
        val url = server.url("/v.mp4").toString()

        val first = cache.fetch(url)
        val second = cache.fetch(url)

        assertEquals(first, second)
        assertEquals("в сеть сходили один раз", 1, server.requestCount)
    }

    @Test
    fun `ошибка сервера не создаёт файл`() {
        server.enqueue(MockResponse().setResponseCode(404))

        assertNull(cache.fetch(server.url("/v.mp4").toString()))
        assertEquals(0, dir.list()!!.size)
    }

    @Test
    fun `обрыв посреди закачки не оставляет готовый файл`() {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(body(64 * 1024))
                .setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY)
        )

        cache.fetch(server.url("/v.mp4").toString())

        assertEquals(
            "ни готового файла, ни временного: недокачанное лучше выбросить",
            0, dir.list()!!.size,
        )
    }

    @Test
    fun `слишком большой ролик не скачивается`() {
        server.enqueue(MockResponse().setResponseCode(200).setBody(body(8192)))

        assertNull(cache.fetch(server.url("/v.mp4").toString(), maxBytes = 1024))
        assertEquals(0, dir.list()!!.size)
    }

    @Test
    fun `старое вычищается, свежее остаётся`() {
        val old = File(dir, "old").apply {
            writeBytes(ByteArray(10))
            setLastModified(System.currentTimeMillis() - 48 * 60 * 60 * 1000L)
        }
        val fresh = File(dir, "fresh").apply { writeBytes(ByteArray(10)) }

        cache.evict(maxAgeMs = 24 * 60 * 60 * 1000L)

        assertFalse(old.exists())
        assertTrue(fresh.exists())
    }

    @Test
    fun `при переполнении удаляется самое давнее`() {
        val now = System.currentTimeMillis()
        val oldest = File(dir, "a").apply { writeBytes(ByteArray(600)); setLastModified(now - 3000) }
        val middle = File(dir, "b").apply { writeBytes(ByteArray(600)); setLastModified(now - 2000) }
        val newest = File(dir, "c").apply { writeBytes(ByteArray(600)); setLastModified(now - 1000) }

        cache.evict(maxAgeMs = Long.MAX_VALUE, maxTotalBytes = 1300)

        assertFalse("самое давнее уходит первым", oldest.exists())
        assertTrue(middle.exists())
        assertTrue(newest.exists())
    }
}
