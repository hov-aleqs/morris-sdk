package com.morris.ads.net

import com.morris.ads.model.AdChoices
import com.morris.ads.model.AdResponse
import com.morris.ads.model.Branding
import com.morris.ads.model.Click
import com.morris.ads.model.MediaFile
import com.morris.ads.model.Reward
import com.morris.ads.model.Tracking
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/** Ответ пришёл, но показать его нельзя. */
public class AdParseException(message: String, cause: Throwable? = null) :
    Exception(message, cause)

/**
 * Разбор ответа бэкенда.
 *
 * Берём `org.json` из платформы, а не подключаем сериализатор: SDK попадает в
 * чужое приложение, и каждая зависимость — это мегабайты в чужом APK и лишний
 * повод для конфликта версий.
 *
 * Разбор строгий там, где без поля показывать нечего, и мягкий там, где поле
 * необязательное. Неизвестные ключи игнорируются: бэкенд может начать присылать
 * новое поле раньше, чем выйдет релиз SDK, и это не должно ломать существующие
 * сборки у партнёров.
 */
public object AdResponseParser {

    /**
     * @throws AdParseException если ответ непригоден к показу.
     */
    public fun parse(body: String): AdResponse {
        val root = try {
            JSONObject(body)
        } catch (e: JSONException) {
            throw AdParseException("не разобрали тело ответа", e)
        }

        val adId = root.optString("ad_id").ifBlank {
            throw AdParseException("в ответе нет ad_id")
        }

        val media = parseMedia(root.optJSONArray("media"))
        if (media.isEmpty()) throw AdParseException("в ответе нет ни одного media-файла")

        return AdResponse(
            adId = adId,
            durationMs = root.optLong("duration_ms", 0L),
            // isNull важен: отсутствие ключа и null означают одно — пропуск
            // запрещён, а вот 0 означал бы «кнопка сразу».
            skipAfterMs = if (root.isNull("skip_after_ms")) null else root.optLong("skip_after_ms"),
            controls = root.optBoolean("controls", false),
            media = media,
            click = parseClick(root.optJSONObject("click")),
            branding = parseBranding(root.optJSONObject("branding")),
            tracking = parseTracking(root.optJSONObject("tracking")),
            reward = parseReward(root.optJSONObject("reward")),
            ttlMs = root.optLong("ttl_ms", 0L),
        )
    }

    /**
     * То же, но пустой ответ — это «рекламы нет», а не ошибка. Отличать важно:
     * no-fill это норма, а ошибка разбора — повод для error-пикселя.
     */
    public fun parseOrNoFill(body: String): AdResponse? {
        if (body.isBlank()) return null
        val root = try {
            JSONObject(body)
        } catch (e: JSONException) {
            throw AdParseException("не разобрали тело ответа", e)
        }
        if (root.length() == 0 || !root.has("ad_id")) return null
        return parse(body)
    }

    private fun parseMedia(arr: JSONArray?): List<MediaFile> {
        if (arr == null) return emptyList()
        val out = ArrayList<MediaFile>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val url = o.optString("url")
            if (url.isBlank()) continue
            out += MediaFile(
                url = url,
                width = o.optInt("w", 0),
                height = o.optInt("h", 0),
                bitrateKbps = o.optInt("bitrate", 0),
                mimeType = o.optString("mime", "video/mp4"),
            )
        }
        return out
    }

    private fun parseClick(o: JSONObject?): Click =
        Click(
            url = o?.optString("url").orEmpty(),
            label = o?.optString("label").orEmpty(),
        )

    private fun parseBranding(o: JSONObject?): Branding {
        if (o == null) return Branding(erid = null, adChoices = null)
        val ac = o.optJSONObject("adchoices")
        return Branding(
            erid = o.optString("erid").ifBlank { null },
            adChoices = ac?.let {
                val icon = it.optString("icon")
                val click = it.optString("click")
                if (icon.isBlank() || click.isBlank()) null
                else AdChoices(
                    iconUrl = icon,
                    clickUrl = click,
                )
            },
        )
    }

    private fun parseTracking(o: JSONObject?): Tracking {
        if (o == null) return Tracking.EMPTY
        val map = LinkedHashMap<String, List<String>>(o.length())
        for (key in o.keys()) {
            val arr = o.optJSONArray(key) ?: continue
            val urls = ArrayList<String>(arr.length())
            for (i in 0 until arr.length()) {
                val u = arr.optString(i)
                if (u.isNotBlank()) urls += u
            }
            if (urls.isNotEmpty()) map[key] = urls
        }
        return Tracking(map)
    }

    private fun parseReward(o: JSONObject?): Reward? {
        if (o == null) return null
        return Reward(
            amount = o.optInt("amount", 0),
            currency = o.optString("currency").ifBlank { "reward" },
        )
    }
}
