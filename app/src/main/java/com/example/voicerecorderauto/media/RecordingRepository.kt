package com.example.voicerecorderauto.media

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class VoiceRecording(
    val id: String,
    val title: String,
    val uri: Uri,
    val duration: Long,
    val dateAdded: Long,
    val filePath: String,
    val size: Long
) {
    val formattedDate: String
        get() = SimpleDateFormat("MMM dd, yyyy HH:mm", Locale.getDefault())
            .format(Date(dateAdded * 1000))

    val formattedDuration: String
        get() {
            val seconds = duration / 1000
            val minutes = seconds / 60
            val hours = minutes / 60
            return when {
                hours > 0 -> String.format(Locale.getDefault(), "%d:%02d:%02d", hours, minutes % 60, seconds % 60)
                else -> String.format(Locale.getDefault(), "%d:%02d", minutes, seconds % 60)
            }
        }
}

class RecordingRepository(private val context: Context) {

    companion object {
        private val SAMSUNG_VOICE_RECORDER_PATHS = listOf(
            "Recordings",
            "Samsung/Voice Recorder",
            "Voice Recorder",
            "VOICE"
        )

        private val SUPPORTED_EXTENSIONS = listOf(
            ".m4a", ".3gp", ".mp3", ".wav", ".ogg", ".aac", ".amr"
        )
    }

    suspend fun getAllRecordings(): List<VoiceRecording> = withContext(Dispatchers.IO) {
        val recordings = mutableListOf<VoiceRecording>()

        // Get recordings from MediaStore
        recordings.addAll(getRecordingsFromMediaStore())

        // Also scan known Samsung Voice Recorder directories
        recordings.addAll(scanVoiceRecorderDirectories())

        // Remove duplicates based on file path
        recordings.distinctBy { it.filePath }
            .sortedByDescending { it.dateAdded }
    }

    private fun getRecordingsFromMediaStore(): List<VoiceRecording> {
        val recordings = mutableListOf<VoiceRecording>()

        val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        } else {
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        }

        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.DISPLAY_NAME,
            MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.DATE_ADDED,
            MediaStore.Audio.Media.DATA,
            MediaStore.Audio.Media.SIZE
        )

        // Filter for recordings in voice recorder directories
        val selection = buildMediaStoreSelection()
        val sortOrder = "${MediaStore.Audio.Media.DATE_ADDED} DESC"

        context.contentResolver.query(
            collection,
            projection,
            selection,
            null,
            sortOrder
        )?.use { cursor ->
            val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
            val nameColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DISPLAY_NAME)
            val durationColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
            val dateColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATE_ADDED)
            val dataColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATA)
            val sizeColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.SIZE)

            while (cursor.moveToNext()) {
                val id = cursor.getLong(idColumn)
                val name = cursor.getString(nameColumn) ?: "Unknown Recording"
                val duration = cursor.getLong(durationColumn)
                val dateAdded = cursor.getLong(dateColumn)
                val filePath = cursor.getString(dataColumn) ?: ""
                val size = cursor.getLong(sizeColumn)

                // Only include files from voice recorder paths
                if (isVoiceRecordingPath(filePath)) {
                    val contentUri = ContentUris.withAppendedId(
                        MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                        id
                    )

                    recordings.add(
                        VoiceRecording(
                            id = id.toString(),
                            title = name.substringBeforeLast("."),
                            uri = contentUri,
                            duration = duration,
                            dateAdded = dateAdded,
                            filePath = filePath,
                            size = size
                        )
                    )
                }
            }
        }

        return recordings
    }

    private fun buildMediaStoreSelection(): String? {
        // We'll filter in code instead of SQL for flexibility
        return null
    }

    private fun isVoiceRecordingPath(path: String): Boolean {
        val lowerPath = path.lowercase()
        return SAMSUNG_VOICE_RECORDER_PATHS.any { voicePath ->
            lowerPath.contains(voicePath.lowercase())
        }
    }

    private fun scanVoiceRecorderDirectories(): List<VoiceRecording> {
        val recordings = mutableListOf<VoiceRecording>()
        val externalStorage = Environment.getExternalStorageDirectory()

        for (relativePath in SAMSUNG_VOICE_RECORDER_PATHS) {
            val directory = File(externalStorage, relativePath)
            if (directory.exists() && directory.isDirectory) {
                recordings.addAll(scanDirectory(directory))
            }
        }

        return recordings
    }

    private fun scanDirectory(directory: File): List<VoiceRecording> {
        val recordings = mutableListOf<VoiceRecording>()

        directory.listFiles()?.forEach { file ->
            if (file.isDirectory) {
                recordings.addAll(scanDirectory(file))
            } else if (isAudioFile(file)) {
                val recording = createRecordingFromFile(file)
                if (recording != null) {
                    recordings.add(recording)
                }
            }
        }

        return recordings
    }

    private fun isAudioFile(file: File): Boolean {
        return SUPPORTED_EXTENSIONS.any { ext ->
            file.name.lowercase().endsWith(ext)
        }
    }

    private fun createRecordingFromFile(file: File): VoiceRecording? {
        return try {
            VoiceRecording(
                id = file.absolutePath.hashCode().toString(),
                title = file.nameWithoutExtension,
                uri = Uri.fromFile(file),
                duration = getAudioDuration(file),
                dateAdded = file.lastModified() / 1000,
                filePath = file.absolutePath,
                size = file.length()
            )
        } catch (e: Exception) {
            null
        }
    }

    private fun getAudioDuration(file: File): Long {
        return try {
            val retriever = android.media.MediaMetadataRetriever()
            retriever.setDataSource(file.absolutePath)
            val duration = retriever.extractMetadata(
                android.media.MediaMetadataRetriever.METADATA_KEY_DURATION
            )?.toLongOrNull() ?: 0L
            retriever.release()
            duration
        } catch (e: Exception) {
            0L
        }
    }

    /**
     * Groups pre-loaded recordings by date (month/year).
     * This method performs NO I/O - it only processes the provided list.
     * Use this instead of getRecordingsByDate() to avoid redundant loading.
     */
    fun groupRecordingsByDate(recordings: List<VoiceRecording>): Map<String, List<VoiceRecording>> {
        val dateFormat = SimpleDateFormat("MMMM yyyy", Locale.getDefault())
        return recordings.groupBy { recording ->
            dateFormat.format(Date(recording.dateAdded * 1000))
        }
    }

    @Deprecated("Use groupRecordingsByDate(recordings) with pre-loaded data instead - this blocks the calling thread")
    fun getRecordingsByDate(): Map<String, List<VoiceRecording>> {
        val dateFormat = SimpleDateFormat("MMMM yyyy", Locale.getDefault())
        return getAllRecordingsSync().groupBy { recording ->
            dateFormat.format(Date(recording.dateAdded * 1000))
        }
    }

    private fun getAllRecordingsSync(): List<VoiceRecording> {
        val recordings = mutableListOf<VoiceRecording>()
        recordings.addAll(getRecordingsFromMediaStore())
        recordings.addAll(scanVoiceRecorderDirectories())
        return recordings.distinctBy { it.filePath }.sortedByDescending { it.dateAdded }
    }
}
