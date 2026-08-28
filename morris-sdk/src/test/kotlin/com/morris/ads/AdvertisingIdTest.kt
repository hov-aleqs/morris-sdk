package com.morris.ads

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.morris.ads.device.AdvertisingId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Чтение рекламного идентификатора там, где Google Play Services нет.
 *
 * Это не экзотика: устройства без сервисов Google, прошивки китайских
 * производителей, эмуляторы, часть планшетов. Отсутствие библиотеки обязано
 * приводить к «идентификатора нет», а не к падению чужого приложения.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AdvertisingIdTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Test
    fun `без Play Services идентификатора нет и ничего не падает`() {
        val info = AdvertisingId.read(context)

        assertNull(info.id)
        assertFalse(
            "«не смогли прочитать» и «пользователь запретил» — разные утверждения",
            info.limitAdTracking,
        )
        assertEquals(AdvertisingId.UNKNOWN, info)
    }

    @Test
    fun `неизвестность не выдаёт себя за запрет отслеживания`() {
        MorrisAds.initialize(context, "https://example.invalid/bid")
        MorrisAds.ensureAdvertisingId(context)

        assertNull(MorrisAds.ifa())
        assertFalse(MorrisAds.isLimitAdTracking())
        MorrisAds.resetForTests()
    }

    @Test
    fun `значение от партнёра имеет приоритет над прочитанным`() {
        MorrisAds.initialize(context, "https://example.invalid/bid")
        MorrisAds.ensureAdvertisingId(context)
        MorrisAds.setAdvertisingId("partner-id", limitAdTracking = false)

        assertEquals("partner-id", MorrisAds.ifa())
        MorrisAds.resetForTests()
    }
}
