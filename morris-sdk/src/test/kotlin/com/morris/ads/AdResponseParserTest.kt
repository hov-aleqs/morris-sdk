package com.morris.ads

import com.morris.ads.model.TrackingEvent
import com.morris.ads.net.AdParseException
import com.morris.ads.net.AdResponseParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Ответ ровно той формы, что зафиксирована в контракте. */
private const val FULL = """
{
  "ad_id": "a1b2c3d4",
  "type": "video",
  "duration_ms": 6000,
  "skip_after_ms": null,
  "controls": false,
  "media": [
    { "url": "https://cdn/1080.mp4", "w": 1080, "h": 1350, "bitrate": 1080, "mime": "video/mp4" },
    { "url": "https://cdn/640.mp4",  "w": 640,  "h": 800,  "bitrate": 1080, "mime": "video/mp4" }
  ],
  "click": { "url": "https://advertiser/landing", "label": "В магазин" },
  "branding": {
    "erid": "2VtzqxAjfmM",
    "adchoices": {
      "icon":  "https://cdn/adchoices.png",
      "click": "https://help/recommendation/",
      "close": "https://cdn/close.gif"
    }
  },
  "tracking": {
    "impression": ["https://t/imp"],
    "start":      ["https://t/start"],
    "q1":         ["https://t/q1"],
    "midpoint":   ["https://t/mid"],
    "q3":         ["https://t/q3"],
    "complete":   ["https://t/done", "https://t/done2"],
    "click":      ["https://t/click"]
  },
  "reward": { "amount": 1, "currency": "coins" },
  "ttl_ms": 1800000
}
"""

class AdResponseParserTest {

    @Test
    fun `полный ответ разбирается целиком`() {
        val ad = AdResponseParser.parse(FULL)

        assertEquals("a1b2c3d4", ad.adId)
        assertEquals(6000L, ad.durationMs)
        assertEquals(1800000L, ad.ttlMs)
        assertFalse(ad.controls)
        assertEquals(2, ad.media.size)
        assertEquals("https://cdn/1080.mp4", ad.media[0].url)
        assertEquals(1080, ad.media[0].width)
        assertEquals("video/mp4", ad.media[0].mimeType)
    }

    @Test
    fun `null в skip_after_ms означает запрет пропуска, а не ноль`() {
        // Ноль означал бы «кнопка сразу» — это противоположный смысл.
        assertNull(AdResponseParser.parse(FULL).skipAfterMs)
    }

    @Test
    fun `числовой skip_after_ms доезжает как есть`() {
        val json = FULL.replace("\"skip_after_ms\": null", "\"skip_after_ms\": 5000")
        assertEquals(5000L, AdResponseParser.parse(json).skipAfterMs)
    }

    @Test
    fun `подпись кнопки берётся из ответа`() {
        assertEquals("В магазин", AdResponseParser.parse(FULL).click.label)
    }

    @Test
    fun `маркировка и adChoices разбираются`() {
        val b = AdResponseParser.parse(FULL).branding
        assertEquals("2VtzqxAjfmM", b.erid)
        assertEquals("https://cdn/adchoices.png", b.adChoices?.iconUrl)
    }

    @Test
    fun `несколько ссылок на одно событие сохраняются все`() {
        val t = AdResponseParser.parse(FULL).tracking
        assertEquals(listOf("https://t/done", "https://t/done2"), t.urlsFor(TrackingEvent.COMPLETE))
    }

    @Test
    fun `событие, которого нет в ответе, даёт пустой список, а не падение`() {
        val t = AdResponseParser.parse(FULL).tracking
        assertEquals(emptyList<String>(), t.urlsFor(TrackingEvent.SKIP))
        assertFalse(t.has(TrackingEvent.SKIP))
        assertTrue(t.has(TrackingEvent.IMPRESSION))
    }

    @Test
    fun `неизвестное SDK событие не ломает разбор`() {
        // Бэкенд начал присылать новое событие раньше, чем вышел релиз SDK.
        val json = FULL.replace("\"click\":      [\"https://t/click\"]",
            "\"click\": [\"https://t/click\"], \"viewable_2sec\": [\"https://t/new\"]")
        val t = AdResponseParser.parse(json).tracking
        assertTrue("viewable_2sec" in t.knownKeys)
        assertEquals(listOf("https://t/click"), t.urlsFor(TrackingEvent.CLICK))
    }

    @Test
    fun `награда разбирается для rewarded`() {
        val r = AdResponseParser.parse(FULL).reward
        assertEquals(1, r?.amount)
        assertEquals("coins", r?.currency)
    }

    @Test
    fun `без блока reward это interstitial`() {
        val json = FULL.replace("\"reward\": { \"amount\": 1, \"currency\": \"coins\" },", "")
        assertNull(AdResponseParser.parse(json).reward)
    }

    @Test
    fun `отсутствие маркировки не роняет разбор`() {
        val json = FULL.replace(
            """"branding": {
    "erid": "2VtzqxAjfmM",
    "adchoices": {
      "icon":  "https://cdn/adchoices.png",
      "click": "https://help/recommendation/",
      "close": "https://cdn/close.gif"
    }
  },""", """"branding": {},""")
        val b = AdResponseParser.parse(json).branding
        assertNull(b.erid)
        assertNull(b.adChoices)
    }

    @Test
    fun `adChoices без close-пикселя допустим`() {
        val json = FULL.replace("""      "close": "https://cdn/close.gif"""", """      "close": null""")
    }

    @Test(expected = AdParseException::class)
    fun `битый JSON — типизированная ошибка, а не падение`() {
        AdResponseParser.parse("{ это не json")
    }

    @Test(expected = AdParseException::class)
    fun `ответ без media показывать нечем`() {
        AdResponseParser.parse(FULL.replace(Regex("\"media\": \\[[^]]*],"), "\"media\": [],"))
    }

    @Test(expected = AdParseException::class)
    fun `ответ без ad_id отвергается`() {
        AdResponseParser.parse(FULL.replace("\"ad_id\": \"a1b2c3d4\",", ""))
    }

    @Test
    fun `пустой ответ трактуется как отсутствие рекламы`() {
        assertNull(AdResponseParser.parseOrNoFill("{}"))
        assertNull(AdResponseParser.parseOrNoFill(""))
    }
}
