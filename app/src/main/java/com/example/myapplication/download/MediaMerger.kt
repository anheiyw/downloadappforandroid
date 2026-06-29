package com.example.myapplication.download

import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.util.Log
import java.io.File
import java.nio.ByteBuffer

object MediaMerger {
    private const val TAG = "MediaMerger"

    fun mergeAudioVideo(
        videoFile: File,
        audioFile: File,
        outputFile: File
    ): Result<File> {
        return try {
            var videoExtractor: MediaExtractor? = null
            var audioExtractor: MediaExtractor? = null
            var muxer: MediaMuxer? = null

            try {
                videoExtractor = MediaExtractor()
                videoExtractor.setDataSource(videoFile.absolutePath)

                var videoTrackIndex = -1
                var videoFormat: MediaFormat? = null
                for (i in 0 until videoExtractor.trackCount) {
                    val format = videoExtractor.getTrackFormat(i)
                    val mime = format.getString(MediaFormat.KEY_MIME) ?: ""
                    if (mime.startsWith("video/")) {
                        videoTrackIndex = i
                        videoFormat = format
                        break
                    }
                }

                if (videoTrackIndex == -1 || videoFormat == null) {
                    return Result.failure(Exception("No video track found in video file"))
                }

                videoExtractor.selectTrack(videoTrackIndex)

                audioExtractor = MediaExtractor()
                audioExtractor.setDataSource(audioFile.absolutePath)

                var audioTrackIndex = -1
                var audioFormat: MediaFormat? = null
                for (i in 0 until audioExtractor.trackCount) {
                    val format = audioExtractor.getTrackFormat(i)
                    val mime = format.getString(MediaFormat.KEY_MIME) ?: ""
                    if (mime.startsWith("audio/")) {
                        audioTrackIndex = i
                        audioFormat = format
                        break
                    }
                }

                if (audioTrackIndex == -1 || audioFormat == null) {
                    return Result.failure(Exception("No audio track found in audio file"))
                }

                audioExtractor.selectTrack(audioTrackIndex)

                muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
                val muxerVideoTrack = muxer.addTrack(videoFormat)
                val muxerAudioTrack = muxer.addTrack(audioFormat)
                muxer.start()

                val bufferSize = 1024 * 1024
                val videoBuffer = ByteBuffer.allocate(bufferSize)
                val audioBuffer = ByteBuffer.allocate(bufferSize)

                var videoDone = false
                var audioDone = false

                val maxPts = Math.max(
                    getTrackDuration(videoExtractor),
                    getTrackDuration(audioExtractor)
                )

                while (!videoDone || !audioDone) {
                    if (!videoDone) {
                        videoExtractor.readSampleData(videoBuffer, 0)
                        val videoSize = videoExtractor.sampleSize.toInt()
                        if (videoSize < 0) {
                            videoDone = true
                        } else {
                            val videoPts = videoExtractor.sampleTime
                            if (!audioDone && videoPts > maxPts) {
                                videoDone = true
                            } else {
                                val info = android.media.MediaCodec.BufferInfo()
                                info.offset = 0
                                info.size = videoSize
                                info.presentationTimeUs = videoPts
                                info.flags = videoExtractor.sampleFlags
                                muxer.writeSampleData(muxerVideoTrack, videoBuffer, info)
                                videoExtractor.advance()
                            }
                        }
                    }

                    if (!audioDone) {
                        audioExtractor.readSampleData(audioBuffer, 0)
                        val audioSize = audioExtractor.sampleSize.toInt()
                        if (audioSize < 0) {
                            audioDone = true
                        } else {
                            val audioPts = audioExtractor.sampleTime
                            if (!videoDone && audioPts > maxPts) {
                                audioDone = true
                            } else {
                                val info = android.media.MediaCodec.BufferInfo()
                                info.offset = 0
                                info.size = audioSize
                                info.presentationTimeUs = audioPts
                                info.flags = audioExtractor.sampleFlags
                                muxer.writeSampleData(muxerAudioTrack, audioBuffer, info)
                                audioExtractor.advance()
                            }
                        }
                    }
                }

                Log.d(TAG, "Merge completed: ${outputFile.absolutePath}")
                Result.success(outputFile)
            } finally {
                try { muxer?.stop() } catch (_: Exception) {}
                try { muxer?.release() } catch (_: Exception) {}
                try { videoExtractor?.release() } catch (_: Exception) {}
                try { audioExtractor?.release() } catch (_: Exception) {}
            }
        } catch (e: Exception) {
            Log.e(TAG, "Merge failed: ${e.message}")
            if (outputFile.exists()) outputFile.delete()
            Result.failure(Exception("MediaMuxer merge failed: ${e.message}"))
        }
    }

    private fun getTrackDuration(extractor: MediaExtractor): Long {
        return try {
            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                if (format.containsKey(MediaFormat.KEY_DURATION)) {
                    return format.getLong(MediaFormat.KEY_DURATION)
                }
            }
            Long.MAX_VALUE
        } catch (_: Exception) {
            Long.MAX_VALUE
        }
    }
}
