# ClipCam

**ClipCam** is a high-performance Android camera application designed specifically for capturing sports highlights and "instant replay" moments. Instead of recording hours of footage, ClipCam maintains a continuous in-memory buffer, allowing you to save only the last few seconds of action *after* it happens.

## 🚀 Key Features

- **Continuous In-Memory Buffering:** Maintains a rolling window of encoded video and audio packets using `MediaCodec`, ensuring zero delay between back-to-back captures.
- **One-Tap Highlight Save:** Tap the shutter to instantly mux the last few seconds of action to your gallery without stopping the live buffer.
- **Pro Camera Controls:**
  - **Variable Frame Rates:** Support for 60FPS (smooth motion) and 30FPS.
  - **High Resolution:** Toggle between SD, HD, FHD, and UHD (4K).
  - **Precision Zoom:** Features a smooth scrollable ruler and quick-access shortcuts.
  - **Tap-to-Focus:** Manual focus and metering support.
- **Advanced Audio Sync:** Configurable lag compensation in settings to ensure perfect audio-video alignment.
- **High-Fidelity Audio:** Captures directional stereo sound optimized for video recording.

## 🛠 Tech Stack

- **Language:** Kotlin
- **Camera Engine:** Jetpack CameraX (with Camera2Interop for FPS control).
- **Video Engine:** Custom `HighlightRecorder` using `MediaCodec` for hardware-accelerated encoding and `MediaMuxer` for instant file generation.
- **Architecture:** Thread-safe concurrent buffering with dedicated background drainage.

## 🛡 Permissions

ClipCam requires:
- `CAMERA`: To capture video.
- `RECORD_AUDIO`: To include high-quality stereo sound.
- `WRITE_EXTERNAL_STORAGE` (API 28 and below): To save highlights to your gallery.

## 📝 License

Distributed under the MIT License.
