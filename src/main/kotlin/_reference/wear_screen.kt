package com.example.ui.wear

/*
 * StyleDrop v2 — Wear History screen + "The Wear Loom" heatmap  (Agent #17)
 *
 * UI layer. Uses ONLY the repo's existing theme (ui/theme/Color.kt, Type.kt, Modifiers.kt):
 *   BackgroundLight #FAF7F0 ivory · SurfaceLight #FFFFFF · InkLight #1B1A17 · InkSoftLight #4A463E
 *   MutedTextLight #837B6C · LineLight #E7E0D2 · GoldLight #B08A4F · SurfaceAltLight #F1ECE1
 * Adds the green / basting-red wear palette (values below — paste into Color.kt).
 *
 * The heatmap is deliberately NOT a GitHub grid: it is a WOVEN FABRIC LOOM — vertical warp
 * threads per weekday, a horizontal weft thread per week, and a hand-stitch at every crossing
 * whose weight = wear count. Green = healthy wear; basting red = at-risk / rescued.
 */

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import com.example.models.WardrobeItem
import com.example.ui.theme.*
import com.example.ui.theme.premiumCard
import com.example.wear.*
import java.text.SimpleDateFormat
import java.util.Locale
import kotlin.math.min

// ---------------------------------------------------------------------------------------------
// Wear palette — ADD THESE TO ui/theme/Color.kt  (green ramp + existing basting red) ----------
// ---------------------------------------------------------------------------------------------
val WearMist   = Color(0xFFE7EDE2)   // level 1 — a single stitch
val WearSage   = Color(0xFFB9CBA8)   // level 2
val WearFern   = Color(0xFF7E9B64)   // level 3
val WearForest = Color(0xFF46703F)   // level 4 — heavy wear
val BastingRed = Color(0xFFC8442C)   // the alarm / "rescued" accent
val BastingRedSoft = Color(0xFFF3DAD3)

private val levelColors = listOf(WearMist, WearSage, WearFern, WearForest)

// ---------------------------------------------------------------------------------------------
// Screen ---------------------------------------------------------------------------------------
// ---------------------------------------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WearHistoryScreen(
    wardrobeItems: List<WardrobeItem>,
    viewModel: WearViewModel,
    onBack: () -> Unit = {}
) {
    val snapshot by viewModel.snapshot.collectAsState()
    LaunchedEffect(wardrobeItems) { viewModel.bindWardrobe(wardrobeItems) }
    val snap = snapshot ?: return

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("How you wear it", style = MaterialTheme.typography.displayMedium) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.Rounded.History, contentDescription = "Back") }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    titleContentColor = MaterialTheme.colorScheme.onBackground
                )
            )
        }
    ) { pad ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(pad)
                .padding(horizontal = 20.dp)
                .verticalScroll(rememberScrollState())
        ) {
            Spacer(Modifier.height(8.dp))

            // ---- headline stats: avg CPW · total value · weekly wear % ----------------------
            WearStatStrip(snap.stats)
            Spacer(Modifier.height(20.dp))

            // ---- the Wear Loom --------------------------------------------------------------
            Text("The Wear Loom", style = MaterialTheme.typography.headlineMedium)
            Spacer(Modifier.height(4.dp))
            Text(
                "Every worn day is a stitch. Bare warp = unworn.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(12.dp))
            WearLoom(
                cells = snap.loom,
                windowWeeks = snap.windowWeeks,
                onCellClick = { cell -> viewModel.undoDay(cell.dayKey) }
            )
            Spacer(Modifier.height(10.dp))
            WearLoomLegend()
            Spacer(Modifier.height(24.dp))

            // ---- sustainability badge -------------------------------------------------------
            SustainabilityCard(snap.badge)
            Spacer(Modifier.height(24.dp))

            // ---- underused alarm ------------------------------------------------------------
            UnderusedAlarmBlock(
                underused = snap.underused,
                onWear = { item -> viewModel.wearToday(listOf(item.id), source = WearSource.QUICK_LOG) }
            )
            Spacer(Modifier.height(24.dp))

            // ---- achievements ---------------------------------------------------------------
            Text("Benchmarks", style = MaterialTheme.typography.headlineMedium)
            Spacer(Modifier.height(12.dp))
            LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                items(snap.achievements) { a -> AchievementChip(a) }
            }
            Spacer(Modifier.height(40.dp))
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Stats strip (FEATURE 8) ---------------------------------------------------------------------
// ---------------------------------------------------------------------------------------------

@Composable
fun WearStatStrip(stats: WearStats) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        StatTile("Avg cost / wear", stats.averageCostPerWear?.let { "\u20B1%.2f".format(it) } ?: "\u2014",
            "${stats.pricedPieces} priced pieces", Modifier.weight(1f))
        StatTile("Wardrobe value", "\u20B1%,.0f".format(stats.totalWardrobeValue),
            "${stats.totalPieces} pieces", Modifier.weight(1f))
        StatTile("Worn this week", "%.0f%%".format(stats.weeklyWearPercentage),
            "${stats.streakDays}-day streak", Modifier.weight(1f))
    }
    Spacer(Modifier.height(12.dp))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        StatTile("Pieces worn", "${stats.distinctPiecesWornEver}",
            "of ${stats.totalPieces} owned", Modifier.weight(1f))
        StatTile("Most worn", stats.mostWorn?.let { "${it.timesWorn}\u00D7" } ?: "\u2014",
            stats.mostWorn?.type ?: "nothing yet", Modifier.weight(1f))
        StatTile("Wears / 30d", "${stats.wearsLast30d}",
            "${stats.totalWearsLogged} all-time", Modifier.weight(1f))
    }
}

@Composable
private fun StatTile(label: String, value: String, sub: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .premiumCard(RoundedCornerShape(16.dp))
            .padding(horizontal = 14.dp, vertical = 12.dp)
    ) {
        Text(label.uppercase(), style = MaterialTheme.typography.bodySmall, color = MutedTextLight)
        Spacer(Modifier.height(4.dp))
        Text(value, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurface)
        Text(sub, style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
    }
}

// ---------------------------------------------------------------------------------------------
// THE WEAR LOOM — unique woven heatmap (FEATURE 4) --------------------------------------------
// ---------------------------------------------------------------------------------------------

/**
 * Not a GitHub square grid. One column per weekday (Mon…Sun); each week is a weft row drawn as a
 * hairline thread with a brass bobbin end-cap; every (day, week) crossing carries a hand-stitch
 * whose stroke weight and colour encode the wear count. A `rescued` day gets a basting-red knot.
 * Tap a cell to undo that day's log.
 */
@Composable
fun WearLoom(
    cells: List<LoomCell>,
    windowWeeks: Int,
    onCellClick: (LoomCell) -> Unit = {}
) {
    val cellW = 44.dp
    val cellH = 26.dp
    val gutter = 6.dp
    val leftPad = 34.dp

    Column {
        Row(
            modifier = Modifier.padding(start = leftPad).width(cellW * 7 + gutter * 6),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            listOf("M", "T", "W", "T", "F", "S", "S").forEach {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MutedTextLight)
            }
        }
        Spacer(Modifier.height(6.dp))
        Box(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
            Canvas(
                modifier = Modifier
                    .width(leftPad + cellW * 7 + gutter * 6 + 8.dp)
                    .height(cellH * windowWeeks + 8.dp)
                    .pointerInput(windowWeeks) {
                        detectTapGestures { offset ->
                            val left = leftPad.toPx()
                            val top = 4.dp.toPx()
                            val cw = cellW.toPx()
                            val ch = cellH.toPx()
                            val g = gutter.toPx()
                            val d = ((offset.x - left) / (cw + g)).toInt()
                            val w = ((offset.y - top) / ch).toInt()
                            if (d in 0..6 && w in 0 until windowWeeks) {
                                cells.firstOrNull { it.dayIndex == d && it.weekIndex == w }
                                    ?.takeIf { !it.future }
                                    ?.let(onCellClick)
                            }
                        }
                    }
            ) {
                val left = leftPad.toPx()
                val top = 4.dp.toPx()
                val cw = cellW.toPx()
                val ch = cellH.toPx()
                val g = gutter.toPx()

                // 1. WARP — one faint vertical thread per weekday, full height
                for (d in 0 until 7) {
                    val x = left + d * (cw + g) + cw / 2f
                    drawLine(LineLight, Offset(x, top), Offset(x, top + ch * windowWeeks), strokeWidth = 1f)
                }
                // 2. WEFT — a hairline thread per week, with a brass bobbin end-cap
                for (w in 0 until windowWeeks) {
                    val y = top + w * ch + ch / 2f
                    drawLine(
                        GoldLight.copy(alpha = 0.30f),
                        Offset(left - 24.dp.toPx(), y),
                        Offset(left + 7 * (cw + g), y),
                        strokeWidth = 1f
                    )
                    drawCircle(
                        GoldLight.copy(alpha = 0.55f), 2.dp.toPx(),
                        Offset(left - 24.dp.toPx(), y)
                    )
                }

                // 3. THE STITCHES
                for (c in cells) {
                    val cx = left + c.dayIndex * (cw + g) + cw / 2f
                    val cy = top + c.weekIndex * ch + ch / 2f
                    when {
                        c.future -> Unit                                   // empty warp only
                        c.preTracking -> drawCircle(LineLight, 1.6.dp.toPx(), Offset(cx, cy))
                        c.level == 0 -> drawLine(                          // bare crossing
                            LineLight, Offset(cx - 4.dp.toPx(), cy), Offset(cx + 4.dp.toPx(), cy),
                            strokeWidth = 1f, cap = StrokeCap.Round
                        )
                        else -> {
                            val color = levelColors[min(c.level, 4) - 1]
                            val thick = (1.6f + c.level * 1.1f).dp.toPx()
                            val half = (cw * 0.30f) * (0.7f + c.level * 0.075f)
                            drawLine(                                  // over-stitch
                                color, Offset(cx - half, cy + 3.dp.toPx()), Offset(cx + half, cy - 3.dp.toPx()),
                                strokeWidth = thick, cap = StrokeCap.Round
                            )
                            if (c.level >= 2) drawLine(                // under-stitch
                                color.copy(alpha = 0.85f),
                                Offset(cx - half * 0.7f, cy - 3.dp.toPx()), Offset(cx + half * 0.7f, cy + 3.dp.toPx()),
                                strokeWidth = thick * 0.8f, cap = StrokeCap.Round
                            )
                            if (c.level >= 4) drawRect(                // heavy-wear cloth patch
                                WearForest.copy(alpha = 0.16f),
                                Offset(cx - half, cy - 6.dp.toPx()),
                                Size(half * 2, 12.dp.toPx())
                            )
                        }
                    }
                    if (c.rescued) drawCircle(                         // basting-red rescue knot
                        BastingRed, 2.6.dp.toPx(),
                        Offset(cx + cw * 0.34f, cy - ch * 0.30f)
                    )
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(weekLabel(cells.firstOrNull()?.dayKey), style = MaterialTheme.typography.bodySmall, color = MutedTextLight)
            Text("today", style = MaterialTheme.typography.bodySmall, color = MutedTextLight)
        }
    }
}

private fun weekLabel(dayKey: String?): String {
    if (dayKey == null) return ""
    return runCatching {
        val d = SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(dayKey)!!
        SimpleDateFormat("d MMM", Locale.US).format(d)
    }.getOrDefault("")
}

@Composable
fun WearLoomLegend() {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("less", style = MaterialTheme.typography.bodySmall, color = MutedTextLight)
        levelColors.forEach { c ->
            Box(Modifier.size(14.dp).clip(RoundedCornerShape(4.dp)).background(c))
        }
        Text("more", style = MaterialTheme.typography.bodySmall, color = MutedTextLight)
        Spacer(Modifier.width(8.dp))
        Box(Modifier.size(8.dp).clip(RoundedCornerShape(4.dp)).background(BastingRed))
        Text("rescued sleeper", style = MaterialTheme.typography.bodySmall, color = MutedTextLight)
    }
}

// ---------------------------------------------------------------------------------------------
// FEATURE 6 — sustainability card --------------------------------------------------------------
// ---------------------------------------------------------------------------------------------

@Composable
fun SustainabilityCard(badge: SustainabilityBadge) {
    val tint = when (badge.level) {
        4, 3 -> WearForest
        2 -> GoldLight
        else -> BastingRed
    }
    Column(
        modifier = Modifier.fillMaxWidth().premiumCard(RoundedCornerShape(20.dp)).padding(18.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("\uD83C\uDF3F", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text("Wardrobe efficiency", style = MaterialTheme.typography.bodySmall, color = MutedTextLight)
                Text(badge.label, style = MaterialTheme.typography.titleLarge, color = tint)
            }
            badge.cpw?.let {
                Text("\u20B1%.2f".format(it), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
                Spacer(Modifier.width(4.dp))
                Text("/ wear", style = MaterialTheme.typography.bodySmall, color = MutedTextLight)
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(badge.tip, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(10.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            (0..4).forEach { i ->
                Box(
                    Modifier.weight(1f).height(6.dp).clip(RoundedCornerShape(3.dp))
                        .background(if (i < badge.level) tint else LineLight)
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------
// FEATURE 5 — underused alarm ------------------------------------------------------------------
// ---------------------------------------------------------------------------------------------

@Composable
fun UnderusedAlarmBlock(underused: List<UnderusedItem>, onWear: (WardrobeItem) -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(BastingRedSoft.copy(alpha = 0.45f))
            .border(1.dp, BastingRed.copy(alpha = 0.35f), RoundedCornerShape(20.dp))
            .padding(18.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.WarningAmber, contentDescription = null, tint = BastingRed)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text("Sleepers on the rail", style = MaterialTheme.typography.titleLarge, color = BastingRed)
                Text(
                    if (underused.isEmpty()) "Nothing dormant \u2014 the whole rail is in rotation."
                    else "${underused.size} piece(s) with no wear in ${WearTracker.UNDERUSE_DAYS}+ days.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        if (underused.isNotEmpty()) {
            Spacer(Modifier.height(14.dp))
            underused.take(5).forEach { u ->
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(com.example.models.ItemCategory.emoji(u.item.category), style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(u.item.type, style = MaterialTheme.typography.titleMedium, maxLines = 1)
                        Text(
                            if (u.neverWorn) "Never worn \u00B7 added ${WearClock.dayLabel(u.item.createdAt)}"
                            else "Last worn ${u.daysSinceLastWear} days ago",
                            style = MaterialTheme.typography.bodySmall,
                            color = MutedTextLight
                        )
                    }
                    TextButton(onClick = { onWear(u.item) }) {
                        Icon(Icons.Rounded.Check, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Wear today")
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------
// FEATURE 7 — achievement chips ----------------------------------------------------------------
// ---------------------------------------------------------------------------------------------

@Composable
fun AchievementChip(a: Achievement) {
    val tint = if (a.unlocked) WearForest else MutedTextLight
    Column(
        modifier = Modifier.width(168.dp).premiumCard(RoundedCornerShape(18.dp)).padding(14.dp)
    ) {
        Text(if (a.unlocked) "\u2726" else "\u2727", style = MaterialTheme.typography.titleLarge, color = tint)
        Spacer(Modifier.height(6.dp))
        Text(a.title, style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface, maxLines = 2)
        Spacer(Modifier.height(4.dp))
        Text(a.progressLabel, style = MaterialTheme.typography.bodySmall, color = MutedTextLight)
        Spacer(Modifier.height(8.dp))
        LinearProgressIndicator(
            progress = { a.progress },
            modifier = Modifier.fillMaxWidth().height(5.dp).clip(RoundedCornerShape(3.dp)),
            color = tint,
            trackColor = LineLight,
            drawStopIndicator = {}
        )
    }
}

// ---------------------------------------------------------------------------------------------
// FEATURE 3 — "Wear this" CTA -------------------------------------------------------------------
// ---------------------------------------------------------------------------------------------

/**
 * Drop-in CTA for the AI result card (ui/AiGeneratorScreen.kt) and for each saved outfit
 * (ui/SavedOutfitsScreen.kt). Calls WearRepository.markWorn -> increments timesWorn,
 * sets lastWorn, writes the WearEvent rows.
 */
@Composable
fun WearThisButton(
    itemIds: List<String>,
    viewModel: WearViewModel,
    outfitId: String? = null,
    source: String = WearSource.AI_RESULT,
    modifier: Modifier = Modifier
) {
    var logged by remember { mutableStateOf(false) }
    val enabled = itemIds.isNotEmpty()
    Button(
        onClick = {
            viewModel.wearToday(itemIds, outfitId, source)
            logged = true
        },
        enabled = enabled && !logged,
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = if (logged) WearForest else MaterialTheme.colorScheme.primary,
            contentColor = Color.White
        )
    ) {
        Icon(Icons.Rounded.Check, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text(
            text = when {
                !enabled -> "Nothing to log"
                logged -> "Logged for today \u2713"
                else -> "Wear this today"
            },
            style = MaterialTheme.typography.labelLarge
        )
    }
}

// ---------------------------------------------------------------------------------------------
// Shared inline rows for Home / Analytics / Item detail ---------------------------------------
// ---------------------------------------------------------------------------------------------

@Composable
fun CostPerWearRow(item: WardrobeItem) {
    val cpw = WearTracker.costPerWear(item.price, item.timesWorn)
    val badge = WearTracker.sustainabilityBadge(item.price, item.timesWorn)
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(item.type, style = MaterialTheme.typography.bodyLarge, maxLines = 1)
            Text("${item.timesWorn} wears \u00B7 ${badge.label}",
                style = MaterialTheme.typography.bodySmall, color = MutedTextLight)
        }
        Text(
            cpw?.let { "\u20B1%.2f/wear".format(it) } ?: "no price",
            style = MaterialTheme.typography.titleMedium,
            color = when {
                badge.level >= 3 -> WearForest
                badge.level == 0 -> BastingRed
                else -> MaterialTheme.colorScheme.onSurface
            }
        )
    }
    LinearProgressIndicator(
        progress = { (badge.level / 4f).coerceIn(0f, 1f) },
        modifier = Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)),
        color = when {
            badge.level >= 3 -> WearForest
            badge.level == 0 -> BastingRed
            else -> WearSage
        },
        trackColor = LineLight,
        drawStopIndicator = {}
    )
}

@Composable
fun WearHistoryHeader(weeks: Int, onWeeksChange: (Int) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        listOf(
            WearTracker.DEFAULT_WINDOW_WEEKS to "12 weeks",
            WearTracker.EXTENDED_WINDOW_WEEKS to "26 weeks"
        ).forEach { (w, label) ->
            FilterChip(
                selected = weeks == w,
                onClick = { onWeeksChange(w) },
                label = { Text(label) },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = WearMist,
                    selectedLabelColor = WearForest
                )
            )
        }
    }
}
