package com.morris.ads

import android.content.Context
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import androidx.test.core.app.ApplicationProvider
import com.morris.ads.player.ExoVideoPlayer
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
 * Обёртка над media3.
 *
 * Воспроизвести на JVM нельзя — декодеров нет, плеер остаётся в буферизации.
 * Но передачу команд проверить можно, а это ровно то, что мы здесь пишем сами:
 * тот ли URL уходит в плеер, ложится ли громкость, отвязывается ли поверхность
 * при закрытии.
 *
 * В плеер заглядываем отражением намеренно. Открыть его наружу ради тестов
 * значило бы расширить публичную поверхность SDK из-за проверки.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ExoVideoPlayerTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private var player: ExoVideoPlayer? = null

    @After fun tearDown() { runCatching { player?.release() } }

    private fun create(): ExoVideoPlayer = ExoVideoPlayer(context).also { player = it }

    private fun exo(p: ExoVideoPlayer): ExoPlayer =
        ExoVideoPlayer::class.java.getDeclaredField("exo")
            .apply { isAccessible = true }
            .get(p) as ExoPlayer

    @Test
    fun `в плеер уходит именно тот файл, который дали`() {
        val p = create()
        p.prepare("file:///data/cache/morris/abc.mp4")

        assertEquals(
            "file:///data/cache/morris/abc.mp4",
            exo(p).currentMediaItem?.localConfiguration?.uri.toString(),
        )
    }

    @Test
    fun `play и pause переключают воспроизведение`() {
        val p = create()
        p.prepare("file:///v.mp4")

        assertFalse("до play плеер не должен рваться играть", exo(p).playWhenReady)
        p.play()
        assertTrue(exo(p).playWhenReady)
        p.pause()
        assertFalse(exo(p).playWhenReady)
    }

    @Test
    fun `громкость доходит до плеера`() {
        val p = create()

        p.setVolume(0.3f)
        assertEquals(0.3f, exo(p).volume, 0.001f)
        p.setVolume(0f)
        assertEquals(0f, exo(p).volume, 0.001f)
        p.setVolume(1f)
        assertEquals(1f, exo(p).volume, 0.001f)
    }

    @Test
    fun `неизвестная длительность отдаётся нулём, а не служебным значением`() {
        // media3 до готовности отдаёт TIME_UNSET — это Long.MIN_VALUE+1.
        // Утечь наружу оно не должно: на нём считается таймер показа.
        val p = create()
        assertEquals(0L, p.durationMs)
    }

    @Test
    fun `позиция не бывает отрицательной`() {
        val p = create()
        assertTrue(p.positionMs >= 0)
    }

    @Test
    fun `после release поверхность отвязана от плеера`() {
        val p = create()
        p.prepare("file:///v.mp4")
        val view = p.view as PlayerView

        p.release()

        assertNull(
            "иначе View держит освобождённый плеер и падает при отрисовке",
            view.player,
        )
    }

    @Test
    fun `картинку показываем своей поверхностью, а не готовым проигрывателем`() {
        // Управление рисует наш оверлей: чужие кнопки поверх рекламы недопустимы.
        val p = create()
        assertTrue(p.view is PlayerView)
    }
}
