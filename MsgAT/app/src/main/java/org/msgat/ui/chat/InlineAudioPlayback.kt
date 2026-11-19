// SPDX-License-Identifier: AGPL-3.0-only
package org.msgat.ui.chat

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import org.msgat.core.media.AttachmentMetadata
import org.msgat.core.media.EncryptedObjectStore

@androidx.annotation.OptIn(UnstableApi::class)
internal class InlineAudioPlayback(private val context: Context, private val objects: EncryptedObjectStore) {
    var activeId by mutableStateOf<String?>(null)
        private set
    var playing by mutableStateOf(false)
        private set
    var position by mutableStateOf(0L)
        private set
    var duration by mutableStateOf(0L)
        private set
    var failed by mutableStateOf(false)
        private set
    var loading by mutableStateOf(false)
        private set
    var speed by mutableStateOf(1f)
        private set
    private var player: ExoPlayer? = null

    fun toggle(id: String, media: AttachmentMetadata) {
        if (id != activeId) {
            close()
            activeId = id
            val source = EncryptedPlaybackSource.Factory(objects, media)
            val active = ExoPlayer.Builder(context).setMediaSourceFactory(DefaultMediaSourceFactory(source)).build()
            player = active
            active.addListener(object : Player.Listener {
                override fun onEvents(player: Player, events: Player.Events) {
                    if (this@InlineAudioPlayback.player === player) update()
                }
            })
            active.setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).build(), true)
            active.setPlaybackSpeed(speed)
            active.setMediaItem(MediaItem.Builder().setUri(media.reference).setMimeType(media.mime).build())
            active.prepare()
            active.play()
        } else {
            val active = player ?: return
            if (playing) active.pause()
            else {
                if (failed) {
                    active.seekTo(position)
                    active.prepare()
                }
                if (active.playbackState == Player.STATE_ENDED) active.seekTo(0)
                active.play()
            }
        }
        update()
    }

    fun update() {
        val active = player ?: return
        failed = active.playerError != null
        loading = !failed && active.playbackState == Player.STATE_BUFFERING
        duration = active.duration.coerceAtLeast(0)
        position = active.currentPosition.coerceAtLeast(0)
        playing = !failed && active.playWhenReady && active.playbackState != Player.STATE_ENDED
    }

    fun seek(value: Long) {
        if (duration <= 0) return
        player?.seekTo(value.coerceIn(0, duration))
        update()
    }

    fun changeSpeed(value: Float) {
        if (value !in mediaPlaybackSpeeds) return
        speed = value
        player?.setPlaybackSpeed(value)
    }

    fun close() {
        val active = player
        player = null
        active?.release()
        activeId = null
        playing = false
        position = 0
        duration = 0
        failed = false
        loading = false
    }
}
