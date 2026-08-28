package com.morris.ads

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkInfo
import android.net.NetworkCapabilities
import android.telephony.TelephonyManager
import androidx.test.core.app.ApplicationProvider
import com.morris.ads.device.DeviceContext
import com.morris.ads.device.DeviceContextCollector
import com.morris.ads.net.AdRequestBuilder
import com.morris.ads.net.AdRequest
import com.morris.ads.device.AppInfo
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowNetworkCapabilities
import org.robolectric.shadows.ShadowNetworkInfo

/**
 * Сбор контекста на настоящем Android-фреймворке, поднятом на JVM.
 *
 * Эмулятора на машине нет, но Robolectric даёт настоящие `Resources`,
 * `ConnectivityManager` и `Build` — то есть ровно те API, на которых обычно и
 * ломается сбор: пустой оператор у Wi-Fi-планшета, отсутствие активной сети,
 * SecurityException на части прошивок.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DeviceContextCollectorTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private fun collector() = DeviceContextCollector(context)

    @Test
    fun `основные поля заполняются с устройства`() {
        val d = collector().collect(ifa = "abc-123", limitAdTracking = false)

        assertEquals("abc-123", d.ifa)
        assertFalse(d.limitAdTracking)
        assertTrue("ширина экрана должна быть положительной", d.widthPx > 0)
        assertTrue("высота экрана должна быть положительной", d.heightPx > 0)
        assertTrue("плотность должна быть положительной", d.density > 0f)
        assertNotNull(d.osVersion)
        assertTrue("язык не должен быть пустым", d.language.isNotBlank())
    }

    @Test
    fun `ориентация по умолчанию портретная`() {
        assertEquals(
            DeviceContext.Orientation.PORTRAIT,
            collector().collect(null, false).orientation,
        )
    }

    @Test
    fun `нет активной сети — тип соединения неизвестен, а не падение`() {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        shadowOf(cm).setDefaultNetworkActive(false)

        val d = collector().collect(null, false)
        assertNotNull("сбор не должен падать без сети", d)
    }

    @Test
    fun `Wi-Fi определяется как WIFI по кодировке OpenRTB`() {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val caps = ShadowNetworkCapabilities.newInstance()
        shadowOf(caps).addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
        shadowOf(cm).setNetworkCapabilities(cm.activeNetwork, caps)

        assertEquals(DeviceContext.ConnectionType.WIFI, collector().collect(null, false).connection)
        assertEquals(2, DeviceContext.ConnectionType.WIFI.wire)
    }

    @Test
    fun `пустой оператор не превращается в пустую строку`() {
        val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
        shadowOf(tm).setNetworkOperatorName("")

        // У Wi-Fi-планшета оператора нет. Поле должно отсутствовать в теле,
        // а не уходить пустой строкой — это разные утверждения.
        val d = collector().collect(null, false)
        val body = JSONObject(
            AdRequestBuilder.toJson(
                AdRequest(
                    placement = "p",
                    app = AppInfo("com.partner.game", "1.0"),
                    device = d,
                )
            )
        )
        assertFalse("carrier не должен уходить пустым", body.getJSONObject("device").has("carrier"))
    }

    @Test
    fun `оператор доезжает, когда он есть`() {
        val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
        shadowOf(tm).setNetworkOperatorName("MTS")

        assertEquals("MTS", collector().collect(null, false).carrier)
    }

    @Test
    fun `собранный контекст сериализуется в валидное тело заявки`() {
        // Сквозная проверка: то, что собрано с устройства, доезжает до JSON
        // без потерь и без пустых полей.
        val d = collector().collect(ifa = "38400000-8cf0-11bd-b23e-10b96e40000d", limitAdTracking = false)
        val body = JSONObject(
            AdRequestBuilder.toJson(
                AdRequest(
                    placement = "rewarded_main",
                    app = AppInfo("com.partner.game", "3.4.1"),
                    device = d,
                )
            )
        )
        val dev = body.getJSONObject("device")

        assertEquals("android", dev.getString("os"))
        assertEquals("38400000-8cf0-11bd-b23e-10b96e40000d", dev.getString("ifa"))
        assertEquals(0, dev.getInt("lmt"))
        assertEquals(d.widthPx, dev.getInt("w"))
        assertEquals(d.heightPx, dev.getInt("h"))
        assertEquals("portrait", dev.getString("orientation"))
        assertTrue(dev.has("connection"))
    }

    // --- минимальная поддерживаемая версия ---------------------------------
    //
    // Тест выше гонялся на API 34 и пропустил три вызова, которых на Android 5
    // просто нет. Крашится при этом не наш экран, а приложение партнёра, и
    // выясняется это уже в сторе. Поэтому сбор проверяется на нижней границе
    // отдельно.

    @Test
    @Config(sdk = [21])
    fun `сбор работает на minSdk и не зовёт отсутствующих методов`() {
        val d = collector().collect(ifa = "abc-123", limitAdTracking = true)

        assertTrue("язык обязан определиться и на API 21", d.language.isNotBlank())
        assertTrue(d.widthPx > 0)
        assertTrue(d.limitAdTracking)
        assertNotNull(d.connection)
    }

    @Test
    @Config(sdk = [21])
    fun `Wi-Fi определяется и на API 21, где NetworkCapabilities ещё нет`() {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        shadowOf(cm).setActiveNetworkInfo(
            ShadowNetworkInfo.newInstance(
                NetworkInfo.DetailedState.CONNECTED,
                ConnectivityManager.TYPE_WIFI,
                0,
                true,
                NetworkInfo.State.CONNECTED,
            )
        )
        assertEquals(DeviceContext.ConnectionType.WIFI, collector().collect(null, false).connection)
    }

    @Test
    @Config(sdk = [23])
    fun `на API 23 работает новый путь, но ещё старый dataNetworkType`() {
        assertNotNull(collector().collect(null, false).connection)
    }
}
