package com.morris.ads

import android.graphics.drawable.Drawable
import androidx.test.core.app.ApplicationProvider
import com.morris.ads.ui.IconLoader
import java.util.concurrent.Executor
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Загрузка значка «о рекламе».
 *
 * Маркировка обязательна, поэтому здесь важнее не «загрузилось», а «не
 * загрузилось и ничего не сломалось»: любой отказ обязан оставить экран с
 * запасной иконкой, а не с пустотой и не с падением.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class IconLoaderTest {

    private lateinit var server: MockWebServer

    /** Синхронный исполнитель: ошибки видно в тесте, а не в тишине фона. */
    private val direct = Executor { it.run() }

    @Before fun setUp() { server = MockWebServer().apply { start() } }
    @After fun tearDown() { server.shutdown() }

    private fun loader(maxBytes: Long = 256L * 1024) = IconLoader(
        resources = ApplicationProvider.getApplicationContext<android.content.Context>().resources,
        executor = direct,
        maxBytes = maxBytes,
    )

    private fun png(): ByteArray = java.util.Base64.getDecoder().decode(
        "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg=="
    )

    private fun loadOnce(url: String, maxBytes: Long = 256L * 1024): Drawable? {
        var result: Drawable? = null
        loader(maxBytes).load(url) { result = it }
        return result
    }

    @Test
    fun `картинка доезжает и превращается в изображение`() {
        server.enqueue(
            MockResponse().setResponseCode(200)
                .setHeader("Content-Type", "image/png")
                .setBody(Buffer().write(png()))
        )
        assertNotNull(loadOnce(server.url("/ac.png").toString()))
    }

    @Test
    fun `ошибка сервера не даёт изображения и не бросает`() {
        server.enqueue(MockResponse().setResponseCode(500))
        assertNull(loadOnce(server.url("/ac.png").toString()))
    }

    @Test
    fun `пришло не изображение — показ не падает`() {
        // Проверяется именно отсутствие исключения. Вернётся ли null, здесь не
        // утверждается: Robolectric подменяет BitmapFactory и отдаёт картинку
        // на любые байты, так что настоящее декодирование тут не происходит.
        server.enqueue(MockResponse().setResponseCode(200).setBody("это не картинка"))
        loadOnce(server.url("/ac.png").toString())
    }

    @Test
    fun `слишком большой файл не грузится`() {
        // Иконка маркировки — это килобайты. Всё крупнее не иконка, и тянуть
        // его посреди показа незачем.
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(Buffer().write(ByteArray(4096)))
        )
        assertNull(loadOnce(server.url("/ac.png").toString(), maxBytes = 1024))
    }

    @Test
    fun `пустая ссылка не порождает запроса`() {
        assertNull(loadOnce(""))
        org.junit.Assert.assertEquals(0, server.requestCount)
    }

    @Test
    fun `недоступный узел не роняет показ`() {
        assertNull(loadOnce("http://127.0.0.1:1/ac.png"))
    }
}
