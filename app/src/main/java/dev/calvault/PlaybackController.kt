package dev.calvault

import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import dev.calvault.core.PlaylistOrder
import dev.calvault.core.Vault
import dev.calvault.core.VaultItem
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

data class PlaybackState(val id: String = "", val position: Long = 0, val duration: Long = 0, val playing: Boolean = false, val ready: Boolean = false, val error: String? = null)

/** Owns one player across Activity rotations; never keeps audio running in the background. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class PlaybackController(
    private val context: Context, private val vault: Vault, private val settings: () -> AppSettings,
    private val changed: (String, Long, Boolean) -> Unit, private val message: (String) -> Unit,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var ticker: Job? = null
    private var base: List<VaultItem> = emptyList()
    private var signature = ""
    private var queueOrder: List<String> = emptyList()
    val order get() = queueOrder.toList()
    var player: ExoPlayer? = null
        private set
    private val state = MutableStateFlow(PlaybackState())
    val flow = state.asStateFlow()
    val position get() = player?.currentPosition ?: state.value.position
    val isPlaying get() = player?.isPlaying == true
    val intendsPlaying get() = player?.playWhenReady == true

    fun bind(videos: List<VaultItem>, current: String, at: Long, play: Boolean, savedOrder: List<String> = emptyList()) {
        val newSignature = videos.joinToString { it.id } + settings().videoShuffle
        if (player != null && signature == newSignature && player?.currentMediaItem?.mediaId == current) return
        base = videos
        signature = newSignature
        val ids = videos.map { it.id }
        val order = if (settings().videoShuffle && savedOrder.size == ids.size && savedOrder.toSet() == ids.toSet()) savedOrder
            else PlaylistOrder.cycle(ids, current, settings().videoShuffle, System.nanoTime())
        queueOrder = order
        if (order.isEmpty()) return
        val instance = player ?: ExoPlayer.Builder(context.applicationContext).build().also {
            player = it
            it.addListener(object : Player.Listener {
                override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) { publish() }
                override fun onIsPlayingChanged(isPlaying: Boolean) { publish() }
                override fun onPlaybackStateChanged(playbackState: Int) { publish() }
                override fun onPlayerError(error: PlaybackException) {
                    state.value = state.value.copy(error = "This video cannot be played. You can unhide it to use another player.")
                }
            })
        }
        val sources = order.map { id ->
            ProgressiveMediaSource.Factory(VaultDataSource.Factory(vault, id)).createMediaSource(
                MediaItem.Builder().setUri("vault://$id").setMediaId(id).build(),
            )
        }
        instance.setMediaSources(sources, order.indexOf(current).coerceAtLeast(0), at.coerceAtLeast(0))
        instance.repeatMode = if (settings().videoReplay) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_ALL
        instance.prepare()
        instance.playWhenReady = play
        ticker?.cancel()
        ticker = scope.launch { while (isActive) { publish(); delay(1000) } }
    }
    private fun publish() {
        val instance = player ?: return
        val id = instance.currentMediaItem?.mediaId.orEmpty()
        state.value = PlaybackState(id, instance.currentPosition.coerceAtLeast(0), instance.duration.coerceAtLeast(0), instance.playWhenReady, instance.playbackState == Player.STATE_READY,
            instance.playerError?.let { "This video cannot be played. Unhide it to use another player." })
        if (id.isNotEmpty()) changed(id, instance.currentPosition.coerceAtLeast(0), instance.playWhenReady)
    }
    fun applySettings() {
        val instance = player ?: return
        val newSignature = base.joinToString { it.id } + settings().videoShuffle
        if (newSignature != signature) {
            val id = instance.currentMediaItem?.mediaId.orEmpty()
            bind(base, id, instance.currentPosition, instance.playWhenReady)
        } else instance.repeatMode = if (settings().videoReplay) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_ALL
    }
    fun play() { player?.play() }
    fun pause() { player?.pause() }
    fun toggle() { if (player?.playWhenReady == true) pause() else play() }
    fun next() { player?.seekToNextMediaItem(); publish() }
    fun previous() { player?.seekToPreviousMediaItem(); publish() }
    fun seek(position: Long) { player?.let { it.seekTo(position.coerceIn(0, it.duration.coerceAtLeast(0))) }; publish() }
    fun skip(forward: Boolean): Int {
        val seconds = if (forward) settings().seekForwardSeconds else settings().seekBackSeconds
        seek(position + if (forward) seconds * 1000L else -seconds * 1000L)
        return seconds
    }
    fun release() {
        ticker?.cancel(); ticker = null
        player?.release(); player = null; signature = ""; base = emptyList(); queueOrder = emptyList()
        state.value = PlaybackState()
    }
}
