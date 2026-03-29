package com.example.myapplication

import android.media.MediaCodec
import android.media.MediaFormat
import android.media.MediaMuxer
import android.util.Log
import java.nio.ByteBuffer
import java.util.*

class CircularMediaBuffer(private val bufferDurationUs: Long) {
    
    private class Sample(
        val data: ByteArray,
        val info: MediaCodec.BufferInfo,
        val isVideo: Boolean
    )

    private val samples = LinkedList<Sample>()
    private var videoFormat: MediaFormat? = null
    private var audioFormat: MediaFormat? = null
    
    @Synchronized
    fun addSample(data: ByteBuffer, info: MediaCodec.BufferInfo, isVideo: Boolean) {
        val buffer = ByteArray(info.size)
        data.position(info.offset)
        data.limit(info.offset + info.size)
        data.get(buffer)
        
        samples.add(Sample(buffer, copyInfo(info), isVideo))
        
        // Trim buffer logic
        if (samples.isNotEmpty()) {
            val latestTime = samples.peekLast()!!.info.presentationTimeUs
            while (samples.size > 1) {
                val oldest = samples.peekFirst()!!
                if (latestTime - oldest.info.presentationTimeUs > bufferDurationUs) {
                    // To keep the buffer startable, we must ensure the first video sample is a keyframe.
                    // We only drop if the NEXT video sample is a keyframe.
                    var nextVideoIdx = -1
                    for (i in 1 until samples.size) {
                        if (samples[i].isVideo) {
                            nextVideoIdx = i
                            break
                        }
                    }

                    if (nextVideoIdx != -1) {
                        val nextVideo = samples[nextVideoIdx]
                        if ((nextVideo.info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0) {
                            // Drop everything before the next video keyframe
                            for (i in 0 until nextVideoIdx) {
                                samples.removeFirst()
                            }
                        } else {
                            // Can't drop yet, need to keep current GOP
                            break
                        }
                    } else {
                        // No more video frames? Just drop the oldest.
                        samples.removeFirst()
                    }
                } else {
                    break
                }
            }
        }
    }

    private fun copyInfo(info: MediaCodec.BufferInfo): MediaCodec.BufferInfo {
        val newInfo = MediaCodec.BufferInfo()
        newInfo.set(info.offset, info.size, info.presentationTimeUs, info.flags)
        return newInfo
    }

    @Synchronized
    fun setFormats(video: MediaFormat?, audio: MediaFormat?) {
        videoFormat = video
        audioFormat = audio
    }

    @Synchronized
    fun savePayload(muxer: MediaMuxer) {
        if (samples.isEmpty()) return

        // Find the first video keyframe to ensure the output starts clean
        var firstKeyframeIdx = -1
        for (i in samples.indices) {
            val s = samples[i]
            if (s.isVideo && (s.info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0) {
                firstKeyframeIdx = i
                break
            }
        }

        if (firstKeyframeIdx == -1) {
            Log.w("CircularBuffer", "No keyframe found in buffer, cannot save.")
            return
        }

        val vTrack = videoFormat?.let { muxer.addTrack(it) } ?: -1
        val aTrack = audioFormat?.let { muxer.addTrack(it) } ?: -1
        
        muxer.start()

        val firstPts = samples[firstKeyframeIdx].info.presentationTimeUs
        
        for (i in firstKeyframeIdx until samples.size) {
            val sample = samples[i]
            val track = if (sample.isVideo) vTrack else aTrack
            if (track == -1) continue
            
            val info = copyInfo(sample.info)
            info.presentationTimeUs -= firstPts
            
            if (info.presentationTimeUs < 0) continue
            
            val buffer = ByteBuffer.wrap(sample.data)
            muxer.writeSampleData(track, buffer, info)
        }
        
        muxer.stop()
    }

    @Synchronized
    fun clear() {
        samples.clear()
    }

    @Synchronized
    fun getAvailableDurationUs(): Long {
        if (samples.size < 2) return 0
        return samples.peekLast()!!.info.presentationTimeUs - samples.peekFirst()!!.info.presentationTimeUs
    }
}
