package com.vidforge.editor

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.SurfaceTexture
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaMetadataRetriever
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.opengl.EGL14
import android.opengl.EGLExt
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.os.Environment
import android.provider.MediaStore
import android.view.Surface
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * VidForge v2a — re-encode engine (original code).
 *
 * Pipeline: MediaExtractor -> MediaCodec decoder -> SurfaceTexture ->
 * OpenGL ES 2.0 (filter shader + static text overlay) -> MediaCodec AVC
 * encoder input surface -> MediaMuxer. Audio is copied losslessly when
 * speed is 1x and not muted; at other speeds audio is dropped (resampling
 * without pitch shift is a later feature) to avoid desync.
 *
 * Runs on the calling thread; callers must run it off the UI thread.
 */
object VideoTranscoder {

    // Filter ids, matching the UI spinner order.
    const val FILTER_NONE = 0
    const val FILTER_GRAY = 1
    const val FILTER_SEPIA = 2
    const val FILTER_WARM = 3
    const val FILTER_COOL = 4
    const val FILTER_INVERT = 5
    const val FILTER_VIGNETTE = 6
    const val FILTER_CHROMA = 7

    // Crop modes, matching the UI spinner order.
    const val CROP_FULL = 0
    const val CROP_SQUARE = 1
    const val CROP_16_9 = 2
    const val CROP_9_16 = 3

    // Sticker shapes, matching the UI spinner order.
    const val SHAPE_NONE = 0
    const val SHAPE_CIRCLE = 1
    const val SHAPE_STAR = 2
    const val SHAPE_HEART = 3
    const val SHAPE_ARROW = 4

    fun transcode(
        context: Context,
        src: Uri,
        startUs: Long,
        endUs: Long,
        filter: Int,
        overlayText: String?,
        overlayPos: Int, // 0 top, 1 center, 2 bottom
        speed: Float,
        includeAudio: Boolean,
        orientationDegrees: Int,
        displayName: String,
        cropMode: Int = CROP_FULL,
        shape: Int = SHAPE_NONE,
        volumeGain: Float = 1.0f,
        fade: Boolean = false,
        musicUri: Uri? = null,
        rotateMode: Int = 0,
        freezeUs: Long = 0,
        qualityMode: Int = 0,
        curveMode: Int = 0,
        textAnim: Int = 0
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
        val videoFormat = extractor.getTrackFormat(videoTrack)
        var srcWidth = videoFormat.getInteger(MediaFormat.KEY_WIDTH)
        var srcHeight = videoFormat.getInteger(MediaFormat.KEY_HEIGHT)
        if (srcWidth % 2 == 1) srcWidth -= 1
        if (srcHeight % 2 == 1) srcHeight -= 1
        // Crop window in source texture coords + output size.
        var texL = 0f
        var texT = 0f
        var texR = 1f
        var texB = 1f
        var width = srcWidth
        var height = srcHeight
        fun even(v: Int) = if (v % 2 == 1) v - 1 else v
        when (cropMode) {
            CROP_SQUARE -> {
                val s = minOf(srcWidth, srcHeight)
                width = s
                height = s
                if (srcWidth > srcHeight) {
                    texL = (srcWidth - srcHeight) / 2f / srcWidth
                    texR = texL + srcHeight.toFloat() / srcWidth
                } else {
                    texT = (srcHeight - srcWidth) / 2f / srcHeight
                    texB = texT + srcWidth.toFloat() / srcHeight
                }
            }
            CROP_16_9 -> {
                val ratio = 16f / 9f
                if (srcWidth.toFloat() / srcHeight > ratio) {
                    height = srcHeight
                    width = even((srcHeight * ratio).toInt())
                    texL = (srcWidth - width) / 2f / srcWidth
                    texR = texL + width.toFloat() / srcWidth
                } else {
                    width = srcWidth
                    height = even((srcWidth / ratio).toInt())
                    texT = (srcHeight - height) / 2f / srcHeight
                    texB = texT + height.toFloat() / srcHeight
                }
            }
            CROP_9_16 -> {
                val ratio = 9f / 16f
                if (srcWidth.toFloat() / srcHeight > ratio) {
                    height = srcHeight
                    width = even((srcHeight * ratio).toInt())
                    texL = (srcWidth - width) / 2f / srcWidth
                    texR = texL + width.toFloat() / srcWidth
                } else {
                    width = srcWidth
                    height = even((srcWidth / ratio).toInt())
                    texT = (srcHeight - height) / 2f / srcHeight
                    texB = texT + height.toFloat() / srcHeight
                }
            }
        }
        // Rotate: 90/270 swap output dimensions (pixels rotate in GL).
        if (rotateMode == 1 || rotateMode == 3) {
            val tmp = width
            width = height
            height = tmp
        }
        // Export quality: scale output to a target height (upscale if needed).
        val targetH = when (qualityMode) {
            1 -> 720
            2 -> 1080
            3 -> 2160
            else -> 0
        }
        if (targetH > 0 && height != targetH) {
            val scale = targetH.toFloat() / height
            width = even((width * scale).toInt())
            height = targetH
        }
        val frameRate = try {
            videoFormat.getInteger(MediaFormat.KEY_FRAME_RATE)
        } catch (e: Exception) {
            30
        }
        val bitrate = (width.toLong() * height * 4).coerceIn(2_000_000L, 12_000_000L).toInt()

        // Audio: if volume/fade/music is requested, decode -> process ->
        // re-encode AAC first (frames are held in memory, then muxed after
        // the video track, because MediaMuxer needs all tracks before start).
        val needsAudioProc = includeAudio && curveMode == 0 && (volumeGain != 1.0f || fade || musicUri != null) && speed == 1.0f
        var audioFrames: List<AudioFrame>? = null
        var audioEncFormat: MediaFormat? = null
        if (needsAudioProc) {
            try {
                val processed = processAudio(
                    context, src, audioTrack, startUs, endUs,
                    musicUri, volumeGain, fade
                )
                if (processed != null) {
                    audioEncFormat = processed.first
                    audioFrames = processed.second
                }
            } catch (e: Exception) {
                audioFrames = null
            }
        }
        val copyAudioFallback = includeAudio && curveMode == 0 && speed == 1.0f && audioTrack >= 0 && audioFrames == null

        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, displayName)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/VidForge")
        }
        val outUri = context.contentResolver.insert(
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values
        ) ?: run { extractor.release(); return null }

        var muxer: MediaMuxer? = null
        var decoder: MediaCodec? = null
        var encoder: MediaCodec? = null
        var gl: GlRenderer? = null
        try {
            val pfd = context.contentResolver.openFileDescriptor(outUri, "w")
                ?: run { extractor.release(); return null }
            muxer = MediaMuxer(pfd.fileDescriptor, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            muxer.setOrientationHint(orientationDegrees)

            // Audio track is added upfront so the muxer can start as soon as
            // the encoder reports its output format.
            val copyAudio = copyAudioFallback
            var muxerAudioTrack = -1
            if (audioFrames != null) {
                muxerAudioTrack = muxer.addTrack(audioEncFormat!!)
            } else if (copyAudio) {
                muxerAudioTrack = muxer.addTrack(extractor.getTrackFormat(audioTrack))
            }

            // Encoder with an input Surface.
            encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            val encFormat = MediaFormat.createVideoFormat(
                MediaFormat.MIMETYPE_VIDEO_AVC, width, height
            ).apply {
                setInteger(
                    MediaFormat.KEY_COLOR_FORMAT,
                    MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface
                )
                setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
                setInteger(MediaFormat.KEY_FRAME_RATE, frameRate)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            }
            encoder.configure(encFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            val encoderSurface = encoder.createInputSurface()
            encoder.start()

            // GL renderer: decoder frames -> filter + text -> encoder surface.
            gl = GlRenderer(
                encoderSurface, filter, overlayText, overlayPos, width, height,
                texL, texT, texR, texB, shape, rotateMode, textAnim
            )
            val decoderSurface = Surface(gl.surfaceTexture)

            decoder = MediaCodec.createDecoderByType(
                videoFormat.getString(MediaFormat.KEY_MIME)!!
            )
            decoder.configure(videoFormat, decoderSurface, null, 0)
            decoder.start()

            extractor.selectTrack(videoTrack)
            extractor.seekTo(startUs, MediaExtractor.SEEK_TO_CLOSEST_SYNC)

            var muxerVideoTrack = -1
            var muxerStarted = false
            var decoderInputEos = false
            var decoderOutputEos = false
            val encInfo = MediaCodec.BufferInfo()
            val decInfo = MediaCodec.BufferInfo()
            var lastRenderPtsUs = 0L
            var curveOutUs = 0L
            var curvePrevSrcUs = startUs
            val clipDurUs = (endUs - startUs).coerceAtLeast(1)
            fun speedAt(srcUs: Long): Float {
                if (curveMode == 0) return speed
                val frac = ((srcUs - startUs).coerceIn(0, clipDurUs)).toFloat() / clipDurUs
                return when (curveMode) {
                    1 -> 0.5f + frac * 1.5f // slow -> fast
                    2 -> 2.0f - frac * 1.5f // fast -> slow
                    else -> speed
                }
            }

            val trackRef = IntArray(1) { -1 }
            val startedRef = BooleanArray(1) { false }
            fun drain(waitUs: Long): Boolean {
                val enc = encoder ?: return false
                val mx = muxer ?: return false
                var sawEos = false
                while (true) {
                    val outIndex = enc.dequeueOutputBuffer(encInfo, waitUs)
                    when {
                        outIndex >= 0 -> {
                            if (encInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                                sawEos = true
                            }
                            if (encInfo.size > 0 && startedRef[0]) {
                                val outBuf = enc.getOutputBuffer(outIndex)!!
                                mx.writeSampleData(trackRef[0], outBuf, encInfo)
                            }
                            enc.releaseOutputBuffer(outIndex, false)
                            if (waitUs == 0L) break
                        }
                        outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            trackRef[0] = mx.addTrack(enc.outputFormat)
                            mx.start()
                            startedRef[0] = true
                        }
                        else -> return sawEos
                    }
                    if (sawEos) return true
                }
                return sawEos
            }

            // Feed + render loop.
            while (!decoderOutputEos) {
                if (!decoderInputEos) {
                    val inIndex = decoder.dequeueInputBuffer(10000)
                    if (inIndex >= 0) {
                        val inBuf = decoder.getInputBuffer(inIndex)!!
                        val sampleSize = extractor.readSampleData(inBuf, 0)
                        if (sampleSize < 0 || extractor.sampleTime > endUs) {
                            decoder.queueInputBuffer(
                                inIndex, 0, 0, 0,
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM
                            )
                            decoderInputEos = true
                        } else {
                            decoder.queueInputBuffer(
                                inIndex, 0, sampleSize,
                                extractor.sampleTime, extractor.sampleFlags
                            )
                            extractor.advance()
                        }
                    }
                }
                val outIndex = decoder.dequeueOutputBuffer(decInfo, 10000)
                when {
                    outIndex >= 0 -> {
                        val ptsUs = decInfo.presentationTimeUs
                        val doRender = decInfo.size >= 0
                        decoder.releaseOutputBuffer(outIndex, doRender)
                        if (decInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                            decoderOutputEos = true
                        } else if (doRender) {
                            val renderPtsUs = if (curveMode == 0) {
                                ((ptsUs - startUs).coerceAtLeast(0) / speed).toLong()
                            } else {
                                val effPts = ptsUs.coerceAtLeast(startUs)
                                val dt = (effPts - curvePrevSrcUs).coerceAtLeast(0)
                                curveOutUs += (dt / speedAt(effPts)).toLong()
                                curvePrevSrcUs = effPts
                                curveOutUs
                            }
                            lastRenderPtsUs = renderPtsUs
                            gl.awaitFrame()
                            gl.drawFrame(renderPtsUs * 1000, 1f, true, renderPtsUs)
                            drain(0)
                        }
                    }
                    outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        // Decoder format change is not needed for this path.
                    }
                }
            }

            // Freeze frame: re-draw the last frame for freezeUs (no new
            // decoder frame needed — the texture keeps the last image).
            if (freezeUs > 0) {
                val stepUs = 1_000_000L / frameRate
                var ft = stepUs
                while (ft <= freezeUs) {
                    gl.drawFrame((lastRenderPtsUs + ft) * 1000, 1f, false, lastRenderPtsUs + ft)
                    drain(0)
                    ft += stepUs
                }
            }

            // Signal encoder EOS and drain it.
            encoder.signalEndOfInputStream()
            var sawEos = false
            var guard = 0
            while (!sawEos && guard < 300) {
                sawEos = drain(10000)
                guard++
            }
            muxerStarted = startedRef[0]

            // Write processed audio frames (volume/fade/music path).
            if (audioFrames != null && muxerStarted) {
                val ainfo = MediaCodec.BufferInfo()
                for (frame in audioFrames) {
                    val buf = ByteBuffer.wrap(frame.data)
                    ainfo.offset = 0
                    ainfo.size = frame.data.size
                    ainfo.presentationTimeUs = frame.ptsUs
                    ainfo.flags = frame.flags
                    muxer.writeSampleData(muxerAudioTrack, buf, ainfo)
                }
            }

            // Copy audio samples (only at 1x speed, when requested).
            if (copyAudio && muxerStarted) {
                val audioExtractor = MediaExtractor()
                try {
                    audioExtractor.setDataSource(context, src, null)
                    audioExtractor.selectTrack(audioTrack)
                    audioExtractor.seekTo(startUs, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
                    val abuf = ByteBuffer.allocate(512 * 1024)
                    val ainfo = MediaCodec.BufferInfo()
                    while (true) {
                        val size = audioExtractor.readSampleData(abuf, 0)
                        if (size < 0) break
                        val t = audioExtractor.sampleTime
                        if (t > endUs) break
                        if (t < startUs) {
                            audioExtractor.advance()
                            continue
                        }
                        ainfo.offset = 0
                        ainfo.size = size
                        ainfo.presentationTimeUs = t - startUs
                        ainfo.flags = audioExtractor.sampleFlags
                        muxer.writeSampleData(muxerAudioTrack, abuf, ainfo)
                        audioExtractor.advance()
                    }
                } finally {
                    audioExtractor.release()
                }
            }

            if (muxerStarted) muxer.stop()
            muxer.release()
            muxer = null
            decoder.stop(); decoder.release(); decoder = null
            encoder.stop(); encoder.release(); encoder = null
            gl.release(); gl = null
            extractor.release()
            return outUri
        } catch (e: Exception) {
            try { decoder?.release() } catch (ignored: Exception) { }
            try { encoder?.release() } catch (ignored: Exception) { }
            try { gl?.release() } catch (ignored: Exception) { }
            try { muxer?.release() } catch (ignored: Exception) { }
            try { extractor.release() } catch (ignored: Exception) { }
            try { context.contentResolver.delete(outUri, null, null) } catch (ignored: Exception) { }
            return null
        }
    }

    private data class AudioFrame(val data: ByteArray, val ptsUs: Long, val flags: Int)

    private class PcmData(
        val chunks: MutableList<ShortArray> = mutableListOf(),
        var total: Int = 0,
        var rate: Int = 0,
        var channels: Int = 0
    ) {
        fun toArray(): ShortArray {
            val out = ShortArray(total)
            var pos = 0
            for (c in chunks) {
                c.copyInto(out, pos)
                pos += c.size
            }
            return out
        }
    }

    /**
     * Decode an audio track to PCM. For the video's own track, only the trim
     * range is decoded; for music, only as much as the video duration needs
     * (the mix loops it if it is shorter).
     */
    private fun decodePcm(
        context: Context,
        uri: Uri,
        trackIndex: Int,
        startUs: Long,
        endUs: Long
    ): PcmData? {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(context, uri, null)
        } catch (e: Exception) {
            return null
        }
        try {
            if (trackIndex < 0 || trackIndex >= extractor.trackCount) return null
            val format = extractor.getTrackFormat(trackIndex)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: return null
            if (!mime.startsWith("audio/")) return null
            val decoder = MediaCodec.createDecoderByType(mime)
            decoder.configure(format, null, null, 0)
            decoder.start()
            extractor.selectTrack(trackIndex)
            extractor.seekTo(startUs, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
            val pcm = PcmData()
            pcm.rate = try { format.getInteger(MediaFormat.KEY_SAMPLE_RATE) } catch (e: Exception) { 44100 }
            pcm.channels = try { format.getInteger(MediaFormat.KEY_CHANNEL_COUNT) } catch (e: Exception) { 2 }
            var inputEos = false
            var outputEos = false
            val info = MediaCodec.BufferInfo()
            while (!outputEos) {
                if (!inputEos) {
                    val inIndex = decoder.dequeueInputBuffer(10000)
                    if (inIndex >= 0) {
                        val inBuf = decoder.getInputBuffer(inIndex)!!
                        val size = extractor.readSampleData(inBuf, 0)
                        if (size < 0 || extractor.sampleTime > endUs) {
                            decoder.queueInputBuffer(
                                inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM
                            )
                            inputEos = true
                        } else {
                            decoder.queueInputBuffer(
                                inIndex, 0, size, extractor.sampleTime, extractor.sampleFlags
                            )
                            extractor.advance()
                        }
                    }
                }
                val outIndex = decoder.dequeueOutputBuffer(info, 10000)
                when {
                    outIndex >= 0 -> {
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                            outputEos = true
                        }
                        if (info.size > 0) {
                            val outBuf = decoder.getOutputBuffer(outIndex)!!
                            val dup = outBuf.duplicate()
                            dup.position(info.offset)
                            dup.limit(info.offset + info.size)
                            dup.order(ByteOrder.nativeOrder())
                            val shorts = ShortArray(info.size / 2)
                            dup.asShortBuffer().get(shorts)
                            pcm.chunks.add(shorts)
                            pcm.total += shorts.size
                        }
                        decoder.releaseOutputBuffer(outIndex, false)
                    }
                    outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val of = decoder.outputFormat
                        pcm.rate = try { of.getInteger(MediaFormat.KEY_SAMPLE_RATE) } catch (e: Exception) { pcm.rate }
                        pcm.channels = try { of.getInteger(MediaFormat.KEY_CHANNEL_COUNT) } catch (e: Exception) { pcm.channels }
                    }
                }
            }
            decoder.stop()
            decoder.release()
            return pcm
        } catch (e: Exception) {
            return null
        } finally {
            extractor.release()
        }
    }

    private fun convertChannels(samples: ShortArray, srcCh: Int, dstCh: Int): ShortArray {
        if (srcCh == dstCh) return samples
        val frames = samples.size / srcCh
        val out = ShortArray(frames * dstCh)
        for (f in 0 until frames) {
            for (c in 0 until dstCh) {
                out[f * dstCh + c] = if (dstCh == 1 && srcCh == 2) {
                    ((samples[f * 2].toInt() + samples[f * 2 + 1].toInt()) / 2).toShort()
                } else {
                    samples[f * srcCh + minOf(c, srcCh - 1)]
                }
            }
        }
        return out
    }

    private fun resample(samples: ShortArray, srcRate: Int, dstRate: Int, channels: Int): ShortArray {
        if (srcRate == dstRate) return samples
        val srcFrames = samples.size / channels
        val dstFrames = (srcFrames.toLong() * dstRate / srcRate).toInt()
        val out = ShortArray(dstFrames * channels)
        for (f in 0 until dstFrames) {
            val srcPos = f.toDouble() * srcRate / dstRate
            val i0 = srcPos.toInt().coerceAtMost(srcFrames - 1)
            val i1 = (i0 + 1).coerceAtMost(srcFrames - 1)
            val frac = (srcPos - i0).toFloat()
            for (c in 0 until channels) {
                val a = samples[i0 * channels + c].toInt()
                val b = samples[i1 * channels + c].toInt()
                out[f * channels + c] = (a + (b - a) * frac).toInt().toShort()
            }
        }
        return out
    }

    /**
     * Mix video audio (gain) + optional music (looped, half gain), apply an
     * optional 1-second fade in/out, and re-encode to AAC. Returns the
     * encoder output format plus frames, or null on failure.
     */
    private fun processAudio(
        context: Context,
        src: Uri,
        audioTrack: Int,
        startUs: Long,
        endUs: Long,
        musicUri: Uri?,
        videoGain: Float,
        fade: Boolean
    ): Pair<MediaFormat, List<AudioFrame>>? {
        val videoPcm = if (audioTrack >= 0) decodePcm(context, src, audioTrack, startUs, endUs) else null
        var musicPcm: PcmData? = null
        if (musicUri != null) {
            // Find the music track (first audio track).
            val probe = MediaExtractor()
            var musicTrack = -1
            try {
                probe.setDataSource(context, musicUri, null)
                for (i in 0 until probe.trackCount) {
                    val mime = probe.getTrackFormat(i).getString(MediaFormat.KEY_MIME) ?: continue
                    if (mime.startsWith("audio/")) {
                        musicTrack = i
                        break
                    }
                }
            } catch (e: Exception) {
                musicTrack = -1
            } finally {
                probe.release()
            }
            if (musicTrack >= 0) {
                musicPcm = decodePcm(context, musicUri, musicTrack, 0, endUs - startUs)
            }
        }
        if (videoPcm == null && musicPcm == null) return null
        val rate = videoPcm?.rate ?: musicPcm!!.rate
        val channels = videoPcm?.channels ?: musicPcm!!.channels
        val videoSamples = videoPcm?.let {
            convertChannels(resample(it.toArray(), it.rate, rate, it.channels), it.channels, channels)
        }
        val musicSamples = musicPcm?.let {
            convertChannels(resample(it.toArray(), it.rate, rate, it.channels), it.channels, channels)
        }
        val outLen = videoSamples?.size ?: musicSamples!!.size
        val mixed = ShortArray(outLen)
        for (i in 0 until outLen) {
            var v = 0f
            if (videoSamples != null && i < videoSamples.size) v = videoSamples[i] * videoGain
            var m = 0f
            if (musicSamples != null && musicSamples.isNotEmpty()) {
                m = musicSamples[i % musicSamples.size] * 0.5f
            }
            mixed[i] = (v + m).toInt().coerceIn(-32768, 32767).toShort()
        }
        if (fade) {
            val frames = outLen / channels
            val fadeFrames = minOf(rate, frames / 2)
            for (f in 0 until frames) {
                val factor = when {
                    f < fadeFrames -> f.toFloat() / fadeFrames
                    f >= frames - fadeFrames -> (frames - 1 - f).toFloat() / fadeFrames
                    else -> 1f
                }
                for (c in 0 until channels) {
                    mixed[f * channels + c] = (mixed[f * channels + c] * factor).toInt().toShort()
                }
            }
        }
        // Encode to AAC.
        val encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        val encFormat = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, rate, channels).apply {
            setInteger(MediaFormat.KEY_BIT_RATE, 128000)
            setInteger(
                MediaFormat.KEY_AAC_PROFILE,
                MediaCodecInfo.CodecProfileLevel.AACObjectLC
            )
        }
        encoder.configure(encFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        encoder.start()
        val frames = mutableListOf<AudioFrame>()
        var outFormat: MediaFormat? = null
        try {
            var fed = 0
            var inputEos = false
            var outputEos = false
            val info = MediaCodec.BufferInfo()
            while (!outputEos) {
                if (!inputEos) {
                    val inIndex = encoder.dequeueInputBuffer(10000)
                    if (inIndex >= 0) {
                        val inBuf = encoder.getInputBuffer(inIndex)!!
                        inBuf.clear()
                        inBuf.order(ByteOrder.nativeOrder())
                        val remaining = mixed.size - fed
                        if (remaining <= 0) {
                            encoder.queueInputBuffer(
                                inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM
                            )
                            inputEos = true
                        } else {
                            val chunk = minOf(remaining, inBuf.asShortBuffer().remaining())
                            inBuf.asShortBuffer().put(mixed, fed, chunk)
                            val ptsUs = (fed / channels).toLong() * 1_000_000 / rate
                            encoder.queueInputBuffer(inIndex, 0, chunk * 2, ptsUs, 0)
                            fed += chunk
                        }
                    }
                }
                val outIndex = encoder.dequeueOutputBuffer(info, 10000)
                when {
                    outIndex >= 0 -> {
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                            outputEos = true
                        }
                        if (info.size > 0) {
                            val outBuf = encoder.getOutputBuffer(outIndex)!!
                            val dup = outBuf.duplicate()
                            dup.position(info.offset)
                            dup.limit(info.offset + info.size)
                            val bytes = ByteArray(info.size)
                            dup.get(bytes)
                            frames.add(AudioFrame(bytes, info.presentationTimeUs, info.flags))
                        }
                        encoder.releaseOutputBuffer(outIndex, false)
                    }
                    outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        outFormat = encoder.outputFormat
                    }
                }
            }
        } finally {
            try { encoder.stop() } catch (ignored: Exception) { }
            try { encoder.release() } catch (ignored: Exception) { }
        }
        val fmt = outFormat ?: return null
        return Pair(fmt, frames)
    }

    // Join transitions, matching the UI spinner order.
    const val JOIN_CUT = 0
    const val JOIN_FADE = 1
    const val JOIN_DISSOLVE = 2
    const val JOIN_SLIDE = 3

    /**
     * Join two clips into one video. CUT tries a lossless sample copy first
     * (both clips must share codec + size) and falls back to re-encode.
     * FADE re-encodes with a fade-through-black (0.5s out of A, 0.5s into B).
     * Audio is concatenated (A then B) when requested.
     */
    fun joinClips(
        context: Context,
        uriA: Uri,
        startAUs: Long,
        endAUs: Long,
        uriB: Uri,
        startBUs: Long,
        endBUs: Long,
        transition: Int,
        filter: Int,
        overlayText: String?,
        overlayPos: Int,
        shape: Int,
        includeAudio: Boolean,
        orientationDegrees: Int,
        displayName: String
    ): Uri? {
        val durA = endAUs - startAUs
        val durB = endBUs - startBUs
        if (durA <= 0 || durB <= 0) return null
        if (transition == JOIN_CUT) {
            val lossless = joinLossless(
                context, uriA, startAUs, endAUs, uriB, startBUs, endBUs,
                includeAudio, orientationDegrees, displayName
            )
            if (lossless != null) return lossless
        }
        if (transition == JOIN_DISSOLVE || transition == JOIN_SLIDE) {
            return joinDual(
                context, uriA, startAUs, endAUs, uriB, startBUs, endBUs,
                transition == JOIN_DISSOLVE, filter, overlayText, overlayPos, shape,
                includeAudio, orientationDegrees, displayName
            )
        }
        return joinReencode(
            context, uriA, startAUs, endAUs, uriB, startBUs, endBUs,
            transition, filter, overlayText, overlayPos, shape,
            includeAudio, orientationDegrees, displayName
        )
    }

    private fun trackInfo(
        context: Context, uri: Uri
    ): Triple<Int, Int, MediaFormat?> {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(context, uri, null)
            var videoTrack = -1
            var audioTrack = -1
            var videoFormat: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
                if (videoTrack < 0 && mime.startsWith("video/")) {
                    videoTrack = i
                    videoFormat = format
                }
                if (audioTrack < 0 && mime.startsWith("audio/")) audioTrack = i
            }
            return Triple(videoTrack, audioTrack, videoFormat)
        } catch (e: Exception) {
            return Triple(-1, -1, null)
        } finally {
            extractor.release()
        }
    }

    private fun joinLossless(
        context: Context,
        uriA: Uri, startAUs: Long, endAUs: Long,
        uriB: Uri, startBUs: Long, endBUs: Long,
        includeAudio: Boolean,
        orientationDegrees: Int,
        displayName: String
    ): Uri? {
        val (videoA, audioA, formatA) = trackInfo(context, uriA)
        val (videoB, audioB, formatB) = trackInfo(context, uriB)
        if (videoA < 0 || videoB < 0 || formatA == null || formatB == null) return null
        val mimeA = formatA.getString(MediaFormat.KEY_MIME)
        val mimeB = formatB.getString(MediaFormat.KEY_MIME)
        if (mimeA != mimeB) return null
        val wA = try { formatA.getInteger(MediaFormat.KEY_WIDTH) } catch (e: Exception) { 0 }
        val hA = try { formatA.getInteger(MediaFormat.KEY_HEIGHT) } catch (e: Exception) { 0 }
        val wB = try { formatB.getInteger(MediaFormat.KEY_WIDTH) } catch (e: Exception) { 0 }
        val hB = try { formatB.getInteger(MediaFormat.KEY_HEIGHT) } catch (e: Exception) { 0 }
        if (wA != wB || hA != hB) return null

        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, displayName)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/VidForge")
        }
        val outUri = context.contentResolver.insert(
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values
        ) ?: return null
        var muxer: MediaMuxer? = null
        try {
            val pfd = context.contentResolver.openFileDescriptor(outUri, "w") ?: return null
            muxer = MediaMuxer(pfd.fileDescriptor, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            muxer.setOrientationHint(orientationDegrees)
            val muxerVideo = muxer.addTrack(formatA)
            var muxerAudio = -1
            var audioFormatA: MediaFormat? = null
            if (includeAudio && audioA >= 0) {
                val probe = MediaExtractor()
                try {
                    probe.setDataSource(context, uriA, null)
                    audioFormatA = probe.getTrackFormat(audioA)
                    muxerAudio = muxer.addTrack(audioFormatA)
                } finally {
                    probe.release()
                }
            }
            muxer.start()
            val durA = endAUs - startAUs
            copyTrackRange(context, uriA, videoA, startAUs, endAUs, muxer, muxerVideo, 0)
            if (muxerAudio >= 0) {
                copyTrackRange(context, uriA, audioA, startAUs, endAUs, muxer, muxerAudio, 0)
            }
            copyTrackRange(context, uriB, videoB, startBUs, endBUs, muxer, muxerVideo, durA)
            if (muxerAudio >= 0 && audioB >= 0) {
                copyTrackRange(context, uriB, audioB, startBUs, endBUs, muxer, muxerAudio, durA)
            }
            muxer.stop()
            muxer.release()
            return outUri
        } catch (e: Exception) {
            try { muxer?.release() } catch (ignored: Exception) { }
            try { context.contentResolver.delete(outUri, null, null) } catch (ignored: Exception) { }
            return null
        }
    }

    private fun copyTrackRange(
        context: Context,
        uri: Uri,
        track: Int,
        startUs: Long,
        endUs: Long,
        muxer: MediaMuxer,
        muxerTrack: Int,
        ptsOffsetUs: Long
    ) {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(context, uri, null)
            extractor.selectTrack(track)
            extractor.seekTo(startUs, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
            val buffer = ByteBuffer.allocate(4 * 1024 * 1024)
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
                info.presentationTimeUs = t - startUs + ptsOffsetUs
                info.flags = extractor.sampleFlags
                muxer.writeSampleData(muxerTrack, buffer, info)
                extractor.advance()
            }
        } finally {
            extractor.release()
        }
    }

    private fun joinReencode(
        context: Context,
        uriA: Uri, startAUs: Long, endAUs: Long,
        uriB: Uri, startBUs: Long, endBUs: Long,
        transition: Int,
        filter: Int,
        overlayText: String?,
        overlayPos: Int,
        shape: Int,
        includeAudio: Boolean,
        orientationDegrees: Int,
        displayName: String
    ): Uri? {
        val (_, audioA, formatA) = trackInfo(context, uriA)
        val (_, audioB, _) = trackInfo(context, uriB)
        if (formatA == null) return null
        var width = try { formatA.getInteger(MediaFormat.KEY_WIDTH) } catch (e: Exception) { 0 }
        var height = try { formatA.getInteger(MediaFormat.KEY_HEIGHT) } catch (e: Exception) { 0 }
        if (width <= 0 || height <= 0) return null
        if (width % 2 == 1) width -= 1
        if (height % 2 == 1) height -= 1
        val frameRate = try { formatA.getInteger(MediaFormat.KEY_FRAME_RATE) } catch (e: Exception) { 30 }
        val bitrate = (width.toLong() * height * 4).coerceIn(2_000_000L, 12_000_000L).toInt()
        val durA = endAUs - startAUs
        val durB = endBUs - startBUs
        val fadeDur = if (transition == JOIN_FADE) minOf(500_000L, durA / 2, durB / 2) else 0L

        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, displayName)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/VidForge")
        }
        val outUri = context.contentResolver.insert(
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values
        ) ?: return null
        var muxer: MediaMuxer? = null
        var encoder: MediaCodec? = null
        try {
            val pfd = context.contentResolver.openFileDescriptor(outUri, "w") ?: return null
            muxer = MediaMuxer(pfd.fileDescriptor, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            muxer.setOrientationHint(orientationDegrees)
            var muxerAudioTrack = -1
            if (includeAudio && audioA >= 0) {
                val probe = MediaExtractor()
                try {
                    probe.setDataSource(context, uriA, null)
                    muxerAudioTrack = muxer.addTrack(probe.getTrackFormat(audioA))
                } finally {
                    probe.release()
                }
            }
            encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            val encFormat = MediaFormat.createVideoFormat(
                MediaFormat.MIMETYPE_VIDEO_AVC, width, height
            ).apply {
                setInteger(
                    MediaFormat.KEY_COLOR_FORMAT,
                    MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface
                )
                setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
                setInteger(MediaFormat.KEY_FRAME_RATE, frameRate)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            }
            encoder.configure(encFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            val encoderSurface = encoder.createInputSurface()
            encoder.start()

            val trackRef = IntArray(1) { -1 }
            val startedRef = BooleanArray(1) { false }
            val encInfo = MediaCodec.BufferInfo()
            fun drain(waitUs: Long): Boolean {
                val enc = encoder ?: return false
                val mx = muxer ?: return false
                var sawEos = false
                while (true) {
                    val outIndex = enc.dequeueOutputBuffer(encInfo, waitUs)
                    when {
                        outIndex >= 0 -> {
                            if (encInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                                sawEos = true
                            }
                            if (encInfo.size > 0 && startedRef[0]) {
                                val outBuf = enc.getOutputBuffer(outIndex)!!
                                mx.writeSampleData(trackRef[0], outBuf, encInfo)
                            }
                            enc.releaseOutputBuffer(outIndex, false)
                            if (waitUs == 0L) break
                        }
                        outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            trackRef[0] = mx.addTrack(enc.outputFormat)
                            mx.start()
                            startedRef[0] = true
                        }
                        else -> return sawEos
                    }
                    if (sawEos) return true
                }
                return sawEos
            }

            fun renderPhase(
                uri: Uri, startUs: Long, endUs: Long,
                ptsOffsetUs: Long, fadeMode: Int
            ) {
                val (videoTrack, _, videoFormat) = trackInfo(context, uri)
                if (videoTrack < 0 || videoFormat == null) return
                val gl = GlRenderer(
                    encoderSurface, filter, overlayText, overlayPos, width, height,
                    0f, 0f, 1f, 1f, shape
                )
                val decoderSurface = Surface(gl.surfaceTexture)
                val decoder = MediaCodec.createDecoderByType(
                    videoFormat.getString(MediaFormat.KEY_MIME)!!
                )
                decoder.configure(videoFormat, decoderSurface, null, 0)
                decoder.start()
                val extractor = MediaExtractor()
                extractor.setDataSource(context, uri, null)
                extractor.selectTrack(videoTrack)
                extractor.seekTo(startUs, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
                val decInfo = MediaCodec.BufferInfo()
                var inputEos = false
                var outputEos = false
                val dur = endUs - startUs
                try {
                    while (!outputEos) {
                        if (!inputEos) {
                            val inIndex = decoder.dequeueInputBuffer(10000)
                            if (inIndex >= 0) {
                                val inBuf = decoder.getInputBuffer(inIndex)!!
                                val size = extractor.readSampleData(inBuf, 0)
                                if (size < 0 || extractor.sampleTime > endUs) {
                                    decoder.queueInputBuffer(
                                        inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM
                                    )
                                    inputEos = true
                                } else {
                                    decoder.queueInputBuffer(
                                        inIndex, 0, size, extractor.sampleTime, extractor.sampleFlags
                                    )
                                    extractor.advance()
                                }
                            }
                        }
                        val outIndex = decoder.dequeueOutputBuffer(decInfo, 10000)
                        if (outIndex >= 0) {
                            val ptsUs = decInfo.presentationTimeUs
                            decoder.releaseOutputBuffer(outIndex, true)
                            if (decInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                                outputEos = true
                            } else {
                                val local = (ptsUs - startUs).coerceAtLeast(0)
                                val alpha = when (fadeMode) {
                                    1 -> if (local > dur - fadeDur) {
                                        (dur - local).toFloat() / fadeDur
                                    } else 1f
                                    2 -> if (local < fadeDur) local.toFloat() / fadeDur else 1f
                                    else -> 1f
                                }
                                gl.awaitFrame()
                                gl.drawFrame((local + ptsOffsetUs) * 1000, alpha, true, local)
                                drain(0)
                            }
                        }
                    }
                } finally {
                    try { decoder.stop() } catch (ignored: Exception) { }
                    try { decoder.release() } catch (ignored: Exception) { }
                    try { extractor.release() } catch (ignored: Exception) { }
                    try { gl.release() } catch (ignored: Exception) { }
                }
            }

            val fadeOutMode = if (transition == JOIN_FADE) 1 else 0
            val fadeInMode = if (transition == JOIN_FADE) 2 else 0
            renderPhase(uriA, startAUs, endAUs, 0, fadeOutMode)
            renderPhase(uriB, startBUs, endBUs, durA, fadeInMode)
            encoder.signalEndOfInputStream()
            var sawEos = false
            var guard = 0
            while (!sawEos && guard < 300) {
                sawEos = drain(10000)
                guard++
            }

            if (includeAudio && muxerAudioTrack >= 0 && startedRef[0]) {
                if (audioA >= 0) {
                    copyTrackRange(context, uriA, audioA, startAUs, endAUs, muxer, muxerAudioTrack, 0)
                }
                if (audioB >= 0) {
                    copyTrackRange(context, uriB, audioB, startBUs, endBUs, muxer, muxerAudioTrack, durA)
                }
            }
            if (startedRef[0]) muxer.stop()
            muxer.release()
            muxer = null
            encoder.stop()
            encoder.release()
            encoder = null
            return outUri
        } catch (e: Exception) {
            try { encoder?.release() } catch (ignored: Exception) { }
            try { muxer?.release() } catch (ignored: Exception) { }
            try { context.contentResolver.delete(outUri, null, null) } catch (ignored: Exception) { }
            return null
        }
    }

    /**
     * Reverse video: frames are pulled newest-first with MediaMetadataRetriever
     * and re-encoded in that order (audio is dropped — reversed audio would
     * need a separate resampling pass). Frame count is capped at 900 by
     * lowering the effective frame rate for long clips.
     */
    fun reverseVideo(
        context: Context,
        src: Uri,
        startUs: Long,
        endUs: Long,
        displayName: String
    ): Uri? {
        val (_, _, format) = trackInfo(context, src)
        if (format == null) return null
        var width = try { format.getInteger(MediaFormat.KEY_WIDTH) } catch (e: Exception) { 0 }
        var height = try { format.getInteger(MediaFormat.KEY_HEIGHT) } catch (e: Exception) { 0 }
        if (width <= 0 || height <= 0) return null
        if (width % 2 == 1) width -= 1
        if (height % 2 == 1) height -= 1
        val frameRate = try { format.getInteger(MediaFormat.KEY_FRAME_RATE) } catch (e: Exception) { 30 }
        val bitrate = (width.toLong() * height * 4).coerceIn(2_000_000L, 12_000_000L).toInt()
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, displayName)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/VidForge")
        }
        val outUri = context.contentResolver.insert(
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values
        ) ?: return null
        var muxer: MediaMuxer? = null
        var encoder: MediaCodec? = null
        var gl: GlRenderer? = null
        try {
            val pfd = context.contentResolver.openFileDescriptor(outUri, "w") ?: return null
            muxer = MediaMuxer(pfd.fileDescriptor, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            val encFormat = MediaFormat.createVideoFormat(
                MediaFormat.MIMETYPE_VIDEO_AVC, width, height
            ).apply {
                setInteger(
                    MediaFormat.KEY_COLOR_FORMAT,
                    MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface
                )
                setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
                setInteger(MediaFormat.KEY_FRAME_RATE, frameRate)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            }
            encoder.configure(encFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            val encoderSurface = encoder.createInputSurface()
            encoder.start()
            val trackRef = IntArray(1) { -1 }
            val startedRef = BooleanArray(1) { false }
            val encInfo = MediaCodec.BufferInfo()
            fun drain(waitUs: Long): Boolean {
                val enc = encoder ?: return false
                val mx = muxer ?: return false
                var sawEos = false
                while (true) {
                    val outIndex = enc.dequeueOutputBuffer(encInfo, waitUs)
                    when {
                        outIndex >= 0 -> {
                            if (encInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                                sawEos = true
                            }
                            if (encInfo.size > 0 && startedRef[0]) {
                                val outBuf = enc.getOutputBuffer(outIndex)!!
                                mx.writeSampleData(trackRef[0], outBuf, encInfo)
                            }
                            enc.releaseOutputBuffer(outIndex, false)
                            if (waitUs == 0L) break
                        }
                        outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            trackRef[0] = mx.addTrack(enc.outputFormat)
                            mx.start()
                            startedRef[0] = true
                        }
                        else -> return sawEos
                    }
                    if (sawEos) return true
                }
                return sawEos
            }
            gl = GlRenderer(
                encoderSurface, FILTER_NONE, null, 0, width, height,
                0f, 0f, 1f, 1f, SHAPE_NONE
            )
            val retriever = MediaMetadataRetriever()
            retriever.setDataSource(context, src)
            var stepUs = 1_000_000L / frameRate
            val totalUs = endUs - startUs
            if (totalUs / stepUs > 900) stepUs = totalUs / 900
            var outPts = 0L
            var t = endUs
            while (t >= startUs) {
                val bmp = try {
                    retriever.getFrameAtTime(t, MediaMetadataRetriever.OPTION_CLOSEST)
                } catch (e: Exception) {
                    null
                }
                if (bmp != null) {
                    gl.drawBitmapFrame(bmp, outPts * 1000)
                    bmp.recycle()
                    drain(0)
                    outPts += stepUs
                }
                t -= stepUs
            }
            retriever.release()
            encoder.signalEndOfInputStream()
            var sawEos = false
            var guard = 0
            while (!sawEos && guard < 300) {
                sawEos = drain(10000)
                guard++
            }
            if (startedRef[0]) muxer.stop()
            muxer.release()
            muxer = null
            encoder.stop()
            encoder.release()
            encoder = null
            gl.release()
            gl = null
            return outUri
        } catch (e: Exception) {
            try { encoder?.release() } catch (ignored: Exception) { }
            try { muxer?.release() } catch (ignored: Exception) { }
            try { gl?.release() } catch (ignored: Exception) { }
            try { context.contentResolver.delete(outUri, null, null) } catch (ignored: Exception) { }
            return null
        }
    }

    private class DecState(
        val decoder: MediaCodec,
        val extractor: MediaExtractor,
        val track: Int,
        val startUs: Long,
        val endUs: Long,
        val surfaceTexture: SurfaceTexture
    ) {
        var inputEos = false
        var outputEos = false
        var pendingIndex = -1
        var pendingPts = 0L
        val lock = Object()
        @Volatile var frameAvailable = false
    }

    /** Dual-texture renderer for dissolve/slide transitions. */
    private class DualRenderer(encoderSurface: Surface, private val filter: Int) {
        private val display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        private var eglContext = EGL14.EGL_NO_CONTEXT
        private var eglSurface = EGL14.EGL_NO_SURFACE
        private var program = 0
        private var posLoc = 0
        private var texCoordLoc = 0
        private var texMatrixALoc = 0
        private var texMatrixBLoc = 0
        private var samplerALoc = 0
        private var samplerBLoc = 0
        private var selLoc = 0
        private var mixLoc = 0
        private var filterLoc = 0
        private var texA = 0
        private var texB = 0
        val surfaceTextureA: SurfaceTexture
        val surfaceTextureB: SurfaceTexture

        init {
            val version = IntArray(2)
            EGL14.eglInitialize(display, version, 0, version, 1)
            val configAttribs = intArrayOf(
                EGL14.EGL_RED_SIZE, 8,
                EGL14.EGL_GREEN_SIZE, 8,
                EGL14.EGL_BLUE_SIZE, 8,
                EGL14.EGL_ALPHA_SIZE, 8,
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                0x3142, 1,
                EGL14.EGL_NONE
            )
            val configs = arrayOfNulls<android.opengl.EGLConfig>(1)
            val numConfigs = IntArray(1)
            EGL14.eglChooseConfig(display, configAttribs, 0, configs, 0, 1, numConfigs, 0)
            val ctxAttribs = intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE)
            eglContext = EGL14.eglCreateContext(display, configs[0], EGL14.EGL_NO_CONTEXT, ctxAttribs, 0)
            eglSurface = EGL14.eglCreateWindowSurface(display, configs[0], encoderSurface, intArrayOf(EGL14.EGL_NONE), 0)
            EGL14.eglMakeCurrent(display, eglSurface, eglSurface, eglContext)
            program = buildProgram()
            posLoc = GLES20.glGetAttribLocation(program, "aPosition")
            texCoordLoc = GLES20.glGetAttribLocation(program, "aTexCoord")
            texMatrixALoc = GLES20.glGetUniformLocation(program, "uTexMatrixA")
            texMatrixBLoc = GLES20.glGetUniformLocation(program, "uTexMatrixB")
            samplerALoc = GLES20.glGetUniformLocation(program, "sTextureA")
            samplerBLoc = GLES20.glGetUniformLocation(program, "sTextureB")
            selLoc = GLES20.glGetUniformLocation(program, "uSel")
            mixLoc = GLES20.glGetUniformLocation(program, "uMix")
            filterLoc = GLES20.glGetUniformLocation(program, "uFilter")
            val texIds = IntArray(2)
            GLES20.glGenTextures(2, texIds, 0)
            texA = texIds[0]
            texB = texIds[1]
            for (tex in texIds) {
                GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, tex)
                GLES20.glTexParameterf(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR.toFloat())
                GLES20.glTexParameterf(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR.toFloat())
                GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
                GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
            }
            surfaceTextureA = SurfaceTexture(texA)
            surfaceTextureB = SurfaceTexture(texB)
        }

        fun beginFrame() {
            GLES20.glClearColor(0f, 0f, 0f, 1f)
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
            GLES20.glUseProgram(program)
            GLES20.glUniform1i(filterLoc, filter)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texA)
            GLES20.glUniform1i(samplerALoc, 0)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE1)
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texB)
            GLES20.glUniform1i(samplerBLoc, 1)
            val mtxA = FloatArray(16)
            val mtxB = FloatArray(16)
            surfaceTextureA.getTransformMatrix(mtxA)
            surfaceTextureB.getTransformMatrix(mtxB)
            GLES20.glUniformMatrix4fv(texMatrixALoc, 1, false, mtxA, 0)
            GLES20.glUniformMatrix4fv(texMatrixBLoc, 1, false, mtxB, 0)
        }

        fun drawSingle(sel: Int) {
            drawQuadDual(sel, 0f, 0f, 0f, 1f, 1f)
        }

        fun drawMix(mix: Float) {
            drawQuadDual(2, mix, 0f, 0f, 1f, 1f)
        }

        fun drawSlideB(mix: Float) {
            // B slides in from the right: quad spans [1-mix, 2-mix] in 0..1
            // space; GL clips the part beyond the viewport.
            drawQuadDual(1, mix, 1f - mix, 0f, 2f - mix, 1f)
        }

        fun endFrame(presentationTimeNs: Long) {
            EGLExt.eglPresentationTimeANDROID(display, eglSurface, presentationTimeNs)
            EGL14.eglSwapBuffers(display, eglSurface)
        }

        private fun drawQuadDual(sel: Int, mix: Float, left: Float, top: Float, right: Float, bottom: Float) {
            GLES20.glUniform1i(selLoc, sel)
            GLES20.glUniform1f(mixLoc, mix)
            val quad = floatArrayOf(
                left * 2 - 1, 1 - top * 2,
                left * 2 - 1, 1 - bottom * 2,
                right * 2 - 1, 1 - top * 2,
                right * 2 - 1, 1 - bottom * 2
            )
            val tex = floatArrayOf(0f, 0f, 0f, 1f, 1f, 0f, 1f, 1f)
            val vb = ByteBuffer.allocateDirect(quad.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
            vb.put(quad).position(0)
            val tb = ByteBuffer.allocateDirect(tex.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
            tb.put(tex).position(0)
            GLES20.glEnableVertexAttribArray(posLoc)
            GLES20.glVertexAttribPointer(posLoc, 2, GLES20.GL_FLOAT, false, 0, vb)
            GLES20.glEnableVertexAttribArray(texCoordLoc)
            GLES20.glVertexAttribPointer(texCoordLoc, 2, GLES20.GL_FLOAT, false, 0, tb)
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
            GLES20.glDisableVertexAttribArray(posLoc)
            GLES20.glDisableVertexAttribArray(texCoordLoc)
        }

        private fun buildProgram(): Int {
            val vertex = """
                attribute vec4 aPosition;
                attribute vec2 aTexCoord;
                varying vec2 vTexCoordA;
                varying vec2 vTexCoordB;
                uniform mat4 uTexMatrixA;
                uniform mat4 uTexMatrixB;
                void main() {
                    gl_Position = aPosition;
                    vTexCoordA = (uTexMatrixA * vec4(aTexCoord, 0.0, 1.0)).xy;
                    vTexCoordB = (uTexMatrixB * vec4(aTexCoord, 0.0, 1.0)).xy;
                }
            """.trimIndent()
            val fragment = """
                #extension GL_OES_EGL_image_external : require
                precision mediump float;
                varying vec2 vTexCoordA;
                varying vec2 vTexCoordB;
                uniform samplerExternalOES sTextureA;
                uniform samplerExternalOES sTextureB;
                uniform int uSel;
                uniform float uMix;
                uniform int uFilter;
                void main() {
                    vec4 a = texture2D(sTextureA, vTexCoordA);
                    vec4 b = texture2D(sTextureB, vTexCoordB);
                    vec4 c = uSel == 0 ? a : (uSel == 1 ? b : mix(a, b, uMix));
                    if (uFilter == 1) {
                        float g = dot(c.rgb, vec3(0.299, 0.587, 0.114));
                        c = vec4(g, g, g, c.a);
                    } else if (uFilter == 2) {
                        float r = c.r * 0.393 + c.g * 0.769 + c.b * 0.189;
                        float g = c.r * 0.349 + c.g * 0.686 + c.b * 0.168;
                        float b2 = c.r * 0.272 + c.g * 0.534 + c.b * 0.131;
                        c = vec4(min(r, 1.0), min(g, 1.0), min(b2, 1.0), c.a);
                    } else if (uFilter == 3) {
                        c = vec4(min(c.r * 1.15, 1.0), c.g, c.b * 0.85, c.a);
                    } else if (uFilter == 4) {
                        c = vec4(c.r * 0.85, c.g, min(c.b * 1.15, 1.0), c.a);
                    } else if (uFilter == 5) {
                        c = vec4(1.0 - c.r, 1.0 - c.g, 1.0 - c.b, c.a);
                    } else if (uFilter == 6) {
                        float d = distance(vTexCoordA, vec2(0.5, 0.5));
                        float v = smoothstep(0.75, 0.35, d);
                        c = vec4(c.rgb * (0.35 + 0.65 * v), c.a);
                    } else if (uFilter == 7) {
                        float key = smoothstep(0.12, 0.35, c.g - max(c.r, c.b));
                        c = vec4(mix(c.rgb, vec3(0.0), key), c.a);
                    }
                    gl_FragColor = c;
                }
            """.trimIndent()
            val vs = compileShader(GLES20.GL_VERTEX_SHADER, vertex)
            val fs = compileShader(GLES20.GL_FRAGMENT_SHADER, fragment)
            val prog = GLES20.glCreateProgram()
            GLES20.glAttachShader(prog, vs)
            GLES20.glAttachShader(prog, fs)
            GLES20.glLinkProgram(prog)
            return prog
        }

        private fun compileShader(type: Int, source: String): Int {
            val shader = GLES20.glCreateShader(type)
            GLES20.glShaderSource(shader, source)
            GLES20.glCompileShader(shader)
            return shader
        }

        fun release() {
            try { surfaceTextureA.release() } catch (ignored: Exception) { }
            try { surfaceTextureB.release() } catch (ignored: Exception) { }
            if (eglSurface != EGL14.EGL_NO_SURFACE) {
                EGL14.eglDestroySurface(display, eglSurface)
            }
            if (eglContext != EGL14.EGL_NO_CONTEXT) {
                EGL14.eglDestroyContext(display, eglContext)
            }
            try { EGL14.eglTerminate(display) } catch (ignored: Exception) { }
        }
    }

    /**
     * Join with a true crossfade (dissolve) or slide transition: two decoders
     * run in lockstep over a 0.5s overlap window and the GL shader mixes or
     * slides their frames. Text/sticker overlays are not composited on dual
     * transitions (filter only) — a later pass can add them.
     */
    private fun joinDual(
        context: Context,
        uriA: Uri, startAUs: Long, endAUs: Long,
        uriB: Uri, startBUs: Long, endBUs: Long,
        dissolve: Boolean,
        filter: Int,
        overlayText: String?,
        overlayPos: Int,
        shape: Int,
        includeAudio: Boolean,
        orientationDegrees: Int,
        displayName: String
    ): Uri? {
        val (videoA, audioA, formatA) = trackInfo(context, uriA)
        val (videoB, audioB, formatB) = trackInfo(context, uriB)
        if (videoA < 0 || videoB < 0 || formatA == null || formatB == null) return null
        var width = try { formatA.getInteger(MediaFormat.KEY_WIDTH) } catch (e: Exception) { 0 }
        var height = try { formatA.getInteger(MediaFormat.KEY_HEIGHT) } catch (e: Exception) { 0 }
        if (width <= 0 || height <= 0) return null
        if (width % 2 == 1) width -= 1
        if (height % 2 == 1) height -= 1
        val frameRate = try { formatA.getInteger(MediaFormat.KEY_FRAME_RATE) } catch (e: Exception) { 30 }
        val bitrate = (width.toLong() * height * 4).coerceIn(2_000_000L, 12_000_000L).toInt()
        val durA = endAUs - startAUs
        val durB = endBUs - startBUs
        val overlapUs = minOf(500_000L, durA / 2, durB / 2)
        val totalUs = durA + durB - overlapUs
        if (totalUs <= 0) return null
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, displayName)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/VidForge")
        }
        val outUri = context.contentResolver.insert(
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values
        ) ?: return null
        var muxer: MediaMuxer? = null
        var encoder: MediaCodec? = null
        var dual: DualRenderer? = null
        var stateA: DecState? = null
        var stateB: DecState? = null
        try {
            val pfd = context.contentResolver.openFileDescriptor(outUri, "w") ?: return null
            muxer = MediaMuxer(pfd.fileDescriptor, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            muxer.setOrientationHint(orientationDegrees)
            var muxerAudioTrack = -1
            var audioMimeA: String? = null
            var audioMimeB: String? = null
            if (includeAudio) {
                if (audioA >= 0) {
                    val probe = MediaExtractor()
                    try {
                        probe.setDataSource(context, uriA, null)
                        val af = probe.getTrackFormat(audioA)
                        audioMimeA = af.getString(MediaFormat.KEY_MIME)
                        muxerAudioTrack = muxer.addTrack(af)
                    } finally {
                        probe.release()
                    }
                } else if (audioB >= 0) {
                    val probe = MediaExtractor()
                    try {
                        probe.setDataSource(context, uriB, null)
                        val af = probe.getTrackFormat(audioB)
                        audioMimeB = af.getString(MediaFormat.KEY_MIME)
                        muxerAudioTrack = muxer.addTrack(af)
                    } finally {
                        probe.release()
                    }
                }
            }
            if (audioA >= 0 && audioB >= 0 && audioMimeA == null) {
                val probe = MediaExtractor()
                try {
                    probe.setDataSource(context, uriA, null)
                    audioMimeA = probe.getTrackFormat(audioA).getString(MediaFormat.KEY_MIME)
                } finally {
                    probe.release()
                }
            }
            if (audioB >= 0 && audioMimeB == null) {
                val probe = MediaExtractor()
                try {
                    probe.setDataSource(context, uriB, null)
                    audioMimeB = probe.getTrackFormat(audioB).getString(MediaFormat.KEY_MIME)
                } finally {
                    probe.release()
                }
            }
            encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            val encFormat = MediaFormat.createVideoFormat(
                MediaFormat.MIMETYPE_VIDEO_AVC, width, height
            ).apply {
                setInteger(
                    MediaFormat.KEY_COLOR_FORMAT,
                    MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface
                )
                setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
                setInteger(MediaFormat.KEY_FRAME_RATE, frameRate)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            }
            encoder.configure(encFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            val encoderSurface = encoder.createInputSurface()
            encoder.start()
            val trackRef = IntArray(1) { -1 }
            val startedRef = BooleanArray(1) { false }
            val encInfo = MediaCodec.BufferInfo()
            fun drain(waitUs: Long): Boolean {
                val enc = encoder ?: return false
                val mx = muxer ?: return false
                var sawEos = false
                while (true) {
                    val outIndex = enc.dequeueOutputBuffer(encInfo, waitUs)
                    when {
                        outIndex >= 0 -> {
                            if (encInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                                sawEos = true
                            }
                            if (encInfo.size > 0 && startedRef[0]) {
                                val outBuf = enc.getOutputBuffer(outIndex)!!
                                mx.writeSampleData(trackRef[0], outBuf, encInfo)
                            }
                            enc.releaseOutputBuffer(outIndex, false)
                            if (waitUs == 0L) break
                        }
                        outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            trackRef[0] = mx.addTrack(enc.outputFormat)
                            mx.start()
                            startedRef[0] = true
                        }
                        else -> return sawEos
                    }
                    if (sawEos) return true
                }
                return sawEos
            }
            dual = DualRenderer(encoderSurface, filter)
            fun makeState(uri: Uri, track: Int, format: MediaFormat, startUs: Long, endUs: Long, st: SurfaceTexture): DecState {
                val extractor = MediaExtractor()
                extractor.setDataSource(context, uri, null)
                extractor.selectTrack(track)
                extractor.seekTo(startUs, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
                val decoder = MediaCodec.createDecoderByType(
                    format.getString(MediaFormat.KEY_MIME)!!
                )
                decoder.configure(format, Surface(st), null, 0)
                decoder.start()
                return DecState(decoder, extractor, track, startUs, endUs, st)
            }
            stateA = makeState(uriA, videoA, formatA, startAUs, endAUs, dual.surfaceTextureA)
            stateB = makeState(uriB, videoB, formatB, startBUs, endBUs, dual.surfaceTextureB)
            dual.surfaceTextureA.setOnFrameAvailableListener {
                synchronized(stateA!!.lock) {
                    stateA!!.frameAvailable = true
                    stateA!!.lock.notifyAll()
                }
            }
            dual.surfaceTextureB.setOnFrameAvailableListener {
                synchronized(stateB!!.lock) {
                    stateB!!.frameAvailable = true
                    stateB!!.lock.notifyAll()
                }
            }
            fun awaitFrame(state: DecState) {
                synchronized(state.lock) {
                    var waited = 0
                    while (!state.frameAvailable && waited < 2000) {
                        try { state.lock.wait(20) } catch (ignored: InterruptedException) { }
                        waited += 20
                    }
                    state.frameAvailable = false
                }
            }
            fun advanceTo(state: DecState, targetRelUs: Long) {
                var guard = 0
                while (guard < 1000) {
                    guard++
                    if (state.pendingIndex >= 0 && state.pendingPts > targetRelUs) return
                    if (state.outputEos && state.pendingIndex < 0) return
                    if (!state.inputEos) {
                        val inIndex = state.decoder.dequeueInputBuffer(10000)
                        if (inIndex >= 0) {
                            val inBuf = state.decoder.getInputBuffer(inIndex)!!
                            val size = state.extractor.readSampleData(inBuf, 0)
                            if (size < 0 || state.extractor.sampleTime > state.endUs) {
                                state.decoder.queueInputBuffer(
                                    inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM
                                )
                                state.inputEos = true
                            } else {
                                state.decoder.queueInputBuffer(
                                    inIndex, 0, size, state.extractor.sampleTime, state.extractor.sampleFlags
                                )
                                state.extractor.advance()
                            }
                        }
                    }
                    if (state.pendingIndex < 0 && !state.outputEos) {
                        val info = MediaCodec.BufferInfo()
                        val outIndex = state.decoder.dequeueOutputBuffer(info, 10000)
                        if (outIndex >= 0) {
                            if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                                state.outputEos = true
                                state.decoder.releaseOutputBuffer(outIndex, false)
                            } else {
                                state.pendingIndex = outIndex
                                state.pendingPts = (info.presentationTimeUs - state.startUs).coerceAtLeast(0)
                            }
                        }
                    }
                    if (state.pendingIndex >= 0 && state.pendingPts <= targetRelUs) {
                        state.decoder.releaseOutputBuffer(state.pendingIndex, true)
                        state.pendingIndex = -1
                        awaitFrame(state)
                    }
                }
            }
            val stepUs = 1_000_000L / frameRate
            var t = 0L
            while (t <= totalUs) {
                dual.beginFrame()
                when {
                    t < durA - overlapUs -> {
                        advanceTo(stateA!!, t)
                        dual.drawSingle(0)
                    }
                    t < durA -> {
                        advanceTo(stateA!!, t)
                        advanceTo(stateB!!, t - (durA - overlapUs))
                        val mix = (t - (durA - overlapUs)).toFloat() / overlapUs
                        if (dissolve) {
                            dual.drawMix(mix)
                        } else {
                            dual.drawSingle(0)
                            dual.drawSlideB(mix)
                        }
                    }
                    else -> {
                        advanceTo(stateB!!, t - (durA - overlapUs))
                        dual.drawSingle(1)
                    }
                }
                dual.endFrame(t * 1000)
                drain(0)
                t += stepUs
            }
            for (state in listOf(stateA, stateB)) {
                try { state?.decoder?.stop() } catch (ignored: Exception) { }
                try { state?.decoder?.release() } catch (ignored: Exception) { }
                try { state?.extractor?.release() } catch (ignored: Exception) { }
            }
            stateA = null
            stateB = null
            encoder.signalEndOfInputStream()
            var sawEos = false
            var guard = 0
            while (!sawEos && guard < 300) {
                sawEos = drain(10000)
                guard++
            }
            if (includeAudio && muxerAudioTrack >= 0 && startedRef[0]) {
                if (audioA >= 0) {
                    copyTrackRange(context, uriA, audioA, startAUs, endAUs, muxer, muxerAudioTrack, 0)
                }
                if (audioB >= 0 && (audioA < 0 || audioMimeA == audioMimeB)) {
                    val offsetB = if (audioA >= 0) durA else 0L
                    copyTrackRange(context, uriB, audioB, startBUs, endBUs, muxer, muxerAudioTrack, offsetB)
                }
            }
            if (startedRef[0]) muxer.stop()
            muxer.release()
            muxer = null
            encoder.stop()
            encoder.release()
            encoder = null
            dual.release()
            dual = null
            return outUri
        } catch (e: Exception) {
            try { stateA?.decoder?.release() } catch (ignored: Exception) { }
            try { stateB?.decoder?.release() } catch (ignored: Exception) { }
            try { stateA?.extractor?.release() } catch (ignored: Exception) { }
            try { stateB?.extractor?.release() } catch (ignored: Exception) { }
            try { encoder?.release() } catch (ignored: Exception) { }
            try { muxer?.release() } catch (ignored: Exception) { }
            try { dual?.release() } catch (ignored: Exception) { }
            try { context.contentResolver.delete(outUri, null, null) } catch (ignored: Exception) { }
            return null
        }
    }

    /** Minimal EGL + GLES 2.0 renderer: external video texture + filter + text. */
    private class GlRenderer(
        encoderSurface: Surface,
        private val filter: Int,
        overlayText: String?,
        overlayPos: Int,
        private val width: Int,
        private val height: Int,
        private val texL: Float,
        private val texT: Float,
        private val texR: Float,
        private val texB: Float,
        shape: Int,
        private val rotateMode: Int = 0,
        private val textAnim: Int = 0
    ) {
        private val display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        private var eglContext = EGL14.EGL_NO_CONTEXT
        private var eglSurface = EGL14.EGL_NO_SURFACE
        private var program = 0
        private var posLoc = 0
        private var texCoordLoc = 0
        private var texMatrixLoc = 0
        private var samplerLoc = 0
        private var filterLoc = 0
        private var fadeLoc = 0
        private var videoTexId = 0
        private var textTexId = 0
        private var hasText = false
        private var shapeTexId = 0
        private var hasShape = false
        private val lastMtx = FloatArray(16)
        private var textTop = 0.05f
        private var textHeightFrac = 0.12f
        private val frameLock = Object()
        @Volatile private var frameAvailable = false
        val surfaceTexture: SurfaceTexture

        init {
            val version = IntArray(2)
            EGL14.eglInitialize(display, version, 0, version, 1)
            val configAttribs = intArrayOf(
                EGL14.EGL_RED_SIZE, 8,
                EGL14.EGL_GREEN_SIZE, 8,
                EGL14.EGL_BLUE_SIZE, 8,
                EGL14.EGL_ALPHA_SIZE, 8,
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                0x3142, 1, // EGL_RECORDABLE_ANDROID
                EGL14.EGL_NONE
            )
            val configs = arrayOfNulls<android.opengl.EGLConfig>(1)
            val numConfigs = IntArray(1)
            EGL14.eglChooseConfig(display, configAttribs, 0, configs, 0, 1, numConfigs, 0)
            val ctxAttribs = intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE)
            eglContext = EGL14.eglCreateContext(display, configs[0], EGL14.EGL_NO_CONTEXT, ctxAttribs, 0)
            val surfAttribs = intArrayOf(EGL14.EGL_NONE)
            eglSurface = EGL14.eglCreateWindowSurface(display, configs[0], encoderSurface, surfAttribs, 0)
            EGL14.eglMakeCurrent(display, eglSurface, eglSurface, eglContext)

            program = buildProgram()
            posLoc = GLES20.glGetAttribLocation(program, "aPosition")
            texCoordLoc = GLES20.glGetAttribLocation(program, "aTexCoord")
            texMatrixLoc = GLES20.glGetUniformLocation(program, "uTexMatrix")
            samplerLoc = GLES20.glGetUniformLocation(program, "sTexture")
            filterLoc = GLES20.glGetUniformLocation(program, "uFilter")
            fadeLoc = GLES20.glGetUniformLocation(program, "uFade")

            val texIds = IntArray(1)
            GLES20.glGenTextures(1, texIds, 0)
            videoTexId = texIds[0]
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, videoTexId)
            GLES20.glTexParameterf(
                GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
                GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR.toFloat()
            )
            GLES20.glTexParameterf(
                GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
                GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR.toFloat()
            )
            GLES20.glTexParameteri(
                GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
                GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE
            )
            GLES20.glTexParameteri(
                GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
                GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE
            )
            surfaceTexture = SurfaceTexture(videoTexId)
            surfaceTexture.setOnFrameAvailableListener {
                synchronized(frameLock) {
                    frameAvailable = true
                    frameLock.notifyAll()
                }
            }

            if (!overlayText.isNullOrEmpty()) {
                textTexId = makeTextTexture(overlayText)
                hasText = true
                textTop = when (overlayPos) {
                    0 -> 0.04f
                    1 -> 0.44f
                    else -> 0.84f
                }
            }
            if (shape != SHAPE_NONE) {
                shapeTexId = makeShapeTexture(shape)
                hasShape = shapeTexId != 0
            }
            GLES20.glViewport(0, 0, width, height)
        }

        fun awaitFrame() {
            synchronized(frameLock) {
                var waited = 0
                while (!frameAvailable && waited < 2000) {
                    try { frameLock.wait(20) } catch (ignored: InterruptedException) { }
                    waited += 20
                }
                frameAvailable = false
            }
        }

        fun drawFrame(
            presentationTimeNs: Long,
            fadeAlpha: Float = 1f,
            updateTex: Boolean = true,
            localUs: Long = -1
        ) {
            if (updateTex) {
                surfaceTexture.updateTexImage()
                surfaceTexture.getTransformMatrix(lastMtx)
            }
            GLES20.glClearColor(0f, 0f, 0f, 1f)
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
            GLES20.glUseProgram(program)
            GLES20.glUniformMatrix4fv(texMatrixLoc, 1, false, lastMtx, 0)
            GLES20.glUniform1i(filterLoc, filter)
            GLES20.glUniform1f(fadeLoc, fadeAlpha)
            drawQuad(
                program, posLoc, texCoordLoc, samplerLoc,
                videoTexId, GLES11Ext.GL_TEXTURE_EXTERNAL_OES, 0f, 0f, 1f, 1f,
                texL, texT, texR, texB, rotateMode
            )
            if (hasText || hasShape) {
                GLES20.glUseProgram(textProgram)
                GLES20.glUniformMatrix4fv(tTexMatrixLoc, 1, false, IDENTITY_MATRIX, 0)
                GLES20.glEnable(GLES20.GL_BLEND)
                GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
                if (hasText) {
                    // Keyframe text animation: 1 = slide in from left over
                    // 1.5s, 2 = fade in over 1s. localUs is clip-local time.
                    val lu = if (localUs < 0) Long.MAX_VALUE else localUs
                    var tLeft = 0f
                    var tRight = 1f
                    var tAlpha = 1f
                    if (textAnim == 1) {
                        val p = (lu / 1_500_000f).coerceIn(0f, 1f)
                        tLeft = p - 1f
                        tRight = p
                    } else if (textAnim == 2) {
                        tAlpha = (lu / 1_000_000f).coerceIn(0f, 1f)
                    }
                    GLES20.glUniform1f(tAlphaLoc, tAlpha)
                    drawQuad(
                        textProgram, tPosLoc, tTexCoordLoc, tSamplerLoc,
                        textTexId, GLES20.GL_TEXTURE_2D, tLeft, textTop, tRight, textTop + textHeightFrac,
                        0f, 0f, 1f, 1f
                    )
                }
                if (hasShape) {
                    // Sticker at the bottom-right corner.
                    GLES20.glUniform1f(tAlphaLoc, 1f)
                    drawQuad(
                        textProgram, tPosLoc, tTexCoordLoc, tSamplerLoc,
                        shapeTexId, GLES20.GL_TEXTURE_2D, 0.78f, 0.72f, 0.98f, 0.97f,
                        0f, 0f, 1f, 1f
                    )
                }
                GLES20.glDisable(GLES20.GL_BLEND)
            }
            EGLExt.eglPresentationTimeANDROID(display, eglSurface, presentationTimeNs)
            EGL14.eglSwapBuffers(display, eglSurface)
        }

        private fun drawQuad(
            prog: Int, aPos: Int, aTex: Int, sampler: Int,
            texId: Int, target: Int, left: Float, top: Float, right: Float, bottom: Float,
            texL: Float, texT: Float, texR: Float, texB: Float,
            rotate: Int = 0
        ) {
            // Map quad region (left, top, right, bottom in 0..1, top-down)
            // to GL coords: x in [-1, 1], y in [1, -1], then rotate/flip.
            fun rot(x: Float, y: Float): Pair<Float, Float> = when (rotate) {
                1 -> Pair(y, -x) // 90 CW
                2 -> Pair(-x, -y) // 180
                3 -> Pair(-y, x) // 270 CW
                4 -> Pair(-x, y) // flip horizontal
                5 -> Pair(x, -y) // flip vertical
                else -> Pair(x, y)
            }
            val corners = listOf(
                rot(left * 2 - 1, 1 - top * 2),
                rot(left * 2 - 1, 1 - bottom * 2),
                rot(right * 2 - 1, 1 - top * 2),
                rot(right * 2 - 1, 1 - bottom * 2)
            )
            val quad = floatArrayOf(
                corners[0].first, corners[0].second,
                corners[1].first, corners[1].second,
                corners[2].first, corners[2].second,
                corners[3].first, corners[3].second
            )
            val tex = floatArrayOf(texL, texT, texL, texB, texR, texT, texR, texB)
            val vb = ByteBuffer.allocateDirect(quad.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
            vb.put(quad).position(0)
            val tb = ByteBuffer.allocateDirect(tex.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
            tb.put(tex).position(0)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(target, texId)
            GLES20.glUniform1i(sampler, 0)
            GLES20.glEnableVertexAttribArray(aPos)
            GLES20.glVertexAttribPointer(aPos, 2, GLES20.GL_FLOAT, false, 0, vb)
            GLES20.glEnableVertexAttribArray(aTex)
            GLES20.glVertexAttribPointer(aTex, 2, GLES20.GL_FLOAT, false, 0, tb)
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
            GLES20.glDisableVertexAttribArray(aPos)
            GLES20.glDisableVertexAttribArray(aTex)
        }

        private fun makeTextTexture(text: String): Int {
            val bmp = Bitmap.createBitmap(width, (height * textHeightFrac).toInt(), Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bmp)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE
                textSize = bmp.height * 0.55f
                textAlign = Paint.Align.CENTER
                setShadowLayer(6f, 0f, 2f, Color.BLACK)
            }
            canvas.drawText(text, bmp.width / 2f, bmp.height * 0.68f, paint)
            val texIds = IntArray(1)
            GLES20.glGenTextures(1, texIds, 0)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texIds[0])
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
            android.opengl.GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bmp, 0)
            bmp.recycle()
            return texIds[0]
        }

        /** Draw one bitmap frame (used by reverse-video export). */
        fun drawBitmapFrame(bitmap: Bitmap, presentationTimeNs: Long) {
            if (bitmapTexId == 0) {
                val texIds = IntArray(1)
                GLES20.glGenTextures(1, texIds, 0)
                bitmapTexId = texIds[0]
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, bitmapTexId)
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
            }
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, bitmapTexId)
            android.opengl.GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0)
            GLES20.glClearColor(0f, 0f, 0f, 1f)
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
            GLES20.glUseProgram(textProgram)
            GLES20.glUniformMatrix4fv(tTexMatrixLoc, 1, false, IDENTITY_MATRIX, 0)
            GLES20.glUniform1f(tAlphaLoc, 1f)
            drawQuad(
                textProgram, tPosLoc, tTexCoordLoc, tSamplerLoc,
                bitmapTexId, GLES20.GL_TEXTURE_2D, 0f, 0f, 1f, 1f,
                0f, 0f, 1f, 1f
            )
            EGLExt.eglPresentationTimeANDROID(display, eglSurface, presentationTimeNs)
            EGL14.eglSwapBuffers(display, eglSurface)
        }

        private fun makeShapeTexture(shape: Int): Int {
            val size = 256
            val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bmp)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.parseColor("#00D1B2")
                style = Paint.Style.FILL
            }
            val cx = size / 2f
            val cy = size / 2f
            when (shape) {
                SHAPE_CIRCLE -> canvas.drawCircle(cx, cy, size * 0.38f, paint)
                SHAPE_STAR -> {
                    val path = android.graphics.Path()
                    val outer = size * 0.42f
                    val inner = size * 0.18f
                    for (i in 0 until 10) {
                        val r = if (i % 2 == 0) outer else inner
                        val angle = Math.PI / 5 * i - Math.PI / 2
                        val x = (cx + r * Math.cos(angle)).toFloat()
                        val y = (cy + r * Math.sin(angle)).toFloat()
                        if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                    }
                    path.close()
                    canvas.drawPath(path, paint)
                }
                SHAPE_HEART -> {
                    val path = android.graphics.Path()
                    path.moveTo(cx, cy + size * 0.32f)
                    path.cubicTo(
                        cx - size * 0.55f, cy - size * 0.05f,
                        cx - size * 0.28f, cy - size * 0.42f,
                        cx, cy - size * 0.12f
                    )
                    path.cubicTo(
                        cx + size * 0.28f, cy - size * 0.42f,
                        cx + size * 0.55f, cy - size * 0.05f,
                        cx, cy + size * 0.32f
                    )
                    path.close()
                    canvas.drawPath(path, paint)
                }
                SHAPE_ARROW -> {
                    val path = android.graphics.Path()
                    path.moveTo(size * 0.15f, cy - size * 0.12f)
                    path.lineTo(size * 0.60f, cy - size * 0.12f)
                    path.lineTo(size * 0.60f, cy - size * 0.30f)
                    path.lineTo(size * 0.90f, cy)
                    path.lineTo(size * 0.60f, cy + size * 0.30f)
                    path.lineTo(size * 0.60f, cy + size * 0.12f)
                    path.lineTo(size * 0.15f, cy + size * 0.12f)
                    path.close()
                    canvas.drawPath(path, paint)
                }
                else -> return 0
            }
            val texIds = IntArray(1)
            GLES20.glGenTextures(1, texIds, 0)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texIds[0])
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
            android.opengl.GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bmp, 0)
            bmp.recycle()
            return texIds[0]
        }

        private fun buildProgram(): Int {
            val vertex = """
                attribute vec4 aPosition;
                attribute vec2 aTexCoord;
                varying vec2 vTexCoord;
                uniform mat4 uTexMatrix;
                void main() {
                    gl_Position = aPosition;
                    vTexCoord = (uTexMatrix * vec4(aTexCoord, 0.0, 1.0)).xy;
                }
            """.trimIndent()
            val fragment = """
                #extension GL_OES_EGL_image_external : require
                precision mediump float;
                varying vec2 vTexCoord;
                uniform samplerExternalOES sTexture;
                uniform int uFilter;
                uniform float uFade;
                void main() {
                    vec4 c = texture2D(sTexture, vTexCoord);
                    if (uFilter == 1) {
                        float g = dot(c.rgb, vec3(0.299, 0.587, 0.114));
                        c = vec4(g, g, g, c.a);
                    } else if (uFilter == 2) {
                        float r = c.r * 0.393 + c.g * 0.769 + c.b * 0.189;
                        float g = c.r * 0.349 + c.g * 0.686 + c.b * 0.168;
                        float b = c.r * 0.272 + c.g * 0.534 + c.b * 0.131;
                        c = vec4(min(r, 1.0), min(g, 1.0), min(b, 1.0), c.a);
                    } else if (uFilter == 3) {
                        c = vec4(min(c.r * 1.15, 1.0), c.g, c.b * 0.85, c.a);
                    } else if (uFilter == 4) {
                        c = vec4(c.r * 0.85, c.g, min(c.b * 1.15, 1.0), c.a);
                    } else if (uFilter == 5) {
                        c = vec4(1.0 - c.r, 1.0 - c.g, 1.0 - c.b, c.a);
                    } else if (uFilter == 6) {
                        float d = distance(vTexCoord, vec2(0.5, 0.5));
                        float v = smoothstep(0.75, 0.35, d);
                        c = vec4(c.rgb * (0.35 + 0.65 * v), c.a);
                    } else if (uFilter == 7) {
                        // Chroma key: green screen -> solid black. Key strength
                        // is how much greener a pixel is than red/blue.
                        float key = smoothstep(0.12, 0.35, c.g - max(c.r, c.b));
                        c = vec4(mix(c.rgb, vec3(0.0), key), c.a);
                    }
                    gl_FragColor = vec4(c.rgb * uFade, c.a);
                }
            """.trimIndent()
            // Note: text quads use a plain 2D texture; they are drawn with the
            // same program but the sampler type differs, so text rendering
            // switches to a second program built below.
            return buildProgramPair(vertex, fragment)
        }

        private var textProgram = 0
        private var tPosLoc = 0
        private var tTexCoordLoc = 0
        private var tSamplerLoc = 0
        private var tTexMatrixLoc = 0
        private var tAlphaLoc = 0
        private var bitmapTexId = 0

        private val IDENTITY_MATRIX = floatArrayOf(
            1f, 0f, 0f, 0f,
            0f, 1f, 0f, 0f,
            0f, 0f, 1f, 0f,
            0f, 0f, 0f, 1f
        )

        private fun buildProgramPair(vertex: String, fragment: String): Int {
            val vs = compileShader(GLES20.GL_VERTEX_SHADER, vertex)
            val fs = compileShader(GLES20.GL_FRAGMENT_SHADER, fragment)
            val prog = GLES20.glCreateProgram()
            GLES20.glAttachShader(prog, vs)
            GLES20.glAttachShader(prog, fs)
            GLES20.glLinkProgram(prog)
            // Second program for plain 2D text textures.
            val textFragment = """
                precision mediump float;
                varying vec2 vTexCoord;
                uniform sampler2D sTexture;
                uniform float uAlpha;
                void main() {
                    vec4 c = texture2D(sTexture, vTexCoord);
                    gl_FragColor = vec4(c.rgb, c.a * uAlpha);
                }
            """.trimIndent()
            val tfs = compileShader(GLES20.GL_FRAGMENT_SHADER, textFragment)
            textProgram = GLES20.glCreateProgram()
            GLES20.glAttachShader(textProgram, vs)
            GLES20.glAttachShader(textProgram, tfs)
            GLES20.glLinkProgram(textProgram)
            tPosLoc = GLES20.glGetAttribLocation(textProgram, "aPosition")
            tTexCoordLoc = GLES20.glGetAttribLocation(textProgram, "aTexCoord")
            tSamplerLoc = GLES20.glGetUniformLocation(textProgram, "sTexture")
            tTexMatrixLoc = GLES20.glGetUniformLocation(textProgram, "uTexMatrix")
            tAlphaLoc = GLES20.glGetUniformLocation(textProgram, "uAlpha")
            return prog
        }

        private fun compileShader(type: Int, source: String): Int {
            val shader = GLES20.glCreateShader(type)
            GLES20.glShaderSource(shader, source)
            GLES20.glCompileShader(shader)
            return shader
        }

        fun release() {
            try { surfaceTexture.release() } catch (ignored: Exception) { }
            if (eglSurface != EGL14.EGL_NO_SURFACE) {
                EGL14.eglDestroySurface(display, eglSurface)
            }
            if (eglContext != EGL14.EGL_NO_CONTEXT) {
                EGL14.eglDestroyContext(display, eglContext)
            }
            try { EGL14.eglTerminate(display) } catch (ignored: Exception) { }
        }
    }
}
