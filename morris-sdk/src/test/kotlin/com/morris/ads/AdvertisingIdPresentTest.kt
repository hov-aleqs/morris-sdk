package com.morris.ads

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.morris.ads.device.AdvertisingId
import com.morris.ads.fake.FakeAdvertisingIdClient
import com.morris.ads.fake.WrongShapeAdvertisingIdClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Чтение идентификатора там, где Google Play Services ЕСТЬ.
 *
 * Настоящую библиотеку в сборку не тянем — весь смысл отражения в том, чтобы её
 * не было. Проверяется наш переходник: та ли форма вызова, правильно ли
 * разбирается ответ и что происходит, когда библиотека ведёт себя не так, как
 * мы ждём. Поведение самих сервисов Google это не проверяет и не может.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AdvertisingIdPresentTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private val fake = FakeAdvertisingIdClient::class.java.name
    private val wrong = WrongShapeAdvertisingIdClient::class.java.name

    @After fun tearDown() = FakeAdvertisingIdClient.reset()

    private fun read(className: String = fake) = AdvertisingId.read(context, className)

    @Test
    fun `идентификатор и разрешение доезжают как есть`() {
        FakeAdvertisingIdClient.behaviour =
            { FakeAdvertisingIdClient.Info("38400000-8cf0-11bd-b23e-10b96e40000d", false) }

        val info = read()

        assertEquals("38400000-8cf0-11bd-b23e-10b96e40000d", info.id)
        assertFalse(info.limitAdTracking)
    }

    @Test
    fun `запрет отслеживания пробрасывается`() {
        FakeAdvertisingIdClient.behaviour =
            { FakeAdvertisingIdClient.Info("38400000-8cf0-11bd-b23e-10b96e40000d", true) }

        assertTrue(read().limitAdTracking)
    }

    @Test
    fun `идентификатор из одних нулей не выдаётся за настоящий`() {
        // Google отдаёт его вместо настоящего после сброса рекламного профиля.
        // Он одинаковый у всех и склеил бы в отчётности разных людей в одного.
        FakeAdvertisingIdClient.behaviour =
            { FakeAdvertisingIdClient.Info("00000000-0000-0000-0000-000000000000", true) }

        val info = read()

        assertNull(info.id)
        assertTrue("а вот запрет отслеживания при этом настоящий", info.limitAdTracking)
    }

    @Test
    fun `пустой идентификатор превращается в его отсутствие`() {
        FakeAdvertisingIdClient.behaviour = { FakeAdvertisingIdClient.Info("", false) }
        assertNull(read().id)
    }

    @Test
    fun `сервисы вернули пусто — это не сбой`() {
        FakeAdvertisingIdClient.behaviour = { null }
        assertEquals(AdvertisingId.UNKNOWN, read())
    }

    @Test
    fun `сервисы упали — падаем не мы`() {
        // GooglePlayServicesNotAvailableException, IOException, таймаут —
        // всё это штатные исходы, а не повод уронить чужое приложение.
        FakeAdvertisingIdClient.behaviour = { error("сервисы недоступны") }
        assertEquals(AdvertisingId.UNKNOWN, read())
    }

    @Test
    fun `несовместимая версия библиотеки не ломает заявку`() {
        assertEquals(AdvertisingId.UNKNOWN, read(wrong))
    }

    @Test
    fun `библиотеки нет вовсе — тоже норма`() {
        assertEquals(AdvertisingId.UNKNOWN, read("com.google.android.gms.ads.identifier.AdvertisingIdClient"))
    }
}
