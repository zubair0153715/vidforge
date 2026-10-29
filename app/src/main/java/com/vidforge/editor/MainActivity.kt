package com.vidforge.editor

import android.app.Activity
import android.content.Intent
import android.media.MediaMetadataRetriever
import android.media.MediaPlayer
import android.media.PlaybackParams
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import java.util.Locale

/**
 * VidForge v1 — original CapCut-type video editor (own code, own design).
 * Pick a video, preview it, trim it, split it at the playhead, change
 * playback speed, mute audio, set orientation, export losslessly and share.
 * Fully offline; the system picker means no storage permission is needed.
 */
class MainActivity : Activity(), SurfaceHolder.Callback {

    private val pickCode = 1001
    private val pickMusicCode = 1002
    private val pickClip2Code = 1003
    private var videoUri: Uri? = null
    private var musicUri: Uri? = null
    private var clip2Uri: Uri? = null
    private var clip2DurationMs: Long = 0
    private var recorder: android.media.MediaRecorder? = null
    private var recording = false
    private val voiceoverRequestCode = 2001
    private var player: MediaPlayer? = null
    private var durationMs: Long = 0
    private var durationUs: Long = 0
    private var videoWidth = 0
    private var videoHeight = 0
    private var videoBytes: Long = 0
    private var surfaceReady = false
    private var lastExport: Uri? = null
    private var previewingTrim = false

    private val handler = Handler(Looper.getMainLooper())
    private val progressTask = object : Runnable {
        override fun run() {
            val p = player
            if (p != null && p.isPlaying) {
                val pos = p.currentPosition
                findViewById<SeekBar>(R.id.seekPlay).progress =
                    if (durationMs > 0) (pos * 1000L / durationMs).toInt() else 0
                findViewById<TextView>(R.id.txtTime).text =
                    fmt(pos.toLong()) + " / " + fmt(durationMs)
                if (previewingTrim && pos.toLong() >= trimEndMs()) {
                    p.pause()
                    previewingTrim = false
                    findViewById<Button>(R.id.btnPlay).text = getString(R.string.play)
                }
            }
            handler.postDelayed(this, 250)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        findViewById<SurfaceView>(R.id.surfaceView).holder.addCallback(this)

        val speedAdapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_item,
            listOf("0.5x", "1x", "1.5x", "2x")
        )
        speedAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        findViewById<Spinner>(R.id.spinnerSpeed).adapter = speedAdapter
        findViewById<Spinner>(R.id.spinnerSpeed).setSelection(1)

        val orientAdapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_item,
            listOf("0°", "90°", "180°", "270°")
        )
        orientAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        findViewById<Spinner>(R.id.spinnerOrient).adapter = orientAdapter

        val filterAdapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_item,
            listOf("None", "Grayscale", "Sepia", "Warm", "Cool", "Invert", "Vignette", "Chroma Key")
        )
        filterAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        findViewById<Spinner>(R.id.spinnerFilter).adapter = filterAdapter

        val overlayPosAdapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_item,
            listOf("Top", "Center", "Bottom")
        )
        overlayPosAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        findViewById<Spinner>(R.id.spinnerOverlayPos).adapter = overlayPosAdapter
        findViewById<Spinner>(R.id.spinnerOverlayPos).setSelection(2)

        val cropAdapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_item,
            listOf("Full frame", "Square (1:1)", "Wide (16:9)", "Vertical (9:16)")
        )
        cropAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        findViewById<Spinner>(R.id.spinnerCrop).adapter = cropAdapter

        val shapeAdapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_item,
            listOf("None", "Circle", "Star", "Heart", "Arrow")
        )
        shapeAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        findViewById<Spinner>(R.id.spinnerShape).adapter = shapeAdapter

        val volumeAdapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_item,
            listOf("50%", "100%", "150%")
        )
        volumeAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        findViewById<Spinner>(R.id.spinnerVolume).adapter = volumeAdapter
        findViewById<Spinner>(R.id.spinnerVolume).setSelection(1)

        val transitionAdapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_item,
            listOf("Cut", "Fade (through black)", "Dissolve", "Slide")
        )
        transitionAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        findViewById<Spinner>(R.id.spinnerTransition).adapter = transitionAdapter

        findViewById<Button>(R.id.btnPickClip2).setOnClickListener {
            val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
                type = "video/*"
                addCategory(Intent.CATEGORY_OPENABLE)
            }
            startActivityForResult(Intent.createChooser(intent, "Pick second clip"), pickClip2Code)
        }

        findViewById<Button>(R.id.btnJoin).setOnClickListener {
            val uriA = videoUri ?: run {
                Toast.makeText(this, R.string.pick_first, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val uriB = clip2Uri ?: run {
                Toast.makeText(this, "Pehle second clip pick karo.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val transition = findViewById<Spinner>(R.id.spinnerTransition).selectedItemPosition
            val filter = findViewById<Spinner>(R.id.spinnerFilter).selectedItemPosition
            val overlay = findViewById<android.widget.EditText>(R.id.editOverlay).text.toString()
                .takeIf { it.isNotBlank() }
            val overlayPos = findViewById<Spinner>(R.id.spinnerOverlayPos).selectedItemPosition
            val shape = findViewById<Spinner>(R.id.spinnerShape).selectedItemPosition
            val mute = findViewById<CheckBox>(R.id.chkMute).isChecked
            val orient = when (findViewById<Spinner>(R.id.spinnerOrient).selectedItemPosition) {
                1 -> 90
                2 -> 180
                3 -> 270
                else -> 0
            }
            val startA = trimStartUs()
            val endA = trimEndUs()
            if (endA <= startA) {
                Toast.makeText(this, "Trim range ghalat hai.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            findViewById<TextView>(R.id.txtStatus).text = "Joining clips… please wait."
            Thread {
                val name = "vidforge_join_" + System.currentTimeMillis() + ".mp4"
                val out = VideoTranscoder.joinClips(
                    this, uriA, startA, endA, uriB, 0, clip2DurationMs * 1000,
                    transition, filter, overlay, overlayPos, shape, !mute, orient, name
                )
                runOnUiThread {
                    if (out != null) {
                        lastExport = out
                        findViewById<TextView>(R.id.txtStatus).text =
                            getString(R.string.export_done) + " " + name
                        Toast.makeText(this, R.string.export_done, Toast.LENGTH_SHORT).show()
                    } else {
                        findViewById<TextView>(R.id.txtStatus).text =
                            getString(R.string.export_failed)
                        Toast.makeText(this, R.string.export_failed, Toast.LENGTH_SHORT).show()
                    }
                }
            }.start()
        }

        val curveAdapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_item,
            listOf("None", "Slow → Fast", "Fast → Slow")
        )
        curveAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        findViewById<Spinner>(R.id.spinnerCurve).adapter = curveAdapter

        val textAnimAdapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_item,
            listOf("None", "Slide in", "Fade in")
        )
        textAnimAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        findViewById<Spinner>(R.id.spinnerTextAnim).adapter = textAnimAdapter

        findViewById<Button>(R.id.btnReverse).setOnClickListener {
            val uri = videoUri ?: run {
                Toast.makeText(this, R.string.pick_first, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val startUs = trimStartUs()
            val endUs = trimEndUs()
            if (endUs <= startUs) {
                Toast.makeText(this, "Trim range ghalat hai.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            findViewById<TextView>(R.id.txtStatus).text = "Reversing video… please wait."
            Thread {
                val name = "vidforge_reverse_" + System.currentTimeMillis() + ".mp4"
                val out = VideoTranscoder.reverseVideo(this, uri, startUs, endUs, name)
                runOnUiThread {
                    if (out != null) {
                        lastExport = out
                        findViewById<TextView>(R.id.txtStatus).text =
                            getString(R.string.export_done) + " " + name
                        Toast.makeText(this, R.string.export_done, Toast.LENGTH_SHORT).show()
                    } else {
                        findViewById<TextView>(R.id.txtStatus).text =
                            getString(R.string.export_failed)
                        Toast.makeText(this, R.string.export_failed, Toast.LENGTH_SHORT).show()
                    }
                }
            }.start()
        }

        val rotateAdapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_item,
            listOf("None", "Rotate 90°", "Rotate 180°", "Rotate 270°", "Flip horizontal", "Flip vertical")
        )
        rotateAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        findViewById<Spinner>(R.id.spinnerRotate).adapter = rotateAdapter

        val freezeAdapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_item,
            listOf("None", "2 sec", "3 sec")
        )
        freezeAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        findViewById<Spinner>(R.id.spinnerFreeze).adapter = freezeAdapter

        val qualityAdapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_item,
            listOf("Original", "720p", "1080p", "4K (upscale)")
        )
        qualityAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        findViewById<Spinner>(R.id.spinnerQuality).adapter = qualityAdapter

        findViewById<Button>(R.id.btnExtractAudio).setOnClickListener {
            val uri = videoUri ?: run {
                Toast.makeText(this, R.string.pick_first, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val startUs = trimStartUs()
            val endUs = trimEndUs()
            if (endUs <= startUs) {
                Toast.makeText(this, "Trim range ghalat hai.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            Thread {
                val name = "vidforge_audio_" + System.currentTimeMillis() + ".m4a"
                val out = TrimExporter.extractAudio(this, uri, startUs, endUs, name)
                runOnUiThread {
                    if (out != null) {
                        lastExport = out
                        Toast.makeText(this, "Audio Music/VidForge me save ho gaya.", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(this, "Audio nahi mila (is video me audio track nahi).", Toast.LENGTH_SHORT).show()
                    }
                }
            }.start()
        }

        findViewById<Button>(R.id.btnRecordVoiceover).setOnClickListener {
            if (recording) {
                stopVoiceover()
                (it as Button).text = "Record voiceover"
                return@setOnClickListener
            }
            if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) !=
                android.content.pm.PackageManager.PERMISSION_GRANTED
            ) {
                requestPermissions(
                    arrayOf(android.Manifest.permission.RECORD_AUDIO), voiceoverRequestCode
                )
            } else {
                startVoiceover()
                (it as Button).text = "Stop recording"
            }
        }

        findViewById<Button>(R.id.btnPickMusic).setOnClickListener {
            val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
                type = "audio/*"
                addCategory(Intent.CATEGORY_OPENABLE)
            }
            startActivityForResult(Intent.createChooser(intent, "Pick music"), pickMusicCode)
        }

        findViewById<Button>(R.id.btnPick).setOnClickListener {
            val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
                type = "video/*"
                addCategory(Intent.CATEGORY_OPENABLE)
            }
            startActivityForResult(Intent.createChooser(intent, "Pick a video"), pickCode)
        }

        findViewById<Button>(R.id.btnPlay).setOnClickListener {
            val p = player ?: run {
                Toast.makeText(this, R.string.pick_first, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (p.isPlaying) {
                p.pause()
                (it as Button).text = getString(R.string.play)
            } else {
                applySpeed()
                p.start()
                previewingTrim = false
                (it as Button).text = getString(R.string.pause)
            }
        }

        findViewById<Button>(R.id.btnPreviewTrim).setOnClickListener {
            val p = player ?: run {
                Toast.makeText(this, R.string.pick_first, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            p.seekTo(trimStartMs().toInt())
            applySpeed()
            p.start()
            previewingTrim = true
            findViewById<Button>(R.id.btnPlay).text = getString(R.string.pause)
        }

        findViewById<SeekBar>(R.id.seekPlay).setOnSeekBarChangeListener(
            object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar, v: Int, fromUser: Boolean) {
                    if (fromUser && durationMs > 0) {
                        player?.seekTo((v * durationMs / 1000).toInt())
                    }
                }
                override fun onStartTrackingTouch(s: SeekBar) {}
                override fun onStopTrackingTouch(s: SeekBar) {}
            }
        )

        val trimWatcher = object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar, v: Int, fromUser: Boolean) {
                updateTrimLabel()
            }
            override fun onStartTrackingTouch(s: SeekBar) {}
            override fun onStopTrackingTouch(s: SeekBar) {}
        }
        findViewById<SeekBar>(R.id.seekStart).setOnSeekBarChangeListener(trimWatcher)
        findViewById<SeekBar>(R.id.seekEnd).setOnSeekBarChangeListener(trimWatcher)

        findViewById<Button>(R.id.btnExport).setOnClickListener {
            val uri = videoUri ?: run {
                Toast.makeText(this, R.string.pick_first, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            doExport(uri, trimStartUs(), trimEndUs(), "vidforge_trim_")
        }

        findViewById<Button>(R.id.btnSplit).setOnClickListener {
            val uri = videoUri ?: run {
                Toast.makeText(this, R.string.pick_first, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val splitMs = player?.currentPosition?.toLong() ?: (durationMs / 2)
            val splitUs = splitMs * 1000
            val first = doExport(uri, 0, splitUs, "vidforge_part1_")
            if (first != null) {
                doExport(uri, splitUs, durationUs, "vidforge_part2_")
            }
        }

        findViewById<Button>(R.id.btnExportEffects).setOnClickListener {
            val uri = videoUri ?: run {
                Toast.makeText(this, R.string.pick_first, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val filter = findViewById<Spinner>(R.id.spinnerFilter).selectedItemPosition
            val overlay = findViewById<android.widget.EditText>(R.id.editOverlay).text.toString()
                .takeIf { it.isNotBlank() }
            val overlayPos = findViewById<Spinner>(R.id.spinnerOverlayPos).selectedItemPosition
            val speed = when (findViewById<Spinner>(R.id.spinnerSpeed).selectedItemPosition) {
                0 -> 0.5f
                2 -> 1.5f
                3 -> 2.0f
                else -> 1.0f
            }
            val mute = findViewById<CheckBox>(R.id.chkMute).isChecked
            val orient = when (findViewById<Spinner>(R.id.spinnerOrient).selectedItemPosition) {
                1 -> 90
                2 -> 180
                3 -> 270
                else -> 0
            }
            val cropMode = findViewById<Spinner>(R.id.spinnerCrop).selectedItemPosition
            val shape = findViewById<Spinner>(R.id.spinnerShape).selectedItemPosition
            val volumeGain = when (findViewById<Spinner>(R.id.spinnerVolume).selectedItemPosition) {
                0 -> 0.5f
                2 -> 1.5f
                else -> 1.0f
            }
            val fade = findViewById<CheckBox>(R.id.chkFade).isChecked
            val rotateMode = findViewById<Spinner>(R.id.spinnerRotate).selectedItemPosition
            val freezeUs = when (findViewById<Spinner>(R.id.spinnerFreeze).selectedItemPosition) {
                1 -> 2_000_000L
                2 -> 3_000_000L
                else -> 0L
            }
            val qualityMode = findViewById<Spinner>(R.id.spinnerQuality).selectedItemPosition
            val curveMode = findViewById<Spinner>(R.id.spinnerCurve).selectedItemPosition
            val textAnim = findViewById<Spinner>(R.id.spinnerTextAnim).selectedItemPosition
            val startUs = trimStartUs()
            val endUs = trimEndUs()
            if (endUs <= startUs) {
                Toast.makeText(this, "Trim range ghalat hai.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            findViewById<TextView>(R.id.txtStatus).text = getString(R.string.effects_exporting)
            Thread {
                val name = "vidforge_fx_" + System.currentTimeMillis() + ".mp4"
                val out = VideoTranscoder.transcode(
                    this, uri, startUs, endUs, filter, overlay, overlayPos,
                    speed, !mute, orient, name, cropMode, shape, volumeGain, fade, musicUri,
                    rotateMode, freezeUs, qualityMode, curveMode, textAnim
                )
                runOnUiThread {
                    if (out != null) {
                        lastExport = out
                        findViewById<TextView>(R.id.txtStatus).text =
                            getString(R.string.export_done) + " " + name
                        Toast.makeText(this, R.string.export_done, Toast.LENGTH_SHORT).show()
                    } else {
                        findViewById<TextView>(R.id.txtStatus).text =
                            getString(R.string.export_failed)
                        Toast.makeText(this, R.string.export_failed, Toast.LENGTH_SHORT).show()
                    }
                }
            }.start()
        }

        findViewById<Button>(R.id.btnCover).setOnClickListener {
            val uri = videoUri ?: run {
                Toast.makeText(this, R.string.pick_first, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val posUs = (player?.currentPosition?.toLong() ?: 0) * 1000
            Thread {
                try {
                    val retriever = MediaMetadataRetriever()
                    retriever.setDataSource(this, uri)
                    val frame = retriever.getFrameAtTime(
                        posUs, MediaMetadataRetriever.OPTION_CLOSEST
                    )
                    retriever.release()
                    if (frame == null) {
                        runOnUiThread {
                            Toast.makeText(this, "Frame nahi mila.", Toast.LENGTH_SHORT).show()
                        }
                        return@Thread
                    }
                    val values = android.content.ContentValues().apply {
                        put(
                            android.provider.MediaStore.Images.Media.DISPLAY_NAME,
                            "vidforge_cover_" + System.currentTimeMillis() + ".png"
                        )
                        put(android.provider.MediaStore.Images.Media.MIME_TYPE, "image/png")
                        put(
                            android.provider.MediaStore.Images.Media.RELATIVE_PATH,
                            android.os.Environment.DIRECTORY_PICTURES + "/VidForge"
                        )
                    }
                    val outUri = contentResolver.insert(
                        android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values
                    )
                    if (outUri != null) {
                        contentResolver.openOutputStream(outUri)?.use { stream ->
                            frame.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, stream)
                        }
                        frame.recycle()
                        runOnUiThread {
                            Toast.makeText(
                                this, "Cover frame Pictures/VidForge me save ho gaya.",
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    }
                } catch (e: Exception) {
                    runOnUiThread {
                        Toast.makeText(this, "Cover save nahi hua.", Toast.LENGTH_SHORT).show()
                    }
                }
            }.start()
        }

        findViewById<Button>(R.id.btnShare).setOnClickListener {
            val uri = lastExport ?: run {
                Toast.makeText(this, "Pehle export karo.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val share = Intent(Intent.ACTION_SEND).apply {
                type = "video/mp4"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(share, "Share video"))
        }

        handler.post(progressTask)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == voiceoverRequestCode &&
            grantResults.isNotEmpty() &&
            grantResults[0] == android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            startVoiceover()
            findViewById<Button>(R.id.btnRecordVoiceover).text = "Stop recording"
        } else if (requestCode == voiceoverRequestCode) {
            Toast.makeText(this, "Mic permission ke baghair voiceover nahi hoga.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun startVoiceover() {
        try {
            val file = java.io.File(cacheDir, "voiceover.m4a")
            val rec = android.media.MediaRecorder()
            rec.setAudioSource(android.media.MediaRecorder.AudioSource.MIC)
            rec.setOutputFormat(android.media.MediaRecorder.OutputFormat.MPEG_4)
            rec.setAudioEncoder(android.media.MediaRecorder.AudioEncoder.AAC)
            rec.setOutputFile(file.absolutePath)
            rec.prepare()
            rec.start()
            recorder = rec
            recording = true
            findViewById<TextView>(R.id.txtVoiceover).text = "Recording… dobara tap karke stop karo."
        } catch (e: Exception) {
            Toast.makeText(this, "Recording shuru nahi hui.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun stopVoiceover() {
        try {
            recorder?.stop()
            recorder?.release()
        } catch (e: Exception) {
            // Stop on a too-short recording throws; ignore.
        }
        recorder = null
        recording = false
        val file = java.io.File(cacheDir, "voiceover.m4a")
        if (!file.exists() || file.length() == 0L) {
            findViewById<TextView>(R.id.txtVoiceover).text = "Recording save nahi hui."
            return
        }
        Thread {
            try {
                val values = android.content.ContentValues().apply {
                    put(
                        android.provider.MediaStore.Audio.Media.DISPLAY_NAME,
                        "vidforge_voiceover_" + System.currentTimeMillis() + ".m4a"
                    )
                    put(android.provider.MediaStore.Audio.Media.MIME_TYPE, "audio/mp4")
                    put(
                        android.provider.MediaStore.Audio.Media.RELATIVE_PATH,
                        android.os.Environment.DIRECTORY_MUSIC + "/VidForge"
                    )
                }
                val outUri = contentResolver.insert(
                    android.provider.MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values
                )
                if (outUri != null) {
                    contentResolver.openOutputStream(outUri)?.use { stream ->
                        file.inputStream().copyTo(stream)
                    }
                    file.delete()
                    musicUri = outUri
                    runOnUiThread {
                        findViewById<TextView>(R.id.txtVoiceover).text =
                            "Voiceover recorded — effects export me mix ho jayega."
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    findViewById<TextView>(R.id.txtVoiceover).text = "Voiceover save nahi hua."
                }
            }
        }.start()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == pickCode && resultCode == RESULT_OK) {
            val uri = data?.data ?: return
            videoUri = uri
            loadVideo(uri)
        }
        if (requestCode == pickClip2Code && resultCode == RESULT_OK) {
            val uri = data?.data ?: return
            clip2Uri = uri
            try {
                val retriever = MediaMetadataRetriever()
                retriever.setDataSource(this, uri)
                clip2DurationMs = retriever.extractMetadata(
                    MediaMetadataRetriever.METADATA_KEY_DURATION
                )?.toLongOrNull() ?: 0
                retriever.release()
            } catch (e: Exception) {
                clip2DurationMs = 0
            }
            findViewById<TextView>(R.id.txtClip2).text =
                "Second clip picked (" + fmt(clip2DurationMs) + ")."
        }
        if (requestCode == pickMusicCode && resultCode == RESULT_OK) {
            val uri = data?.data ?: return
            musicUri = uri
            findViewById<TextView>(R.id.txtMusic).text = "Music picked (mixes at half volume)."
        }
    }

    private fun loadVideo(uri: Uri) {
        releasePlayer()
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(this, uri)
            durationMs = retriever.extractMetadata(
                MediaMetadataRetriever.METADATA_KEY_DURATION
            )?.toLongOrNull() ?: 0
            videoWidth = retriever.extractMetadata(
                MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH
            )?.toIntOrNull() ?: 0
            videoHeight = retriever.extractMetadata(
                MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT
            )?.toIntOrNull() ?: 0
        } catch (e: Exception) {
            durationMs = 0
        } finally {
            try { retriever.release() } catch (ignored: Exception) { }
        }
        durationUs = durationMs * 1000
        try {
            contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                videoBytes = pfd.statSize
            }
        } catch (e: Exception) {
            videoBytes = 0
        }
        findViewById<TextView>(R.id.txtInfo).text =
            "Duration: " + fmt(durationMs) +
                "  •  Resolution: " + videoWidth + "×" + videoHeight +
                "  •  Size: " + String.format(Locale.US, "%.1f MB", videoBytes / 1048576.0)
        updateTrimLabel()
        if (surfaceReady) startPlayer(uri)
    }

    private fun startPlayer(uri: Uri) {
        try {
            val mp = MediaPlayer()
            mp.setDisplay(findViewById<SurfaceView>(R.id.surfaceView).holder)
            mp.setDataSource(this, uri)
            mp.setOnPreparedListener { p ->
                p.start()
                p.pause()
                findViewById<Button>(R.id.btnPlay).text = getString(R.string.play)
            }
            mp.setOnCompletionListener {
                findViewById<Button>(R.id.btnPlay).text = getString(R.string.play)
            }
            mp.prepareAsync()
            player = mp
        } catch (e: Exception) {
            Toast.makeText(this, "Video load nahi hua.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun applySpeed() {
        val p = player ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val speed = when (findViewById<Spinner>(R.id.spinnerSpeed).selectedItemPosition) {
                0 -> 0.5f
                2 -> 1.5f
                3 -> 2.0f
                else -> 1.0f
            }
            try {
                p.playbackParams = PlaybackParams().setSpeed(speed)
            } catch (e: Exception) {
                // Older behaviour: ignore, play at normal speed.
            }
        }
    }

    private fun trimStartMs(): Long {
        val v = findViewById<SeekBar>(R.id.seekStart).progress
        return v * durationMs / 1000
    }

    private fun trimEndMs(): Long {
        val v = findViewById<SeekBar>(R.id.seekEnd).progress
        return v * durationMs / 1000
    }

    private fun trimStartUs(): Long = trimStartMs() * 1000

    private fun trimEndUs(): Long {
        val end = trimEndMs() * 1000
        return if (end <= trimStartUs()) durationUs else end
    }

    private fun updateTrimLabel() {
        if (durationMs <= 0) return
        findViewById<TextView>(R.id.txtTrim).text =
            "Trim: " + fmt(trimStartMs()) + " – " + fmt(trimEndMs())
    }

    private fun doExport(src: Uri, startUs: Long, endUs: Long, prefix: String): Uri? {
        if (endUs <= startUs) {
            Toast.makeText(this, "Trim range ghalat hai.", Toast.LENGTH_SHORT).show()
            return null
        }
        findViewById<TextView>(R.id.txtStatus).text = getString(R.string.exporting)
        val mute = findViewById<CheckBox>(R.id.chkMute).isChecked
        val orient = when (findViewById<Spinner>(R.id.spinnerOrient).selectedItemPosition) {
            1 -> 90
            2 -> 180
            3 -> 270
            else -> 0
        }
        val name = prefix + System.currentTimeMillis() + ".mp4"
        val out = TrimExporter.export(this, src, startUs, endUs, !mute, orient, name)
        if (out != null) {
            lastExport = out
            findViewById<TextView>(R.id.txtStatus).text =
                getString(R.string.export_done) + " " + name
            Toast.makeText(this, R.string.export_done, Toast.LENGTH_SHORT).show()
        } else {
            findViewById<TextView>(R.id.txtStatus).text = getString(R.string.export_failed)
            Toast.makeText(this, R.string.export_failed, Toast.LENGTH_SHORT).show()
        }
        return out
    }

    private fun releasePlayer() {
        try { player?.release() } catch (ignored: Exception) { }
        player = null
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        surfaceReady = true
        videoUri?.let { startPlayer(it) }
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {}

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        surfaceReady = false
    }

    override fun onDestroy() {
        handler.removeCallbacks(progressTask)
        releasePlayer()
        super.onDestroy()
    }

    private fun fmt(ms: Long): String {
        val total = ms / 1000
        return String.format(Locale.US, "%02d:%02d", total / 60, total % 60)
    }
}
