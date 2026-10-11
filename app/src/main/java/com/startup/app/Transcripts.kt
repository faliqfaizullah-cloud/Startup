package com.startup.app

import android.Manifest
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.vosk.LibVosk
import org.vosk.LogLevel
import org.vosk.Model
import org.vosk.Recognizer
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException
import java.nio.ByteOrder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipInputStream

// ───────────────────────── Model + storage ─────────────────────────

data class Transcript(
    val id: Long,
    val title: String,
    val source: String,        // "Imported" or "Recorded"
    val lang: String,
    val created: Long,
    val durationSec: Int,
    val words: Int,
    val audio: String = "",    // path of the in-app recording, if any
)

/** Transcript list lives in the account's prefs; the (possibly long) text lives in a file per transcript. */
object TStore {
    private fun prefs(c: Context, acct: String) =
        c.getSharedPreferences("startup_data_$acct", Context.MODE_PRIVATE)

    private fun dir(c: Context, acct: String) =
        File(c.filesDir, "transcripts_$acct").apply { mkdirs() }

    fun list(c: Context, acct: String = Store.acctId(c)): List<Transcript> {
        val raw = prefs(c, acct).getString("transcripts", null) ?: return emptyList()
        return try {
            val a = JSONArray(raw)
            (0 until a.length()).map {
                val o = a.getJSONObject(it)
                Transcript(
                    o.getLong("id"), o.getString("title"), o.getString("source"), o.getString("lang"),
                    o.getLong("created"), o.getInt("dur"), o.getInt("words"), o.optString("audio", ""),
                )
            }.sortedByDescending { it.created }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun write(c: Context, acct: String, list: List<Transcript>) {
        val a = JSONArray()
        list.forEach {
            a.put(
                JSONObject().put("id", it.id).put("title", it.title).put("source", it.source)
                    .put("lang", it.lang).put("created", it.created).put("dur", it.durationSec)
                    .put("words", it.words).put("audio", it.audio)
            )
        }
        prefs(c, acct).edit().putString("transcripts", a.toString()).apply()
    }

    fun add(c: Context, acct: String, t: Transcript, text: String) {
        File(dir(c, acct), "${t.id}.txt").writeText(text)
        write(c, acct, list(c, acct) + t)
    }

    fun text(c: Context, t: Transcript, acct: String = Store.acctId(c)): String =
        try { File(dir(c, acct), "${t.id}.txt").readText() } catch (_: Exception) { "" }

    fun delete(c: Context, t: Transcript, acct: String = Store.acctId(c)) {
        File(dir(c, acct), "${t.id}.txt").delete()
        if (t.audio.isNotBlank()) {
            val f = File(t.audio)
            if (f.absolutePath.startsWith(c.filesDir.absolutePath)) f.delete()
        }
        write(c, acct, list(c, acct).filter { it.id != t.id })
    }

    fun lang(c: Context): String =
        c.getSharedPreferences("startup", Context.MODE_PRIVATE).getString("t_lang", "en-us") ?: "en-us"

    fun setLang(c: Context, code: String) {
        c.getSharedPreferences("startup", Context.MODE_PRIVATE).edit().putString("t_lang", code).apply()
    }

    private val stampRe = Regex("^\\[([0-9:]+)] ?(.*)$")

    /** Splits a stored line "[01:23] text" into (stamp, text). */
    fun parse(line: String): Pair<String?, String> {
        val m = stampRe.matchEntire(line) ?: return null to line
        return m.groupValues[1] to m.groupValues[2]
    }

    fun render(raw: String, stamps: Boolean): String =
        if (stamps) raw else raw.lines().joinToString("\n") { parse(it).second }

    fun export(t: Transcript, raw: String, stamps: Boolean): String =
        t.title + "\n" +
            SimpleDateFormat("d MMM yyyy, HH:mm", Locale.getDefault()).format(Date(t.created)) +
            " · " + mmss(t.durationSec) + " · " + t.words + " words\n\n" + render(raw, stamps)
}

fun displayName(c: Context, uri: Uri): String {
    try {
        c.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cur ->
            if (cur.moveToFirst()) {
                val n = cur.getString(0)
                if (!n.isNullOrBlank()) return n.substringBeforeLast('.')
            }
        }
    } catch (_: Exception) {
    }
    return "Recording"
}

// ───────────────────────── Language packs (downloaded once, then offline) ─────────────────────────

data class Lang(val code: String, val label: String, val dir: String, val mb: Int) {
    val url: String get() = "https://alphacephei.com/vosk/models/$dir.zip"
}

object Models {
    /** Malay, Indonesian and Tamil have no offline model in Vosk. */
    val langs = listOf(
        Lang("en-us", "English (US)", "vosk-model-small-en-us-0.15", 39),
        Lang("en-gb", "English (UK)", "vosk-model-small-en-gb-0.15", 41),
        Lang("en-in", "English (India)", "vosk-model-small-en-in-0.4", 36),
        Lang("cn", "Chinese", "vosk-model-small-cn-0.22", 42),
        Lang("hi", "Hindi", "vosk-model-small-hi-0.22", 42),
        Lang("es", "Spanish", "vosk-model-small-es-0.42", 38),
        Lang("fr", "French", "vosk-model-small-fr-0.22", 40),
        Lang("de", "German", "vosk-model-small-de-0.15", 44),
        Lang("ja", "Japanese", "vosk-model-small-ja-0.22", 47),
    )

    fun lang(code: String): Lang = langs.firstOrNull { it.code == code } ?: langs[0]

    private fun root(c: Context) = File(c.filesDir, "models").apply { mkdirs() }
    fun path(c: Context, l: Lang) = File(root(c), l.dir)
    fun installed(c: Context, l: Lang) = File(path(c, l), ".ok").exists()

    fun download(c: Context, l: Lang, onProgress: (Int) -> Unit, cancelled: () -> Boolean) {
        val zip = File(c.cacheDir, "${l.dir}.zip")
        try {
            val conn = URL(l.url).openConnection() as HttpURLConnection
            conn.connectTimeout = 20_000
            conn.readTimeout = 30_000
            try {
                if (conn.responseCode !in 200..299) {
                    throw UserError("Couldn't download the language pack (server error ${conn.responseCode}).")
                }
                val total = conn.contentLengthLong
                conn.inputStream.use { input ->
                    FileOutputStream(zip).use { out ->
                        val buf = ByteArray(64 * 1024)
                        var done = 0L
                        var lastPct = -1
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            if (cancelled()) throw CancellationException("Cancelled")
                            out.write(buf, 0, n)
                            done += n
                            if (total > 0) {
                                val pct = (done * 90 / total).toInt()
                                if (pct != lastPct) {
                                    lastPct = pct
                                    onProgress(pct)
                                }
                            }
                        }
                    }
                }
            } finally {
                conn.disconnect()
            }

            onProgress(92)
            val root = root(c)
            val target = path(c, l)
            target.deleteRecursively()
            val rootPrefix = root.canonicalPath + File.separator
            ZipInputStream(FileInputStream(zip).buffered()).use { zin ->
                var e = zin.nextEntry
                while (e != null) {
                    if (cancelled()) throw CancellationException("Cancelled")
                    val outFile = File(root, e.name)
                    if (!outFile.canonicalPath.startsWith(rootPrefix)) throw IOException("Bad archive")
                    if (e.isDirectory) {
                        outFile.mkdirs()
                    } else {
                        outFile.parentFile?.mkdirs()
                        FileOutputStream(outFile).use { zin.copyTo(it) }
                    }
                    zin.closeEntry()
                    e = zin.nextEntry
                }
            }
            if (!File(target, "am").exists()) throw UserError("The language pack download was incomplete. Try again.")
            File(target, ".ok").writeText("ok")
            onProgress(100)
        } catch (t: Throwable) {
            val partial = path(c, l)
            if (!File(partial, ".ok").exists()) {
                partial.deleteRecursively()
            }
            throw t
        } finally {
            zip.delete()
        }
    }
}

class UserError(message: String) : Exception(message)

// ───────────────────────── Decoder: any audio/video file -> 16 kHz mono PCM ─────────────────────────

private const val RATE = 16000

/** Collects 16-bit little-endian samples and hands them over in half-second chunks. */
private class PcmPacker(private val sink: (ByteArray, Int) -> Unit) {
    private val buf = ByteArray(RATE) // 0.5 s
    private var len = 0

    fun add(s: Int) {
        buf[len++] = s.toByte()
        buf[len++] = (s shr 8).toByte()
        if (len >= buf.size) {
            sink(buf, len)
            len = 0
        }
    }

    fun flush() {
        if (len > 0) {
            sink(buf, len)
            len = 0
        }
    }
}

/** Streaming resampler to 16 kHz: averages when going down, holds the sample when going up. */
private class Resampler(inRate: Int, private val out: PcmPacker) {
    private val ratio = inRate.toDouble() / RATE
    private var idx = 0.0
    private var next = if (ratio <= 1.0) 0.0 else ratio
    private var sum = 0.0
    private var n = 0

    fun push(s: Int) {
        if (ratio <= 1.0) {
            val end = idx + 1.0
            while (next < end) {
                out.add(s)
                next += ratio
            }
            idx += 1.0
        } else {
            sum += s
            n++
            idx += 1.0
            if (idx >= next) {
                out.add((sum / n).toInt().coerceIn(-32768, 32767))
                sum = 0.0
                n = 0
                next += ratio
            }
        }
    }
}

object Decoder {
    /** Returns the media duration in seconds. onPcm receives (bytes, byteCount). */
    fun decode(
        c: Context,
        uri: Uri,
        onPcm: (ByteArray, Int) -> Unit,
        onProgress: (Int) -> Unit,
        cancelled: () -> Boolean,
    ): Int {
        val ex = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            ex.setDataSource(c, uri, null)
            var track = -1
            var found: MediaFormat? = null
            for (i in 0 until ex.trackCount) {
                val f = ex.getTrackFormat(i)
                if (f.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) {
                    track = i
                    found = f
                    break
                }
            }
            val format: MediaFormat = found ?: throw UserError("No audio was found in this file.")
            ex.selectTrack(track)

            val mime = format.getString(MediaFormat.KEY_MIME)!!
            val durationUs = if (format.containsKey(MediaFormat.KEY_DURATION)) format.getLong(MediaFormat.KEY_DURATION) else 0L
            var inRate = if (format.containsKey(MediaFormat.KEY_SAMPLE_RATE)) format.getInteger(MediaFormat.KEY_SAMPLE_RATE) else 44100
            var channels = if (format.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) format.getInteger(MediaFormat.KEY_CHANNEL_COUNT) else 1
            if (channels < 1) channels = 1

            val dec = MediaCodec.createDecoderByType(mime)
            codec = dec
            dec.configure(format, null, null, 0)
            dec.start()

            val packer = PcmPacker(onPcm)
            var resampler = Resampler(inRate, packer)
            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false
            var lastUs = 0L
            var lastPct = -1

            while (!outputDone) {
                if (cancelled()) throw CancellationException("Cancelled")

                if (!inputDone) {
                    val i = dec.dequeueInputBuffer(10_000)
                    if (i >= 0) {
                        val buf = dec.getInputBuffer(i)!!
                        val size = ex.readSampleData(buf, 0)
                        if (size < 0) {
                            dec.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            dec.queueInputBuffer(i, 0, size, ex.sampleTime, 0)
                            ex.advance()
                        }
                    }
                }

                val o = dec.dequeueOutputBuffer(info, 10_000)
                if (o >= 0) {
                    if (info.size > 0) {
                        val ob = dec.getOutputBuffer(o)!!
                        ob.position(info.offset)
                        ob.limit(info.offset + info.size)
                        val sb = ob.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                        val frames = sb.remaining() / channels
                        for (f in 0 until frames) {
                            var sum = 0
                            for (ch in 0 until channels) sum += sb.get().toInt()
                            resampler.push(sum / channels)
                        }
                        lastUs = info.presentationTimeUs
                    }
                    dec.releaseOutputBuffer(o, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                    if (durationUs > 0) {
                        val pct = (lastUs * 100 / durationUs).toInt().coerceIn(0, 99)
                        if (pct != lastPct) {
                            lastPct = pct
                            onProgress(pct)
                        }
                    }
                } else if (o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val nf = dec.outputFormat
                    if (nf.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                        channels = maxOf(1, nf.getInteger(MediaFormat.KEY_CHANNEL_COUNT))
                    }
                    if (nf.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                        val r = nf.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        if (r != inRate) {
                            inRate = r
                            resampler = Resampler(r, packer)
                        }
                    }
                }
            }
            packer.flush()
            return (lastUs / 1_000_000L).toInt()
        } catch (e: UserError) {
            throw e
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw UserError("Couldn't read this file. Try an MP3, M4A, WAV, MP4 or similar.")
        } finally {
            try { codec?.stop() } catch (_: Exception) {}
            try { codec?.release() } catch (_: Exception) {}
            ex.release()
        }
    }
}

// ───────────────────────── Speech recognition ─────────────────────────

class TResult(val text: String, val durationSec: Int, val words: Int)

object Engine {
    fun transcribe(
        c: Context,
        uri: Uri,
        lang: Lang,
        onProgress: (Int) -> Unit,
        cancelled: () -> Boolean,
    ): TResult {
        LibVosk.setLogLevel(LogLevel.WARNINGS)
        val out = StringBuilder()
        var words = 0
        val model = Model(Models.path(c, lang).absolutePath)
        try {
            val rec = Recognizer(model, RATE.toFloat())
            try {
                rec.setWords(true)
                val dur = Decoder.decode(
                    c, uri,
                    { bytes, n ->
                        if (rec.acceptWaveForm(bytes, n)) {
                            words += addLine(rec.result, out)
                        }
                    },
                    onProgress, cancelled,
                )
                words += addLine(rec.finalResult, out)
                return TResult(out.toString().trim(), dur, words)
            } finally {
                rec.close()
            }
        } finally {
            model.close()
        }
    }

    /** Appends "[mm:ss] Sentence" and returns the number of words added. */
    private fun addLine(json: String, out: StringBuilder): Int {
        val o = JSONObject(json)
        val text = o.optString("text").trim()
        if (text.isEmpty()) return 0
        var start = 0.0
        val arr = o.optJSONArray("result")
        if (arr != null && arr.length() > 0) start = arr.getJSONObject(0).optDouble("start", 0.0)
        val count = text.split(" ").count { it.isNotBlank() }
        out.append('[').append(mmss(start.toInt())).append("] ")
            .append(text.replaceFirstChar { it.uppercase() }).append('\n')
        return count
    }
}

// ───────────────────────── Background job (survives screen changes) ─────────────────────────

sealed interface JobState {
    data object Idle : JobState
    data class Downloading(val pct: Int, val lang: String) : JobState
    data class Working(val pct: Int, val title: String) : JobState // pct -1 = unknown
    data class Failed(val message: String) : JobState
    data class Done(val id: Long) : JobState
}

object TJob {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val _state = MutableStateFlow<JobState>(JobState.Idle)
    val state: StateFlow<JobState> = _state
    private var job: Job? = null

    val busy: Boolean
        get() = _state.value is JobState.Downloading || _state.value is JobState.Working

    /** Clears Done/Failed. Does nothing while a job is running. */
    fun reset() {
        if (!busy) _state.value = JobState.Idle
    }

    fun cancel() {
        job?.cancel()
        _state.value = JobState.Idle
    }

    fun start(ctx: Context, uri: Uri, title: String, source: String, langCode: String, audioPath: String = "") {
        if (busy) return
        val app = ctx.applicationContext
        val acct = Store.acctId(app)
        val lang = Models.lang(langCode)
        _state.value = JobState.Working(-1, title)

        job = scope.launch {
            try {
                val res = withContext(Dispatchers.IO) {
                    val cancelled = { !isActive }
                    if (!Models.installed(app, lang)) {
                        _state.value = JobState.Downloading(0, lang.label)
                        Models.download(app, lang, { p -> _state.value = JobState.Downloading(p, lang.label) }, cancelled)
                    }
                    _state.value = JobState.Working(-1, title)
                    Engine.transcribe(app, uri, lang, { p -> _state.value = JobState.Working(p, title) }, cancelled)
                }
                if (res.words == 0) {
                    if (audioPath.isNotBlank()) File(audioPath).delete()
                    _state.value = JobState.Failed("No speech was found in this recording. Check the language and that the audio is clear.")
                    return@launch
                }
                val id = System.currentTimeMillis()
                TStore.add(
                    app, acct,
                    Transcript(id, title, source, lang.code, id, res.durationSec, res.words, audioPath),
                    res.text,
                )
                _state.value = JobState.Done(id)
                notifyDone(app, title, id)
            } catch (e: CancellationException) {
                if (audioPath.isNotBlank()) File(audioPath).delete()
                _state.value = JobState.Idle
                throw e
            } catch (e: Throwable) {
                _state.value = JobState.Failed(friendly(e))
            }
        }
    }

    private fun friendly(e: Throwable): String = when (e) {
        is UserError -> e.message ?: "Something went wrong."
        is UnknownHostException, is ConnectException, is SocketTimeoutException ->
            "Couldn't download the language pack. Check your internet connection and try again."
        is UnsatisfiedLinkError, is NoClassDefFoundError -> "The speech engine couldn't start on this phone."
        is OutOfMemoryError -> "Not enough memory to transcribe this file."
        else -> e.message ?: "Something went wrong."
    }

    private fun notifyDone(c: Context, title: String, id: Long) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(c, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        val code = (id % 100_000L).toInt() + 300_000
        val open = PendingIntent.getActivity(
            c, code,
            Intent(c, MainActivity::class.java).putExtra("transcript", id)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val n = NotificationCompat.Builder(c, CH_REMIND)
            .setSmallIcon(R.drawable.ic_stat_bell)
            .setContentTitle("Transcript ready")
            .setContentText(title)
            .setAutoCancel(true)
            .setContentIntent(open)
            .build()
        try {
            NotificationManagerCompat.from(c).notify(code, n)
        } catch (_: SecurityException) {
        }
    }
}

// ───────────────────────── Voice recorder ─────────────────────────

class VoiceRecorder(private val ctx: Context) {
    private var rec: MediaRecorder? = null
    private var file: File? = null

    fun start(): Boolean {
        val f = File(ctx.filesDir, "rec_${System.currentTimeMillis()}.m4a")
        val r = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(ctx) else @Suppress("DEPRECATION") MediaRecorder()
        return try {
            r.setAudioSource(MediaRecorder.AudioSource.MIC)
            r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            r.setAudioChannels(1)
            r.setAudioSamplingRate(16000)
            r.setAudioEncodingBitRate(64_000)
            r.setOutputFile(f.absolutePath)
            r.prepare()
            r.start()
            rec = r
            file = f
            true
        } catch (e: Exception) {
            try { r.release() } catch (_: Exception) {}
            f.delete()
            false
        }
    }

    /** Stops and returns the recording, or null if it was too short to keep. */
    fun stop(): File? {
        val r = rec ?: return null
        rec = null
        val f = file
        return try {
            r.stop()
            f
        } catch (e: RuntimeException) {
            f?.delete()
            null
        } finally {
            try { r.release() } catch (_: Exception) {}
        }
    }

    /** Stops and deletes. Safe to call when nothing is recording. */
    fun discard() {
        val r = rec ?: return
        rec = null
        try { r.stop() } catch (_: RuntimeException) {}
        try { r.release() } catch (_: Exception) {}
        file?.delete()
    }
}
