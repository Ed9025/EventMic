package com.eventmic.app

import android.Manifest
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.IBinder
import android.view.Menu
import android.view.MenuItem
import android.view.animation.AnimationUtils
import android.widget.SeekBar
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.eventmic.app.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var audioService: AudioService? = null
    private var isBound = false
    private val PERMISSION_REQUEST_CODE = 100

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as AudioService.AudioBinder
            audioService = binder.getService()
            isBound = true
            updateUI()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            audioService = null
            isBound = false
        }
    }

    private val statusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                AudioService.ACTION_AUDIO_LEVEL -> {
                    val level = intent.getIntExtra(AudioService.EXTRA_AUDIO_LEVEL, 0)
                    updateVuMeter(level)
                }
                AudioService.ACTION_STATE_CHANGED -> {
                    updateUI()
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)

        setupUI()
        checkPermissions()
    }

    private fun setupUI() {
        // Main mic button
        binding.btnMic.setOnClickListener {
            if (isBound) {
                if (audioService?.isRunning == true) {
                    stopAudio()
                } else {
                    startAudio()
                }
            } else {
                startAudio()
            }
        }

        // Volume seekbar
        binding.seekVolume.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    val volume = progress / 100f
                    audioService?.setVolume(volume)
                    binding.tvVolumeValue.text = "$progress%"
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        // Gain seekbar
        binding.seekGain.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    val gain = (progress / 50f) * 3f // 0x to 6x gain
                    audioService?.setGain(gain)
                    val gainDb = if (progress == 50) "0 dB" else
                        "${String.format("%.1f", 20 * Math.log10(gain.toDouble()))} dB"
                    binding.tvGainValue.text = gainDb
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        // Noise cancel toggle
        binding.switchNoiseCancelling.setOnCheckedChangeListener { _, isChecked ->
            audioService?.setNoiseCancelling(isChecked)
            binding.tvNoiseCancelStatus.text = if (isChecked) "Activo" else "Inactivo"
            binding.tvNoiseCancelStatus.setTextColor(
                ContextCompat.getColor(this,
                    if (isChecked) R.color.green_active else R.color.gray_inactive)
            )
        }

        // HD Audio toggle
        binding.switchHdAudio.setOnCheckedChangeListener { _, isChecked ->
            audioService?.setHdAudio(isChecked)
            binding.tvHdAudioStatus.text = if (isChecked) "48kHz / 16-bit" else "8kHz / 8-bit"
        }

        // Echo cancellation toggle
        binding.switchEchoCancellation.setOnCheckedChangeListener { _, isChecked ->
            audioService?.setEchoCancellation(isChecked)
        }

        // Bluetooth toggle
        binding.switchBluetooth.setOnCheckedChangeListener { _, isChecked ->
            audioService?.setBluetoothHeadset(isChecked)
            if (isChecked) {
                Toast.makeText(this, "Conectando auricular Bluetooth...", Toast.LENGTH_SHORT).show()
            }
        }

        // Speaker output toggle
        binding.switchSpeaker.setOnCheckedChangeListener { _, isChecked ->
            audioService?.setSpeakerOutput(isChecked)
            binding.ivSpeakerIcon.setImageResource(
                if (isChecked) R.drawable.ic_volume_up else R.drawable.ic_volume_off
            )
        }

        // Set default values
        binding.seekVolume.progress = 80
        binding.tvVolumeValue.text = "80%"
        binding.seekGain.progress = 50
        binding.tvGainValue.text = "0 dB"
    }

    private fun startAudio() {
        val intent = Intent(this, AudioService::class.java)
        ContextCompat.startForegroundService(this, intent)
        bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)

        val pulseAnim = AnimationUtils.loadAnimation(this, R.anim.pulse)
        binding.btnMic.startAnimation(pulseAnim)
        binding.btnMic.setImageResource(R.drawable.ic_mic_active)
        binding.tvStatus.text = "🎙️ EN VIVO"
        binding.tvStatus.setTextColor(ContextCompat.getColor(this, R.color.red_live))
        binding.cardStatus.setCardBackgroundColor(ContextCompat.getColor(this, R.color.red_live_bg))
    }

    private fun stopAudio() {
        audioService?.stopAudio()
        binding.btnMic.clearAnimation()
        binding.btnMic.setImageResource(R.drawable.ic_mic_off)
        binding.tvStatus.text = "⏸ PAUSADO"
        binding.tvStatus.setTextColor(ContextCompat.getColor(this, R.color.gray_inactive))
        binding.cardStatus.setCardBackgroundColor(ContextCompat.getColor(this, R.color.card_background))
        binding.vuMeter.progress = 0
    }

    private fun updateVuMeter(level: Int) {
        binding.vuMeter.progress = level
        val color = when {
            level > 85 -> ContextCompat.getColor(this, R.color.red_clip)
            level > 65 -> ContextCompat.getColor(this, R.color.yellow_warn)
            else -> ContextCompat.getColor(this, R.color.green_active)
        }
        binding.tvLevelDb.text = "${level}%"
    }

    private fun updateUI() {
        audioService?.let { service ->
            if (service.isRunning) {
                binding.btnMic.setImageResource(R.drawable.ic_mic_active)
                binding.tvStatus.text = "🎙️ EN VIVO"
                binding.tvStatus.setTextColor(ContextCompat.getColor(this, R.color.red_live))
            } else {
                binding.btnMic.setImageResource(R.drawable.ic_mic_off)
                binding.tvStatus.text = "⏸ PAUSADO"
                binding.tvStatus.setTextColor(ContextCompat.getColor(this, R.color.gray_inactive))
            }
        }
    }

    private fun checkPermissions() {
        val permissions = arrayOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.MODIFY_AUDIO_SETTINGS,
            Manifest.permission.BLUETOOTH_CONNECT
        )
        val missing = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, missing.toTypedArray(), PERMISSION_REQUEST_CODE)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSION_REQUEST_CODE) {
            if (grantResults.any { it != PackageManager.PERMISSION_GRANTED }) {
                Toast.makeText(this,
                    "Se requieren permisos de micrófono para funcionar",
                    Toast.LENGTH_LONG).show()
            }
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_settings -> {
                startActivity(Intent(this, SettingsActivity::class.java))
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    override fun onResume() {
        super.onResume()
        val filter = IntentFilter().apply {
            addAction(AudioService.ACTION_AUDIO_LEVEL)
            addAction(AudioService.ACTION_STATE_CHANGED)
        }
        registerReceiver(statusReceiver, filter, RECEIVER_NOT_EXPORTED)
    }

    override fun onPause() {
        super.onPause()
        unregisterReceiver(statusReceiver)
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isBound) {
            unbindService(serviceConnection)
            isBound = false
        }
    }
}
