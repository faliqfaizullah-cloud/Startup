package com.startup.app

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineWhisperModelConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.abs
import kotlin.math.sqrt

// ───────────────────────── Offline Whisper (sherpa-onnx): Malay, Indonesian, Tamil ─────────────────────────

object WhisperEngine {
    private const val MAX_CHUNK = 28 * RATE     // Whisper reads at most 30 s at a time
    private const val SEARCH = 6 * RATE         // look for a quiet spot in the last 6 s of a chunk
    private const val WINDOW = RATE / 10        // 100 ms energy window

    fun transcribe(
        c: Context,
        uri: Uri,
        lang: Lang,
        onProgress: (Int) -> Unit,
        cancelled: () -> Boolean,
    ): TResult {
        val w = Models.wsize(c)
        val dir = Models.wdir(c, w)
        val cfg = OfflineRecognizerConfig(
            featConfig = FeatureConfig(sampleRate = RATE, featureDim = 80),
            modelConfig = OfflineModelConfig(
                whisper = OfflineWhisperModelConfig(
                    encoder = File(dir, "${w.key}-encoder.int8.onnx").absolutePath,
                    decoder = File(dir, "${w.key}-decoder.int8.onnx").absolutePath,
                    language = lang.tag,
                    task = "transcribe",
                ),
                tokens = File(dir, "${w.key}-tokens.txt").absolutePath,
                numThreads = Runtime.getRuntime().availableProcessors().coerceIn(2, 4),
                provider = "cpu",
            ),
            decodingMethod = "greedy_search",
        )
        val rec = try {
            OfflineRecognizer(null, cfg)
        } catch (e: IllegalArgumentException) {
            dir.deleteRecursively()
            throw UserError("The Whisper model couldn't be loaded, so it was removed. Try again to download it fresh.")
        }
        try {
            val out = StringBuilder()
            var words = 0

            fun decode(samples: FloatArray, startSample: Long) {
                if (isQuiet(samples)) return
                val st = rec.createStream()
                try {
                    st.acceptWaveform(samples, RATE)
                    rec.decode(st)
                    val text = rec.getResult(st).text.trim()
                    if (text.isEmpty()) return
                    words += text.split(Regex("\\s+")).count { it.isNotBlank() }
                    out.append('[').append(mmss((startSample / RATE).toInt())).append("] ")
                        .append(text.replaceFirstChar { it.uppercase() }).append('\n')
                } finally {
                    st.release()
                }
            }

            val buf = FloatArray(MAX_CHUNK + RATE)
            var len = 0
            var base = 0L // samples already handed to the recognizer

            fun cut() {
                var at = len
                if (len >= MAX_CHUNK) {
                    // cut at the quietest 100 ms near the end so words aren't split
                    var best = Double.MAX_VALUE
                    var i = len - SEARCH
                    while (i + WINDOW <= len) {
                        var e = 0.0
                        for (k in i until i + WINDOW) e += buf[k] * buf[k]
                        if (e < best) {
                            best = e
                            at = i + WINDOW / 2
                        }
                        i += WINDOW / 2
                    }
                }
                decode(buf.copyOf(at), base)
                System.arraycopy(buf, at, buf, 0, len - at)
                len -= at
                base += at
            }

            val dur = Decoder.decode(
                c, uri,
                { bytes, n ->
                    var i = 0
                    while (i + 1 < n) {
                        val v = (bytes[i].toInt() and 0xFF) or (bytes[i + 1].toInt() shl 8)
                        buf[len++] = v.toShort() / 32768f
                        i += 2
                    }
                    if (len >= MAX_CHUNK) cut()
                },
                onProgress, cancelled,
            )
            if (len > RATE / 3) decode(buf.copyOf(len), base)
            return TResult(out.toString().trim(), dur, words)
        } finally {
            rec.release()
        }
    }

    /** Whisper invents words from silence, so near-silent chunks are skipped. */
    private fun isQuiet(s: FloatArray): Boolean {
        var sum = 0.0
        var peak = 0f
        for (v in s) {
            sum += v * v
            if (abs(v) > peak) peak = abs(v)
        }
        val rms = sqrt(sum / s.size.coerceAtLeast(1))
        return rms < 0.003 || peak < 0.02f
    }
}

// ───────────────────────── The phone's own Google recognizer (Android 13+) ─────────────────────────

object GoogleEngine {
    // Literal keys from RecognizerIntent (API 33), so the code reads the same on every compile target.
    private const val AUDIO_SOURCE = "android.speech.extra.AUDIO_SOURCE"
    private const val AUDIO_CHANNELS = "android.speech.extra.AUDIO_SOURCE_CHANNEL_COUNT"
    private const val AUDIO_ENCODING = "android.speech.extra.AUDIO_SOURCE_ENCODING"
    private const val AUDIO_RATE = "android.speech.extra.AUDIO_SOURCE_SAMPLING_RATE"
    private const val SEGMENTED = "android.speech.extra.SEGMENTED_SESSION"

    suspend fun transcribe(
        c: Context,
        uri: Uri,
        lang: Lang,
        onProgress: (Int) -> Unit,
        cancelled: () -> Boolean,
    ): TResult {
        if (!Models.googleOk(c)) {
            throw UserError("Google speech recognition from a file needs Android 13 or newer with the Google app installed. Use Malay (Whisper) instead.")
        }
        if (c.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            throw UserError("Allow the microphone permission for Startup (the Google recognizer requires it), then try again.")
        }
        val pcm = File(c.cacheDir, "g_${System.nanoTime()}.pcm")
        try {
            val dur = FileOutputStream(pcm).buffered().use { o ->
                Decoder.decode(c, uri, { b, n -> o.write(b, 0, n) }, { p -> onProgress(p * 30 / 100) }, cancelled)
            }
            onProgress(-1)
            val lines = recognize(c, pcm, lang.tag, dur)
            val words = lines.sumOf { l -> l.split(Regex("\\s+")).count { it.isNotBlank() } }
            val text = lines.joinToString("\n") { it.replaceFirstChar { ch -> ch.uppercase() } }
            return TResult(text, dur, words)
        } finally {
            pcm.delete()
        }
    }

    private suspend fun recognize(c: Context, pcm: File, tag: String, durSec: Int): List<String> =
        withContext(Dispatchers.Main) {
            suspendCancellableCoroutine { cont ->
                val main = Handler(Looper.getMainLooper())
                val sr = SpeechRecognizer.createSpeechRecognizer(c)
                val pfd = ParcelFileDescriptor.open(pcm, ParcelFileDescriptor.MODE_READ_ONLY)
                val parts = ArrayList<String>()
                var gotSegments = false
                var finished = false

                fun close() {
                    try { sr.cancel() } catch (_: Exception) {}
                    try { sr.destroy() } catch (_: Exception) {}
                    try { pfd.close() } catch (_: Exception) {}
                }

                val timeout = Runnable {
                    if (!finished) {
                        finished = true
                        close()
                        if (cont.isActive) {
                            cont.resumeWithException(UserError("Google speech recognition didn't respond. Try Malay (Whisper) instead."))
                        }
                    }
                }

                fun finish(err: Throwable?) {
                    if (finished) return
                    finished = true
                    main.removeCallbacks(timeout)
                    close()
                    if (cont.isActive) {
                        if (err != null) cont.resumeWithException(err) else cont.resume(parts)
                    }
                }

                fun take(b: Bundle?) {
                    val t = b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.trim()
                    if (!t.isNullOrEmpty()) parts.add(t)
                }

                sr.setRecognitionListener(object : RecognitionListener {
                    override fun onReadyForSpeech(params: Bundle?) {}
                    override fun onBeginningOfSpeech() {}
                    override fun onRmsChanged(rmsdB: Float) {}
                    override fun onBufferReceived(buffer: ByteArray?) {}
                    override fun onEndOfSpeech() {}
                    override fun onPartialResults(partialResults: Bundle?) {}
                    override fun onEvent(eventType: Int, params: Bundle?) {}

                    override fun onSegmentResults(segmentResults: Bundle) {
                        gotSegments = true
                        take(segmentResults)
                    }

                    override fun onEndOfSegmentedSession() = finish(null)

                    override fun onResults(results: Bundle?) {
                        if (!gotSegments) take(results)
                        finish(null)
                    }

                    override fun onError(error: Int) {
                        // 6 = speech timeout, 7 = no match: the end of the audio, not a failure
                        if (error == 6 || error == 7) { finish(null); return }
                        finish(UserError(when (error) {
                            2, 1, 3 -> "Google speech recognition couldn't reach its service. Check your internet connection."
                            9 -> "Allow the microphone permission for Startup (the Google recognizer requires it), then try again."
                            12, 13 -> "Your phone's Google recognizer doesn't support this language for files. Use Malay (Whisper) instead."
                            else -> "Google speech recognition failed (code $error). Use Malay (Whisper) instead."
                        }))
                    }
                })

                val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                    .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    .putExtra(RecognizerIntent.EXTRA_LANGUAGE, tag)
                    .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
                    .putExtra(AUDIO_SOURCE, pfd)
                    .putExtra(AUDIO_CHANNELS, 1)
                    .putExtra(AUDIO_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
                    .putExtra(AUDIO_RATE, RATE)
                    .putExtra(SEGMENTED, true)

                cont.invokeOnCancellation { main.post { finished = true; main.removeCallbacks(timeout); close() } }
                main.postDelayed(timeout, maxOf(180L, durSec * 2L + 120L) * 1000L)
                try {
                    sr.startListening(intent)
                } catch (e: Exception) {
                    finish(UserError("Google speech recognition couldn't start on this phone. Use Malay (Whisper) instead."))
                }
            }
        }
}
