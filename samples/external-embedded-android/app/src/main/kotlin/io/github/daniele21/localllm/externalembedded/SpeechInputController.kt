package io.github.daniele21.localllm.externalembedded

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.SystemClock
import io.github.daniele21.localllm.embedded.whisper.EmbeddedWhisper
import io.github.daniele21.localllm.embedded.whisper.WhisperCancellation
import java.io.IOException
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
    private val model = EmbeddedWhisper(context)

    @Volatile private var cancelled = WhisperCancellation()
    private val destroyed = AtomicBoolean(false)
    private val recording = AtomicBoolean(false)
    private val recorder = AtomicReference<AudioRecord?>(null)

    init {
        worker.execute {
            if (model.isReady() && state.modelAlreadyReady()) {
                observer.onVoiceStatus("Whisper Small Q5 ready · offline")
                observer.onVoicePhase(VoicePhase.READY)
            } else {
                observer.onVoiceStatus("Voice model not installed · ~190 MB download")
            }
        }
    }

    val phase: VoicePhase
        get() = state.current()

    fun prepare() {
        if (!state.beginPreparation() || destroyed.get()) return
        cancelled = WhisperCancellation()
        observer.onVoicePhase(VoicePhase.PREPARING)
        worker.execute {
            var completed = false
            try {
                model.prepare(cancelled) { bytes, total ->
                    observer.onVoiceStatus("Downloading Whisper · ${bytes / 1_000_000} / ${total / 1_000_000} MB")
                }
                if (cancelled.isCancelled || destroyed.get()) return@execute
                if (state.completePreparation()) {
                    completed = true
                    observer.onVoiceStatus("Whisper Small Q5 ready · offline")
                    observer.onVoicePhase(VoicePhase.READY)
                }
            } catch (failure: IllegalStateException) {
                reportFailure("Voice setup failed", failure)
            } catch (failure: IOException) {
                reportFailure("Voice setup failed", failure)
            } finally {
                if (!destroyed.get()) {
                    state.settleIfUnfinished(completed, modelReady = false)?.let(observer::onVoicePhase)
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
        cancelled = WhisperCancellation()
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
        cancelled.cancel()
        recording.set(false)
        runCatching { recorder.get()?.stop() }
        observer.onVoicePhase(VoicePhase.CANCELLING)
        observer.onVoiceStatus("Cancelling voice input…")
    }

    fun close() {
        if (!destroyed.compareAndSet(false, true)) return
        cancelled.cancel()
        recording.set(false)
        runCatching { recorder.get()?.stop() }
        worker.shutdown()
    }

    private fun captureThenTranscribe() {
        var completed = false
        try {
            val audio = capturePcm()
            if (cancelled.isCancelled || destroyed.get() || state.current() != VoicePhase.TRANSCRIBING) return
            val started = SystemClock.elapsedRealtime()
            val transcript = transcribeAudio(audio)
            if (cancelled.isCancelled || destroyed.get()) return
            if (state.completeTranscription()) {
                completed = true
                val status =
                    if (transcript.isBlank()) {
                        "No speech recognized · try again"
                    } else {
                        "Transcribed locally in ${SystemClock.elapsedRealtime() - started} ms · review before Send"
                    }
                observer.onVoiceStatus(status)
                observer.onVoicePhase(VoicePhase.READY)
                if (transcript.isNotBlank()) observer.onVoiceTranscript(transcript)
            }
        } catch (failure: IllegalStateException) {
            reportFailure("Voice input failed", failure)
        } catch (failure: IllegalArgumentException) {
            reportFailure("Voice input failed", failure)
        } catch (failure: SecurityException) {
            reportFailure("Voice input failed", failure)
        } catch (failure: IOException) {
            reportFailure("Voice input failed", failure)
        } finally {
            settleCapture(completed)
        }
    }

    private fun transcribeAudio(audio: ShortArray): String {
        check(audio.size >= MIN_SAMPLES) { "Recording too short · speak for at least 0.25 seconds" }
        val samples = FloatArray(audio.size) { index -> audio[index] / 32768f }
        check(hasSpeech(samples)) { "No audible speech detected · try again" }
        return model.transcribeItalian(audio, cancelled)
    }

    private fun reportFailure(prefix: String, failure: Throwable) {
        if (!cancelled.isCancelled && !destroyed.get()) {
            observer.onVoiceStatus("$prefix · ${failure.message ?: "retry"}")
        }
    }

    private fun settleCapture(completed: Boolean) {
        if (destroyed.get()) return
        state.settleIfUnfinished(completed, modelReady = true)?.let { phase ->
            observer.onVoicePhase(phase)
            if (cancelled.isCancelled) observer.onVoiceStatus("Voice input cancelled")
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
        val collected = ShortArray(MAX_SAMPLES)
        val chunk = ShortArray(bufferBytes / 2)
        var written = 0

        @Suppress("MissingPermission")
        val source = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            bufferBytes,
        )
        try {
            // Even an uninitialized AudioRecord must be released.
            check(source.state == AudioRecord.STATE_INITIALIZED) { "Microphone unavailable" }
            recorder.set(source)
            if (cancelled.isCancelled || !recording.get()) return ShortArray(0)
            source.startRecording()
            check(source.recordingState == AudioRecord.RECORDSTATE_RECORDING) { "Microphone could not start" }
            while (recording.get() && !cancelled.isCancelled && written < MAX_SAMPLES) {
                val count = source.read(
                    chunk,
                    0,
                    minOf(chunk.size, MAX_SAMPLES - written),
                    AudioRecord.READ_BLOCKING,
                )
                if (count > 0) {
                    System.arraycopy(chunk, 0, collected, written, count)
                    written += count
                } else if (count < 0 && recording.get() && !cancelled.isCancelled) {
                    error("Microphone read failed ($count)")
                }
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
    }
}

private fun hasSpeech(samples: FloatArray): Boolean {
    val power = samples.fold(0.0) { accumulated, sample ->
        accumulated + sample.toDouble() * sample.toDouble()
    }
    return sqrt(power / samples.size) >= 0.003
}
