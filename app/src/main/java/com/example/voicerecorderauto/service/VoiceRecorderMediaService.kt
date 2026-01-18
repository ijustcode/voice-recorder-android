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
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

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

    // Thread-safe state using AtomicReference
    private val recordingsRef = AtomicReference<List<VoiceRecording>>(emptyList())
    private val recordingsByDateRef = AtomicReference<Map<String, List<VoiceRecording>>>(emptyMap())
    private val isDataLoaded = AtomicBoolean(false)

    // Pending results queue for requests that arrive before data loads
    private val pendingResults = mutableListOf<Pair<String, Result<MutableList<MediaBrowserCompat.MediaItem>>>>()
    private val pendingResultsLock = Object()

    private var recordings: List<VoiceRecording>
        get() = recordingsRef.get()
        set(value) = recordingsRef.set(value)

    private var recordingsByDate: Map<String, List<VoiceRecording>>
        get() = recordingsByDateRef.get()
        set(value) = recordingsByDateRef.set(value)

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
            // No permission - mark as loaded so pending requests get empty results
            isDataLoaded.set(true)
            processPendingResults()
            return
        }

        // Step 1: Load from cache instantly (no blocking I/O)
        val cached = recordingRepository.getCachedRecordings()
        if (cached.isNotEmpty()) {
            recordings = cached
            recordingsByDate = recordingRepository.groupRecordingsByDate(cached)
            isDataLoaded.set(true)
            processPendingResults()
        }

        // Step 2: Refresh from disk in background
        serviceScope.launch {
            try {
                val loadedRecordings = withContext(Dispatchers.IO) {
                    recordingRepository.getAllRecordings()
                }

                // Update both caches atomically
                recordings = loadedRecordings
                recordingsByDate = recordingRepository.groupRecordingsByDate(loadedRecordings)

                // Mark data as loaded and process any pending requests (if not already done)
                if (!isDataLoaded.getAndSet(true)) {
                    processPendingResults()
                }

                // Notify all browsable nodes that data has changed
                notifyChildrenChanged(MediaItemBuilder.MEDIA_ROOT_ID)
                notifyChildrenChanged(MediaItemBuilder.MEDIA_ALL_RECORDINGS_ID)
                notifyChildrenChanged(MediaItemBuilder.MEDIA_BY_DATE_ID)
            } catch (e: Exception) {
                // On error, still mark as loaded so pending requests complete (with empty data)
                if (!isDataLoaded.getAndSet(true)) {
                    processPendingResults()
                }
            }
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

        // If data hasn't loaded yet, queue the request to be processed later
        if (!isDataLoaded.get()) {
            synchronized(pendingResultsLock) {
                pendingResults.add(Pair(parentId, result))
            }
            return
        }

        // Data is loaded, process immediately
        sendResultForParentId(parentId, result)
    }

    /**
     * Builds and sends the result for a given parentId.
     * This method uses ONLY cached data - no I/O operations.
     */
    private fun sendResultForParentId(
        parentId: String,
        result: Result<MutableList<MediaBrowserCompat.MediaItem>>
    ) {
        val items = when (parentId) {
            MediaItemBuilder.MEDIA_ROOT_ID -> {
                MediaItemBuilder.buildRootItems().toMutableList()
            }
            MediaItemBuilder.MEDIA_ALL_RECORDINGS_ID -> {
                // Use cached recordings (already loaded)
                recordings.map { MediaItemBuilder.buildRecordingItem(it) }.toMutableList()
            }
            MediaItemBuilder.MEDIA_BY_DATE_ID -> {
                // Use cached by-date grouping (no I/O needed!)
                recordingsByDate.map { (date, recs) ->
                    MediaItemBuilder.buildDateCategoryItem(date, recs.size)
                }.toMutableList()
            }
            else -> {
                // Handle date category items
                if (parentId.startsWith("date_")) {
                    val date = parentId.removePrefix("date_")
                    // Use cached by-date grouping (no I/O needed!)
                    recordingsByDate[date]?.map {
                        MediaItemBuilder.buildRecordingItem(it)
                    }?.toMutableList() ?: mutableListOf()
                } else {
                    mutableListOf()
                }
            }
        }
        result.sendResult(items)
    }

    /**
     * Processes all pending results that were queued while data was loading.
     */
    private fun processPendingResults() {
        synchronized(pendingResultsLock) {
            pendingResults.forEach { (parentId, result) ->
                sendResultForParentId(parentId, result)
            }
            pendingResults.clear()
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
