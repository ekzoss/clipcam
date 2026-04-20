package com.clipcam

import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Context
import android.media.*
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.provider.MediaStore
import android.util.Log
import android.view.Surface
import java.io.File
import java.nio.ByteBuffer
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.ConcurrentLinkedDeque
import java.util.concurrent.atomic.AtomicBoolean

class HighlightRecorder(
    private val width: Int,
    private val height: Int,
    private val bitRate: Int,
    private val frameRate: Int,
    private var bufferDurationSec: Int,
    private var lagCompensationMs: Long = 250L
) {
    private var videoEncoder: MediaCodec? = null
    var inputSurface: Surface? = null
        private set

    private var audioEncoder: MediaCodec? = null
    private var audioRecord: AudioRecord? = null

    private val videoBuffer = ConcurrentLinkedDeque<EncodedPacket>()
    private val audioBuffer = ConcurrentLinkedDeque<EncodedPacket>()
    
    private var videoFormat: MediaFormat? = null
    private var audioFormat: MediaFormat? = null

    private val isRunning = AtomicBoolean(false)
    private var videoThread: Thread? = null
    private var audioThread: Thread? = null
    private var audioInputThread: Thread? = null

    data class EncodedPacket(
        val data: ByteArray,
        val info: MediaCodec.BufferInfo,
        val isKeyFrame: Boolean
    )

    fun start() {
        if (isRunning.get()) return
        isRunning.set(true)
        
        setupVideoEncoder()
        setupAudioEncoder()

        videoThread = Thread({ drainVideoEncoder() }, "VideoDrainThread").apply { start() }
        audioThread = Thread({ drainAudioEncoder() }, "AudioDrainThread").apply { start() }
        audioInputThread = Thread({ recordAudio() }, "AudioInputThread").apply { start() }
        
        Log.d(TAG, "HighlightRecorder started: ${width}x${height} @ $frameRate FPS, lag: ${lagCompensationMs}ms")
    }

    private fun setupVideoEncoder() {
        try {
            val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height)
            format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            format.setInteger(MediaFormat.KEY_BIT_RATE, bitRate)
            format.setInteger(MediaFormat.KEY_FRAME_RATE, frameRate)
            format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)

            videoEncoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            videoEncoder?.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            inputSurface = videoEncoder?.createInputSurface()
            videoEncoder?.start()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to setup video encoder", e)
        }
    }

    @SuppressLint("MissingPermission")
    private fun setupAudioEncoder() {
        try {
            val sampleRate = 44100
            val channelConfig = AudioFormat.CHANNEL_IN_MONO
            val audioFormatType = AudioFormat.ENCODING_PCM_16BIT
            val bufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormatType)
            
            audioRecord = AudioRecord(MediaRecorder.AudioSource.MIC, sampleRate, channelConfig, audioFormatType, bufferSize)
            
            val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, 1)
            format.setInteger(MediaFormat.KEY_BIT_RATE, 128000)
            format.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, bufferSize * 2)

            audioEncoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
            audioEncoder?.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            audioEncoder?.start()
            
            audioRecord?.startRecording()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to setup audio encoder", e)
        }
    }

    private fun drainVideoEncoder() {
        val bufferInfo = MediaCodec.BufferInfo()
        while (isRunning.get()) {
            val encoder = videoEncoder ?: break
            try {
                val outputBufferId = encoder.dequeueOutputBuffer(bufferInfo, 10000)
                if (outputBufferId == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    videoFormat = encoder.outputFormat
                } else if (outputBufferId >= 0) {
                    val outputBuffer = encoder.getOutputBuffer(outputBufferId) ?: continue
                    if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0 && bufferInfo.size > 0) {
                        val data = ByteArray(bufferInfo.size)
                        outputBuffer.get(data)
                        val isKeyFrame = (bufferInfo.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0
                        videoBuffer.add(EncodedPacket(data, cloneBufferInfo(bufferInfo), isKeyFrame))
                        trimBuffer(videoBuffer)
                    }
                    encoder.releaseOutputBuffer(outputBufferId, false)
                }
            } catch (e: Exception) {
                if (isRunning.get()) Log.e(TAG, "Video drain error", e)
            }
        }
    }

    private fun recordAudio() {
        val sampleRate = 44100
        val bufferSize = 4096
        val bufferDurationUs = (bufferSize / 2) * 1_000_000L / sampleRate
        val buffer = ByteArray(bufferSize)
        
        while (isRunning.get()) {
            val read = audioRecord?.read(buffer, 0, buffer.size) ?: -1
            if (read > 0) {
                val encoder = audioEncoder ?: continue
                try {
                    val inputBufferId = encoder.dequeueInputBuffer(10000)
                    if (inputBufferId >= 0) {
                        val inputBuffer = encoder.getInputBuffer(inputBufferId) ?: continue
                        inputBuffer.clear()
                        inputBuffer.put(buffer, 0, read)
                        
                        // Use System.nanoTime() to match the clock MediaCodec uses for surface input.
                        val captureTimeUs = (System.nanoTime() / 1000) - bufferDurationUs - (lagCompensationMs * 1000)
                        
                        encoder.queueInputBuffer(inputBufferId, 0, read, captureTimeUs, 0)
                    }
                } catch (e: Exception) {
                    if (isRunning.get()) Log.e(TAG, "Audio input error", e)
                }
            }
        }
    }

    private fun drainAudioEncoder() {
        val bufferInfo = MediaCodec.BufferInfo()
        while (isRunning.get()) {
            val encoder = audioEncoder ?: break
            try {
                val outputBufferId = encoder.dequeueOutputBuffer(bufferInfo, 10000)
                if (outputBufferId == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    audioFormat = encoder.outputFormat
                } else if (outputBufferId >= 0) {
                    val outputBuffer = encoder.getOutputBuffer(outputBufferId) ?: continue
                    if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0 && bufferInfo.size > 0) {
                        val data = ByteArray(bufferInfo.size)
                        outputBuffer.get(data)
                        audioBuffer.add(EncodedPacket(data, cloneBufferInfo(bufferInfo), false))
                        trimBuffer(audioBuffer)
                    }
                    encoder.releaseOutputBuffer(outputBufferId, false)
                }
            } catch (e: Exception) {
                if (isRunning.get()) Log.e(TAG, "Audio drain error", e)
            }
        }
    }

    private fun trimBuffer(buffer: ConcurrentLinkedDeque<EncodedPacket>) {
        if (buffer.isEmpty()) return
        val bufferDurationUs = (bufferDurationSec + 2) * 1_000_000L
        while (buffer.size > 20 && (buffer.last.info.presentationTimeUs - buffer.first.info.presentationTimeUs) > bufferDurationUs) {
            buffer.removeFirst()
        }
    }

    private fun cloneBufferInfo(info: MediaCodec.BufferInfo): MediaCodec.BufferInfo {
        val newInfo = MediaCodec.BufferInfo()
        newInfo.set(info.offset, info.size, info.presentationTimeUs, info.flags)
        return newInfo
    }

    fun stop() {
        isRunning.set(false)
        try {
            videoThread?.join(500)
            audioThread?.join(500)
            audioInputThread?.join(500)
            
            videoEncoder?.stop()
            videoEncoder?.release()
            audioEncoder?.stop()
            audioEncoder?.release()
            audioRecord?.stop()
            audioRecord?.release()
        } catch (e: Exception) {
            Log.e(TAG, "Error during stop", e)
        }
        
        videoEncoder = null
        audioEncoder = null
        audioRecord = null
        inputSurface?.release()
        inputSurface = null
        
        videoBuffer.clear()
        audioBuffer.clear()
    }

    fun updateBufferDuration(seconds: Int) {
        bufferDurationSec = seconds
    }

    fun updateLagCompensation(ms: Long) {
        lagCompensationMs = ms
    }

    fun saveHighlight(context: Context, rotationHint: Int, onComplete: (Uri?) -> Unit) {
        val vSnapshot = videoBuffer.toList()
        val aSnapshot = audioBuffer.toList()
        val vFormat = videoFormat
        val aFormat = audioFormat

        if (vSnapshot.isEmpty() || vFormat == null) {
            Log.e(TAG, "Cannot save: vSnapshot empty or vFormat null")
            onComplete(null)
            return
        }

        Thread {
            try {
                val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmssSSS", Locale.US).format(Date())
                val tempFile = File(context.cacheDir, "HL_$timeStamp.mp4")
                val muxer = MediaMuxer(tempFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
                
                muxer.setOrientationHint(rotationHint)
                
                val vTrack = muxer.addTrack(vFormat)
                val aTrack = if (aFormat != null) muxer.addTrack(aFormat) else -1
                muxer.start()

                val targetDurationUs = bufferDurationSec * 1_000_000L
                val lastVPts = vSnapshot.last().info.presentationTimeUs
                val targetStartPts = lastVPts - targetDurationUs

                var firstVIdx = vSnapshot.indexOfFirst { it.isKeyFrame && it.info.presentationTimeUs >= targetStartPts }
                if (firstVIdx == -1) firstVIdx = vSnapshot.indexOfFirst { it.isKeyFrame }
                
                if (firstVIdx != -1) {
                    val basePts = vSnapshot[firstVIdx].info.presentationTimeUs
                    
                    val maxVSize = vSnapshot.maxOf { it.data.size }
                    val maxASize = if (aSnapshot.isNotEmpty()) aSnapshot.maxOf { it.data.size } else 0
                    val buffer = ByteBuffer.allocate(maxOf(maxVSize, maxASize))
                    
                    // Write Video
                    for (i in firstVIdx until vSnapshot.size) {
                        val p = vSnapshot[i]
                        buffer.clear()
                        buffer.put(p.data)
                        buffer.flip()
                        val info = cloneBufferInfo(p.info)
                        info.presentationTimeUs -= basePts
                        muxer.writeSampleData(vTrack, buffer, info)
                    }
                    
                    // Write Audio - use the same basePts for absolute sync
                    if (aTrack != -1) {
                        aSnapshot.filter { it.info.presentationTimeUs >= basePts }.forEach { p ->
                            buffer.clear()
                            buffer.put(p.data)
                            buffer.flip()
                            val info = cloneBufferInfo(p.info)
                            info.presentationTimeUs -= basePts
                            if (info.presentationTimeUs >= 0) {
                                muxer.writeSampleData(aTrack, buffer, info)
                            }
                        }
                    }
                }

                muxer.stop()
                muxer.release()
                val uri = saveToGallery(context, tempFile)
                tempFile.delete()
                onComplete(uri)
            } catch (e: Exception) {
                Log.e(TAG, "Save failed", e)
                onComplete(null)
            }
        }.start()
    }

    private fun saveToGallery(context: Context, file: File): Uri? {
        val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val fileName = "ClipCam_$timeStamp.mp4"
        val values = ContentValues()
        values.put(MediaStore.Video.Media.DISPLAY_NAME, fileName)
        values.put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            values.put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/ClipCam")
        }
        
        val uri = context.contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
        uri?.let {
            context.contentResolver.openOutputStream(it)?.use { out ->
                file.inputStream().use { input -> input.copyTo(out) }
            }
        }
        return uri
    }

    companion object { private const val TAG = "HighlightRecorder" }
}
