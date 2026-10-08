package com.startup.app

import android.Manifest
import android.app.AlarmManager
import android.app.PendingIntent
import android.app.TimePickerDialog
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.random.Random

// ───────────────────────── Design tokens ─────────────────────────

val Yellow = Color(0xFFF2FF1A)
val Sky = Color(0xFF7FA8CF)
val Sand = Color(0xFFE6C9A2)
val Ink = Color(0xFF2B2B2B)
val Grey = Color(0xFF8A8A8A)

/** Reference UI uses a rounded geometric grotesk. Drop e.g. Satoshi/Outfit into res/font and swap this line. */
val AppFont: FontFamily = FontFamily.SansSerif

val R28 = RoundedCornerShape(28.dp)

fun txt(size: Int, w: FontWeight = FontWeight.Medium, c: Color = Color.White) =
    TextStyle(fontFamily = AppFont, fontSize = size.sp, fontWeight = w, color = c)

fun hm(t: Long): String = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(t))

fun nextLabel(t: Long): String {
    val a = Calendar.getInstance()
    val b = Calendar.getInstance().apply { timeInMillis = t }
    val same = a.get(Calendar.YEAR) == b.get(Calendar.YEAR) && a.get(Calendar.DAY_OF_YEAR) == b.get(Calendar.DAY_OF_YEAR)
    val day = if (same) "Today" else SimpleDateFormat("EEE", Locale.getDefault()).format(Date(t))
    return "Next: $day, ${SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date(t))}"
}

fun mmss(s: Int): String =
    if (s >= 3600) "%d:%02d:%02d".format(s / 3600, (s % 3600) / 60, s % 60) else "%02d:%02d".format(s / 60, s % 60)

// ───────────────────────── Reusable pieces ─────────────────────────

/** Bouncy press-scale + haptic tick on every tap. */
fun Modifier.bounceClick(onClick: () -> Unit): Modifier = composed {
    val haptic = LocalHapticFeedback.current
    val src = remember { MutableInteractionSource() }
    val pressed by src.collectIsPressedAsState()
    val scale by animateFloatAsState(
        if (pressed) 0.88f else 1f,
        spring(Spring.DampingRatioMediumBouncy, Spring.StiffnessMedium), label = "bounce",
    )
    this
        .graphicsLayer { scaleX = scale; scaleY = scale }
        .clickable(src, null) {
            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
            onClick()
        }
}

fun Modifier.glass(shape: Shape = R28, a: Float = 0.14f): Modifier =
    this.clip(shape).background(Color.White.copy(alpha = a)).border(1.dp, Color.White.copy(alpha = 0.25f), shape)

@Composable
fun Avatar(name: String, size: Dp, modifier: Modifier = Modifier) {
    val palette = listOf(0xFFE8A598, 0xFF98B4E8, 0xFFE8D498, 0xFF9AD6B3, 0xFFC3A5E8).map { Color(it) }
    Box(
        modifier.size(size).clip(CircleShape)
            .background(palette[(name.hashCode() and 0x7fffffff) % palette.size])
            .border(2.dp, Color.White, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Text(name.first().uppercase(), style = txt((size.value / 2.4f).toInt(), FontWeight.SemiBold, Ink))
    }
}

@Composable
fun PillButton(label: String, filled: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val shape = RoundedCornerShape(50)
    Box(
        modifier.height(56.dp).clip(shape)
            .then(if (filled) Modifier.background(Color.White) else Modifier.border(1.5.dp, Color.White.copy(0.9f), shape))
            .bounceClick(onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, style = txt(16, FontWeight.SemiBold, if (filled) Ink else Color.White))
    }
}

@Composable
fun CircleBtn(modifier: Modifier = Modifier, size: Dp = 52.dp, a: Float = 0.22f, onClick: () -> Unit, content: @Composable () -> Unit) {
    Box(
        modifier.size(size).clip(CircleShape).background(Color.White.copy(a)).bounceClick(onClick),
        contentAlignment = Alignment.Center,
    ) { content() }
}

// ───────────────────────── Navigation root ─────────────────────────

sealed interface Screen {
    data object Onboard : Screen
    data object Home : Screen
    data class Meet(val id: Int) : Screen
}

@Composable
fun StartupRoot(openId: Int?) {
    val ctx = LocalContext.current
    var meetings by remember { mutableStateOf(Store.meetings(ctx)) }
    var members by remember { mutableStateOf(Store.members(ctx)) }
    var screen by remember {
        mutableStateOf<Screen>(
            when {
                openId != null -> Screen.Meet(openId)
                Store.onboarded(ctx) -> Screen.Home
                else -> Screen.Onboard
            }
        )
    }

    fun reload() {
        meetings = Store.meetings(ctx)
        StartupWidget.refreshAll(ctx)
    }

    BackHandler(enabled = screen !is Screen.Home) { screen = Screen.Home }

    MaterialTheme(colorScheme = lightColorScheme(primary = Ink)) {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            AnimatedContent(screen, transitionSpec = { fadeIn(tween(250)) togetherWith fadeOut(tween(250)) }, label = "nav") { s ->
                when (s) {
                    Screen.Onboard -> Onboarding(
                        onStart = {
                            Store.setOnboarded(ctx)
                            val first = meetings.firstOrNull()
                            screen = if (first != null) Screen.Meet(first.id) else Screen.Home
                        },
                        onFinish = { Store.setOnboarded(ctx); screen = Screen.Home },
                    )
                    Screen.Home -> HomeScreen(
                        meetings = meetings,
                        members = members,
                        onOpen = { screen = Screen.Meet(it) },
                        onAddMeeting = { m ->
                            Store.save(ctx, meetings + m)
                            Reminders.schedule(ctx, m)
                            reload()
                        },
                        onAddMember = { n ->
                            members = members + n
                            Store.saveMembers(ctx, members)
                        },
                    )
                    is Screen.Meet -> {
                        val m = meetings.firstOrNull { it.id == s.id } ?: meetings.firstOrNull()
                        if (m != null) {
                            MeetingScreen(m, onClose = { screen = Screen.Home })
                        } else {
                            LaunchedEffect(Unit) { screen = Screen.Home }
                        }
                    }
                }
            }
        }
    }
}

// ───────────────────────── Onboarding ─────────────────────────

@Composable
fun Onboarding(onStart: () -> Unit, onFinish: () -> Unit) {
    val pages = listOf(
        Triple("Plan your day", "Arrange meetings and tasks in one place.\nGet reminded before every start.", Sky to Sand),
        Triple("Never miss a beat", "Notifications and alarms keep your\nwork routine right on track.", Color(0xFFE9A58A) to Color(0xFFF5E3CF)),
        Triple("Join meetings", "Host meetings instantly or join with a simple code.\nStay connected anytime, anywhere.", Color(0xFFD3D3D1) to Color(0xFFB9A6D6)),
    )
    var i by remember { mutableIntStateOf(0) }
    Box(Modifier.fillMaxSize().background(Color(0xFFFFF4E8)).systemBarsPadding().padding(16.dp)) {
        Crossfade(i, label = "page") { idx ->
            val (t, d, cols) = pages[idx]
            Box(Modifier.fillMaxSize().clip(R28).background(Brush.verticalGradient(listOf(cols.first, cols.second)))) {
                Box(
                    Modifier.align(Alignment.Center).offset(y = (-90).dp).size(190.dp)
                        .clip(CircleShape).background(Color.White.copy(0.3f)),
                    contentAlignment = Alignment.Center,
                ) { Text("👋", fontSize = 88.sp) }
                Column(Modifier.align(Alignment.BottomStart).padding(24.dp)) {
                    Text(t, style = txt(44, FontWeight.Medium))
                    Spacer(Modifier.height(8.dp))
                    Text(d, style = txt(15, FontWeight.Normal, Color.White.copy(0.85f)))
                    Spacer(Modifier.height(24.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        PillButton("Start Meeting", true, Modifier.weight(1f), onStart)
                        PillButton(if (idx == pages.lastIndex) "Get started" else "Next", false, Modifier.weight(1f)) {
                            if (idx == pages.lastIndex) onFinish() else i++
                        }
                    }
                }
            }
        }
    }
}

// ───────────────────────── Home ─────────────────────────

fun requestPin(ctx: Context) {
    val mgr = AppWidgetManager.getInstance(ctx)
    if (mgr.isRequestPinAppWidgetSupported) {
        val cb = PendingIntent.getBroadcast(
            ctx, 7, Intent(ctx, PinResultReceiver::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
        )
        mgr.requestPinAppWidget(ComponentName(ctx, StartupWidget::class.java), null, cb)
    } else {
        Toast.makeText(ctx, "Long-press your home screen → Widgets → Startup", Toast.LENGTH_LONG).show()
    }
}

@Composable
fun HomeScreen(
    meetings: List<Meeting>,
    members: List<String>,
    onOpen: (Int) -> Unit,
    onAddMeeting: (Meeting) -> Unit,
    onAddMember: (String) -> Unit,
) {
    val ctx = LocalContext.current
    var searching by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var menu by remember { mutableStateOf(false) }
    var showNew by remember { mutableStateOf(false) }
    var showMember by remember { mutableStateOf(false) }
    var showJoin by remember { mutableStateOf(false) }

    fun exactCheck() {
        if (Build.VERSION.SDK_INT >= 31) {
            val am = ctx.getSystemService(AlarmManager::class.java)
            if (!am.canScheduleExactAlarms()) {
                ctx.startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:${ctx.packageName}")))
            }
        }
    }
    val notifLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { exactCheck() }
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ctx.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS) else exactCheck()
    }

    val now = System.currentTimeMillis()
    val upcoming = meetings.filter { it.time >= now }.ifEmpty { meetings }
    val shown = meetings.filter {
        query.isBlank() || it.title.contains(query, true) || it.host.contains(query, true)
    }

    val bounce = remember { Animatable(1f) }
    LaunchedEffect(Unit) {
        PinEvents.flow.collect {
            bounce.snapTo(0.55f)
            bounce.animateTo(1f, spring(Spring.DampingRatioHighBouncy, Spring.StiffnessLow))
        }
    }

    Box(
        Modifier.fillMaxSize().background(
            Brush.verticalGradient(listOf(Color(0xFF1F1B1A), Color(0xFF4A3832), Color(0xFFB5604A), Color(0xFFD08A73)))
        )
    ) {
        Column(Modifier.fillMaxSize().systemBarsPadding().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
            // header
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Box {
                    Avatar("Caleb May", 44.dp)
                    Box(
                        Modifier.align(Alignment.TopEnd).size(18.dp).clip(CircleShape).background(Yellow),
                        contentAlignment = Alignment.Center,
                    ) { Text("3", style = txt(10, FontWeight.SemiBold, Ink)) }
                }
                Spacer(Modifier.width(10.dp))
                if (searching) {
                    TextField(
                        value = query, onValueChange = { query = it }, singleLine = true,
                        placeholder = { Text("Search meetings", style = txt(15, FontWeight.Normal, Color.White.copy(.6f))) },
                        textStyle = txt(16),
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent,
                            focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent,
                            cursorColor = Color.White,
                        ),
                        modifier = Modifier.weight(1f),
                    )
                } else {
                    Text("Caleb May", style = txt(20), modifier = Modifier.weight(1f))
                }
                CircleBtn(size = 46.dp, onClick = { searching = !searching; if (!searching) query = "" }) {
                    Icon(if (searching) Icons.Filled.Close else Icons.Filled.Search, null, tint = Color.White)
                }
            }

            // members
            Spacer(Modifier.height(18.dp))
            LazyRow(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                item {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(
                            Modifier.size(64.dp).clip(CircleShape).background(Color.White).bounceClick { showMember = true },
                            contentAlignment = Alignment.Center,
                        ) { Icon(Icons.Filled.Add, null, tint = Ink, modifier = Modifier.size(30.dp)) }
                        Spacer(Modifier.height(6.dp))
                        Text("Add member", style = txt(12, FontWeight.Normal, Color.White.copy(.85f)))
                    }
                }
                items(members) { n ->
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Avatar(n, 64.dp)
                        Spacer(Modifier.height(6.dp))
                        Text(n, style = txt(12, FontWeight.Normal, Color.White.copy(.85f)))
                    }
                }
            }

            // glass cards
            Spacer(Modifier.height(18.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                upcoming.take(2).forEach { m ->
                    Column(
                        Modifier.weight(1f).height(150.dp).glass().bounceClick { onOpen(m.id) }.padding(16.dp),
                    ) {
                        Text(m.title, style = txt(17), maxLines = 2)
                        Spacer(Modifier.height(6.dp))
                        Text(nextLabel(m.time), style = txt(11, FontWeight.Normal, Color.White.copy(.75f)))
                        Spacer(Modifier.weight(1f))
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Avatar(m.host, 36.dp)
                            Spacer(Modifier.weight(1f))
                            Box(Modifier.size(36.dp).clip(CircleShape).background(Color.White.copy(.18f)), contentAlignment = Alignment.Center) {
                                Text("${m.people}", style = txt(12, FontWeight.Normal, Color.White.copy(.85f)))
                            }
                        }
                    }
                }
                if (upcoming.size < 2) Spacer(Modifier.weight(1f))
            }

            // white list card
            Spacer(Modifier.height(16.dp))
            Column(Modifier.fillMaxWidth().clip(R28).background(Color.White).padding(20.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Daily Standup", style = txt(20, FontWeight.Medium, Ink), modifier = Modifier.weight(1f))
                    Box {
                        Box(
                            Modifier.size(34.dp).clip(CircleShape).border(1.dp, Ink.copy(.5f), CircleShape).bounceClick { menu = true },
                            contentAlignment = Alignment.Center,
                        ) { Icon(Icons.Filled.MoreVert, null, tint = Ink, modifier = Modifier.size(18.dp)) }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(text = { Text("Join with code or link") }, onClick = { menu = false; showJoin = true })
                            DropdownMenuItem(text = { Text("New meeting") }, onClick = { menu = false; showNew = true })
                            DropdownMenuItem(text = { Text("Add widget to home screen") }, onClick = { menu = false; requestPin(ctx) })
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                shown.forEachIndexed { idx, m ->
                    if (idx > 0) HorizontalDivider(color = Color(0xFFEDEDED))
                    Row(
                        Modifier.fillMaxWidth().clickable { onOpen(m.id) }.padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Avatar(m.host, 52.dp)
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f)) {
                            Text(m.title, style = txt(17, FontWeight.Medium, Ink))
                            Text(m.host, style = txt(14, FontWeight.Normal, Grey))
                        }
                        if (m.link.isNotBlank()) {
                            Box(
                                Modifier.clip(RoundedCornerShape(50)).background(Yellow)
                                    .bounceClick { Join.open(ctx, m.link) }
                                    .padding(horizontal = 12.dp, vertical = 5.dp),
                            ) { Text("Join", style = txt(12, FontWeight.SemiBold, Ink)) }
                            Spacer(Modifier.width(8.dp))
                        }
                        Text(hm(m.time), style = txt(13, FontWeight.Normal, Grey))
                    }
                }
                if (shown.isEmpty()) Text("No meetings found", style = txt(14, FontWeight.Normal, Grey), modifier = Modifier.padding(vertical = 16.dp))
            }

            // widget card
            Spacer(Modifier.height(16.dp))
            Row(Modifier.fillMaxWidth().glass().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                WidgetPreview(upcoming.firstOrNull(), Modifier.graphicsLayer { scaleX = bounce.value; scaleY = bounce.value })
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f)) {
                    Text("Home screen widget", style = txt(16))
                    Text("2×2 · 28dp corners", style = txt(12, FontWeight.Normal, Color.White.copy(.75f)))
                    Spacer(Modifier.height(10.dp))
                    PillButton("Add to home screen", true, Modifier.fillMaxWidth().height(44.dp)) { requestPin(ctx) }
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }

    if (showNew) NewMeetingDialog({ showNew = false }) { onAddMeeting(it); showNew = false }
    if (showMember) AddMemberDialog({ showMember = false }) { onAddMember(it); showMember = false }
    if (showJoin) JoinDialog { showJoin = false }
}

@Composable
fun WidgetPreview(m: Meeting?, modifier: Modifier = Modifier) {
    Box(modifier.size(110.dp).clip(RoundedCornerShape(28.dp))) {
        Image(painterResource(R.drawable.meeting_bg), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.45f to Color.Transparent, 1f to Color(0xE6000000))))
        Text(
            m?.title ?: "Team Standup", style = txt(9, FontWeight.Normal, Ink), maxLines = 1,
            modifier = Modifier.padding(10.dp).clip(RoundedCornerShape(12.dp)).background(Color.White.copy(.8f))
                .padding(horizontal = 8.dp, vertical = 3.dp),
        )
        Column(Modifier.align(Alignment.BottomStart).padding(10.dp)) {
            Text("${m?.let { hm(it.time) } ?: "09:30"} | ${m?.team ?: "Product Team"}", style = txt(8, FontWeight.Normal, Color.White.copy(.85f)), maxLines = 1)
            Text("Meeting", style = txt(20, FontWeight.SemiBold))
        }
        Box(
            Modifier.align(Alignment.BottomEnd).padding(10.dp).size(28.dp).clip(CircleShape).background(Yellow),
            contentAlignment = Alignment.Center,
        ) { Box(Modifier.size(9.dp).clip(RoundedCornerShape(2.dp)).background(Ink)) }
    }
}

// ───────────────────────── Meeting screen ─────────────────────────

data class Sticker(val id: Int, val kind: String, val x: Float, val y: Float)

@Composable
fun StickerFace(kind: String, small: Boolean = false) {
    when (kind) {
        "HOT!" -> StickerLabel(kind, Color(0xFFE5173F), Color(0xFFFFC21A), small)
        "THANKS" -> StickerLabel(kind, Color(0xFFFF8A1F), Color.White, small)
        "OK!" -> StickerLabel(kind, Color(0xFF14B8A6), Color(0xFFFFD233), small)
        else -> Text(kind, fontSize = if (small) 28.sp else 56.sp)
    }
}

@Composable
fun StickerLabel(text: String, fg: Color, bg: Color, small: Boolean) {
    val shape = RoundedCornerShape(18.dp)
    Box(
        Modifier.clip(shape).background(bg).border(3.dp, Color.White, shape)
            .padding(horizontal = if (small) 8.dp else 14.dp, vertical = if (small) 4.dp else 8.dp),
    ) { Text(text, style = txt(if (small) 13 else 22, FontWeight.ExtraBold, fg)) }
}

@Composable
fun StickerView(kind: String, modifier: Modifier) {
    val scale = remember { Animatable(0f) }
    LaunchedEffect(Unit) { scale.animateTo(1f, spring(Spring.DampingRatioHighBouncy, Spring.StiffnessLow)) }
    Box(modifier.graphicsLayer { scaleX = scale.value; scaleY = scale.value; rotationZ = -8f }) { StickerFace(kind) }
}

@Composable
fun MeetingScreen(m: Meeting, onClose: () -> Unit) {
    val haptic = LocalHapticFeedback.current
    val ctx = LocalContext.current
    var secs by remember { mutableIntStateOf(0) }
    var tray by remember { mutableStateOf(false) }
    val stickers = remember { mutableStateListOf<Sticker>() }
    LaunchedEffect(Unit) { while (true) { delay(1000); secs++ } }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val w = constraints.maxWidth
        val h = constraints.maxHeight
        Image(painterResource(R.drawable.meeting_bg), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.55f to Color.Transparent, 1f to Color(0x55000000))))

        stickers.forEach { s ->
            key(s.id) { StickerView(s.kind, Modifier.offset { IntOffset((s.x * w).toInt(), (s.y * h).toInt()) }) }
        }

        Column(Modifier.fillMaxSize().systemBarsPadding().padding(horizontal = 16.dp)) {
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                CircleBtn(size = 48.dp, onClick = onClose) { Icon(Icons.Filled.Close, null, tint = Color.White) }
                Text(m.title, style = txt(22), textAlign = TextAlign.Center, modifier = Modifier.weight(1f))
                CircleBtn(size = 48.dp, onClick = { tray = !tray }) { Icon(Icons.Filled.MoreVert, null, tint = Color.White) }
            }

            Spacer(Modifier.weight(1f))

            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(hm(m.time), style = txt(34, FontWeight.Light))
                    Spacer(Modifier.width(12.dp))
                    Box(Modifier.width(1.dp).height(34.dp).background(Color.White.copy(.5f)))
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text("${m.team} ·", style = txt(11, FontWeight.Normal, Color.White.copy(.85f)))
                        Text(m.kind, style = txt(11, FontWeight.Normal, Color.White.copy(.7f)))
                    }
                }
                Text("Meeting", style = txt(60, FontWeight.SemiBold))
                Text(mmss(secs), style = txt(14, FontWeight.Normal, Color.White.copy(.9f)))
            }

            if (m.link.isNotBlank()) {
                PillButton(
                    "Join ${Platform.detect(m.link)?.label ?: "meeting"}", true,
                    Modifier.align(Alignment.CenterHorizontally).padding(top = 14.dp).width(240.dp).height(48.dp),
                ) { Join.open(ctx, m.link) }
            }

            AnimatedVisibility(tray, enter = fadeIn(), exit = fadeOut()) {
                Row(
                    Modifier.fillMaxWidth().padding(top = 16.dp).glass(RoundedCornerShape(24.dp), 0.25f).padding(12.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically,
                ) {
                    listOf("🔥", "🐸", "🤪", "HOT!", "THANKS", "OK!").forEach { k ->
                        Box(Modifier.bounceClick {
                            stickers.add(Sticker(Random.nextInt(), k, Random.nextFloat() * 0.55f + 0.08f, Random.nextFloat() * 0.45f + 0.15f))
                        }) { StickerFace(k, small = true) }
                    }
                }
            }

            Row(
                Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 16.dp),
                horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically,
            ) {
                CircleBtn(onClick = onClose) { Icon(Icons.Filled.ChevronLeft, null, tint = Color.White, modifier = Modifier.size(28.dp)) }

                Box(
                    Modifier.size(84.dp).bounceClick {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        onClose()
                    },
                    contentAlignment = Alignment.Center,
                ) {
                    val sweep = (secs % 60) / 60f * 360f
                    Canvas(Modifier.fillMaxSize()) {
                        drawCircle(Color.White.copy(.35f), style = Stroke(3.dp.toPx()))
                        drawArc(
                            Yellow, -90f, sweep, false,
                            topLeft = Offset.Zero, size = size,
                            style = Stroke(4.dp.toPx(), cap = StrokeCap.Round),
                        )
                    }
                    Box(Modifier.size(42.dp).clip(RoundedCornerShape(12.dp)).background(Yellow))
                }

                CircleBtn(onClick = { tray = !tray }) { Icon(Icons.Filled.AutoAwesome, null, tint = Color.White) }
            }
        }
    }
}

// ───────────────────────── Dialogs ─────────────────────────

@Composable
fun NewMeetingDialog(onDismiss: () -> Unit, onSave: (Meeting) -> Unit) {
    val ctx = LocalContext.current
    var title by remember { mutableStateOf("") }
    var host by remember { mutableStateOf("Caleb May") }
    var hour by remember { mutableIntStateOf(9) }
    var minute by remember { mutableIntStateOf(30) }
    var remind by remember { mutableIntStateOf(10) }
    var alarm by remember { mutableStateOf(true) }
    var link by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New meeting") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(title, { title = it }, label = { Text("Title") }, singleLine = true)
                OutlinedTextField(host, { host = it }, label = { Text("Host") }, singleLine = true)
                OutlinedTextField(
                    link, { link = it }, singleLine = true,
                    label = { Text("Meet / Teams / Zoom link (optional)") },
                )
                OutlinedButton(onClick = {
                    TimePickerDialog(ctx, { _, h, mi -> hour = h; minute = mi }, hour, minute, true).show()
                }) { Text("Time  %02d:%02d".format(hour, minute)) }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(5, 10, 30).forEach {
                        FilterChip(selected = remind == it, onClick = { remind = it }, label = { Text("$it min") })
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Loud alarm", Modifier.weight(1f))
                    Switch(alarm, { alarm = it })
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                if (title.isNotBlank()) {
                    val cal = Calendar.getInstance().apply {
                        set(Calendar.HOUR_OF_DAY, hour); set(Calendar.MINUTE, minute)
                        set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
                        if (timeInMillis < System.currentTimeMillis()) add(Calendar.DAY_OF_YEAR, 1)
                    }
                    onSave(
                        Meeting(
                            (System.currentTimeMillis() % 1_000_000_000L).toInt(), title.trim(), "Product Team",
                            host.ifBlank { "Me" }, cal.timeInMillis, remind, alarm, 1,
                            link = link.trim().let { if (it.isNotEmpty() && !it.startsWith("http", true)) "https://$it" else it },
                        )
                    )
                }
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
fun JoinDialog(onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    var platform by remember { mutableStateOf(Platform.MEET) }
    var input by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Join with code or link") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Platform.entries.forEach { p ->
                        FilterChip(selected = platform == p, onClick = { platform = p }, label = { Text(p.short) })
                    }
                }
                OutlinedTextField(
                    input, { input = it }, singleLine = true,
                    label = { Text(if (platform == Platform.TEAMS) "Meeting link" else "Code, ID or link") },
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val link = Platform.toLink(platform, input)
                if (link == null) {
                    Toast.makeText(ctx, "Enter a valid code, ID or full link", Toast.LENGTH_LONG).show()
                } else {
                    Join.open(ctx, link)
                    onDismiss()
                }
            }) { Text("Join") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
fun AddMemberDialog(onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var name by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add member") },
        text = { OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true) },
        confirmButton = { TextButton(onClick = { if (name.isNotBlank()) onSave(name.trim()) }) { Text("Add") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
