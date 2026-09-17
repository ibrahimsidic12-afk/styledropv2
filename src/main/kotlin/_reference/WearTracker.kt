package com.example.wear

/*
 * StyleDrop v2 — Wear Tracking domain layer  (Agent #17)
 *
 * Grounded in the real repo (commit a53013b):
 *   models/WardrobeItem.kt        -> timesWorn: Int = 0, isFavoriteItem: Boolean = false,
 *                                    lastWorn: Long? = null   ... ALL THREE ARE DEAD TODAY
 *                                    (only read by ui/ItemDetailScreen.kt:179, never written)
 *   data/AppDatabase.kt           -> version = 2, fallbackToDestructiveMigration()
 *   data/WardrobeDao.kt           -> 6 ops, no wear query
 *   ui/AppViewModelProvider.kt    -> manual factory (no Hilt/Koin)
 *   ui/SavedOutfitsScreen.kt      -> STUB: no outfit entity exists anywhere
 *
 * This file adds: WearEvent + Outfit entities, WearDao, WearConverters, WearRepository,
 * WearTracker (pure analytics), WearViewModel.  Package: com.example.wear (new).
 *
 * NOTE ON VERSIONS: minSdk = 24 in app/build.gradle.kts, so java.time is NOT used here
 * (it needs desugaring on API < 26). All date maths goes through java.util.Calendar.
 */

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.TypeConverter
import androidx.room.withTransaction
import com.example.data.AppDatabase
import com.example.data.WardrobeDao
import com.example.models.WardrobeItem
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import kotlin.math.abs
import kotlin.math.max

// ---------------------------------------------------------------------------------------------
// 0. Required one-line edits to EXISTING files (see WEAR_TRACKING.md §3) ------------------------
//    models/WardrobeItem.kt   :  + val price: Double? = null      <-- NEW field (nullable = no
//                                  destructive migration for existing rows)
//    data/AppDatabase.kt      :  version = 2 -> 3, entities += Outfit::class, WearEvent::class,
//                                 .addMigrations(MIGRATION_2_3)  and DROP destructive fallback
//    ui/AppViewModelProvider.kt: build WearRepository(db, db.wardrobeDao(), db.wearDao())
// ---------------------------------------------------------------------------------------------

// ---------------------------------------------------------------------------------------------
// 1. Entities ---------------------------------------------------------------------------------
// ---------------------------------------------------------------------------------------------

/** One row per *item-wear* (not per outfit) so per-piece analytics & cost-per-wear stay exact.
 *  An outfit worn once writes N rows sharing [outfitId]; that keeps both views derivable. */
@Entity(
    tableName = "wear_events",
    indices = [Index("itemId"), Index("dayKey"), Index("wornAt"), Index("outfitId")]
)
data class WearEvent(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val itemId: String,
    val outfitId: String? = null,
    val wornAt: Long = System.currentTimeMillis(),
    /** Local-calendar bucket "YYYY-MM-DD" — the heatmap's atomic unit (see WearClock). */
    val dayKey: String = WearClock.dayKey(System.currentTimeMillis()),
    /** manual | ai_result | saved_outfit | quick_log  (see [WearSource]). */
    val source: String = WearSource.MANUAL,
    val createdAt: Long = System.currentTimeMillis()
)

/** Minimal outfit record — the app has NO outfit entity today (SavedOutfitsScreen is a stub). */
@Entity(tableName = "outfits")
data class Outfit(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val name: String,
    val itemIds: List<String>,
    val occasion: String = "",
    val style: String = "",
    /** [NEW] full-look price if the pieces were bought as a set; null = not priced. */
    val price: Double? = null,
    val timesWorn: Int = 0,
    val lastWorn: Long? = null,
    val createdAt: Long = System.currentTimeMillis()
)

object WearSource {
    const val MANUAL = "manual"
    const val AI_RESULT = "ai_result"
    const val SAVED_OUTFIT = "saved_outfit"
    const val QUICK_LOG = "quick_log"
}

/** Room type converters for [Outfit.itemIds]. Register on AppDatabase: @TypeConverters(WearConverters::class) */
class WearConverters {
    @TypeConverter fun idsToString(ids: List<String>): String = ids.joinToString("|")
    @TypeConverter fun stringToIds(raw: String): List<String> =
        if (raw.isBlank()) emptyList() else raw.split("|")
}

// ---------------------------------------------------------------------------------------------
// 2. DAO + projections ------------------------------------------------------------------------
// ---------------------------------------------------------------------------------------------

data class DayCount(val dayKey: String, val wears: Int)
data class ItemCount(val itemId: String, val wears: Int, val lastAt: Long?)
data class SourceCount(val source: String, val wears: Int)

@Dao
interface WearDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun logWear(event: WearEvent)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun logWears(events: List<WearEvent>)

    @Query("SELECT * FROM wear_events ORDER BY wornAt DESC")
    fun observeAll(): Flow<List<WearEvent>>

    @Query("SELECT * FROM wear_events WHERE wornAt >= :since ORDER BY wornAt DESC")
    suspend fun eventsSince(since: Long): List<WearEvent>

    @Query("SELECT * FROM wear_events WHERE dayKey = :dayKey ORDER BY wornAt DESC")
    suspend fun eventsOnDay(dayKey: String): List<WearEvent>

    @Query("SELECT dayKey AS dayKey, COUNT(*) AS wears FROM wear_events WHERE wornAt >= :since GROUP BY dayKey")
    suspend fun dailyCounts(since: Long): List<DayCount>

    @Query("SELECT itemId AS itemId, COUNT(*) AS wears, MAX(wornAt) AS lastAt FROM wear_events WHERE wornAt >= :since GROUP BY itemId")
    suspend fun itemCounts(since: Long): List<ItemCount>

    @Query("SELECT source AS source, COUNT(*) AS wears FROM wear_events WHERE wornAt >= :since GROUP BY source")
    suspend fun sourceCounts(since: Long): List<SourceCount>

    @Query("SELECT COUNT(DISTINCT itemId) FROM wear_events")
    suspend fun distinctItemsEverWorn(): Int

    @Query("SELECT COUNT(DISTINCT itemId) FROM wear_events WHERE wornAt >= :since")
    suspend fun distinctItemsWornSince(since: Long): Int

    @Query("SELECT COUNT(*) FROM wear_events WHERE itemId = :itemId")
    suspend fun wearsOf(itemId: String): Int

    @Query("DELETE FROM wear_events WHERE itemId = :itemId")
    suspend fun purgeItem(itemId: String)

    // -- outfits -------------------------------------------------------------------------------
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertOutfit(outfit: Outfit)

    @Query("SELECT * FROM outfits ORDER BY createdAt DESC")
    fun observeOutfits(): Flow<List<Outfit>>

    @Query("SELECT * FROM outfits WHERE id = :outfitId LIMIT 1")
    suspend fun outfitById(outfitId: String): Outfit?

    @Query("UPDATE outfits SET timesWorn = timesWorn + 1, lastWorn = :at WHERE id = :outfitId")
    suspend fun touchOutfit(outfitId: String, at: Long)

    @Query("DELETE FROM outfits WHERE id = :outfitId")
    suspend fun deleteOutfit(outfitId: String)
}

// ---------------------------------------------------------------------------------------------
// 3. Clock — java.util.Calendar only (minSdk 24; no java.time / no desugaring dependency) ------
// ---------------------------------------------------------------------------------------------

object WearClock {
    const val DAY_MS = 86_400_000L

    fun dayKey(millis: Long, zone: TimeZone = TimeZone.getDefault()): String =
        SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { timeZone = zone }.format(Date(millis))

    fun dayLabel(millis: Long, zone: TimeZone = TimeZone.getDefault()): String =
        SimpleDateFormat("EEE d MMM", Locale.US).apply { timeZone = zone }.format(Date(millis))

    fun startOfDay(millis: Long): Long = Calendar.getInstance().apply {
        timeInMillis = millis
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    /** Monday 00:00 of the week containing [millis] — Monday is the heatmap's first column. */
    fun startOfWeek(millis: Long): Long = Calendar.getInstance().apply {
        timeInMillis = startOfDay(millis)
        val dow = get(Calendar.DAY_OF_WEEK)             // Sun=1 .. Sat=7
        val offset = (dow + 5) % 7                       // Mon->0 .. Sun->6
        add(Calendar.DAY_OF_MONTH, -offset)
    }.timeInMillis

    fun addDays(millis: Long, days: Int): Long =
        Calendar.getInstance().apply { timeInMillis = millis; add(Calendar.DAY_OF_MONTH, days) }.timeInMillis

    fun addWeeks(millis: Long, weeks: Int): Long = addDays(millis, weeks * 7)

    fun daysBetween(from: Long, to: Long): Int =
        ((startOfDay(to) - startOfDay(from)) / DAY_MS).toInt()

    fun nowWeekKey(millis: Long): String = "${Calendar.getInstance().apply { timeInMillis = millis }.get(Calendar.YEAR)}-W${isoWeek(millis)}"

    fun isoWeek(millis: Long): Int {
        val c = Calendar.getInstance().apply { timeInMillis = millis; firstDayOfWeek = Calendar.MONDAY; minimalDaysInFirstWeek = 4 }
        return c.get(Calendar.WEEK_OF_YEAR)
    }
}

// ---------------------------------------------------------------------------------------------
// 4. Repository — the ONLY place that writes timesWorn / lastWorn ------------------------------
// ---------------------------------------------------------------------------------------------

class WearRepository(
    private val db: AppDatabase,
    private val wardrobeDao: WardrobeDao,
    private val wearDao: WearDao
) {
    val events: Flow<List<WearEvent>> = wearDao.observeAll()
    val outfits: Flow<List<Outfit>> = wearDao.observeOutfits()

    /** Double-tap / repeated save guard: ignore an identical item+day log inside this window. */
    private val dedupeWindowMs = 300_000L

    /**
     * FEATURE 1 — "Worn today": atomically increments `timesWorn`, sets `lastWorn = now`
     * and writes one [WearEvent] per item. Wrapped in a single Room transaction so the
     * counter and the log can never diverge.
     */
    suspend fun markWorn(
        itemIds: List<String>,
        outfitId: String? = null,
        source: String = WearSource.MANUAL,
        nowMs: Long = System.currentTimeMillis()
    ) {
        val unique = itemIds.filter { it.isNotBlank() }.distinct()
        if (unique.isEmpty()) return
        val today = WearClock.dayKey(nowMs)

        db.withTransaction {
            val todaysEvents = wearDao.eventsOnDay(today)
            for (id in unique) {
                val already = todaysEvents.firstOrNull { it.itemId == id }
                if (already != null && abs(nowMs - already.wornAt) < dedupeWindowMs) continue

                val item = wardrobeDao.getItemById(id) ?: continue
                wardrobeDao.updateItem(item.copy(timesWorn = item.timesWorn + 1, lastWorn = nowMs)) // <-- the dead-field fix
                wearDao.logWear(WearEvent(itemId = id, outfitId = outfitId, wornAt = nowMs, dayKey = today, source = source))
            }
            if (outfitId != null) wearDao.touchOutfit(outfitId, nowMs)
        }
    }

    /** Undo the last log on a calendar day (whole day, or a single item when [itemId] is given). */
    suspend fun undoDay(dayKey: String, itemId: String? = null) {
        db.withTransaction {
            val events = wearDao.eventsOnDay(dayKey).filter { itemId == null || it.itemId == itemId }
            for (e in events) {
                val item = wardrobeDao.getItemById(e.itemId) ?: continue
                wardrobeDao.updateItem(
                    item.copy(timesWorn = max(0, item.timesWorn - 1), lastWorn = null)
                )
            }
        }
    }

    suspend fun saveOutfit(outfit: Outfit) = wearDao.upsertOutfit(outfit)

    suspend fun setPrice(itemId: String, price: Double?) {
        val item = wardrobeDao.getItemById(itemId) ?: return
        wardrobeDao.updateItem(item.copy(price = price))
    }

    suspend fun setOutfitPrice(outfitId: String, price: Double?) {
        val o = wearDao.outfitById(outfitId) ?: return
        wearDao.upsertOutfit(o.copy(price = price))
    }

    suspend fun deleteItemAndHistory(itemId: String) {
        db.withTransaction {
            wearDao.purgeItem(itemId)
            wardrobeDao.deleteItem(itemId)
        }
    }

    /** Everything the wear screen needs, computed once per call (see §6 sampling in the spec). */
    suspend fun snapshot(
        items: List<WardrobeItem>,
        nowMs: Long = System.currentTimeMillis(),
        windowWeeks: Int = WearTracker.DEFAULT_WINDOW_WEEKS
    ): WearSnapshot {
        val since = WearClock.addWeeks(WearClock.startOfWeek(nowMs), -(windowWeeks - 1))
        val eventsInWindow = wearDao.eventsSince(since)
        val allEvents = wearDao.eventsSince(0L)
        val distinctEver = wearDao.distinctItemsEverWorn()
        val distinct7 = wearDao.distinctItemsWornSince(WearClock.addDays(nowMs, -7))

        return WearTracker.snapshot(
            items = items,
            eventsInWindow = eventsInWindow,
            allEvents = allEvents,
            distinctEverWorn = distinctEver,
            distinctWornLast7d = distinct7,
            nowMs = nowMs,
            windowWeeks = windowWeeks
        )
    }
}

// ---------------------------------------------------------------------------------------------
// 5. Pure analytics — [WearTracker]. No Android, no I/O: unit-testable with plain JUnit 4 ------
// ---------------------------------------------------------------------------------------------

data class LoomCell(
    val weekIndex: Int,          // 0 = oldest week in the window
    val dayIndex: Int,           // 0 = Monday .. 6 = Sunday
    val dayKey: String,
    val wears: Int,
    val level: Int,              // 0..4 intensity
    val rescued: Boolean,        // the day included a wear that ended a 60+ day dormancy
    val future: Boolean,         // beyond today — render as empty warp only
    val preTracking: Boolean     // before the first-ever recorded wear
)

data class UnderusedItem(val item: WardrobeItem, val daysSinceLastWear: Int?, val neverWorn: Boolean)

data class Achievement(
    val id: String,
    val title: String,
    val detail: String,
    val unlocked: Boolean,
    val progress: Float,         // 0f..1f
    val progressLabel: String
)

data class SustainabilityBadge(val level: Int, val label: String, val tip: String, val cpw: Double?)

data class WearStats(
    val totalPieces: Int,
    val pricedPieces: Int,
    val totalWardrobeValue: Double,
    val averageCostPerWear: Double?,
    val weeklyWearPercentage: Double,
    val distinctPiecesWornEver: Int,
    val totalWearsLogged: Int,
    val wearsLast30d: Int,
    val streakDays: Int,
    val mostWorn: WardrobeItem?,
    val leastWorn: WardrobeItem?
)

data class WearSnapshot(
    val stats: WearStats,
    val loom: List<LoomCell>,
    val underused: List<UnderusedItem>,
    val achievements: List<Achievement>,
    val badge: SustainabilityBadge,
    val windowWeeks: Int
)

object WearTracker {

    const val DEFAULT_WINDOW_WEEKS = 12
    const val EXTENDED_WINDOW_WEEKS = 26
    const val UNDERUSE_DAYS = 60
    const val NEW_ITEM_GRACE_DAYS = 14

    // -- 5.1 cost-per-wear -------------------------------------------------------------------

    /**
     * FEATURE 2 — cost per wear. `effectiveWears` floors at 1 so an unworn £120 coat reads
     * £120.00, not ∞ — an honest "you haven't worn it yet" number.
     */
    fun costPerWear(price: Double?, timesWorn: Int): Double? {
        if (price == null || price <= 0.0) return null
        return price / max(1, timesWorn)
    }

    /** Age-normalised CPW used only for ranking: prorates to 1 year of ownership. */
    fun projectedCostPerWear(item: WardrobeItem, nowMs: Long): Double? {
        val price = item.price ?: return null
        val daysOwned = max(1, WearClock.daysBetween(item.createdAt, nowMs))
        val wearsPerYear = item.timesWorn.toDouble() * 365.0 / daysOwned
        return price / max(1.0, wearsPerYear)
    }

    // -- 5.2 sustainability badge (FEATURE 6) ------------------------------------------------

    /**
     * Badge bands are driven *only* by cost-per-wear — the owner's own proxy for how hard a
     * garment is working. `co2eKgPerWear` is a rough, UNVERIFIED planning constant
     * (labelled [assumption] in WEAR_TRACKING.md §4.6) — it is never presented as a cited figure.
     */
    fun sustainabilityBadge(price: Double?, timesWorn: Int): SustainabilityBadge {
        val cpw = costPerWear(price, timesWorn)
        if (cpw == null) return SustainabilityBadge(2, "Unpriced", "Add a price to score this piece's wear efficiency.", null)
        return when {
            cpw <= 1.0 -> SustainabilityBadge(4, "Circular", "Under \u20B11 per wear — this piece is earning its keep.", cpw)
            cpw <= 3.0 -> SustainabilityBadge(3, "Low-impact", "Great rotation. Keep it in the weekly mix.", cpw)
            cpw <= 7.5 -> SustainabilityBadge(2, "Breaking even", "On track — a few more wears and it pays for itself.", cpw)
            cpw <= 20.0 -> SustainabilityBadge(1, "Still settling", "Wear it 2\u00D7 more this month to bring the cost down.", cpw)
            else -> SustainabilityBadge(0, "High turnover risk", "Rarely worn and expensive per wear \u2014 restyle it or pass it on.", cpw)
        }
    }

    /** Planning constant only: rough kg CO2e attributed per wear, by badge band. [assumption] */
    fun estimatedCo2ePerWear(badge: SustainabilityBadge): Double? = when (badge.level) {
        4 -> 0.4; 3 -> 0.9; 2 -> 1.8; 1 -> 3.6; 0 -> 7.2; else -> null
    }

    // -- 5.3 underused alarm (FEATURE 5) ----------------------------------------------------

    /**
     * Items with 0 wears in the last [UNDERUSE_DAYS] days. Excluded: pieces added fewer than
     * [NEW_ITEM_GRACE_DAYS] days ago (they simply haven't had a chance), and pieces the user
     * marked as fanned/archived by category (Accessories/Bags are naturally low-frequency).
     */
    fun underused(
        items: List<WardrobeItem>,
        events: List<WearEvent>,
        nowMs: Long,
        windowDays: Int = UNDERUSE_DAYS,
        includeLowFrequencyCategories: Boolean = false
    ): List<UnderusedItem> {
        val lastWornMap = events.groupBy { it.itemId }.mapValues { (_, evs) -> evs.maxOf { it.wornAt } }
        val lowFreq = setOf("Accessories", "Bags", "Watches", "Jewelry")

        return items.mapNotNull { item ->
            if (WearClock.daysBetween(item.createdAt, nowMs) < NEW_ITEM_GRACE_DAYS) return@mapNotNull null
            if (!includeLowFrequencyCategories && item.category in lowFreq) return@mapNotNull null

            val lastAt = lastWornMap[item.id] ?: item.lastWorn
            val daysSince = lastAt?.let { WearClock.daysBetween(it, nowMs) }
            val neverWorn = item.timesWorn == 0 && lastAt == null
            val dormant = neverWorn || (daysSince != null && daysSince >= windowDays)
            if (!dormant) null else UnderusedItem(item, daysSince, neverWorn)
        }.sortedWith(compareByDescending<UnderusedItem> { it.neverWorn }.thenByDescending { it.daysSinceLastWear ?: Int.MAX_VALUE })
    }

    // -- 5.4 the Wear Loom heatmap (FEATURE 4) -----------------------------------------------

    /**
     * Aggregates [events] into a Monday-first week x day grid of [windowWeeks] weeks.
     * Level thresholds: 0 wears -> 0; 1 -> 1; 2 -> 2; 3 -> 3; 4+ -> 4.
     * `rescued` marks a day whose wears include a piece that had been dormant >= 60 days.
     */
    fun buildLoom(
        items: List<WardrobeItem>,
        events: List<WearEvent>,
        nowMs: Long,
        windowWeeks: Int = DEFAULT_WINDOW_WEEKS
    ): List<LoomCell> {
        val firstWeekStart = WearClock.addWeeks(WearClock.startOfWeek(nowMs), -(windowWeeks - 1))
        val todayStart = WearClock.startOfDay(nowMs)
        val firstEverWear = events.minOfOrNull { it.wornAt }

        val dormantBefore = events
            .groupBy { it.itemId }
            .mapValues { (_, evs) -> evs.sortedBy { it.wornAt }.map { it.wornAt } }

        val cells = ArrayList<LoomCell>(windowWeeks * 7)
        for (w in 0 until windowWeeks) {
            for (d in 0 until 7) {
                val dayStart = WearClock.addDays(firstWeekStart, w * 7 + d)
                val key = WearClock.dayKey(dayStart)
                val dayEvents = events.filter { it.dayKey == key }
                val wears = dayEvents.size
                val level = when {
                    wears <= 0 -> 0; wears == 1 -> 1; wears == 2 -> 2; wears == 3 -> 3; else -> 4
                }
                val rescued = dayEvents.any { e ->
                    val prior = dormantBefore[e.itemId]?.filter { it < e.wornAt }?.maxOrNull() ?: return@any false
                    WearClock.daysBetween(prior, e.wornAt) >= UNDERUSE_DAYS
                }
                cells += LoomCell(
                    weekIndex = w,
                    dayIndex = d,
                    dayKey = key,
                    wears = wears,
                    level = level,
                    rescued = rescued,
                    future = dayStart > todayStart,
                    preTracking = firstEverWear == null || dayStart < WearClock.startOfDay(firstEverWear)
                )
            }
        }
        return cells
    }

    // -- 5.5 achievements (FEATURE 7) --------------------------------------------------------

    fun achievements(
        items: List<WardrobeItem>,
        events: List<WearEvent>,
        distinctEverWorn: Int,
        nowMs: Long
    ): List<Achievement> {
        val distinct = distinctEverWorn
        val mostWorn = items.maxByOrNull { it.timesWorn }
        val daysWithWear = events.map { it.dayKey }.toSet()
        val streak = longestStreak(daysWithWear)
        val cpwCrusher = items.any { costPerWear(it.price, it.timesWorn)?.let { c -> c <= 1.0 } == true }
        val pricedRatio = if (items.isEmpty()) 0f else items.count { it.price != null }.toFloat() / items.size
        val rescued = underused(items, events, nowMs, includeLowFrequencyCategories = true).isEmpty() && events.isNotEmpty()

        return listOf(
            Achievement("first_wear", "First wear", "Log your first outfit as worn.", events.isNotEmpty(), if (events.isEmpty()) 0f else 1f, "${events.size} wears logged"),
            Achievement("thirty_pieces", "Wore 30 different pieces", "A fully exercised wardrobe.", distinct >= 30, (distinct / 30f).coerceAtMost(1f), "$distinct / 30 pieces"),
            Achievement("most_worn", "Most-worn piece", "One piece you reach for again and again.", (mostWorn?.timesWorn ?: 0) >= 10, ((mostWorn?.timesWorn ?: 0) / 10f).coerceAtMost(1f), mostWorn?.let { "${it.type} \u00B7 ${it.timesWorn}\u00D7" } ?: "no wears yet"),
            Achievement("full_rotation", "Full rotation", "Every piece worn at least once.", items.isNotEmpty() && items.all { it.timesWorn > 0 }, if (items.isEmpty()) 0f else items.count { it.timesWorn > 0 }.toFloat() / items.size, "${items.count { it.timesWorn > 0 }} / ${items.size} pieces"),
            Achievement("streak_7", "Seven-day streak", "Seven days in a row, dressed on purpose.", streak >= 7, (streak / 7f).coerceAtMost(1f), "$streak-day streak"),
            Achievement("cost_crusher", "Cost crusher", "A piece down to \u20B11.00 or less per wear.", cpwCrusher, if (cpwCrusher) 1f else 0.4f, if (cpwCrusher) "unlocked" else "keep wearing"),
            Achievement("rescue", "Rescue mission", "No piece dormant for 60+ days.", rescued, if (rescued) 1f else 0f, if (rescued) "all active" else "there is a sleeper"),
            Achievement("auditor", "Wardrobe auditor", "Price 80% of your pieces to unlock real CPW.", pricedRatio >= 0.8f, pricedRatio, "${items.count { it.price != null }} / ${items.size} priced")
        )
    }

    fun longestStreak(dayKeys: Set<String>): Int {
        if (dayKeys.isEmpty()) return 0
        val sorted = dayKeys.sorted()
        var best = 1; var run = 1
        var prev = sorted.first()
        for (k in sorted.drop(1)) {
            val next = WearClock.addDays(
                Calendar.getInstance().apply { timeInMillis = parseDayKey(prev) }.timeInMillis, 1
            )
            run = if (WearClock.dayKey(next) == k) run + 1 else 1
            best = max(best, run)
            prev = k
        }
        return best
    }

    private fun parseDayKey(key: String): Long =
        SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(key)?.time ?: 0L

    // -- 5.6 headline stats (FEATURE 8) ------------------------------------------------------

    fun stats(
        items: List<WardrobeItem>,
        events: List<WearEvent>,
        distinctEverWorn: Int,
        distinctWornLast7d: Int,
        nowMs: Long
    ): WearStats {
        val priced = items.filter { it.price != null && it.price!! > 0.0 }
        val totalValue = priced.sumOf { it.price ?: 0.0 }
        val cpws = priced.mapNotNull { costPerWear(it.price, it.timesWorn) }
        val weeklyPct = if (items.isEmpty()) 0.0 else distinctWornLast7d * 100.0 / items.size
        val last30 = WearClock.addDays(nowMs, -30)
        return WearStats(
            totalPieces = items.size,
            pricedPieces = priced.size,
            totalWardrobeValue = totalValue,
            averageCostPerWear = if (cpws.isEmpty()) null else cpws.average(),
            weeklyWearPercentage = weeklyPct,
            distinctPiecesWornEver = distinctEverWorn,
            totalWearsLogged = events.size,
            wearsLast30d = events.count { it.wornAt >= last30 },
            streakDays = longestStreak(events.map { it.dayKey }.toSet()),
            mostWorn = items.maxByOrNull { it.timesWorn },
            leastWorn = items.filter { it.createdAt <= WearClock.addDays(nowMs, -NEW_ITEM_GRACE_DAYS) }.minByOrNull { it.timesWorn }
        )
    }

    fun snapshot(
        items: List<WardrobeItem>,
        eventsInWindow: List<WearEvent>,
        allEvents: List<WearEvent>,
        distinctEverWorn: Int,
        distinctWornLast7d: Int,
        nowMs: Long,
        windowWeeks: Int = DEFAULT_WINDOW_WEEKS
    ): WearSnapshot = WearSnapshot(
        stats = stats(items, allEvents, distinctEverWorn, distinctWornLast7d, nowMs),
        loom = buildLoom(items, eventsInWindow, nowMs, windowWeeks),
        underused = underused(items, allEvents, nowMs),
        achievements = achievements(items, allEvents, distinctEverWorn, nowMs),
        badge = wardrobeBadge(items),
        windowWeeks = windowWeeks
    )

    /** Wardrobe-level badge = the median piece badge, so one hero coat can't carry the score. */
    fun wardrobeBadge(items: List<WardrobeItem>): SustainabilityBadge {
        val levels = items.map { sustainabilityBadge(it.price, it.timesWorn).level }.sorted()
        if (levels.isEmpty()) return SustainabilityBadge(2, "Unpriced", "Add prices to score the wardrobe.", null)
        val median = levels[levels.size / 2]
        val avg = items.mapNotNull { costPerWear(it.price, it.timesWorn) }.takeIf { it.isNotEmpty() }?.average()
        return when (median) {
            4 -> SustainabilityBadge(4, "Circular wardrobe", "Your whole rail is working hard.", avg)
            3 -> SustainabilityBadge(3, "Low-impact wardrobe", "Strong rotation across the collection.", avg)
            2 -> SustainabilityBadge(2, "Steady wardrobe", "Balanced \u2014 keep the weekly mix going.", avg)
            1 -> SustainabilityBadge(1, "Heavy rail", "Many pieces are still settling. Rotate more.", avg)
            else -> SustainabilityBadge(0, "High turnover", "Half the rail barely gets worn.", avg)
        }
    }
}

// ---------------------------------------------------------------------------------------------
// 6. ViewModel --------------------------------------------------------------------------------
// ---------------------------------------------------------------------------------------------

class WearViewModel(private val repository: WearRepository) : ViewModel() {

    private val _snapshot = MutableStateFlow<WearSnapshot?>(null)
    val snapshot: StateFlow<WearSnapshot?> = _snapshot.asStateFlow()

    private val _items = MutableStateFlow<List<WardrobeItem>>(emptyList())

    /** Call from the screen: keeps the last known wardrobe list so snapshot() stays I/O-light. */
    fun bindWardrobe(items: List<WardrobeItem>, windowWeeks: Int = WearTracker.DEFAULT_WINDOW_WEEKS) {
        _items.value = items
        viewModelScope.launch { refresh(windowWeeks) }
    }

    fun refresh(windowWeeks: Int = WearTracker.DEFAULT_WINDOW_WEEKS) {
        viewModelScope.launch {
            _snapshot.value = repository.snapshot(_items.value, windowWeeks = windowWeeks)
        }
    }

    /** FEATURE 3 — the "Wear this" CTA entry point (AI result card + saved outfits). */
    fun wearToday(itemIds: List<String>, outfitId: String? = null, source: String = WearSource.MANUAL) {
        viewModelScope.launch {
            repository.markWorn(itemIds, outfitId, source)
            refresh(_snapshot.value?.windowWeeks ?: WearTracker.DEFAULT_WINDOW_WEEKS)
        }
    }

    fun undoDay(dayKey: String, itemId: String? = null) {
        viewModelScope.launch { repository.undoDay(dayKey, itemId); refresh() }
    }

    fun setPrice(itemId: String, price: Double?) {
        viewModelScope.launch { repository.setPrice(itemId, price); refresh() }
    }

    fun saveOutfit(outfit: Outfit) {
        viewModelScope.launch { repository.saveOutfit(outfit); refresh() }
    }
}
