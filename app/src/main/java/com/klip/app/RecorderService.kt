package com.klip.app

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.MediaStore
import android.view.Surface
import android.widget.Toast
import java.nio.ByteBuffer
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale

class RecorderService : Service() {

    companion object {
        @Volatile var instance: RecorderService? = null
        const val WINDOW_US = 30_000_000L // 30 saniye
        const val RATE = 44100
        val PRESETS = listOf(
            Preset("720p 30fps 8 Mbps", 720, 30, 8_000_000),
            Preset("1080p 30fps 14 Mbps", 1080, 30, 14_000_000),
            Preset("1080p 60fps 45 Mbps", 1080, 60, 45_000_000),
            Preset("1080p 120fps 60 Mbps", 1080, 120, 60_000_000),
            Preset("1440p 60fps 70 Mbps", 1440, 60, 70_000_000),
            Preset("4K 60fps 100 Mbps", 2160, 60, 100_000_000),
            Preset("4K 120fps 100 Mbps", 2160, 120, 100_000_000)
        )
    }

    class Frame(val data: ByteArray, val pts: Long, val flags: Int)

    class Preset(val name: String, val shortSide: Int, val fps: Int, val bitrate: Int)

    private val frames = ArrayDeque<Frame>()   // video
    private val aframes = ArrayDeque<Frame>()  // ses
    @Volatile private var vformat: MediaFormat? = null
    @Volatile private var aformat: MediaFormat? = null
    private var projection: MediaProjection? = null
    private var display: VirtualDisplay? = null
    private var encoder: MediaCodec? = null
    @Volatile private var running = false
    private val main = Handler(Looper.getMainLooper())

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            "START" -> start(intent)
            "STOP" -> { stopAll(); stopSelf() }
        }
        return START_NOT_STICKY
    }

    private fun start(intent: Intent) {
        if (running) return
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel("klip", "Klip kaydı", NotificationManager.IMPORTANCE_LOW)
        )
        val n = Notification.Builder(this, "klip")
            .setContentTitle("Klip kaydı açık")
            .setContentText("F8 = son 30 saniyeyi kaydet")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .build()
        val hasMic = checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        var type = ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
        if (hasMic) type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        startForeground(1, n, type)

        val code = intent.getIntExtra("code", 0)
        @Suppress("DEPRECATION")
        val data = intent.getParcelableExtra<Intent>("data") ?: return
        val mpm = getSystemService(MediaProjectionManager::class.java)
        val proj = mpm.getMediaProjection(code, data)
        proj.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() { stopAll() }
        }, main)
        projection = proj

        val dm = resources.displayMetrics
        var qi = getSharedPreferences("klip", MODE_PRIVATE).getInt("q", 2)
            .coerceIn(0, PRESETS.size - 1)
        var enc: MediaCodec? = null
        var surface: Surface? = null
        var w = 0
        var h = 0
        // İstenen kalite desteklenmiyorsa bir alt kaliteye düşer
        while (enc == null) {
            val pr = PRESETS[qi]
            val scale = minOf(1f, pr.shortSide.toFloat() / minOf(dm.widthPixels, dm.heightPixels))
            w = ((dm.widthPixels * scale).toInt() / 16) * 16
            h = ((dm.heightPixels * scale).toInt() / 16) * 16
            try {
                val fmt = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, w, h).apply {
                    setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                    setInteger(MediaFormat.KEY_BIT_RATE, pr.bitrate)
                    setInteger(MediaFormat.KEY_FRAME_RATE, pr.fps)
                    setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
                    setLong(MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER, 200_000L)
                }
                val e = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
                try {
                    e.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                    surface = e.createInputSurface()
                    e.start()
                    enc = e
                } catch (ex: Exception) {
                    try { e.release() } catch (_: Exception) {}
                    throw ex
                }
            } catch (ex: Exception) {
                if (qi == 0) {
                    toast("Kodlayıcı başlatılamadı: ${ex.message}")
                    stopAll()
                    stopSelf()
                    return
                }
                qi--
                toast("Bu kalite desteklenmiyor, ${PRESETS[qi].name} deneniyor")
            }
        }
        encoder = enc

        display = proj.createVirtualDisplay(
            "klip", w, h, dm.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, surface!!, null, null
        )
        running = true
        instance = this
        val encFinal = enc!!
        Thread { drain(encFinal) }.start()
        if (hasMic) Thread { audioLoop(proj) }.start()
    }

    // ---------------- VIDEO ----------------
    private fun drain(enc: MediaCodec) {
        val info = MediaCodec.BufferInfo()
        try {
            while (running) {
                val i = enc.dequeueOutputBuffer(info, 100_000)
                if (i == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    vformat = enc.outputFormat
                } else if (i >= 0) {
                    val buf = enc.getOutputBuffer(i)!!
                    val isConfig = (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0
                    if (info.size > 0 && !isConfig) {
                        val b = ByteArray(info.size)
                        buf.position(info.offset)
                        buf.limit(info.offset + info.size)
                        buf.get(b)
                        addVideo(Frame(b, info.presentationTimeUs, info.flags))
                    }
                    enc.releaseOutputBuffer(i, false)
                }
            }
        } catch (_: Exception) {
        }
    }

    private fun addVideo(f: Frame) {
        synchronized(frames) {
            frames.addLast(f)
            while (frames.size > 1 && f.pts - frames.peekFirst()!!.pts > WINDOW_US) {
                frames.removeFirst()
            }
            // Klip her zaman bir anahtar kareyle başlamalı
            while (frames.isNotEmpty() &&
                (frames.peekFirst()!!.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME) == 0
            ) {
                frames.removeFirst()
            }
        }
    }

    // ---------------- SES (mikrofon + telefon sesi) ----------------
    @SuppressLint("MissingPermission")
    private fun audioLoop(proj: MediaProjection) {
        var play: AudioRecord? = null
        var mic: AudioRecord? = null
        var enc: MediaCodec? = null
        val chunk = 1024 // kare (frame) sayısı
        try {
            // Telefon sesi (uygulama/oyun sesleri)
            try {
                val cfg = AudioPlaybackCaptureConfiguration.Builder(proj)
                    .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                    .addMatchingUsage(AudioAttributes.USAGE_GAME)
                    .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                    .build()
                val p = AudioRecord.Builder()
                    .setAudioPlaybackCaptureConfig(cfg)
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(RATE)
                            .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
                            .build()
                    )
                    .setBufferSizeInBytes(chunk * 4 * 8)
                    .build()
                if (p.state == AudioRecord.STATE_INITIALIZED) play = p else p.release()
            } catch (_: Exception) {
            }
            // Kendi sesin (mikrofon)
            try {
                val minMic = AudioRecord.getMinBufferSize(
                    RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
                )
                val m = AudioRecord(
                    MediaRecorder.AudioSource.MIC, RATE, AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT, maxOf(minMic, chunk * 2 * 8)
                )
                if (m.state == AudioRecord.STATE_INITIALIZED) mic = m else m.release()
            } catch (_: Exception) {
            }
            if (play == null && mic == null) {
                toast("Ses başlatılamadı, klipler sessiz olacak")
                return
            }

            val afmt = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, RATE, 2).apply {
                setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                setInteger(MediaFormat.KEY_BIT_RATE, 128_000)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, chunk * 4)
            }
            val e = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
            e.configure(afmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            e.start()
            enc = e

            play?.startRecording()
            mic?.startRecording()

            val pBuf = ShortArray(chunk * 2)
            val mBuf = ShortArray(chunk)
            val bytes = ByteArray(chunk * 4)
            val info = MediaCodec.BufferInfo()

            while (running) {
                java.util.Arrays.fill(pBuf, 0.toShort())
                java.util.Arrays.fill(mBuf, 0.toShort())
                play?.read(pBuf, 0, pBuf.size)
                mic?.read(mBuf, 0, mBuf.size)
                var t = System.nanoTime() / 1000 - chunk * 1_000_000L / RATE

                for (i in 0 until chunk) {
                    val mv = mBuf[i].toInt()
                    val l = (pBuf[2 * i] + mv).coerceIn(-32768, 32767)
                    val r = (pBuf[2 * i + 1] + mv).coerceIn(-32768, 32767)
                    bytes[4 * i] = (l and 0xFF).toByte()
                    bytes[4 * i + 1] = ((l shr 8) and 0xFF).toByte()
                    bytes[4 * i + 2] = (r and 0xFF).toByte()
                    bytes[4 * i + 3] = ((r shr 8) and 0xFF).toByte()
                }

                var off = 0
                while (off < bytes.size && running) {
                    val ii = e.dequeueInputBuffer(10_000)
                    if (ii >= 0) {
                        val ib = e.getInputBuffer(ii)!!
                        ib.clear()
                        val n = minOf(ib.remaining(), bytes.size - off)
                        ib.put(bytes, off, n)
                        e.queueInputBuffer(ii, 0, n, t, 0)
                        off += n
                        t += (n / 4) * 1_000_000L / RATE
                    }
                    drainAudio(e, info)
                }
                drainAudio(e, info)
            }
        } catch (_: Exception) {
        } finally {
            try { play?.stop() } catch (_: Exception) {}
            try { play?.release() } catch (_: Exception) {}
            try { mic?.stop() } catch (_: Exception) {}
            try { mic?.release() } catch (_: Exception) {}
            try { enc?.stop() } catch (_: Exception) {}
            try { enc?.release() } catch (_: Exception) {}
        }
    }

    private fun drainAudio(e: MediaCodec, info: MediaCodec.BufferInfo) {
        while (true) {
            val oi = e.dequeueOutputBuffer(info, 0)
            if (oi == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                aformat = e.outputFormat
            } else if (oi >= 0) {
                val buf = e.getOutputBuffer(oi)!!
                val isConfig = (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0
                if (info.size > 0 && !isConfig) {
                    val b = ByteArray(info.size)
                    buf.position(info.offset)
                    buf.limit(info.offset + info.size)
                    buf.get(b)
                    addAudio(Frame(b, info.presentationTimeUs, 0))
                }
                e.releaseOutputBuffer(oi, false)
            } else if (oi == MediaCodec.INFO_TRY_AGAIN_LATER) {
                break
            }
        }
    }

    private fun addAudio(f: Frame) {
        synchronized(aframes) {
            aframes.addLast(f)
            while (aframes.size > 1 && f.pts - aframes.peekFirst()!!.pts > WINDOW_US + 2_000_000L) {
                aframes.removeFirst()
            }
        }
    }

    // ---------------- KAYDET ----------------
    fun saveClip() {
        val vfmt = vformat
        val v = synchronized(frames) { frames.toList() }
        val a = synchronized(aframes) { aframes.toList() }
        if (vfmt == null || v.isEmpty()) {
            toast("Henüz kayıt yok")
            return
        }
        Thread {
            try {
                val name = "klip_" +
                    SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".mp4"
                val cv = ContentValues().apply {
                    put(MediaStore.Video.Media.DISPLAY_NAME, name)
                    put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                    put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/Klipler")
                }
                val uri = contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, cv)!!
                contentResolver.openFileDescriptor(uri, "rw")!!.use { pfd ->
                    val mux = MediaMuxer(pfd.fileDescriptor, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
                    val base = v[0].pts
                    val af = aformat
                    val aList = if (af != null) a.filter { it.pts >= base } else emptyList()
                    val vt = mux.addTrack(vfmt)
                    val at = if (af != null && aList.isNotEmpty()) mux.addTrack(af) else -1
                    mux.start()
                    val bi = MediaCodec.BufferInfo()
                    var vi = 0
                    var ai = 0
                    val aUse = if (at >= 0) aList else emptyList()
                    while (vi < v.size || ai < aUse.size) {
                        val takeV = ai >= aUse.size || (vi < v.size && v[vi].pts <= aUse[ai].pts)
                        if (takeV) {
                            val f = v[vi++]
                            bi.set(0, f.data.size, f.pts - base, f.flags)
                            mux.writeSampleData(vt, ByteBuffer.wrap(f.data), bi)
                        } else {
                            val f = aUse[ai++]
                            bi.set(0, f.data.size, f.pts - base, f.flags)
                            mux.writeSampleData(at, ByteBuffer.wrap(f.data), bi)
                        }
                    }
                    mux.stop()
                    mux.release()
                }
                toast("Klip kaydedildi: Movies/Klipler")
            } catch (e: Exception) {
                toast("Hata: ${e.message}")
            }
        }.start()
    }

    private fun toast(msg: String) {
        main.post { Toast.makeText(applicationContext, msg, Toast.LENGTH_SHORT).show() }
    }

    private fun stopAll() {
        running = false
        instance = null
        try { encoder?.stop() } catch (_: Exception) {}
        try { encoder?.release() } catch (_: Exception) {}
        try { display?.release() } catch (_: Exception) {}
        try { projection?.stop() } catch (_: Exception) {}
        encoder = null; display = null; projection = null
        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    override fun onDestroy() {
        stopAll()
        super.onDestroy()
    }
}
