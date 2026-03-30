package com.clipcam

import android.content.ContentValues
import android.content.Context
import android.media.*
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import java.io.File
import java.nio.ByteBuffer
import java.text.SimpleDateFormat
import java.util.*

object VideoTrimmer {
    private const val TAG = "VideoTrimmer"

    fun mergeAndTrim(
        context: Context,
        files: List<File>,
        targetDurationSec: Int,
        onComplete: (Uri?) -> Unit
    ) {
        if (files.isEmpty()) {
            onComplete(null)
            return
        }

        val finalHighlight = File(context.cacheDir, "final_highlight_${System.currentTimeMillis()}.mp4")
        
        try {
            val retriever = MediaMetadataRetriever()
            retriever.setDataSource(files[0].absolutePath)
            val rotationStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)
            val rotation = rotationStr?.toInt() ?: 0
            retriever.release()

            val muxer = MediaMuxer(finalHighlight.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            muxer.setOrientationHint(rotation)
            
            val trackMap = mutableMapOf<Int, Int>()
            val firstExtractor = MediaExtractor()
            firstExtractor.setDataSource(files[0].absolutePath)
            
            var maxInputSize = 1024 * 1024 * 16 // Start with 16MB for 4K
            
            for (i in 0 until firstExtractor.trackCount) {
                val format = firstExtractor.getTrackFormat(i)
                
                // Ensure the format has a large enough buffer size defined
                if (format.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) {
                    val size = format.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE)
                    if (size > maxInputSize) maxInputSize = size
                }
                format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, maxInputSize)

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    format.removeKey(MediaFormat.KEY_ROTATION)
                } else {
                    format.setInteger(MediaFormat.KEY_ROTATION, 0)
                }

                trackMap[i] = muxer.addTrack(format)
            }
            
            firstExtractor.release()
            muxer.start()

            var totalDurationUs = 0L
            val fileDurations = mutableListOf<Long>()
            files.forEach { file ->
                val ex = MediaExtractor()
                ex.setDataSource(file.absolutePath)
                var d = 0L
                for (i in 0 until ex.trackCount) {
                    val format = ex.getTrackFormat(i)
                    if (format.getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true) {
                        if (format.containsKey(MediaFormat.KEY_DURATION)) {
                            d = format.getLong(MediaFormat.KEY_DURATION)
                        }
                        break
                    }
                }
                fileDurations.add(d)
                totalDurationUs += d
                ex.release()
            }

            val startTrimUs = (totalDurationUs - (targetDurationSec * 1_000_000L)).coerceAtLeast(0L)
            
            val buffer = ByteBuffer.allocate(maxInputSize)
            val bufferInfo = MediaCodec.BufferInfo()
            var globalBaseOffsetUs = 0L
            var isFirstSampleWritten = false
            var firstPtsInFinalUs = 0L

            files.forEachIndexed { index, file ->
                val extractor = MediaExtractor()
                extractor.setDataSource(file.absolutePath)
                var fileVideoTrack = -1

                for (i in 0 until extractor.trackCount) {
                    extractor.selectTrack(i)
                    val format = extractor.getTrackFormat(i)
                    if (format.getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true) {
                        fileVideoTrack = i
                    }
                }

                while (true) {
                    val sampleSize = extractor.readSampleData(buffer, 0)
                    if (sampleSize < 0) break

                    val rawPts = extractor.sampleTime
                    val absolutePts = globalBaseOffsetUs + rawPts
                    
                    if (absolutePts >= startTrimUs) {
                        val sampleFlags = extractor.sampleFlags
                        val isKeyframe = (sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC) != 0
                        
                        if (!isFirstSampleWritten) {
                            if (extractor.sampleTrackIndex == fileVideoTrack && isKeyframe) {
                                isFirstSampleWritten = true
                                firstPtsInFinalUs = absolutePts
                            } else {
                                extractor.advance()
                                continue
                            }
                        }

                        bufferInfo.offset = 0
                        bufferInfo.size = sampleSize
                        bufferInfo.presentationTimeUs = absolutePts - firstPtsInFinalUs
                        
                        // Map flags safely for API 24+
                        var flags = 0
                        if ((sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC) != 0) {
                            flags = flags or MediaCodec.BUFFER_FLAG_KEY_FRAME
                        }
                        // BUFFER_FLAG_PARTIAL_FRAME requires API 26
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                            if ((sampleFlags and MediaExtractor.SAMPLE_FLAG_PARTIAL_FRAME) != 0) {
                                flags = flags or MediaCodec.BUFFER_FLAG_PARTIAL_FRAME
                            }
                        }
                        bufferInfo.flags = flags
                        
                        val dstTrack = trackMap[extractor.sampleTrackIndex]
                        if (dstTrack != null) {
                            muxer.writeSampleData(dstTrack, buffer, bufferInfo)
                        }
                    }
                    extractor.advance()
                }
                globalBaseOffsetUs += fileDurations[index]
                extractor.release()
            }

            muxer.stop()
            muxer.release()

            val uri = saveToGallery(context, finalHighlight)
            onComplete(uri)
            finalHighlight.delete()

        } catch (e: Exception) {
            Log.e(TAG, "Highlight processing failed: ${e.message}", e)
            onComplete(null)
        }
    }

    private fun saveToGallery(context: Context, file: File): Uri? {
        val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmssSSS", Locale.US).format(Date())
        val fileName = "ClipCam_$timeStamp.mp4"
        
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, fileName)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Video.Media.RELATIVE_PATH, "DCIM/ClipCam")
            }
        }
        val uri = context.contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
        uri?.let {
            context.contentResolver.openOutputStream(it)?.use { out ->
                file.inputStream().use { input -> input.copyTo(out) }
            }
        }
        return uri
    }
}
