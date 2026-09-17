package com.example.analytics

import com.example.models.ItemCategory
import com.example.models.WardrobeItem
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * AnalyticsEngine.kt — StyleDrop Analytics Layer (Agent #19)
 *
 * Kotlin mirror of analytics/analytics_engine.py. Pure functions over the app's
 * REAL types: [WardrobeItem] and [ItemCategory] from
 * app/src/main/java/com/example/models/WardrobeItem.kt.
 *
 * REQUIRED SCHEMA EXTENSION (declared, not assumed):
 *   - WardrobeItem.price: Double            (cost-per-wear)
 *   - WardrobeItem.purchaseDate: Long?      (aging)
 *   - new @Entity WearEvent(itemId, date, occasion)  (calendar + occasion radar)
 * Until those exist, pass price/purchaseDate as parallel maps (see overloads below).
 */

// ---------------------------------------------------------------------------
// 1. REAL-TIME INSIGHTS  (extends the existing total / category / top-color stats)
// ---------------------------------------------------------------------------

data class RealtimeInsights(
    val totalItems: Int,
    val totalWears: Int,
    val avgWearsPerItem: Double,
    val wornAtLeastOnce: Int,
    val neverWorn: Int,
    val favorites: Int,
    val byCategory: Map<String, Int>,
    val topColors: List<Pair<String, Int>>,
    val topStyles: List<Pair<String, Int>>,
    val bySeason: Map<String, Int>,
    val wearConcentrationTop20: Double,
)

object AnalyticsEngine {

    private const val MS_DAY = 86_400_000L
    fun msDays(ms: Long) = ms / MS_DAY

    fun realtimeInsights(items: List<WardrobeItem>): RealtimeInsights {
        val total = items.size
        val totalWears = items.sumOf { it.timesWorn }
        val worn = items.count { it.timesWorn > 0 }
        val ranked = items.sortedByDescending { it.timesWorn }
        val top20 = max(1, (total * 0.2).roundToInt())
        val conc = if (totalWears > 0)
            ranked.take(top20).sumOf { it.timesWorn }.toDouble() / totalWears else 0.0
        return RealtimeInsights(
            totalItems = total,
            totalWears = totalWears,
            avgWearsPerItem = if (total > 0) (totalWears.toDouble() / total * 100).roundToInt() / 100.0 else 0.0,
            wornAtLeastOnce = worn,
            neverWorn = total - worn,
            favorites = items.count { it.isFavoriteItem },
            byCategory = ItemCategory.all.associateWith { c -> items.count { it.category == c } },
            topColors = items.groupingBy { it.color }.eachCount().entries
                .sortedByDescending { it.value }.take(5).map { it.key to it.value },
            topStyles = items.groupingBy { it.style }.eachCount().entries
                .sortedByDescending { it.value }.take(5).map { it.key to it.value },
            bySeason = listOf("All Season", "Summer", "Winter", "Spring/Fall")
                .associateWith { s -> items.count { it.season == s } },
            wearConcentrationTop20 = conc,
        )
    }

    // -----------------------------------------------------------------------
    // 2. STYLE DNA (radar: occasion / style / season / formality)
    // -----------------------------------------------------------------------

    val STYLE_FORMALITY = mapOf(
        "Formal" to 0.95, "Old Money" to 0.85, "Smart Casual" to 0.65, "Minimalist" to 0.6,
        "Korean" to 0.5, "Casual" to 0.4, "Vintage" to 0.5, "Streetwear" to 0.35,
        "Y2K" to 0.35, "Sporty" to 0.2, "Grunge" to 0.3, "Techwear" to 0.4,
    )

    data class StyleDna(
        val styleDominance: Double,
        val seasonBalance: Double,
        val occasionSpread: Double,
        val formality: Double,
        val topStyle: String,
        val topOccasion: String,
        val occasionsUsed: Int,
        val occasionsTotal: Int = 11,
    )

    fun styleDna(items: List<WardrobeItem>, events: List<WearEvent>): StyleDna {
        val n = items.size
        val style = items.groupingBy { it.style }.eachCount()
        val season = items.groupingBy { it.season }.eachCount()
        val occ = events.groupingBy { it.occasion }.eachCount()
        val formality = if (events.isEmpty()) 0.0 else events.mapNotNull { e ->
            items.firstOrNull { it.id == e.itemId }?.let { STYLE_FORMALITY[it.style] ?: 0.4 }
        }.average()
        val topStyle = style.maxByOrNull { it.value }?.key ?: "-"
        val topSeason = season.maxByOrNull { it.value }?.key ?: "-"
        val topOcc = occ.maxByOrNull { it.value }?.key ?: "-"
        fun r2(x: Double) = (x * 1000).roundToInt() / 1000.0
        return StyleDna(
            styleDominance = if (n > 0) r2((style[topStyle] ?: 0).toDouble() / n) else 0.0,
            seasonBalance = if (n > 0) r2((season[topSeason] ?: 0).toDouble() / n) else 0.0,
            occasionSpread = if (occ.isNotEmpty()) r2(occ.size.toDouble() / 11) else 0.0,
            formality = r2(formality),
            topStyle = topStyle, topOccasion = topOcc,
            occasionsUsed = occ.size,
        )
    }

    // -----------------------------------------------------------------------
    // 3. COST PER WEAR
    // -----------------------------------------------------------------------

    data class CpwRow(val id: String, val type: String, val category: String,
                      val price: Double, val wears: Int, val cpw: Double?)
    data class CpwReport(val totalSpend: Double, val portfolioCpw: Double?,
                         val bestValue: List<CpwRow>, val worstValue: List<CpwRow>,
                         val deadMoney: Double)

    fun costPerWear(items: List<WardrobeItem>, price: (WardrobeItem) -> Double): CpwReport {
        val rows = items.map { it ->
            CpwRow(it.id, it.type, it.category, price(it), it.timesWorn,
                if (it.timesWorn > 0) (price(it) / it.timesWorn * 100).roundToInt() / 100.0 else null)
        }
        val worn = rows.filter { it.cpw != null }.sortedBy { it.cpw }
        val spend = rows.sumOf { it.price }
        val wears = items.sumOf { it.timesWorn }
        fun r2(x: Double) = (x * 100).roundToInt() / 100.0
        return CpwReport(
            totalSpend = r2(spend),
            portfolioCpw = if (wears > 0) r2(spend / wears) else null,
            bestValue = worn.take(5),
            worstValue = worn.takeLast(5).reversed(),
            deadMoney = r2(rows.filter { it.wears == 0 }.sumOf { it.price }),
        )
    }

    // -----------------------------------------------------------------------
    // 4. COLOR DISTRIBUTION (donut)
    // -----------------------------------------------------------------------

    data class ColorSlice(val color: String, val count: Int, val pct: Double, val hex: String)
    data class ColorDistribution(val slices: List<ColorSlice>, val neutralShare: Double,
                                 val chromaticShare: Double, val distinctColors: Int)

    val COLOR_HEX = mapOf(
        "Black" to "#1B1A17", "White" to "#FFFFFF", "Gray" to "#8A8A8A", "Beige" to "#D9C7A7",
        "Navy" to "#1B2A4A", "Brown" to "#6B4A2F", "Olive" to "#77754E", "Blue" to "#46688C",
        "Green" to "#4A6B4A", "Red" to "#B5533C", "Cream" to "#F3EEE2",
    )
    private val NEUTRALS = setOf("Black", "White", "Gray", "Beige", "Cream")

    fun colorDistribution(items: List<WardrobeItem>): ColorDistribution {
        val total = items.size
        val prim = items.groupingBy { it.color }.eachCount()
        val slices = prim.entries.sortedByDescending { it.value }.map {
            ColorSlice(it.key, it.value,
                if (total > 0) (1000.0 * it.value / total).roundToInt() / 10.0 else 0.0,
                COLOR_HEX[it.key] ?: "#8A8A8A")
        }
        val neutral = prim.filterKeys { it in NEUTRALS }.values.sum()
        fun r3(x: Double) = (x * 1000).roundToInt() / 1000.0
        return ColorDistribution(
            slices = slices,
            neutralShare = if (total > 0) r3(neutral.toDouble() / total) else 0.0,
            chromaticShare = if (total > 0) r3(1 - neutral.toDouble() / total) else 0.0,
            distinctColors = prim.size,
        )
    }

    // -----------------------------------------------------------------------
    // 5. CLOSET HEALTH  (utilization 45 / rotation 35 / balance 20)
    // -----------------------------------------------------------------------

    data class ClosetHealth(val score: Int, val grade: String, val utilization: Double,
                            val rotation: Double, val balance: Double, val gini: Double?)

    fun closetHealth(items: List<WardrobeItem>, nowMs: Long = System.currentTimeMillis()): ClosetHealth {
        val n = items.size
        if (n == 0) return ClosetHealth(0, "Empty", 0.0, 0.0, 0.0, null)

        // utilization: fraction worn, blended with recency (worn within 90d)
        val worn = items.filter { it.timesWorn > 0 }
        val live = worn.count { it.lastWorn != null && msDays(nowMs - it.lastWorn!!) <= 90 }
        val utilization = 100.0 * (0.6 * worn.size / n + 0.4 * live / n)

        // rotation: 1 - Gini of wear counts (1.0 = perfectly even)
        val wears = items.map { it.timesWorn }.sorted()
        val tot = wears.sum()
        val gini = if (tot > 0) wears.mapIndexed { idx, w -> (2L * (idx + 1) - n - 1) * w }
            .sum().toDouble() / (n * tot) else 0.0
        val rotation = if (tot > 0) 100.0 * (1 - max(0.0, gini)) else 0.0

        // balance: L1 distance of category share vs an ideal closet ratio
        val ideal = mapOf("Tops" to 0.22, "Bottoms" to 0.18, "Shoes" to 0.14, "Outerwear" to 0.12,
            "Accessories" to 0.14, "Bags" to 0.08, "Watches" to 0.06, "Jewelry" to 0.06)
        val l1 = ItemCategory.all.sumOf { c ->
            abs(items.count { it.category == c }.toDouble() / n - (ideal[c] ?: 0.0))
        }
        val maxL1 = 2 * (1 - ideal.values.min())
        val balance = 100.0 * max(0.0, 1 - l1 / maxL1)

        val score = (0.45 * utilization + 0.35 * rotation + 0.20 * balance).roundToInt()
        val grade = when {
            score >= 85 -> "Excellent"; score >= 70 -> "Healthy"
            score >= 50 -> "Fair"; else -> "Needs work"
        }
        fun r1(x: Double) = (x * 10).roundToInt() / 10.0
        return ClosetHealth(score, grade, r1(utilization), r1(rotation), r1(balance),
            (gini * 1000).roundToInt() / 1000.0)
    }

    // -----------------------------------------------------------------------
    // 6. WEAR CALENDAR HEATMAP
    // -----------------------------------------------------------------------

    data class HeatDay(val date: String, val count: Int, val level: Int)

    fun wearCalendar(events: List<WearEvent>, days: Int = 364): List<List<HeatDay>> {
        val perDay = events.groupingBy { it.date }.eachCount()
        val cal = java.util.Calendar.getInstance()
        cal.add(java.util.Calendar.DAY_OF_YEAR, -days)
        val grid = mutableListOf<MutableList<HeatDay>>()
        var week = mutableListOf<HeatDay>()
        val fmt = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
        repeat(days + 1) {
            val key = fmt.format(cal.time)
            val c = perDay[key] ?: 0
            val lvl = when { c == 0 -> 0; c == 1 -> 1; c <= 3 -> 2; c <= 5 -> 3; else -> 4 }
            week.add(HeatDay(key, c, lvl))
            if (week.size == 7) { grid.add(week); week = mutableListOf() }
            cal.add(java.util.Calendar.DAY_OF_YEAR, 1)
        }
        if (week.isNotEmpty()) grid.add(week)
        return grid
    }

    // -----------------------------------------------------------------------
    // 7. SEASONAL COVERAGE GAP
    // -----------------------------------------------------------------------

    data class SeasonCoverage(val count: Int, val share: Double, val hasCoreOutfit: Boolean,
                              val missingCore: List<String>, val covered: Boolean)

    fun seasonalCoverage(items: List<WardrobeItem>, minItems: Int = 6): Map<String, SeasonCoverage> {
        val n = items.size
        return listOf("All Season", "Summer", "Winter", "Spring/Fall").associateWith { s ->
            val grp = items.filter { it.season == s }
            val core = listOf(ItemCategory.TOP, ItemCategory.BOTTOM, ItemCategory.SHOES)
            val missing = core.filter { c -> grp.none { it.category == c } }
            SeasonCoverage(
                count = grp.size,
                share = if (n > 0) (1000.0 * grp.size / n).roundToInt() / 1000.0 else 0.0,
                hasCoreOutfit = missing.isEmpty(),
                missingCore = missing,
                covered = grp.size >= minItems && missing.isEmpty(),
            )
        }
    }

    // -----------------------------------------------------------------------
    // 8. UNUSED ITEMS REPORT
    // -----------------------------------------------------------------------

    data class UnusedReport(val neverWornCount: Int, val neverWornPct: Double,
                            val dormantValue: Double, val neverWorn: List<WardrobeItem>,
                            val stale180dCount: Int)

    fun unusedItems(items: List<WardrobeItem>, price: (WardrobeItem) -> Double,
                    nowMs: Long = System.currentTimeMillis()): UnusedReport {
        val never = items.filter { it.timesWorn == 0 }
        val stale = items.count { it.timesWorn > 0 && it.lastWorn != null &&
                msDays(nowMs - it.lastWorn!!) > 180 }
        val aged = java.util.concurrent.TimeUnit.DAYS
        return UnusedReport(
            neverWornCount = never.size,
            neverWornPct = if (items.isNotEmpty()) (1000.0 * never.size / items.size).roundToInt() / 10.0 else 0.0,
            dormantValue = (never.sumOf { price(it) } * 100).roundToInt() / 100.0,
            neverWorn = never.sortedByDescending { nowMs - it.createdAt },
            stale180dCount = stale,
        )
    }
}

/** REQUIRED SCHEMA EXTENSION — new Room entity for the calendar + occasion radar. */
@androidx.room.Entity(tableName = "wear_events")
data class WearEvent(
    @androidx.room.PrimaryKey(autoGenerate = true) val rowId: Long = 0,
    val itemId: String,
    val date: String,       // ISO yyyy-MM-dd
    val occasion: String,   // from AppConstants.occasions
)
