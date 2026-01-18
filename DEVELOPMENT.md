# Voice Recorder Auto - Development Guide

## What the App Does

Voice Recorder Auto is an Android application that allows users to browse and play voice recordings through Android Auto. The app:

- Scans the device for voice recordings from common recorder apps
- Displays recordings in a browsable list (all recordings or grouped by date)
- Provides full playback controls (play, pause, skip, seek)
- Integrates with Android Auto's media interface for hands-free use while driving
- Caches recording metadata for fast loading

### Architecture Overview

- **UI Layer**: Jetpack Compose with Material 3
- **Media Playback**: Media3 ExoPlayer
- **Android Auto Integration**: MediaBrowserServiceCompat
- **Async Operations**: Kotlin Coroutines

## Setting Up the Development Environment

### Prerequisites

1. **Java Development Kit (JDK) 17**
   ```bash
   # macOS (Homebrew)
   brew install openjdk@17

   # Verify installation
   java -version
   ```

2. **Android Studio** (Hedgehog 2023.1.1 or newer)
   - Download from: https://developer.android.com/studio
   - During setup, install the Android SDK (API 34)

3. **Android SDK Components**
   Open Android Studio → Settings → Languages & Frameworks → Android SDK:
   - SDK Platforms: Android 14 (API 34)
   - SDK Tools:
     - Android SDK Build-Tools 34
     - Android SDK Command-line Tools
     - Android Emulator
     - Android SDK Platform-Tools

### Clone and Open the Project

```bash
git clone <repository-url>
cd voice-recorder-android
```

Open the project in Android Studio:
- File → Open → Select the `voice-recorder-android` folder
- Wait for Gradle sync to complete

### Device Setup

#### Physical Device (Recommended for Android Auto testing)
1. Enable Developer Options on your Android device
2. Enable USB Debugging
3. Connect via USB and authorize the computer

#### Android Auto Testing
1. Install "Android Auto" app on your device
2. Enable Developer Mode in Android Auto:
   - Open Android Auto settings
   - Tap version number 10 times
   - Enable "Unknown sources" in developer settings
3. Use the "Desktop Head Unit" (DHU) emulator for testing:
   ```bash
   # Install DHU via Android Studio SDK Manager
   # Run DHU
   $ANDROID_HOME/extras/google/auto/desktop-head-unit
   ```

## Building the App

### Using Gradle Wrapper (Command Line)

```bash
# Build debug APK
./gradlew assembleDebug

# Build release APK
./gradlew assembleRelease

# Force rebuild (clean first)
./gradlew clean assembleDebug
```

> **Note**: Gradle uses incremental builds. If no source files changed, `assembleDebug` will report all tasks as `UP-TO-DATE` and won't modify the APK. This is expected - the existing APK already contains the latest code. Use `clean assembleDebug` to force a full rebuild.

The built APK will be at:
- Debug: `app/build/outputs/apk/debug/app-debug.apk`
- Release: `app/build/outputs/apk/release/app-release.apk`

### Install on Device

```bash
# Install debug build (device must be connected)
./gradlew installDebug

# Or use adb directly
adb install -r app/build/outputs/apk/debug/app-debug.apk

# Install and launch
adb install -r app/build/outputs/apk/debug/app-debug.apk && \
adb shell am start -n com.example.voicerecorderauto/.MainActivity
```

### Using Android Studio

1. Select your device from the device dropdown
2. Click the Run button (▶) or press `Shift+F10`

### Running Tests

```bash
# Unit tests
./gradlew test

# Instrumented tests (requires connected device)
./gradlew connectedAndroidTest
```

## Project Structure

```
app/src/main/java/com/example/voicerecorderauto/
├── MainActivity.kt              # Main UI entry point
├── VoiceRecorderAutoApp.kt      # Application class
├── media/
│   ├── MediaItemBuilder.kt      # Builds MediaItems for browsing
│   ├── RecordingRepository.kt   # Loads recordings from storage
│   └── VoiceRecording.kt        # Recording data model
├── service/
│   ├── PlaybackService.kt       # UI-layer playback controller
│   └── VoiceRecorderMediaService.kt  # MediaBrowserService for Auto
└── util/
    └── StorageHelper.kt         # Permission utilities
```

## Configuration

### Supported Recording Paths

The app scans these directories for recordings (see `RecordingRepository.kt`):

- `/Recordings/`
- `/VoiceRecorder/`
- `/Voice Recorder/`
- `/AudioRecorder/`
- `/SoundRecorder/`
- `/Music/`

### Minimum SDK

- **minSdk**: 26 (Android 8.0)
- **targetSdk**: 34 (Android 14)

## Further Improvements

### Pull-to-Refresh

Implement swipe-down refresh in the recordings list:

```kotlin
// In MainActivity.kt, wrap the LazyColumn with PullToRefreshBox
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecordingsList(
    recordings: List<VoiceRecording>,
    onRefresh: () -> Unit
) {
    var isRefreshing by remember { mutableStateOf(false) }

    PullToRefreshBox(
        isRefreshing = isRefreshing,
        onRefresh = {
            isRefreshing = true
            onRefresh()
            // Set isRefreshing = false when complete
        }
    ) {
        LazyColumn {
            items(recordings) { recording ->
                RecordingItem(recording)
            }
        }
    }
}
```

Add the callback to trigger `RecordingRepository.getAllRecordings()` with `forceRefresh = true`.

### Other Potential Improvements

- **Search functionality**: Filter recordings by name
- **Sorting options**: Sort by date, duration, or name
- **Playlist support**: Create and manage playlists
- **Waveform visualization**: Show audio waveform during playback
- **Playback speed control**: 0.5x, 1x, 1.5x, 2x speed options
- **Sleep timer**: Auto-stop playback after duration
- **Recording deletion**: Delete recordings from within the app
- **Custom recording paths**: Let users configure additional scan directories
- **Metadata editing**: Rename recordings
- **Android Auto improvements**:
  - Album art / recording thumbnails
  - Voice commands integration
  - Queue management

## Troubleshooting

### Build Issues

**Gradle sync failed**:
```bash
./gradlew clean
./gradlew --refresh-dependencies
```

**SDK version mismatch**:
Ensure Android SDK 34 is installed via Android Studio SDK Manager.

### Runtime Issues

**No recordings found**:
- Verify storage permission is granted
- Check that recordings exist in supported directories
- Look at logcat for scanning errors

**Android Auto not showing app**:
- Ensure "Unknown sources" is enabled in Android Auto developer settings
- Verify the `automotive_app_desc.xml` is correctly configured
- Check that the MediaBrowserService is properly declared in AndroidManifest.xml

**No audio playback**:
- Verify audio focus is being requested (check logcat for AudioManager logs)
- Ensure the device isn't in Do Not Disturb mode
- Test with a different audio file format
