package io.github.daniele21.localllm.externalembedded

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.SystemClock
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.sqrt

internal interface VoiceObserver {
    fun onVoicePhase(phase: VoicePhase)

    fun onVoiceStatus(status: String)

    fun onVoiceTranscript(text: String)
}

/**
 * Sample-app-only foreground dictation. One worker serializes download, microphone and
 * native transcription. Native whisper_full reads an AtomicBoolean cancellation signal.
 */
internal class SpeechInputController(private val context: Context, private val observer: VoiceObserver) {
    private val worker = Executors.newSingleThreadExecutor()
    private val state = VoiceInputState()
    private val model = WhisperModelStore(context)
    private val cancelled = AtomicBoolean(false)
    private val destroyed = AtomicBoolean(false)
    private val recording = AtomicBoolean(false)
    private val recorder = AtomicReference<AudioRecord?>(null)

    init {
        worker.execute {
            if (model.verifiedModel() != null && state.modelAlreadyReady()) {
                observer.onVoiceStatus("Whisper Small Q5 ready · offline")
                observer.onVoicePhase(VoicePhase.READY)
            } else {
                observer.onVoiceStatus("Voice model not installed · ~190 MB download")
            }
        }
    }

    fun current(): VoicePhase = state.current()

    fun prepare() {
        if (!state.beginPreparation() || destroyed.get()) return
        cancelled.set(false)
        observer.onVoicePhase(VoicePhase.PREPARING)
        worker.execute {
            try {
                model.ensureReady(cancelled, observer::onVoiceStatus)
                if (cancelled.get() || destroyed.get()) return@execute
                if (state.completePreparation()) {
                    observer.onVoiceStatus("Whisper Small Q5 ready · offline")
                    observer.onVoicePhase(VoicePhase.READY)
                }
            } catch (failure: Exception) {
                if (!destroyed.get() && !cancelled.get()) {
                    observer.onVoiceStatus("Voice setup failed · ${failure.message ?: "retry"}")
                }
            } finally {
                if (!destroyed.get() && state.current() != VoicePhase.READY) {
                    observer.onVoicePhase(state.settle(false))
                }
            }
        }
    }

    fun record(): Boolean {
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            observer.onVoiceStatus("Microphone permission required")
            return false
        }
        if (destroyed.get() || !state.beginRecording()) return false
        cancelled.set(false)
        recording.set(true)
        observer.onVoicePhase(VoicePhase.RECORDING)
        observer.onVoiceStatus("Recording · tap Finish when done")
        worker.execute { captureThenTranscribe() }
        return true
    }

    fun finish() {
        if (!state.finishRecording()) return
        recording.set(false)
        runCatching { recorder.get()?.stop() }
        observer.onVoicePhase(VoicePhase.TRANSCRIBING)
        observer.onVoiceStatus("Transcribing locally · Whisper Small Q5…")
    }

    fun cancel() {
        if (!state.cancel()) return
        cancelled.set(true)
        recording.set(false)
        runCatching { recorder.get()?.stop() }
        observer.onVoicePhase(VoicePhase.CANCELLING)
        observer.onVoiceStatus("Cancelling voice input…")
    }

    fun close() {
        if (!destroyed.compareAndSet(false, true)) return
        cancelled.set(true)
        recording.set(false)
        runCatching { recorder.get()?.stop() }
        worker.shutdown()
    }

    private fun captureThenTranscribe() {
        try {
            val audio = capturePcm()
            if (cancelled.get() || destroyed.get() || state.current() != VoicePhase.TRANSCRIBING) return
            check(audio.size >= MIN_SAMPLES) { "Recording too short · speak for at least 0.25 seconds" }
            val samples = FloatArray(audio.size) { index -> audio[index] / 32768f }
            check(hasSpeech(samples)) { "No audible speech detected · try again" }
            val verified = checkNotNull(model.verifiedModel()) { "Voice model is not verified" }
            val started = SystemClock.elapsedRealtime()
            val transcript = WhisperNative.transcribe(verified.absolutePath, samples, cancelled).trim()
            if (cancelled.get() || destroyed.get()) return
            if (state.completeTranscription()) {
                observer.onVoiceStatus(
                    if (transcript.isBlank()) "No speech recognized · try again" else
                        "Transcribed locally in ${SystemClock.elapsedRealtime() - started} ms · review before Send",
                )
                observer.onVoicePhase(VoicePhase.READY)
                if (transcript.isNotBlank()) observer.onVoiceTranscript(transcript)
            }
        } catch (failure: Exception) {
            if (!cancelled.get() && !destroyed.get()) {
                observer.onVoiceStatus("Voice input failed · ${failure.message ?: "retry"}")
            }
        } finally {
            if (!destroyed.get() && state.current() != VoicePhase.READY) {
                val phase = state.settle(true)
                observer.onVoicePhase(phase)
                if (cancelled.get()) observer.onVoiceStatus("Voice input cancelled")
            }
        }
    }

    private fun capturePcm(): ShortArray {
        val minimum = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        check(minimum > 0) { "Microphone configuration unavailable" }
        val bufferBytes = maxOf(minimum, 4096)
        @Suppress("MissingPermission")
        val source = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            bufferBytes,
        )
        check(source.state == AudioRecord.STATE_INITIALIZED) { "Microphone unavailable" }
        recorder.set(source)
        val collected = ShortArray(MAX_SAMPLES)
        val chunk = ShortArray(bufferBytes / 2)
        var written = 0
        try {
            if (cancelled.get() || !recording.get()) return ShortArray(0)
            source.startRecording()
            check(source.recordingState == AudioRecord.RECORDSTATE_RECORDING) { "Microphone could not start" }
            while (recording.get() && !cancelled.get() && written < MAX_SAMPLES) {
                val count = source.read(
                    chunk,
                    0,
                    minOf(chunk.size, MAX_SAMPLES - written),
                    AudioRecord.READ_BLOCKING,
                )
                if (count < 0) {
                    if (!recording.get() || cancelled.get()) break
                    error("Microphone read failed ($count)")
                }
                if (count == 0) continue
                System.arraycopy(chunk, 0, collected, written, count)
                written += count
            }
            if (written == MAX_SAMPLES && state.finishRecording()) {
                observer.onVoicePhase(VoicePhase.TRANSCRIBING)
                observer.onVoiceStatus("30-second limit reached · transcribing locally…")
            }
        } finally {
            recorder.compareAndSet(source, null)
            runCatching {
                if (source.recordingState == AudioRecord.RECORDSTATE_RECORDING) source.stop()
            }
            source.release()
            recording.set(false)
        }
        return collected.copyOf(written)
    }

    private companion object {
        const val SAMPLE_RATE = 16_000
        const val MAX_SAMPLES = SAMPLE_RATE * 30
        const val MIN_SAMPLES = SAMPLE_RATE / 4

        fun hasSpeech(samples: FloatArray): Boolean {
            val power = samples.fold(0.0) { accumulated, sample ->
                accumulated + sample.toDouble() * sample.toDouble()
            }
            return sqrt(power / samples.size) >= 0.003
        }
    }
}
