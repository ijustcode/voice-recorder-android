package com.example.voicerecorderauto.service

import android.content.ComponentName
import android.content.Context
import android.support.v4.media.MediaBrowserCompat
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaControllerCompat
import android.support.v4.media.session.PlaybackStateCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class PlaybackState(
    val isPlaying: Boolean = false,
    val currentTitle: String = "",
    val currentDuration: Long = 0,
    val currentPosition: Long = 0,
    val state: Int = PlaybackStateCompat.STATE_NONE
)

class PlaybackService(private val context: Context) {

    private var mediaBrowser: MediaBrowserCompat? = null
    private var mediaController: MediaControllerCompat? = null

    private val _playbackState = MutableStateFlow(PlaybackState())
    val playbackState: StateFlow<PlaybackState> = _playbackState.asStateFlow()

    private val _isConnected = MutableStateFlow(false)
    val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()

    private val connectionCallback = object : MediaBrowserCompat.ConnectionCallback() {
        override fun onConnected() {
            mediaBrowser?.sessionToken?.let { token ->
                mediaController = MediaControllerCompat(context, token).apply {
                    registerCallback(controllerCallback)
                }
                _isConnected.value = true
            }
        }

        override fun onConnectionSuspended() {
            _isConnected.value = false
            mediaController?.unregisterCallback(controllerCallback)
            mediaController = null
        }

        override fun onConnectionFailed() {
            _isConnected.value = false
        }
    }

    private val controllerCallback = object : MediaControllerCompat.Callback() {
        override fun onPlaybackStateChanged(state: PlaybackStateCompat?) {
            state?.let { updatePlaybackState(it) }
        }

        override fun onMetadataChanged(metadata: MediaMetadataCompat?) {
            metadata?.let { updateMetadata(it) }
        }
    }

    fun connect() {
        if (mediaBrowser == null) {
            mediaBrowser = MediaBrowserCompat(
                context,
                ComponentName(context, VoiceRecorderMediaService::class.java),
                connectionCallback,
                null
            )
            mediaBrowser?.connect()
        } else if (!mediaBrowser!!.isConnected) {
            // Only connect if not already connected
            try {
                mediaBrowser?.connect()
            } catch (e: IllegalStateException) {
                // Already connecting, ignore
            }
        }
    }

    fun disconnect() {
        mediaController?.unregisterCallback(controllerCallback)
        mediaBrowser?.disconnect()
        _isConnected.value = false
    }

    fun play() {
        mediaController?.transportControls?.play()
    }

    fun pause() {
        mediaController?.transportControls?.pause()
    }

    fun stop() {
        mediaController?.transportControls?.stop()
    }

    fun skipToNext() {
        mediaController?.transportControls?.skipToNext()
    }

    fun skipToPrevious() {
        mediaController?.transportControls?.skipToPrevious()
    }

    fun seekTo(position: Long) {
        mediaController?.transportControls?.seekTo(position)
    }

    fun playFromMediaId(mediaId: String) {
        mediaController?.transportControls?.playFromMediaId(mediaId, null)
    }

    private fun updatePlaybackState(state: PlaybackStateCompat) {
        _playbackState.value = _playbackState.value.copy(
            isPlaying = state.state == PlaybackStateCompat.STATE_PLAYING,
            currentPosition = state.position,
            state = state.state
        )
    }

    private fun updateMetadata(metadata: MediaMetadataCompat) {
        _playbackState.value = _playbackState.value.copy(
            currentTitle = metadata.getString(MediaMetadataCompat.METADATA_KEY_TITLE) ?: "",
            currentDuration = metadata.getLong(MediaMetadataCompat.METADATA_KEY_DURATION)
        )
    }

    fun subscribe(parentId: String, callback: MediaBrowserCompat.SubscriptionCallback) {
        mediaBrowser?.subscribe(parentId, callback)
    }

    fun unsubscribe(parentId: String) {
        mediaBrowser?.unsubscribe(parentId)
    }
}
