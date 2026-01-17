package com.example.voicerecorderauto.media

import android.net.Uri
import android.os.Bundle
import android.support.v4.media.MediaBrowserCompat
import android.support.v4.media.MediaDescriptionCompat
import android.support.v4.media.MediaMetadataCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata

object MediaItemBuilder {

    const val MEDIA_ROOT_ID = "root"
    const val MEDIA_ALL_RECORDINGS_ID = "all_recordings"
    const val MEDIA_BY_DATE_ID = "by_date"

    fun buildRootItems(): List<MediaBrowserCompat.MediaItem> {
        return listOf(
            createBrowsableItem(
                id = MEDIA_ALL_RECORDINGS_ID,
                title = "All Recordings",
                subtitle = "Browse all voice recordings"
            ),
            createBrowsableItem(
                id = MEDIA_BY_DATE_ID,
                title = "By Date",
                subtitle = "Browse recordings by date"
            )
        )
    }

    fun buildRecordingItem(recording: VoiceRecording): MediaBrowserCompat.MediaItem {
        val description = MediaDescriptionCompat.Builder()
            .setMediaId(recording.id)
            .setTitle(recording.title)
            .setSubtitle(recording.formattedDuration)
            .setDescription(recording.formattedDate)
            .setMediaUri(recording.uri)
            .setExtras(Bundle().apply {
                putLong("duration", recording.duration)
                putLong("dateAdded", recording.dateAdded)
                putString("filePath", recording.filePath)
            })
            .build()

        return MediaBrowserCompat.MediaItem(
            description,
            MediaBrowserCompat.MediaItem.FLAG_PLAYABLE
        )
    }

    fun buildDateCategoryItem(date: String, count: Int): MediaBrowserCompat.MediaItem {
        return createBrowsableItem(
            id = "date_$date",
            title = date,
            subtitle = "$count recordings"
        )
    }

    private fun createBrowsableItem(
        id: String,
        title: String,
        subtitle: String
    ): MediaBrowserCompat.MediaItem {
        val description = MediaDescriptionCompat.Builder()
            .setMediaId(id)
            .setTitle(title)
            .setSubtitle(subtitle)
            .build()

        return MediaBrowserCompat.MediaItem(
            description,
            MediaBrowserCompat.MediaItem.FLAG_BROWSABLE
        )
    }

    fun buildMedia3Item(recording: VoiceRecording): MediaItem {
        val metadata = MediaMetadata.Builder()
            .setTitle(recording.title)
            .setArtist("Voice Recording")
            .setDisplayTitle(recording.title)
            .setSubtitle(recording.formattedDate)
            .build()

        return MediaItem.Builder()
            .setMediaId(recording.id)
            .setUri(recording.uri)
            .setMediaMetadata(metadata)
            .build()
    }

    fun buildMediaMetadata(recording: VoiceRecording): MediaMetadataCompat {
        return MediaMetadataCompat.Builder()
            .putString(MediaMetadataCompat.METADATA_KEY_MEDIA_ID, recording.id)
            .putString(MediaMetadataCompat.METADATA_KEY_TITLE, recording.title)
            .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_TITLE, recording.title)
            .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_SUBTITLE, recording.formattedDate)
            .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_DESCRIPTION, recording.formattedDuration)
            .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, recording.duration)
            .putString(MediaMetadataCompat.METADATA_KEY_MEDIA_URI, recording.uri.toString())
            .build()
    }

    fun getMediaIdFromUri(uri: Uri): String {
        return uri.lastPathSegment ?: uri.toString().hashCode().toString()
    }
}
