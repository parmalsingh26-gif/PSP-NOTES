package app.inkflow.studio.feature.audio

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import android.os.SystemClock
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

enum class RecordingState {
    IDLE,
    RECORDING,
    PAUSED
}

class AudioNoteRecorder(private val context: Context) {
    private var mediaRecorder: MediaRecorder? = null
    private var currentAudioFile: File? = null
    private var startTimeMs: Long = 0L
    private var pauseOffsetMs: Long = 0L

    private val _recordingState = MutableStateFlow(RecordingState.IDLE)
    val recordingState: StateFlow<RecordingState> = _recordingState.asStateFlow()

    private val _durationMs = MutableStateFlow(0L)
    val durationMs: StateFlow<Long> = _durationMs.asStateFlow()

    private var timerJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.Main + Job())

    fun startRecording(documentId: String): File? {
        if (_recordingState.value == RecordingState.RECORDING) return currentAudioFile

        val audioDir = File(context.filesDir, "audio").apply { mkdirs() }
        val audioFile = File(audioDir, "$documentId.m4a")
        currentAudioFile = audioFile

        try {
            val recorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                MediaRecorder(context)
            } else {
                @Suppress("DEPRECATION")
                MediaRecorder()
            }

            recorder.apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setAudioEncodingBitRate(128000)
                setAudioSamplingRate(44100)
                setOutputFile(audioFile.absolutePath)
                prepare()
                start()
            }
            mediaRecorder = recorder
            startTimeMs = SystemClock.elapsedRealtime()
            pauseOffsetMs = 0L
            _recordingState.value = RecordingState.RECORDING

            startDurationTimer()
            return audioFile
        } catch (e: Exception) {
            e.printStackTrace()
            releaseRecorder()
            _recordingState.value = RecordingState.IDLE
            return null
        }
    }

    fun stopRecording(): Long {
        val totalDuration = _durationMs.value
        try {
            mediaRecorder?.stop()
        } catch (e: Exception) {
            e.printStackTrace()
        }
        releaseRecorder()
        timerJob?.cancel()
        _recordingState.value = RecordingState.IDLE
        return totalDuration
    }

    val currentTimestampMs: Long
        get() {
            return if (_recordingState.value == RecordingState.RECORDING) {
                SystemClock.elapsedRealtime() - startTimeMs + pauseOffsetMs
            } else {
                0L
            }
        }

    private fun startDurationTimer() {
        timerJob?.cancel()
        timerJob = scope.launch {
            while (isActive && _recordingState.value == RecordingState.RECORDING) {
                _durationMs.value = SystemClock.elapsedRealtime() - startTimeMs + pauseOffsetMs
                delay(200)
            }
        }
    }

    private fun releaseRecorder() {
        try {
            mediaRecorder?.reset()
            mediaRecorder?.release()
        } catch (e: Exception) {
            e.printStackTrace()
        }
        mediaRecorder = null
    }
}

