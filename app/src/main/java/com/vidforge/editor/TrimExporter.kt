package com.vidforge.editor

import android.content.ContentValues
import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import java.nio.ByteBuffer

/**
 * Lossless trim/split: copies the video track (and the audio track unless
 * muted) between [startUs] and [endUs] without re-encoding, so quality is
 * identical to the source and export is fast. Original code for VidForge.
 */
object TrimExporter {

    /**
     * Extract only the audio track of the trim range as an .m4a file
     * (no re-encode — the AAC samples are copied as-is).
     */
    fun extractAudio(
        context: Context,
        src: Uri,
        startUs: Long,
        endUs: Long,
        displayName: String
    ): Uri? {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(context, src, null)
        } catch (e: Exception) {
            return null
        }
        var audioTrack = -1
        var audioFormat: MediaFormat? = null
        for (i in 0 until extractor.trackCount) {
            val format = extractor.getTrackFormat(i)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
            if (audioTrack < 0 && mime.startsWith("audio/")) {
                audioTrack = i
                audioFormat = format
            }
        }
        if (audioTrack < 0 || audioFormat == null) {
            extractor.release()
            return null
        }
        val values = ContentValues().apply {
            put(MediaStore.Audio.Media.DISPLAY_NAME, displayName)
            put(MediaStore.Audio.Media.MIME_TYPE, "audio/mp4")
            put(MediaStore.Audio.Media.RELATIVE_PATH, Environment.DIRECTORY_MUSIC + "/VidForge")
        }
        val outUri = context.contentResolver.insert(
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values
        ) ?: run { extractor.release(); return null }
        var muxer: MediaMuxer? = null
        try {
            val pfd = context.contentResolver.openFileDescriptor(outUri, "w")
                ?: run { extractor.release(); return null }
            muxer = MediaMuxer(pfd.fileDescriptor, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            val muxerTrack = muxer.addTrack(audioFormat)
            muxer.start()
            extractor.selectTrack(audioTrack)
            extractor.seekTo(startUs, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
            val buffer = ByteBuffer.allocate(512 * 1024)
            val info = MediaCodec.BufferInfo()
            while (true) {
                val size = extractor.readSampleData(buffer, 0)
                if (size < 0) break
                val t = extractor.sampleTime
                if (t > endUs) break
                if (t < startUs) {
                    extractor.advance()
                    continue
                }
                info.offset = 0
                info.size = size
                info.presentationTimeUs = t - startUs
                info.flags = extractor.sampleFlags
                muxer.writeSampleData(muxerTrack, buffer, info)
                extractor.advance()
            }
            muxer.stop()
            muxer.release()
            extractor.release()
            return outUri
        } catch (e: Exception) {
            try { muxer?.release() } catch (ignored: Exception) { }
            try { extractor.release() } catch (ignored: Exception) { }
            try { context.contentResolver.delete(outUri, null, null) } catch (ignored: Exception) { }
            return null
        }
    }

    fun export(
        context: Context,
        src: Uri,
        startUs: Long,
        endUs: Long,
        includeAudio: Boolean,
        orientationDegrees: Int,
        displayName: String
    ): Uri? {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(context, src, null)
        } catch (e: Exception) {
            return null
        }
        var videoTrack = -1
        var audioTrack = -1
        for (i in 0 until extractor.trackCount) {
            val format = extractor.getTrackFormat(i)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
            if (videoTrack < 0 && mime.startsWith("video/")) videoTrack = i
            if (audioTrack < 0 && mime.startsWith("audio/")) audioTrack = i
        }
        if (videoTrack < 0) {
            extractor.release()
            return null
        }

        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, displayName)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/VidForge")
        }
        val outUri = context.contentResolver.insert(
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values
        ) ?: run { extractor.release(); return null }

        var muxer: MediaMuxer? = null
        try {
            val pfd = context.contentResolver.openFileDescriptor(outUri, "w")
                ?: run { extractor.release(); return null }
            muxer = MediaMuxer(pfd.fileDescriptor, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            muxer.setOrientationHint(orientationDegrees)

            val trackMap = HashMap<Int, Int>()
            extractor.selectTrack(videoTrack)
            trackMap[videoTrack] = muxer.addTrack(extractor.getTrackFormat(videoTrack))
            if (includeAudio && audioTrack >= 0) {
                trackMap[audioTrack] = muxer.addTrack(extractor.getTrackFormat(audioTrack))
            }
            muxer.start()

            val buffer = ByteBuffer.allocate(4 * 1024 * 1024)
            val info = MediaCodec.BufferInfo()
            for ((srcTrack, _) in trackMap) {
                extractor.selectTrack(srcTrack)
                extractor.seekTo(startUs, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
                while (true) {
                    info.offset = 0
                    info.size = extractor.readSampleData(buffer, 0)
                    if (info.size < 0) break
                    val sampleTime = extractor.sampleTime
                    if (sampleTime > endUs) break
                    if (sampleTime < startUs) {
                        extractor.advance()
                        continue
                    }
                    info.presentationTimeUs = sampleTime
                    info.flags = extractor.sampleFlags
                    muxer.writeSampleData(trackMap[srcTrack]!!, buffer, info)
                    extractor.advance()
                }
                extractor.unselectTrack(srcTrack)
            }
            muxer.stop()
            muxer.release()
            muxer = null
            extractor.release()
            return outUri
        } catch (e: Exception) {
            try { muxer?.release() } catch (ignored: Exception) { }
            try { extractor.release() } catch (ignored: Exception) { }
            try { context.contentResolver.delete(outUri, null, null) } catch (ignored: Exception) { }
            return null
        }
    }
}
