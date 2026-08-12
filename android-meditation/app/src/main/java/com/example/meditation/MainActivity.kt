package com.example.meditation

import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.CountDownTimer
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.OpenableColumns
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.NumberPicker
import android.widget.SeekBar
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.documentfile.provider.DocumentFile
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.switchmaterial.SwitchMaterial
import java.util.Locale
import kotlin.math.max

class MainActivity : AppCompatActivity() {
    private lateinit var timerText: TextView
    private lateinit var folderText: TextView
    private lateinit var trackText: TextView
    private lateinit var alarmFileText: TextView
    private lateinit var volumeText: TextView
    private lateinit var statusText: TextView
    private lateinit var playButton: MaterialButton
    private lateinit var timerControlButton: MaterialButton
    private lateinit var alarmSwitch: SwitchMaterial
    private lateinit var volumeSeekBar: SeekBar

    private var selectedDurationMs = 10 * 60 * 1000L
    private var remainingMs = selectedDurationMs
    private var countDownTimer: CountDownTimer? = null
    private var isRunning = false
    private var playAudioWithTimer = false

    private var folderUri: Uri? = null
    private val audioFiles = mutableListOf<DocumentFile>()
    private var currentTrackIndex = 0
    private var playlistPlayer: MediaPlayer? = null

    private var alarmUri: Uri? = null
    private var alarmPlayer: MediaPlayer? = null
    private val volumeHandler = Handler(Looper.getMainLooper())
    private var alarmVolume = 0.01f

    private val preferences by lazy { getSharedPreferences("meditation", MODE_PRIVATE) }

    private val folderPicker = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri ?: return@registerForActivityResult
        persistReadPermission(uri)
        folderUri = uri
        preferences.edit().putString("folder_uri", uri.toString()).apply()
        loadAudioFolder(uri)
    }

    private val alarmPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@registerForActivityResult
        persistReadPermission(uri)
        alarmUri = uri
        preferences.edit().putString("alarm_uri", uri.toString()).apply()
        alarmFileText.text = displayName(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        bindViews()
        restoreSelections()
        configureActions()
        updateTimerText()
    }

    private fun bindViews() {
        timerText = findViewById(R.id.timerText)
        folderText = findViewById(R.id.folderText)
        trackText = findViewById(R.id.trackText)
        alarmFileText = findViewById(R.id.alarmFileText)
        volumeText = findViewById(R.id.volumeText)
        statusText = findViewById(R.id.statusText)
        playButton = findViewById(R.id.playButton)
        timerControlButton = findViewById(R.id.timerControlButton)
        alarmSwitch = findViewById(R.id.alarmSwitch)
        volumeSeekBar = findViewById(R.id.volumeSeekBar)
    }

    private fun restoreSelections() {
        preferences.getString("folder_uri", null)?.let {
            folderUri = Uri.parse(it)
            loadAudioFolder(requireNotNull(folderUri))
        }
        preferences.getString("alarm_uri", null)?.let {
            alarmUri = Uri.parse(it)
            alarmFileText.text = displayName(requireNotNull(alarmUri))
        }
        val savedVolume = preferences.getInt("alarm_volume", 50).coerceIn(1, 100)
        volumeSeekBar.progress = savedVolume
        updateVolumeLabel(savedVolume)
        alarmSwitch.isChecked = preferences.getBoolean("alarm_enabled", false)
    }

    private fun configureActions() {
        findViewById<MaterialCardView>(R.id.timerCard).setOnClickListener {
            if (isRunning) pauseSession()
            showDurationPicker()
        }
        findViewById<MaterialButton>(R.id.selectFolderButton).setOnClickListener {
            folderPicker.launch(folderUri)
        }
        findViewById<MaterialButton>(R.id.selectAlarmButton).setOnClickListener {
            alarmPicker.launch(arrayOf("audio/*"))
        }
        findViewById<MaterialButton>(R.id.previousButton).setOnClickListener { skipTrack(-1) }
        findViewById<MaterialButton>(R.id.nextButton).setOnClickListener { skipTrack(1) }
        timerControlButton.setOnClickListener {
            if (isRunning) pauseSession() else startTimerOnly()
        }
        timerControlButton.setOnLongClickListener {
            resetSession()
            true
        }
        playButton.setOnClickListener {
            when {
                isRunning && playAudioWithTimer -> pauseSession()
                isRunning -> startAudioDuringTimer()
                else -> startSession()
            }
        }
        alarmSwitch.setOnCheckedChangeListener { _, enabled ->
            preferences.edit().putBoolean("alarm_enabled", enabled).apply()
        }
        volumeSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                val safeProgress = max(progress, 1)
                if (progress == 0) seekBar.progress = safeProgress
                updateVolumeLabel(safeProgress)
                if (fromUser) preferences.edit().putInt("alarm_volume", safeProgress).apply()
            }

            override fun onStartTrackingTouch(seekBar: SeekBar) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar) = Unit
        })
    }

    private fun startSession() {
        if (audioFiles.isEmpty()) {
            statusText.setText(R.string.select_folder_first)
            return
        }
        if (remainingMs <= 0L) remainingMs = selectedDurationMs
        stopAlarm()
        isRunning = true
        playAudioWithTimer = true
        updateTimerButtons(true)
        playButton.setText(R.string.pause_symbol)
        playButton.contentDescription = getString(R.string.pause)
        statusText.setText(R.string.playing)
        startCountdown()
        if (playlistPlayer?.isPlaying != true) playTrack(currentTrackIndex)
    }

    private fun startTimerOnly() {
        if (remainingMs <= 0L) remainingMs = selectedDurationMs
        stopAlarm()
        stopPlaylist()
        isRunning = true
        playAudioWithTimer = false
        updateTimerButtons(true)
        playButton.setText(R.string.play_symbol)
        playButton.contentDescription = getString(R.string.play)
        statusText.setText(R.string.timer_running)
        startCountdown()
    }

    private fun startAudioDuringTimer() {
        if (audioFiles.isEmpty()) {
            statusText.setText(R.string.select_folder_first)
            return
        }
        playAudioWithTimer = true
        playButton.setText(R.string.pause_symbol)
        playButton.contentDescription = getString(R.string.pause)
        statusText.setText(R.string.playing)
        playTrack(currentTrackIndex)
    }

    private fun pauseSession() {
        isRunning = false
        playAudioWithTimer = false
        countDownTimer?.cancel()
        countDownTimer = null
        playlistPlayer?.takeIf { it.isPlaying }?.pause()
        playButton.setText(R.string.play_symbol)
        playButton.contentDescription = getString(R.string.play)
        updateTimerButtons(false)
        statusText.setText(R.string.paused)
    }

    private fun resetSession() {
        isRunning = false
        playAudioWithTimer = false
        countDownTimer?.cancel()
        countDownTimer = null
        stopPlaylist()
        stopAlarm()
        remainingMs = selectedDurationMs
        updateTimerText()
        playButton.setText(R.string.play_symbol)
        playButton.contentDescription = getString(R.string.play)
        updateTimerButtons(false)
        statusText.setText(R.string.ready)
    }

    private fun startCountdown() {
        countDownTimer?.cancel()
        countDownTimer = object : CountDownTimer(remainingMs, 250L) {
            override fun onTick(millisUntilFinished: Long) {
                remainingMs = millisUntilFinished
                updateTimerText()
            }

            override fun onFinish() {
                remainingMs = 0L
                isRunning = false
                playAudioWithTimer = false
                stopPlaylist()
                updateTimerText()
                playButton.setText(R.string.play_symbol)
                playButton.contentDescription = getString(R.string.play)
                updateTimerButtons(false)
                statusText.setText(R.string.timer_finished)
                vibrateAtZero()
                if (alarmSwitch.isChecked) startGradualAlarm()
            }
        }.start()
    }

    private fun playTrack(index: Int) {
        if (audioFiles.isEmpty()) return
        currentTrackIndex = (index + audioFiles.size) % audioFiles.size
        val document = audioFiles[currentTrackIndex]
        trackText.text = document.name ?: getString(R.string.no_track)
        playlistPlayer?.release()
        playlistPlayer = createPlayer(document.uri) { player ->
            if (isRunning) player.start()
        }?.also { player ->
            player.setOnCompletionListener {
                if (isRunning) playTrack(currentTrackIndex + 1)
            }
        }
    }

    private fun skipTrack(offset: Int) {
        if (audioFiles.isEmpty()) {
            statusText.setText(R.string.select_folder_first)
            return
        }
        currentTrackIndex = (currentTrackIndex + offset + audioFiles.size) % audioFiles.size
        if (isRunning && playAudioWithTimer) playTrack(currentTrackIndex)
        else trackText.text = audioFiles[currentTrackIndex].name ?: getString(R.string.no_track)
    }

    private fun createPlayer(uri: Uri, whenPrepared: (MediaPlayer) -> Unit): MediaPlayer? {
        return try {
            MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                setDataSource(this@MainActivity, uri)
                setOnPreparedListener(whenPrepared)
                setOnErrorListener { _, _, _ ->
                    statusText.setText(R.string.cannot_play)
                    true
                }
                prepareAsync()
            }
        } catch (_: Exception) {
            statusText.setText(R.string.cannot_play)
            null
        }
    }

    private fun startGradualAlarm() {
        val uri = alarmUri ?: return
        stopAlarm()
        alarmVolume = 0.01f
        alarmPlayer = createPlayer(uri) { player ->
            player.setVolume(alarmVolume, alarmVolume)
            player.start()
            scheduleVolumeIncrease()
        }?.also { player ->
            player.setOnCompletionListener { stopAlarm() }
        }
    }

    private fun scheduleVolumeIncrease() {
        val target = volumeSeekBar.progress.coerceAtLeast(1) / 100f
        if (alarmVolume >= target) return
        volumeHandler.postDelayed({
            val player = alarmPlayer ?: return@postDelayed
            alarmVolume = (alarmVolume + 0.01f).coerceAtMost(target)
            player.setVolume(alarmVolume, alarmVolume)
            scheduleVolumeIncrease()
        }, 2_000L)
    }

    private fun stopPlaylist() {
        playlistPlayer?.run {
            stopSafely()
            release()
        }
        playlistPlayer = null
    }

    private fun stopAlarm() {
        volumeHandler.removeCallbacksAndMessages(null)
        alarmPlayer?.run {
            stopSafely()
            release()
        }
        alarmPlayer = null
    }

    private fun MediaPlayer.stopSafely() {
        try {
            stop()
        } catch (_: IllegalStateException) {
            // The player may still be preparing.
        }
    }

    private fun loadAudioFolder(uri: Uri) {
        audioFiles.clear()
        try {
            DocumentFile.fromTreeUri(this, uri)
                ?.listFiles()
                ?.filter { it.isFile && isAudioFile(it) }
                ?.sortedBy { it.name?.lowercase(Locale.getDefault()) }
                ?.let(audioFiles::addAll)
        } catch (_: SecurityException) {
            preferences.edit().remove("folder_uri").apply()
        }
        currentTrackIndex = 0
        folderText.text = if (audioFiles.isEmpty()) {
            getString(R.string.no_audio_files)
        } else {
            getString(R.string.folder_track_count, audioFiles.size)
        }
        trackText.text = audioFiles.firstOrNull()?.name ?: getString(R.string.no_track)
    }

    private fun isAudioFile(file: DocumentFile): Boolean {
        if (file.type?.startsWith("audio/") == true) return true
        val extension = file.name?.substringAfterLast('.', "")?.lowercase(Locale.ROOT)
        return extension in setOf("mp3", "m4a", "aac", "wav", "ogg", "flac", "opus")
    }

    private fun showDurationPicker() {
        val totalSeconds = (remainingMs / 1000L).toInt()
        val minutesPicker = NumberPicker(this).apply {
            minValue = 0
            maxValue = 180
            value = totalSeconds / 60
            contentDescription = getString(R.string.minutes)
        }
        val secondsPicker = NumberPicker(this).apply {
            minValue = 0
            maxValue = 59
            value = totalSeconds % 60
            contentDescription = getString(R.string.seconds)
        }
        val padding = (20 * resources.displayMetrics.density).toInt()
        val pickerLayout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(padding, padding, padding, 0)
            addView(minutesPicker)
            addView(secondsPicker)
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.set_timer)
            .setView(pickerLayout)
            .setPositiveButton(R.string.set) { _, _ ->
                val seconds = max(1, minutesPicker.value * 60 + secondsPicker.value)
                selectedDurationMs = seconds * 1000L
                remainingMs = selectedDurationMs
                updateTimerText()
                statusText.setText(R.string.ready)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun updateTimerText() {
        val totalSeconds = (remainingMs + 999L) / 1000L
        timerText.text = String.format(
            Locale.getDefault(),
            "%02d:%02d",
            totalSeconds / 60,
            totalSeconds % 60
        )
    }

    private fun updateVolumeLabel(progress: Int) {
        volumeText.text = getString(R.string.alarm_volume, progress)
    }

    private fun updateTimerButtons(running: Boolean) {
        timerControlButton.setText(if (running) R.string.stop_symbol else R.string.play_symbol)
        timerControlButton.contentDescription = getString(
            if (running) R.string.stop_timer else R.string.start_timer_without_audio
        )
    }

    private fun vibrateAtZero() {
        val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            getSystemService(VibratorManager::class.java).defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }
        vibrator.vibrate(VibrationEffect.createOneShot(700L, VibrationEffect.DEFAULT_AMPLITUDE))
    }

    private fun persistReadPermission(uri: Uri) {
        try {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (_: SecurityException) {
            // Some document providers grant access without supporting persistence.
        }
    }

    private fun displayName(uri: Uri): String {
        return contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            } ?: uri.lastPathSegment ?: getString(R.string.no_alarm_selected)
    }

    override fun onDestroy() {
        countDownTimer?.cancel()
        stopPlaylist()
        stopAlarm()
        super.onDestroy()
    }
}
