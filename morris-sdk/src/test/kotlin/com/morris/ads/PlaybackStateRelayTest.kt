package com.morris.ads

import androidx.media3.common.Player
import com.morris.ads.player.PlaybackStateRelay
import com.morris.ads.player.VideoPlayer
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Настоящий плеер на JVM не воспроизводит, и его слушатель ни разу не
 * срабатывает — а именно здесь живут два инварианта, которые легко нарушить.
 */
class PlaybackStateRelayTest {

    private val events = mutableListOf<String>()

    private val listener = object : VideoPlayer.Listener {
        override fun onReady(durationMs: Long) { events += "ready:$durationMs" }
        override fun onEnded() { events += "ended" }
        override fun onError(message: String) { events += "error:$message" }
    }

    private fun relay(duration: Long = 15_000) =
        PlaybackStateRelay(durationMs = { duration }, listener = { listener })

    @Test
    fun `готовность сообщается один раз, а не на каждой добуферизации`() {
        val r = relay()
        // ExoPlayer входит в READY на старте и снова после каждой заминки связи.
        r.onState(Player.STATE_READY)
        r.onState(Player.STATE_BUFFERING)
        r.onState(Player.STATE_READY)
        r.onState(Player.STATE_BUFFERING)
        r.onState(Player.STATE_READY)

        assertEquals(listOf("ready:15000"), events)
    }

    @Test
    fun `завершение сообщается один раз`() {
        val r = relay()
        r.onState(Player.STATE_READY)
        r.onState(Player.STATE_ENDED)
        r.onState(Player.STATE_ENDED)

        assertEquals(listOf("ready:15000", "ended"), events)
    }

    @Test
    fun `простой и буферизация наружу не выходят`() {
        val r = relay()
        r.onState(Player.STATE_IDLE)
        r.onState(Player.STATE_BUFFERING)

        assertEquals(emptyList<String>(), events)
    }

    @Test
    fun `ошибка сообщается каждый раз — она повторяемая`() {
        val r = relay()
        r.onError("SOURCE_UNAVAILABLE")
        r.onError("DECODER_INIT_FAILED")

        assertEquals(listOf("error:SOURCE_UNAVAILABLE", "error:DECODER_INIT_FAILED"), events)
    }

    @Test
    fun `длительность берётся в момент готовности, а не при создании`() {
        var duration = 0L
        val r = PlaybackStateRelay(durationMs = { duration }, listener = { listener })
        duration = 30_000                       // media3 узнаёт её только к READY
        r.onState(Player.STATE_READY)

        assertEquals(listOf("ready:30000"), events)
    }
}
