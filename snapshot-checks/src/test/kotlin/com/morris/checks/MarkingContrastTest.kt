package com.morris.checks

import java.io.File
import javax.imageio.ImageIO
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Контраст обязательной маркировки, замеренный по отрисованным пикселям.
 *
 * Маркировка рекламы обязана быть читаемой поверх ЛЮБОГО ролика, а не только
 * поверх удобного тёмного. Проверять это глазами нельзя: разработчик правит
 * прозрачность плашки, снимок «вроде нормальный», а на светлом креативе текст
 * исчезает. Поэтому цифра меряется и сравнивается с порогом WCAG.
 *
 * Тест читает снимки, записанные [AdOverlaySnapshotTest]. Если снимков нет:
 * `gradle :morris-sdk:recordPaparazziDebug`
 */
class MarkingContrastTest {

    /**
     * Области заданы в пикселях снимка PIXEL_5 (461×1000) под текущую вёрстку.
     * Если элемент переедет, области перестанут его накрывать и тест упадёт —
     * это и требуется: переезд маркировки должен быть замечен.
     */
    private data class Region(val name: String, val box: IntArray, val minRatio: Double)

    private val regions = listOf(
        Region("плашка erid", intArrayOf(258, 20, 442, 42), 4.5),
        Region("иконка adChoices", intArrayOf(22, 22, 70, 70), 3.0),
        Region("слот состояния", intArrayOf(378, 58, 438, 92), 4.5),
        Region("кнопка действия", intArrayOf(40, 930, 420, 965), 4.5),
    )

    /** Снимки на разных подложках: тёмной, светлой и пёстрой. */
    private val backdrops = listOf(
        "идёт_отсчёт" to "тёмный кадр",
        "светлого" to "светлый кадр",
        "пёстрого" to "пёстрый кадр",
    )

    @Test
    fun `маркировка читается поверх любого кадра`() {
        val dir = File(rootDir(), "morris-sdk/src/test/snapshots/images")
        assertTrue("нет каталога снимков — прогони recordPaparazziDebug", dir.isDirectory)

        val problems = mutableListOf<String>()

        for ((needle, human) in backdrops) {
            val file = dir.listFiles()?.firstOrNull { it.name.contains(needle) }
            assertTrue("не нашли снимок для «$human» (искали «$needle»)", file != null)
            val img = ImageIO.read(file)

            for (r in regions) {
                val (x0, y0, x1, y1) = r.box
                val pixels = ArrayList<Int>((x1 - x0) * (y1 - y0))
                for (x in x0 until x1) for (y in y0 until y1) pixels += img.getRGB(x, y)

                // Фон — самый частый цвет области; текст — самый светлый.
                // Брать «средний» нельзя: усреднение смешивает буквы с фоном
                // и даёт заниженный контраст на ровном месте.
                val background = pixels.groupingBy { it }.eachCount().maxBy { it.value }.key
                val foreground = pixels.maxBy { luminance(it) }

                val ratio = contrast(background, foreground)
                if (ratio < r.minRatio) {
                    problems += "%s поверх «%s»: %.1f:1, нужно %.1f:1"
                        .format(r.name, human, ratio, r.minRatio)
                }
            }
        }

        assertTrue(
            "маркировка нечитаема:\n" + problems.joinToString("\n") { "  · $it" },
            problems.isEmpty(),
        )
    }

    /** Корень проекта: тест запускается с рабочим каталогом модуля. */
    private fun rootDir(): File = File("").absoluteFile.parentFile

    private operator fun IntArray.component1() = this[0]
    private operator fun IntArray.component2() = this[1]
    private operator fun IntArray.component3() = this[2]
    private operator fun IntArray.component4() = this[3]

    /** Относительная яркость по WCAG 2.1. */
    private fun luminance(argb: Int): Double {
        fun channel(v: Int): Double {
            val c = v / 255.0
            return if (c <= 0.03928) c / 12.92 else Math.pow((c + 0.055) / 1.055, 2.4)
        }
        val r = channel((argb shr 16) and 0xFF)
        val g = channel((argb shr 8) and 0xFF)
        val b = channel(argb and 0xFF)
        return 0.2126 * r + 0.7152 * g + 0.0722 * b
    }

    private fun contrast(a: Int, b: Int): Double {
        val la = luminance(a)
        val lb = luminance(b)
        val hi = maxOf(la, lb)
        val lo = minOf(la, lb)
        return (hi + 0.05) / (lo + 0.05)
    }
}
