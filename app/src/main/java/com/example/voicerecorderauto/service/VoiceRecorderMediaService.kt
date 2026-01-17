package com.example.voicerecorderauto.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.support.v4.media.MediaBrowserCompat
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.media.MediaBrowserServiceCompat
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.example.voicerecorderauto.MainActivity
import com.example.voicerecorderauto.R
import com.example.voicerecorderauto.media.MediaItemBuilder
import com.example.voicerecorderauto.media.RecordingRepository
import com.example.voicerecorderauto.media.VoiceRecording
import com.example.voicerecorderauto.util.StorageHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class VoiceRecorderMediaService : MediaBrowserServiceCompat() {

    companion object {
        private const val NOTIFICATION_ID = 1
        private const val CHANNEL_ID = "voice_recorder_playback"
        private const val LOG_TAG = "VoiceRecorderMediaService"
    }

    private lateinit var mediaSession: MediaSessionCompat
    private lateinit var player: ExoPlayer
    private lateinit var recordingRepository: RecordingRepository
    private val serviceJob = Job()
    private val serviceScope = CoroutineScope(Dispatchers.Main + serviceJob)

    private var recordings: List<VoiceRecording> = emptyList()
    private var currentRecording: VoiceRecording? = null

    override fun onCreate() {
        super.onCreate()

        recordingRepository = RecordingRepository(this)
        createNotificationChannel()
        initializePlayer()
        initializeMediaSession()

        // Load recordings
        loadRecordings()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Voice Recorder Playback",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows current playback"
                setShowBadge(false)
            }

            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun initializePlayer() {
        player = ExoPlayer.Builder(this).build()
        player.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                updatePlaybackState()
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                updatePlaybackState()
            }
        })
    }

    private fun initializeMediaSession() {
        val sessionIntent = packageManager?.getLaunchIntentForPackage(packageName)?.let {
            PendingIntent.getActivity(
                this, 0, it,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
        }

        mediaSession = MediaSessionCompat(this, LOG_TAG).apply {
            setSessionActivity(sessionIntent)
            setCallback(MediaSessionCallback())
            isActive = true
        }

        sessionToken = mediaSession.sessionToken
    }

    private fun loadRecordings() {
        if (!StorageHelper.hasStoragePermission(this)) {
            return
        }

        serviceScope.launch {
            recordings = withContext(Dispatchers.IO) {
                recordingRepository.getAllRecordings()
            }
            notifyChildrenChanged(MediaItemBuilder.MEDIA_ROOT_ID)
            notifyChildrenChanged(MediaItemBuilder.MEDIA_ALL_RECORDINGS_ID)
        }
    }

    override fun onGetRoot(
        clientPackageName: String,
        clientUid: Int,
        rootHints: Bundle?
    ): BrowserRoot {
        // Allow Android Auto and our own app
        return BrowserRoot(MediaItemBuilder.MEDIA_ROOT_ID, null)
    }

    override fun onLoadChildren(
        parentId: String,
        result: Result<MutableList<MediaBrowserCompat.MediaItem>>
    ) {
        result.detach()

        serviceScope.launch {
            val items = when (parentId) {
                MediaItemBuilder.MEDIA_ROOT_ID -> {
                    MediaItemBuilder.buildRootItems().toMutableList()
                }
                MediaItemBuilder.MEDIA_ALL_RECORDINGS_ID -> {
                    recordings.map { MediaItemBuilder.buildRecordingItem(it) }.toMutableList()
                }
                MediaItemBuilder.MEDIA_BY_DATE_ID -> {
                    val byDate = recordingRepository.getRecordingsByDate()
                    byDate.map { (date, recs) ->
                        MediaItemBuilder.buildDateCategoryItem(date, recs.size)
                    }.toMutableList()
                }
                else -> {
                    // Handle date category items
                    if (parentId.startsWith("date_")) {
                        val date = parentId.removePrefix("date_")
                        val byDate = recordingRepository.getRecordingsByDate()
                        byDate[date]?.map { MediaItemBuilder.buildRecordingItem(it) }?.toMutableList()
                            ?: mutableListOf()
                    } else {
                        mutableListOf()
                    }
                }
            }
            result.sendResult(items)
        }
    }

    private inner class MediaSessionCallback : MediaSessionCompat.Callback() {

        override fun onPlay() {
            if (currentRecording != null) {
                player.play()
                startForegroundService()
                updatePlaybackState()
            }
        }

        override fun onPause() {
            player.pause()
            updatePlaybackState()
        }

        override fun onStop() {
            player.stop()
            stopForeground(STOP_FOREGROUND_REMOVE)
            updatePlaybackState()
        }

        override fun onSkipToNext() {
            val currentIndex = recordings.indexOfFirst { it.id == currentRecording?.id }
            if (currentIndex >= 0 && currentIndex < recordings.size - 1) {
                playRecording(recordings[currentIndex + 1])
            }
        }

        override fun onSkipToPrevious() {
            val currentIndex = recordings.indexOfFirst { it.id == currentRecording?.id }
            if (currentIndex > 0) {
                playRecording(recordings[currentIndex - 1])
            }
        }

        override fun onSeekTo(pos: Long) {
            player.seekTo(pos)
            updatePlaybackState()
        }

        override fun onPlayFromMediaId(mediaId: String?, extras: Bundle?) {
            val recording = recordings.find { it.id == mediaId }
            if (recording != null) {
                playRecording(recording)
            }
        }

        override fun onPlayFromUri(uri: android.net.Uri?, extras: Bundle?) {
            val recording = recordings.find { it.uri == uri }
            if (recording != null) {
                playRecording(recording)
            }
        }
    }

    private fun playRecording(recording: VoiceRecording) {
        currentRecording = recording

        val mediaItem = MediaItemBuilder.buildMedia3Item(recording)
        player.setMediaItem(mediaItem)
        player.prepare()
        player.play()

        updateMetadata(recording)
        startForegroundService()
    }

    private fun updateMetadata(recording: VoiceRecording) {
        val metadata = MediaItemBuilder.buildMediaMetadata(recording)
        mediaSession.setMetadata(metadata)
    }

    private fun updatePlaybackState() {
        val state = when {
            player.isPlaying -> PlaybackStateCompat.STATE_PLAYING
            player.playbackState == Player.STATE_BUFFERING -> PlaybackStateCompat.STATE_BUFFERING
            player.playbackState == Player.STATE_READY -> PlaybackStateCompat.STATE_PAUSED
            player.playbackState == Player.STATE_ENDED -> PlaybackStateCompat.STATE_STOPPED
            else -> PlaybackStateCompat.STATE_NONE
        }

        val playbackState = PlaybackStateCompat.Builder()
            .setActions(
                PlaybackStateCompat.ACTION_PLAY or
                PlaybackStateCompat.ACTION_PAUSE or
                PlaybackStateCompat.ACTION_PLAY_PAUSE or
                PlaybackStateCompat.ACTION_STOP or
                PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
                PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS or
                PlaybackStateCompat.ACTION_SEEK_TO
            )
            .setState(state, player.currentPosition, player.playbackParameters.speed)
            .build()

        mediaSession.setPlaybackState(playbackState)
        updateNotification()
    }

    private fun startForegroundService() {
        val notification = buildNotification()
        ContextCompat.startForegroundService(
            this,
            Intent(this, VoiceRecorderMediaService::class.java)
        )
        startForeground(NOTIFICATION_ID, notification)
    }

    private fun updateNotification() {
        if (currentRecording != null) {
            val notification = buildNotification()
            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.notify(NOTIFICATION_ID, notification)
        }
    }

    private fun buildNotification(): Notification {
        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val playPauseAction = if (player.isPlaying) {
            NotificationCompat.Action(
                R.drawable.ic_pause,
                "Pause",
                createMediaPendingIntent(PlaybackStateCompat.ACTION_PAUSE)
            )
        } else {
            NotificationCompat.Action(
                R.drawable.ic_play,
                "Play",
                createMediaPendingIntent(PlaybackStateCompat.ACTION_PLAY)
            )
        }

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(currentRecording?.title ?: "Voice Recorder")
            .setContentText(currentRecording?.formattedDuration ?: "")
            .setSmallIcon(R.drawable.ic_mic)
            .setContentIntent(contentIntent)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .addAction(
                R.drawable.ic_skip_previous,
                "Previous",
                createMediaPendingIntent(PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS)
            )
            .addAction(playPauseAction)
            .addAction(
                R.drawable.ic_skip_next,
                "Next",
                createMediaPendingIntent(PlaybackStateCompat.ACTION_SKIP_TO_NEXT)
            )
            .setStyle(
                androidx.media.app.NotificationCompat.MediaStyle()
                    .setMediaSession(mediaSession.sessionToken)
                    .setShowActionsInCompactView(0, 1, 2)
            )
            .build()
    }

    private fun createMediaPendingIntent(action: Long): PendingIntent {
        val intent = Intent(Intent.ACTION_MEDIA_BUTTON).apply {
            setPackage(packageName)
            putExtra("action", action)
        }
        return PendingIntent.getBroadcast(
            this,
            action.toInt(),
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceJob.cancel()
        mediaSession.release()
        player.release()
    }

    fun refreshRecordings() {
        loadRecordings()
    }
}
