# ClipCam

**ClipCam** is a high-performance Android camera application designed specifically for capturing sports highlights and "instant replay" moments. Instead of recording hours of footage, ClipCam maintains a continuous buffer, allowing you to save only the last few seconds of action *after* it happens.

## 🚀 Key Features

- **Continuous Buffering:** Keeps a sliding window of video (e.g., last 6-10 seconds) in memory/cache.
- **One-Tap Highlight Save:** Press the "Save" button after a great play to permanently store that specific moment to your gallery.
- **Pro Camera Controls:**
  - **Variable Frame Rates:** Support for 60FPS (smooth motion) and 30FPS.
  - **High Resolution:** Toggle between SD, HD, FHD, and UHD (4K) depending on your device capabilities.
  - **Precision Zoom:** Features a smooth scrollable ruler and quick-access shortcuts (0.5x, 1x, 2x).
  - **Tap-to-Focus:** Manual focus and metering support.
- **Smart UI:**
  - **Orientation Locking:** UI elements rotate to match your grip while keeping the recording orientation stable.
  - **Settings Panel:** On-the-fly adjustment of buffer duration and video quality.

## 🛠 Tech Stack

- **Language:** Kotlin
- **Camera Engine:** [Jetpack CameraX](https://developer.android.com/jetpack/androidx/releases/camera) (utilizing Camera2Interop for advanced FPS control).
- **Video Processing:** Custom segment merging and trimming logic for seamless highlight generation.
- **Architecture:** ViewBinding for clean, type-safe UI interactions.

## 📥 Installation

Currently, ClipCam is in development. To use it:
1. Clone this repository.
2. Open the project in **Android Studio Koala** (or newer).
3. Build and deploy to an Android device (Physical device recommended for CameraX features).

## 🛡 Permissions

ClipCam requires the following permissions to function:
- `CAMERA`: To capture video.
- `RECORD_AUDIO`: To include sound in your highlights.
- `WRITE_EXTERNAL_STORAGE` (API 28 and below): To save videos to your gallery.

## 📝 License

Distributed under the MIT License. See `LICENSE` for more information.
