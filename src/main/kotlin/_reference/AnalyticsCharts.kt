package com.example.analytics

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.models.WardrobeItem
import com.example.ui.theme.GoldLight
import com.example.ui.theme.premiumCard
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * AnalyticsCharts.kt — StyleDrop Analytics Layer (Agent #19)
 * Pure-Compose Canvas charts (no charting dependency). Colors from ui/theme/Color.kt,
 * chrome from Modifier.premiumCard(). State is hoisted; every composable is stateless.
 */

private val GOLD = Color(0xFFB08A4F)
private val DANGER = Color(0xFFB5533C)
private val LINE = Color(0xFFE7E0D2)
private val MUTED = Color(0xFF837B6C)
private val RAMP = listOf(Color(0xFFF1ECE1), Color(0xFFD8C79E), Color(0xFFC3A96E), GOLD, Color(0xFF8F6B33))

// ---------------------------------------------------------------------------
// 10.1 Radar — Style DNA
// ---------------------------------------------------------------------------
@Composable
fun StyleDnaRadar(
    dna: AnalyticsEngine.StyleDna,
    modifier: Modifier = Modifier,
    rings: Int = 4,
) {
    val values = listOf(dna.styleDominance, dna.seasonBalance, dna.occasionSpread, dna.formality)
    val labels = listOf("Style", "Season", "Occasion", "Formality")
    Column(modifier = modifier.premiumCard(RoundedCornerShape(20.dp)).padding(16.dp)) {
        Text("Style DNA", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(12.dp))
        Canvas(Modifier.fillMaxWidth().height(220.dp)) {
            val cx = size.width / 2f; val cy = size.height / 2f
            val r = minOf(cx, cy) - 28.dp.toPx()
            repeat(rings) { i ->
                val rr = r * (i + 1) / rings
                val p = Path()
                for (a in 0..360 step 90) {
                    val rad = (a - 90) * PI / 180
                    val x = cx + rr * cos(rad).toFloat(); val y = cy + rr * sin(rad).toFloat()
                    if (a == 0) p.moveTo(x, y) else p.lineTo(x, y)
                }
                p.close(); drawPath(p, LINE, style = Stroke(1.dp.toPx()))
            }
            val shape = Path()
            values.forEachIndexed { i, v ->
                val rad = (i * 90 - 90) * PI / 180
                val x = cx + r * v.toFloat() * cos(rad).toFloat()
                val y = cy + r * v.toFloat() * sin(rad).toFloat()
                if (i == 0) shape.moveTo(x, y) else shape.lineTo(x, y)
            }
            shape.close()
            drawPath(shape, GOLD.copy(alpha = 0.18f))
            drawPath(shape, GOLD, style = Stroke(2.dp.toPx()))
        }
        Text(labels.joinToString("  ·  "), style = MaterialTheme.typography.labelLarge, color = MUTED)
    }
}

// ---------------------------------------------------------------------------
// 10.2 Cost-per-wear leaderboard
// ---------------------------------------------------------------------------
@Composable
fun CpwLeaderboard(report: AnalyticsEngine.CpwReport, modifier: Modifier = Modifier) {
    Column(modifier = modifier.premiumCard(RoundedCornerShape(20.dp)).padding(16.dp)) {
        Text("Cost per wear", style = MaterialTheme.typography.titleLarge)
        Text("Portfolio ${report.portfolioCpw} /wear · dead money ${report.deadMoney}",
            style = MaterialTheme.typography.bodyMedium, color = MUTED)
        Spacer(Modifier.height(12.dp))
        CpwSet("Best value", report.bestValue, GOLD)
        Spacer(Modifier.height(10.dp))
        CpwSet("Worst value", report.worstValue, DANGER)
    }
}

@Composable
private fun CpwSet(title: String, rows: List<AnalyticsEngine.CpwRow>, color: Color) {
    val maxCpw = (rows.maxOfOrNull { it.cpw ?: 0.0 } ?: 1.0).coerceAtLeast(1.0)
    Column {
        Text(title, style = MaterialTheme.typography.labelLarge, color = MUTED)
        rows.forEach { r ->
            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(r.type.take(20), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                Box(Modifier.weight(1f).height(8.dp).clip(RoundedCornerShape(4.dp)).background(color.copy(alpha = 0.25f))) {
                    Box(Modifier.fillMaxHeight().fillMaxWidth(((r.cpw ?: 0.0) / maxCpw).toFloat()).background(color))
                }
                Text(" ${r.cpw}", style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

// ---------------------------------------------------------------------------
// 10.3 Color donut
// ---------------------------------------------------------------------------
@Composable
fun ColorDistributionDonut(
    dist: AnalyticsEngine.ColorDistribution,
    modifier: Modifier = Modifier,
    holeRatio: Float = 0.62f,
) {
    Column(modifier = modifier.premiumCard(RoundedCornerShape(20.dp)).padding(16.dp)) {
        Text("Colour distribution", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(12.dp))
        Box(Modifier.fillMaxWidth().height(180.dp), contentAlignment = Alignment.Center) {
            Canvas(Modifier.size(180.dp)) {
                val sw = size.minDimension * (1 - holeRatio) / 2
                var start = -90f
                dist.slices.forEach { s ->
                    val sweep = 360f * s.pct.toFloat() / 100f
                    drawArc(Color(android.graphics.Color.parseColor(s.hex)), start, sweep - 2f, false,
                        topLeft = Offset(sw / 2, sw / 2), size = Size(size.width - sw, size.height - sw),
                        style = Stroke(sw))
                    start += sweep
                }
            }
            Text("${dist.distinctColors}", style = MaterialTheme.typography.displayMedium)
        }
        Text("neutral ${(dist.neutralShare * 100).toInt()}%  ·  chromatic ${(dist.chromaticShare * 100).toInt()}%",
            style = MaterialTheme.typography.bodyMedium, color = MUTED)
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(dist.slices) { s ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(12.dp).clip(RoundedCornerShape(3.dp))
                        .background(Color(android.graphics.Color.parseColor(s.hex))))
                    Spacer(Modifier.width(4.dp))
                    Text("${s.color} ${s.pct}%", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// 10.4 Closet Health gauge
// ---------------------------------------------------------------------------
@Composable
fun ClosetHealthGauge(health: AnalyticsEngine.ClosetHealth, modifier: Modifier = Modifier) {
    val arcColor = when (health.grade) {
        "Excellent", "Healthy" -> GOLD
        "Fair" -> Color(0xFFC98A3C)
        else -> DANGER
    }
    Column(modifier = modifier.premiumCard(RoundedCornerShape(20.dp)).padding(16.dp)) {
        Text("Closet Health", style = MaterialTheme.typography.titleLarge)
        Box(Modifier.fillMaxWidth().height(160.dp), contentAlignment = Alignment.Center) {
            Canvas(Modifier.size(160.dp)) {
                val sw = 14.dp.toPx()
                drawArc(LINE, 135f, 270f, false,
                    topLeft = Offset(sw / 2, sw / 2), size = Size(size.width - sw, size.height - sw),
                    style = Stroke(sw))
                drawArc(arcColor, 135f, 270f * health.score / 100f, false,
                    topLeft = Offset(sw / 2, sw / 2), size = Size(size.width - sw, size.height - sw),
                    style = Stroke(sw))
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("${health.score}", style = MaterialTheme.typography.displayMedium)
                Text(health.grade, style = MaterialTheme.typography.labelLarge, color = MUTED)
            }
        }
        HealthBar("Utilization", health.utilization, 0.45)
        HealthBar("Rotation", health.rotation, 0.35)
        HealthBar("Balance", health.balance, 0.20)
    }
}

@Composable
private fun HealthBar(label: String, value: Double, weight: Double) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.width(88.dp), style = MaterialTheme.typography.bodyMedium)
        LinearProgressIndicator(progress = { (value / 100).toFloat() }, modifier = Modifier.weight(1f))
        Text(" ${value.toInt()} (${weight})", style = MaterialTheme.typography.bodySmall, color = MUTED)
    }
}

// ---------------------------------------------------------------------------
// 10.5 Wear calendar heatmap
// ---------------------------------------------------------------------------
@Composable
fun WearCalendarHeatmap(
    grid: List<List<AnalyticsEngine.HeatDay>>,
    modifier: Modifier = Modifier,
    cell: Dp = 12.dp,
    onDayClick: ((AnalyticsEngine.HeatDay) -> Unit)? = null,
) {
    Column(modifier = modifier.premiumCard(RoundedCornerShape(20.dp)).padding(16.dp)) {
        Text("Wear calendar", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(12.dp))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            items(grid) { week ->
                Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    week.forEach { day ->
                        val cd = "${day.date}: ${day.count} worn"
                        Box(Modifier.size(cell).clip(RoundedCornerShape(3.dp))
                            .background(RAMP[day.level])
                            .semantics { contentDescription = cd }
                            .then(if (onDayClick != null) Modifier.clickable { onDayClick(day) } else Modifier))
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// 10.6 Seasonal coverage board
// ---------------------------------------------------------------------------
@Composable
fun SeasonalCoverageBoard(coverage: Map<String, AnalyticsEngine.SeasonCoverage>, modifier: Modifier = Modifier) {
    Column(modifier = modifier) {
        Text("Seasonal coverage", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(8.dp))
        coverage.forEach { (season, c) ->
            Row(Modifier.fillMaxWidth().premiumCard(RoundedCornerShape(12.dp)).padding(12.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Text(season, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                Text("${c.count} · ${(c.share * 100).toInt()}%", style = MaterialTheme.typography.bodyMedium, color = MUTED)
                Spacer(Modifier.width(8.dp))
                Text(if (c.covered) "OK" else "GAP",
                    style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
                    color = if (c.covered) GOLD else DANGER)
            }
            Spacer(Modifier.height(6.dp))
        }
    }
}

// ---------------------------------------------------------------------------
// 10.7 Unused items panel
// ---------------------------------------------------------------------------
@Composable
fun UnusedItemsPanel(
    report: AnalyticsEngine.UnusedReport,
    modifier: Modifier = Modifier,
    onItemClick: (WardrobeItem) -> Unit,
) {
    Column(modifier = modifier.premiumCard(RoundedCornerShape(20.dp)).padding(16.dp)) {
        Text("Unused items", style = MaterialTheme.typography.titleLarge)
        Text("${report.neverWornCount} never worn (${report.neverWornPct}%) · dormant value ${report.dormantValue}",
            style = MaterialTheme.typography.bodyMedium, color = MUTED)
        Spacer(Modifier.height(8.dp))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(report.neverWorn) { it ->
                Column(Modifier.width(96.dp).clickable { onItemClick(it) }) {
                    // Reuse Coil AsyncImage exactly as CategoryGrid does today:
                    // AsyncImage(model = it.imageUrl, contentDescription = it.type,
                    //            modifier = Modifier.size(96.dp).clip(RoundedCornerShape(8.dp)))
                    Text(it.type.take(14), style = MaterialTheme.typography.bodySmall, color = MUTED)
                }
            }
        }
    }
}
