package com.eventmic.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothHeadset
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import android.os.Binder
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import kotlin.math.abs
import kotlin.math.sqrt

class AudioService : Service() {

    companion object {
        const val ACTION_AUDIO_LEVEL = "com.eventmic.app.AUDIO_LEVEL"
        const val ACTION_STATE_CHANGED = "com.eventmic.app.STATE_CHANGED"
        const val EXTRA_AUDIO_LEVEL = "audio_level"
        const val NOTIFICATION_ID = 1001
        const val CHANNEL_ID = "EventMicChannel"

        // HD Audio: 48kHz, Standard: 44100Hz
        const val SAMPLE_RATE_HD = 48000
        const val SAMPLE_RATE_STANDARD = 44100
        const val CHANNEL_IN = AudioFormat.CHANNEL_IN_MONO
        const val CHANNEL_OUT = AudioFormat.CHANNEL_OUT_MONO
        const val ENCODING_HD = AudioFormat.ENCODING_PCM_16BIT
    }

    inner class AudioBinder : Binder() {
        fun getService(): AudioService = this@AudioService
    }

    private val binder = AudioBinder()
    private var audioRecord: AudioRecord? = null
    private var audioTrack: AudioTrack? = null
    private var noiseSuppressor: NoiseSuppressor? = null
    private var echoCanceler: AcousticEchoCanceler? = null
    private var gainControl: AutomaticGainControl? = null
    private var audioManager: AudioManager? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var bluetoothAdapter: BluetoothAdapter? = null
    private var bluetoothHeadset: BluetoothHeadset? = null

    private var processingThread: Thread? = null

    var isRunning = false
        private set

    // Audio settings
    private var volume = 0.8f
    private var gain = 1.0f
    private var noiseCancellingEnabled = true
    private var hdAudioEnabled = true
    private var echoCancellationEnabled = true
    private var bluetoothEnabled = false
    private var speakerOutput = true

    // Noise gate threshold
    private val NOISE_GATE_THRESHOLD = 800

    // Compressor settings
    private val COMPRESSOR_THRESHOLD = 20000
    private val COMPRESSOR_RATIO = 0.7f

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        bluetoothAdapter = BluetoothAdapter.getDefaultAdapter()
        createNotificationChannel()
        acquireWakeLock()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, buildNotification())
        startAudio()
        return START_STICKY
    }

    fun startAudio() {
        if (isRunning) return

        val sampleRate = if (hdAudioEnabled) SAMPLE_RATE_HD else SAMPLE_RATE_STANDARD

        val bufferSize = AudioRecord.getMinBufferSize(
            sampleRate, CHANNEL_IN, ENCODING_HD
        ).coerceAtLeast(4096) * 2

        // Configure microphone source
        val audioSource = when {
            bluetoothEnabled -> MediaRecorder.AudioSource.VOICE_COMMUNICATION
            else -> MediaRecorder.AudioSource.MIC
        }

        try {
            audioRecord = AudioRecord(
                audioSource,
                sampleRate,
                CHANNEL_IN,
                ENCODING_HD,
                bufferSize
            )

            // Apply hardware audio effects
            audioRecord?.audioSessionId?.let { sessionId ->
                if (noiseCancellingEnabled && NoiseSuppressor.isAvailable()) {
                    noiseSuppressor = NoiseSuppressor.create(sessionId)
                    noiseSuppressor?.enabled = true
                }
                if (echoCancellationEnabled && AcousticEchoCanceler.isAvailable()) {
                    echoCanceler = AcousticEchoCanceler.create(sessionId)
                    echoCanceler?.enabled = true
                }
                if (AutomaticGainControl.isAvailable()) {
                    gainControl = AutomaticGainControl.create(sessionId)
                    gainControl?.enabled = true
                }
            }

            // Setup audio output
            val outBufferSize = AudioTrack.getMinBufferSize(
                sampleRate, CHANNEL_OUT, ENCODING_HD
            ).coerceAtLeast(4096) * 2

            val audioUsage = if (speakerOutput)
                AudioAttributes.USAGE_MEDIA
            else
                AudioAttributes.USAGE_VOICE_COMMUNICATION

            audioTrack = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(audioUsage)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(ENCODING_HD)
                        .setSampleRate(sampleRate)
                        .setChannelMask(CHANNEL_OUT)
                        .build()
                )
                .setBufferSizeInBytes(outBufferSize)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()

            // Configure audio routing
            if (speakerOutput) {
                audioManager?.isSpeakerphoneOn = true
                audioManager?.mode = AudioManager.MODE_NORMAL
            } else {
                audioManager?.isSpeakerphoneOn = false
                audioManager?.mode = AudioManager.MODE_IN_COMMUNICATION
            }

            if (bluetoothEnabled) {
                audioManager?.startBluetoothSco()
                audioManager?.isBluetoothScoOn = true
            }

            audioRecord?.startRecording()
            audioTrack?.play()
            isRunning = true

            broadcastStateChanged()
            startProcessingThread(bufferSize)

        } catch (e: Exception) {
            e.printStackTrace()
            stopAudio()
        }
    }

    private fun startProcessingThread(bufferSize: Int) {
        processingThread = Thread {
            val buffer = ShortArray(bufferSize / 2)
            var levelCounter = 0

            while (isRunning && !Thread.interrupted()) {
                val read = audioRecord?.read(buffer, 0, buffer.size) ?: break
                if (read <= 0) continue

                // Apply audio processing pipeline
                val processed = processAudio(buffer, read)

                // Write to output
                audioTrack?.write(processed, 0, read)

                // Update VU meter every 10 frames
                levelCounter++
                if (levelCounter >= 10) {
                    val level = calculateLevel(processed, read)
                    broadcastAudioLevel(level)
                    levelCounter = 0
                }
            }
        }.apply {
            priority = Thread.MAX_PRIORITY
            isDaemon = true
            start()
        }
    }

    private fun processAudio(buffer: ShortArray, length: Int): ShortArray {
        val processed = buffer.copyOf()

        for (i in 0 until length) {
            var sample = processed[i].toFloat()

            // 1. Noise Gate - silence below threshold
            if (!noiseCancellingEnabled || abs(sample) > NOISE_GATE_THRESHOLD) {

                // 2. Apply gain
                sample *= gain

                // 3. Soft knee compressor
                if (abs(sample) > COMPRESSOR_THRESHOLD) {
                    val excess = abs(sample) - COMPRESSOR_THRESHOLD
                    val compressed = COMPRESSOR_THRESHOLD + (excess * COMPRESSOR_RATIO)
                    sample = if (sample > 0) compressed else -compressed
                }

                // 4. Apply volume
                sample *= volume

                // 5. Hard limiter (prevent clipping)
                sample = sample.coerceIn(-32767f, 32767f)

            } else {
                // Noise gate: fade to near-silence (not total zero to avoid click)
                sample *= 0.05f
            }

            processed[i] = sample.toInt().toShort()
        }

        return processed
    }

    private fun calculateLevel(buffer: ShortArray, length: Int): Int {
        // RMS calculation
        var sumSquares = 0.0
        for (i in 0 until length) {
            val sample = buffer[i].toDouble() / 32768.0
            sumSquares += sample * sample
        }
        val rms = sqrt(sumSquares / length)
        return (rms * 100).toInt().coerceIn(0, 100)
    }

    fun stopAudio() {
        isRunning = false
        processingThread?.interrupt()
        processingThread = null

        audioRecord?.apply {
            stop()
            release()
        }
        audioRecord = null

        audioTrack?.apply {
            stop()
            release()
        }
        audioTrack = null

        noiseSuppressor?.release()
        noiseSuppressor = null
        echoCanceler?.release()
        echoCanceler = null
        gainControl?.release()
        gainControl = null

        audioManager?.apply {
            isSpeakerphoneOn = false
            mode = AudioManager.MODE_NORMAL
            if (bluetoothEnabled) {
                stopBluetoothSco()
                isBluetoothScoOn = false
            }
        }

        broadcastStateChanged()
        broadcastAudioLevel(0)
    }

    // Settings setters
    fun setVolume(vol: Float) { volume = vol }

    fun setGain(g: Float) {
        gain = g
    }

    fun setNoiseCancelling(enabled: Boolean) {
        noiseCancellingEnabled = enabled
        noiseSuppressor?.enabled = enabled
    }

    fun setHdAudio(enabled: Boolean) {
        hdAudioEnabled = enabled
        if (isRunning) {
            stopAudio()
            startAudio()
        }
    }

    fun setEchoCancellation(enabled: Boolean) {
        echoCancellationEnabled = enabled
        echoCanceler?.enabled = enabled
    }

    fun setBluetoothHeadset(enabled: Boolean) {
        bluetoothEnabled = enabled
        if (isRunning) {
            stopAudio()
            startAudio()
        }
    }

    fun setSpeakerOutput(enabled: Boolean) {
        speakerOutput = enabled
        if (isRunning) {
            audioManager?.isSpeakerphoneOn = enabled
        }
    }

    private fun broadcastAudioLevel(level: Int) {
        val intent = Intent(ACTION_AUDIO_LEVEL).apply {
            putExtra(EXTRA_AUDIO_LEVEL, level)
        }
        sendBroadcast(intent)
    }

    private fun broadcastStateChanged() {
        sendBroadcast(Intent(ACTION_STATE_CHANGED))
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "EventMic Audio",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Micrófono activo para eventos"
            setShowBadge(false)
        }
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = PendingIntent.getService(
            this, 1,
            Intent(this, AudioService::class.java).apply { action = "STOP" },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("🎙️ EventMic EN VIVO")
            .setContentText("Micrófono activo • Toca para abrir")
            .setSmallIcon(R.drawable.ic_mic_notification)
            .setContentIntent(pendingIntent)
            .addAction(R.drawable.ic_stop, "Detener", stopIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun acquireWakeLock() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "EventMic::AudioWakeLock"
        ).apply { acquire(10 * 60 * 60 * 1000L) } // 10 hours max
    }

    override fun onDestroy() {
        super.onDestroy()
        stopAudio()
        wakeLock?.release()
        bluetoothHeadset?.let {
            bluetoothAdapter?.closeProfileProxy(BluetoothProfile.HEADSET, it)
        }
    }
}
