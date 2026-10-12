package com.startup.app

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaPlayer
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val BgBrush = Brush.verticalGradient(
    listOf(Color(0xFF1F1B1A), Color(0xFF4A3832), Color(0xFFB5604A), Color(0xFFD08A73))
)

private fun whenLabel(t: Long): String =
    SimpleDateFormat("d MMM, HH:mm", Locale.getDefault()).format(Date(t))

/** Keeps the screen on while a transcript is being made, so the phone doesn't sleep mid-job. */
@Composable
fun KeepAwakeWhileBusy() {
    val job by TJob.state.collectAsState()
    val view = LocalView.current
    val busy = job is JobState.Downloading || job is JobState.Working
    DisposableEffect(busy) {
        view.keepScreenOn = busy
        onDispose { view.keepScreenOn = false }
    }
}

/** Short status line for the Home entry card. */
fun jobLine(job: JobState, count: Int): String = when (job) {
    is JobState.Downloading -> "Downloading language pack ${job.pct}%"
    is JobState.Working -> if (job.pct >= 0) "Transcribing ${job.pct}%" else "Transcribing…"
    is JobState.Done -> "Transcript ready"
    is JobState.Failed -> "Last transcript failed"
    JobState.Idle -> if (count == 0) "Turn recordings into text" else "$count saved"
}

// ───────────────────────── List screen ─────────────────────────

@Composable
fun TranscriptsScreen(onBack: () -> Unit, onOpen: (Long) -> Unit) {
    val ctx = LocalContext.current
    var list by remember { mutableStateOf(TStore.list(ctx)) }
    var langCode by remember { mutableStateOf(TStore.lang(ctx)) }
    val job by TJob.state.collectAsState()
    val initial = remember { TJob.state.value }
    var recording by remember { mutableStateOf(false) }
    var recSecs by remember { mutableIntStateOf(0) }
    val recorder = remember { VoiceRecorder(ctx) }
    val busy = job is JobState.Downloading || job is JobState.Working

    LaunchedEffect(Unit) { if (initial is JobState.Done) TJob.reset() }
    LaunchedEffect(job) {
        val j = job
        if (j is JobState.Done) {
            list = TStore.list(ctx)
            if (j !== initial) {
                TJob.reset()
                onOpen(j.id)
            }
        }
    }
    DisposableEffect(Unit) { onDispose { recorder.discard() } }
    LaunchedEffect(recording) {
        if (recording) {
            recSecs = 0
            while (true) {
                delay(1000)
                recSecs++
            }
        }
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) {
            try {
                ctx.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (_: Exception) {
            }
            TJob.start(ctx, uri, displayName(ctx, uri), "Imported", langCode)
        }
    }
    val micForGoogle = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) picker.launch(arrayOf("audio/*", "video/*"))
        else Toast.makeText(ctx, "The Google recognizer needs the microphone permission", Toast.LENGTH_LONG).show()
    }

    fun beginRecording() {
        if (recorder.start()) {
            recording = true
        } else {
            Toast.makeText(ctx, "Couldn't start the microphone", Toast.LENGTH_LONG).show()
        }
    }

    fun finishRecording() {
        recording = false
        val f = recorder.stop()
        if (f == null) {
            Toast.makeText(ctx, "Recording was too short", Toast.LENGTH_SHORT).show()
            return
        }
        val name = "Voice recording " + SimpleDateFormat("d MMM HH:mm", Locale.getDefault()).format(Date())
        TJob.start(ctx, Uri.fromFile(f), name, "Recorded", langCode, f.absolutePath)
    }

    val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) beginRecording()
        else Toast.makeText(ctx, "Microphone permission is needed to record", Toast.LENGTH_LONG).show()
    }

    val lang = Models.lang(langCode)
    var wsize by remember { mutableStateOf(Models.wsize(ctx).key) }
    val shown = remember { Models.langs.filter { Models.usable(ctx, it) } }

    Box(Modifier.fillMaxSize().background(BgBrush)) {
        Column(
            Modifier.fillMaxSize().systemBarsPadding().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
        ) {
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                CircleBtn(size = 46.dp, onClick = onBack) {
                    Icon(Icons.Filled.ChevronLeft, null, tint = Color.White, modifier = Modifier.size(28.dp))
                }
                Spacer(Modifier.width(12.dp))
                Text("Transcripts", style = txt(26), modifier = Modifier.weight(1f))
            }

            Spacer(Modifier.height(10.dp))
            Text(
                "Import a voice or video recording, or record now, and get every word as text. " +
                    "Most languages run fully on your phone, so your audio never leaves it.",
                style = txt(14, FontWeight.Normal, Color.White.copy(.8f)),
            )

            Spacer(Modifier.height(18.dp))
            Text("Language of the speech", style = txt(13, FontWeight.Medium, Color.White.copy(.8f)))
            Spacer(Modifier.height(8.dp))
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(shown) { l ->
                    val sel = l.code == langCode
                    Box(
                        Modifier.clip(RoundedCornerShape(50))
                            .background(if (sel) Color.White else Color.White.copy(.16f))
                            .bounceClick {
                                langCode = l.code
                                TStore.setLang(ctx, l.code)
                            }
                            .padding(horizontal = 14.dp, vertical = 8.dp),
                    ) { Text(l.label, style = txt(13, FontWeight.Medium, if (sel) Ink else Color.White)) }
                }
            }
            Spacer(Modifier.height(6.dp))
            if (lang.engine == "whisper") {
                Spacer(Modifier.height(10.dp))
                Text("Model size (bigger is more accurate but slower)", style = txt(12, FontWeight.Medium, Color.White.copy(.8f)))
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Models.wsizes.forEach { w ->
                        val sel = w.key == wsize
                        Box(
                            Modifier.clip(RoundedCornerShape(50))
                                .background(if (sel) Color.White else Color.White.copy(.16f))
                                .bounceClick {
                                    if (!busy) {
                                        wsize = w.key
                                        Models.setWsize(ctx, w.key)
                                    }
                                }
                                .padding(horizontal = 14.dp, vertical = 8.dp),
                        ) { Text("${w.label} · ${w.mb} MB", style = txt(12, FontWeight.Medium, if (sel) Ink else Color.White)) }
                    }
                }
            }
            Spacer(Modifier.height(6.dp))
            Text(
                when {
                    lang.engine == "google" ->
                        "Uses the Google speech recognizer built into your phone (Android 13+). Needs internet, " +
                            "so the audio may be processed by Google. Experimental: it depends on your phone."
                    lang.engine == "whisper" && Models.installed(ctx, lang) ->
                        "Whisper ${Models.wsize(ctx).label} is on your phone. Works offline, and is slower than other languages."
                    lang.engine == "whisper" ->
                        "${lang.label} uses Whisper: a one-time ${Models.wsize(ctx).mb} MB download, then works offline. " +
                            "Small is the most accurate."
                    Models.installed(ctx, lang) -> "${lang.label} pack is on your phone. Works offline."
                    else -> "${lang.label} needs a one-time ${lang.mb} MB download, then works offline."
                },
                style = txt(12, FontWeight.Normal, Color.White.copy(.65f)),
            )

            Spacer(Modifier.height(18.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                PillButton("Import file", true, Modifier.weight(1f)) {
                    if (busy || recording) {
                        Toast.makeText(ctx, "Finish the current task first", Toast.LENGTH_SHORT).show()
                    } else if (lang.engine == "google" &&
                        ctx.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED
                    ) {
                        micForGoogle.launch(Manifest.permission.RECORD_AUDIO)
                    } else {
                        picker.launch(arrayOf("audio/*", "video/*"))
                    }
                }
                PillButton("Record voice", false, Modifier.weight(1f)) {
                    if (busy || recording) {
                        Toast.makeText(ctx, "Finish the current task first", Toast.LENGTH_SHORT).show()
                    } else if (ctx.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                        beginRecording()
                    } else {
                        micPermission.launch(Manifest.permission.RECORD_AUDIO)
                    }
                }
            }

            // status cards
            if (recording) {
                Spacer(Modifier.height(16.dp))
                Column(Modifier.fillMaxWidth().clip(R28).background(Color.White).padding(20.dp)) {
                    Text("Recording…  ${mmss(recSecs)}", style = txt(20, FontWeight.Medium, Ink))
                    Spacer(Modifier.height(4.dp))
                    Text("Speak close to the microphone.", style = txt(13, FontWeight.Normal, Grey))
                    Spacer(Modifier.height(14.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            Modifier.weight(1f).height(52.dp).clip(RoundedCornerShape(50)).background(Yellow)
                                .bounceClick { finishRecording() },
                            contentAlignment = Alignment.Center,
                        ) { Text("Stop and transcribe", style = txt(15, FontWeight.SemiBold, Ink)) }
                        Spacer(Modifier.width(10.dp))
                        TextButton(onClick = {
                            recorder.discard()
                            recording = false
                        }) { Text("Discard", color = Grey) }
                    }
                }
            }

            when (val j = job) {
                is JobState.Downloading -> {
                    Spacer(Modifier.height(16.dp))
                    StatusCard(
                        title = "Downloading ${j.lang} language pack",
                        body = "${j.pct}%  ·  one-time download, keep the app open",
                        progress = j.pct / 100f,
                        action = "Cancel",
                        onAction = { TJob.cancel() },
                    )
                }
                is JobState.Working -> {
                    Spacer(Modifier.height(16.dp))
                    StatusCard(
                        title = "Transcribing…",
                        body = j.title + (if (j.pct >= 0) "  ·  ${j.pct}%" else "") +
                            "\nKeep the app open. Long recordings can take a while.",
                        progress = if (j.pct >= 0) j.pct / 100f else null,
                        action = "Cancel",
                        onAction = { TJob.cancel() },
                    )
                }
                is JobState.Failed -> {
                    Spacer(Modifier.height(16.dp))
                    StatusCard(
                        title = "Couldn't make the transcript",
                        body = j.message,
                        progress = null,
                        showBar = false,
                        action = "Dismiss",
                        onAction = { TJob.reset() },
                    )
                }
                else -> {}
            }

            // saved transcripts
            Spacer(Modifier.height(18.dp))
            Column(Modifier.fillMaxWidth().clip(R28).background(Color.White).padding(20.dp)) {
                Text("Saved transcripts", style = txt(20, FontWeight.Medium, Ink))
                Spacer(Modifier.height(6.dp))
                if (list.isEmpty()) {
                    Text(
                        "Nothing here yet. Import a recording or tap Record voice.",
                        style = txt(14, FontWeight.Normal, Grey),
                        modifier = Modifier.padding(vertical = 12.dp),
                    )
                }
                list.forEachIndexed { idx, t ->
                    if (idx > 0) HorizontalDivider(color = Color(0xFFEDEDED))
                    Row(
                        Modifier.fillMaxWidth().clickable { onOpen(t.id) }.padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            Modifier.size(46.dp).clip(CircleShape).background(Yellow),
                            contentAlignment = Alignment.Center,
                        ) { Icon(Icons.Filled.Mic, null, tint = Ink) }
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f)) {
                            Text(t.title, style = txt(16, FontWeight.Medium, Ink), maxLines = 1)
                            Text(
                                "${t.source} · ${mmss(t.durationSec)} · ${t.words} words · ${whenLabel(t.created)}",
                                style = txt(12, FontWeight.Normal, Grey), maxLines = 1,
                            )
                        }
                        Icon(Icons.Filled.ChevronRight, null, tint = Grey)
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun StatusCard(
    title: String,
    body: String,
    progress: Float?,
    action: String,
    onAction: () -> Unit,
    showBar: Boolean = true,
) {
    Column(Modifier.fillMaxWidth().clip(R28).background(Color.White).padding(20.dp)) {
        Text(title, style = txt(18, FontWeight.Medium, Ink))
        Spacer(Modifier.height(6.dp))
        Text(body, style = txt(13, FontWeight.Normal, Grey))
        if (showBar) {
            Spacer(Modifier.height(14.dp))
            if (progress != null) {
                LinearProgressIndicator(
                    progress = { progress.coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(50)),
                    color = Ink,
                    trackColor = Color(0xFFE6E6E6),
                )
            } else {
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(50)),
                    color = Ink,
                    trackColor = Color(0xFFE6E6E6),
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        TextButton(onClick = onAction) { Text(action, color = Ink) }
    }
}

// ───────────────────────── Detail screen ─────────────────────────

@Composable
fun TranscriptDetail(id: Long, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val t = remember(id) { TStore.list(ctx).firstOrNull { it.id == id } }
    val raw = remember(id) { if (t != null) TStore.text(ctx, t) else "" }
    var stamps by remember { mutableStateOf(true) }
    var confirmDelete by remember { mutableStateOf(false) }

    // playback of the in-app recording, so every line can be checked against the sound
    val audioFile = remember(id) { t?.audio?.takeIf { it.isNotBlank() }?.let { File(it) }?.takeIf { it.exists() } }
    var player by remember { mutableStateOf<MediaPlayer?>(null) }
    var playing by remember { mutableStateOf(false) }
    var posSec by remember { mutableIntStateOf(0) }
    DisposableEffect(id) { onDispose { try { player?.release() } catch (_: Exception) {} } }
    LaunchedEffect(playing) {
        while (playing) {
            posSec = (player?.currentPosition ?: 0) / 1000
            delay(250)
        }
    }
    fun ensurePlayer(): MediaPlayer? {
        player?.let { return it }
        val f = audioFile ?: return null
        return try {
            MediaPlayer().apply {
                setDataSource(f.absolutePath)
                prepare()
                setOnCompletionListener { playing = false; posSec = 0 }
            }.also { player = it }
        } catch (_: Exception) {
            Toast.makeText(ctx, "Couldn't play the recording", Toast.LENGTH_SHORT).show()
            null
        }
    }
    fun playFrom(sec: Int?) {
        val p = ensurePlayer() ?: return
        if (sec != null) { p.seekTo(sec * 1000); posSec = sec }
        p.start()
        playing = true
    }
    fun pause() {
        try { player?.pause() } catch (_: Exception) {}
        playing = false
    }
    fun toSec(stamp: String): Int = stamp.split(":").fold(0) { a, p -> a * 60 + (p.toIntOrNull() ?: 0) }
    val lines = remember(raw) {
        raw.lines().filter { it.isNotBlank() }.map { l ->
            val (st, tx) = TStore.parse(l)
            Triple(st?.let { toSec(it) }, st, tx)
        }
    }
    val activeIdx = lines.indexOfLast { (sec, _, _) -> sec != null && sec <= posSec }

    val saver = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri: Uri? ->
        if (uri != null && t != null) {
            try {
                ctx.contentResolver.openOutputStream(uri)?.use {
                    it.write(TStore.export(t, raw, stamps).toByteArray())
                }
                Toast.makeText(ctx, "Saved", Toast.LENGTH_SHORT).show()
            } catch (_: Exception) {
                Toast.makeText(ctx, "Couldn't save the file", Toast.LENGTH_LONG).show()
            }
        }
    }

    val body = remember(raw, stamps) {
        buildAnnotatedString {
            raw.lines().filter { it.isNotBlank() }.forEach { line ->
                val (stamp, text) = TStore.parse(line)
                if (stamp != null && stamps) {
                    withStyle(SpanStyle(color = Grey, fontSize = 12.sp, fontWeight = FontWeight.Medium)) {
                        append("[$stamp]  ")
                    }
                }
                append(text)
                append("\n\n")
            }
        }
    }

    if (t == null) {
        LaunchedEffect(Unit) { onBack() }
        return
    }

    Box(Modifier.fillMaxSize().background(BgBrush)) {
        Column(
            Modifier.fillMaxSize().systemBarsPadding().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
        ) {
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                CircleBtn(size = 46.dp, onClick = onBack) {
                    Icon(Icons.Filled.ChevronLeft, null, tint = Color.White, modifier = Modifier.size(28.dp))
                }
                Spacer(Modifier.width(12.dp))
                Text(t.title, style = txt(20), maxLines = 2, modifier = Modifier.weight(1f))
                CircleBtn(size = 46.dp, onClick = { confirmDelete = true }) {
                    Icon(Icons.Filled.Delete, null, tint = Color.White)
                }
            }

            Spacer(Modifier.height(10.dp))
            Text(
                "${t.source} · ${mmss(t.durationSec)} · ${t.words} words · ${Models.lang(t.lang).label} · ${whenLabel(t.created)}",
                style = txt(12, FontWeight.Normal, Color.White.copy(.75f)),
            )

            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PillButton("Copy", true, Modifier.weight(1f).height(46.dp)) {
                    val cm = ctx.getSystemService(ClipboardManager::class.java)
                    cm.setPrimaryClip(ClipData.newPlainText("Transcript", TStore.export(t, raw, stamps)))
                    Toast.makeText(ctx, "Copied", Toast.LENGTH_SHORT).show()
                }
                PillButton("Share", false, Modifier.weight(1f).height(46.dp)) {
                    val send = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_SUBJECT, t.title)
                        putExtra(Intent.EXTRA_TEXT, TStore.export(t, raw, stamps))
                    }
                    ctx.startActivity(Intent.createChooser(send, "Share transcript"))
                }
                PillButton("Save .txt", false, Modifier.weight(1f).height(46.dp)) {
                    val safe = t.title.replace(Regex("[^A-Za-z0-9 _-]"), "").trim().ifBlank { "transcript" }
                    saver.launch("$safe.txt")
                }
            }

            Spacer(Modifier.height(12.dp))
            Box(
                Modifier.clip(RoundedCornerShape(50))
                    .background(if (stamps) Color.White else Color.White.copy(.16f))
                    .bounceClick { stamps = !stamps }
                    .padding(horizontal = 14.dp, vertical = 8.dp),
            ) { Text("Timestamps", style = txt(13, FontWeight.Medium, if (stamps) Ink else Color.White)) }

            if (audioFile != null) {
                Spacer(Modifier.height(14.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    PillButton(if (playing) "Pause" else "Play recording", true, Modifier.height(46.dp)) {
                        if (playing) pause() else playFrom(null)
                    }
                    Spacer(Modifier.width(12.dp))
                    Text("${mmss(posSec)} / ${mmss(t.durationSec)}", style = txt(13, FontWeight.Medium, Color.White.copy(.85f)))
                }
            }

            Spacer(Modifier.height(14.dp))
            Column(Modifier.fillMaxWidth().clip(R28).background(Color.White).padding(20.dp)) {
                if (audioFile != null) {
                    Text("Tap a line to hear it.", style = txt(12, FontWeight.Normal, Grey))
                    Spacer(Modifier.height(8.dp))
                    lines.forEachIndexed { i, (sec, st, tx) ->
                        val on = playing && i == activeIdx
                        Row(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                                .background(if (on) Yellow.copy(alpha = .45f) else Color.Transparent)
                                .clickable(enabled = sec != null) { playFrom(sec) }
                                .padding(horizontal = 8.dp, vertical = 8.dp),
                        ) {
                            if (st != null && stamps) {
                                Text("[$st]", style = txt(12, FontWeight.Medium, Grey), modifier = Modifier.padding(top = 3.dp, end = 8.dp))
                            }
                            Text(tx, style = txt(16, FontWeight.Normal, Ink).copy(lineHeight = 24.sp), modifier = Modifier.weight(1f))
                        }
                    }
                } else {
                    SelectionContainer {
                        Text(body, style = txt(16, FontWeight.Normal, Ink).copy(lineHeight = 24.sp))
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            Text(
                "Speech recognition can miss words, so check anything important. " +
                    "Tip: for the best accuracy choose Whisper (Small), even for English.",
                style = txt(12, FontWeight.Normal, Color.White.copy(.65f)),
            )
            Spacer(Modifier.height(24.dp))
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete transcript?") },
            text = { Text("This removes the transcript and any recording saved with it.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    TStore.delete(ctx, t)
                    onBack()
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }
}
