# WEAR_TRACKING.md — StyleDrop v2 Wear Tracking System

**Agent #17** · Repo: `github.com/ibrahimsidic12-afk/styledropv2` · Commit analyzed: `a53013b`
*"feat(ui): update visual identity to ivory theme"* · Clone: **SUCCESS** (`git clone --depth 1`, exit 0)

---

## 0. Executive summary

StyleDrop ships three fields that are **written once at insert and never touched again**:

| Field | Declared in `models/WardrobeItem.kt` | Where it is used today |
|---|---|---|
| `timesWorn: Int = 0` | line 21 | **read once** — `ui/ItemDetailScreen.kt:179` → `InfoRow("Worn", "${item!!.timesWorn} times")` |
| `isFavoriteItem: Boolean = false` | line 22 | **never read, never written** — dead |
| `lastWorn: Long? = null` | line 23 | **never read, never written** — dead |

Grep proof (`grep -rn "timesWorn\|lastWorn\|isFavoriteItem" app/src/main/java`):
```
app/src/main/java/com/example/ui/ItemDetailScreen.kt:179:  InfoRow("Worn", "${item!!.timesWorn} times")
app/src/main/java/com/example/models/WardrobeItem.kt:21:   val timesWorn: Int = 0,
app/src/main/java/com/example/models/WardrobeItem.kt:22:   val isFavoriteItem: Boolean = false,
app/src/main/java/com/example/models/WardrobeItem.kt:23:   val lastWorn: Long? = null,
```

So the detail screen displays **"Worn 0 times" forever**. There is also **no `Outfit` entity anywhere in the
repo** — `SavedOutfitsScreen.kt` is a stub (empty state + two `TODO` icon buttons). This document wires the
dead layer up end to end: **8 features**, all grounded in the real DAO/entity/theme code.

**Stack confirmed from the clone:** Kotlin 2.2.10 · Jetpack Compose + Material 3 (BOM 2024.09.00) ·
**Room 2.7.0** (SQLite, `AppDatabase` version 2, `fallbackToDestructiveMigration()`) · Coil · Retrofit +
Moshi → Gemini · Navigation-Compose · **manual DI** (`ui/AppViewModelProvider.kt`, no Hilt/Koin) ·
`minSdk 24` / `targetSdk 36`.

> **`minSdk 24` is the hard constraint that shapes this whole design:** `java.time` needs desugaring on
> API < 26, and the repo enables **no** desugaring. Every date computation in `WearTracker.kt` therefore
> goes through **`java.util.Calendar` + `SimpleDateFormat`** — nothing else.

---

## 1. What ships

| # | Feature requested | Delivered as | File · symbol |
|---|---|---|---|
| 1 | Increment `timesWorn`, set `lastWorn` on "worn today" | Atomic Room transaction, double-tap dedupe | `WearTracker.kt` → `WearRepository.markWorn()` |
| 2 | `price` field on item + outfit, cost-per-wear | `WardrobeItem.price`, `Outfit.price` | `WearTracker.kt` → `WearTracker.costPerWear()` |
| 3 | "Wear this" CTA on AI result + saved outfits | Drop-in Compose button | `wear_screen.kt` → `WearThisButton()` |
| 4 | Wear history: calendar heatmap | **"The Wear Loom"** — woven fabric, not GitHub squares | `WearTracker.buildLoom()` + `WearLoom()` |
| 5 | Underused alarm (0 wears in 60+ days) | Generous grace window + category filter | `WearTracker.underused()` |
| 6 | Carbon / sustainability badge from CPW | 5-band badge + planning constant | `WearTracker.sustainabilityBadge()` |
| 7 | Achievements ("Wore 30 different pieces", "Most-worn piece") | 8 achievements, progress-tracked | `WearTracker.achievements()` |
| 8 | Stats: avg CPW, total wardrobe value, weekly wear % | `WearStats` data class + stat strip | `WearTracker.stats()` + `WearStatStrip()` |

Two new Kotlin files: **`WearTracker.kt`** (domain + persistence + ViewModel, 648 lines) and
**`wear_screen.kt`** (UI, 558 lines). Both are inside the clone at `app/src/main/java/com/example/wear/`
and `.../ui/wear/` respectively.

---

## 2. Data model

```
WardrobeItem (EXISTING, 15 fields)           WearEvent (NEW)
 ├ id, imageUrl, category, type               ├ id (autoGenerate)
 ├ color, secondaryColor, style, fit          ├ itemId ──────► wardrobe_items.id
 ├ pattern, season, brand                     ├ outfitId ───► outfits.id (nullable)
 ├ timesWorn   ← the dead field               ├ wornAt: Long
 ├ isFavoriteItem ← the dead field            ├ dayKey: "YYYY-MM-DD"  ← heatmap bucket
 ├ lastWorn    ← the dead field               └ source: manual|ai_result|saved_outfit|quick_log
 └ createdAt
                                            Outfit (NEW — no outfit entity exists in repo today)
 + price: Double?  ← NEW                    ├ id, name, itemIds: List<String> (converter)
                                            ├ occasion, style
                                            ├ price: Double?  ← NEW
                                            ├ timesWorn, lastWorn, createdAt
```

**Why one row per *item-wear*, not per *outfit-wear*.** An outfit worn on Tuesday writes **N** `WearEvent`
rows sharing one `outfitId`. That single decision makes all three views derivable with plain SQL:

- **per-piece cost-per-wear** → `wearsOf(itemId)` (exact, no outfit-join)
- **per-outfit wear count** → `GROUP BY outfitId`, or the denormalised `Outfit.timesWorn`
- **the heatmap** → `GROUP BY dayKey`

The alternative (one row per outfit, items serialised inside) would have made per-piece analytics a string
split, and cost-per-wear — the app's headline stat — would have been wrong for any outfit worn with a
piece swapped out. `WardrobeItem.timesWorn` is kept as the denormalised fast counter the detail screen
already reads, and `WearEvent` is the append-only ledger of truth.

`WearConverters` (Room `@TypeConverter`) stores `Outfit.itemIds` as a `"id1|id2|id3"` string — matching how
the repo already stores `imageUrl` as a raw string rather than introducing a Junction table.

### 2.1 Entities (verbatim from `WearTracker.kt`)

```kotlin
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
```

---

## 3. Migration — the part that crashes the app if skipped

`AppDatabase.kt` today:

```kotlin
@Database(entities = [WardrobeItem::class], version = 2, exportSchema = false)
...
    .fallbackToDestructiveMigration()      // <-- any schema bump SILENTLY WIPES the wardrobe
```

Adding `price` + two entities **must** bump the version. If `fallbackToDestructiveMigration()` is left in
place the version bump **deletes every garment the user has ever scanned** — that is the single most
dangerous line in the repo for this feature.

**Required edits to existing files:**

**`models/WardrobeItem.kt`** — one new field, nullable so existing rows need no value:
```kotlin
    val brand: String = "",
    val price: Double? = null,          // <-- ADD: purchase price, drives cost-per-wear
    val timesWorn: Int = 0,
```

**`data/AppDatabase.kt`** — version 2 → 3, register entities + converter, add the migration, drop the fallback:
```kotlin
@Database(
    entities = [WardrobeItem::class, Outfit::class, WearEvent::class],
    version = 3,
    exportSchema = false
)
@TypeConverters(WearConverters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun wardrobeDao(): WardrobeDao
    abstract fun wearDao(): WearDao

    companion object {
        /** ADDITIVE only — no table is touched, so no existing wardrobe row is lost. */
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE wardrobe_items ADD COLUMN price REAL")
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS wear_events (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        itemId TEXT NOT NULL, outfitId TEXT,
                        wornAt INTEGER NOT NULL, dayKey TEXT NOT NULL,
                        source TEXT NOT NULL, createdAt INTEGER NOT NULL)
                """)
                db.execSQL("CREATE INDEX IF NOT EXISTS index_wear_events_itemId ON wear_events(itemId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_wear_events_dayKey ON wear_events(dayKey)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_wear_events_wornAt ON wear_events(wornAt)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_wear_events_outfitId ON wear_events(outfitId)")
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS outfits (
                        id TEXT NOT NULL PRIMARY KEY, name TEXT NOT NULL, itemIds TEXT NOT NULL,
                        occasion TEXT NOT NULL, style TEXT NOT NULL, price REAL,
                        timesWorn INTEGER NOT NULL, lastWorn INTEGER, createdAt INTEGER NOT NULL)
                """)
            }
        }

        fun getDatabase(context: Context): AppDatabase =
            INSTANCE ?: synchronized(this) {
                Room.databaseBuilder(context.applicationContext, AppDatabase::class.java, "styledrop_database")
                    .addMigrations(MIGRATION_2_3)
                    // .fallbackToDestructiveMigration()  <-- REMOVED on purpose. See §3.1.
                    .build().also { INSTANCE = it }
            }
    }
}
```

### 3.1 Why the fallback is removed, not kept
Keeping it as a *safety net* is precisely backwards: it converts a recoverable "no migration found"
exception into **silent, irreversible data loss**. With `MIGRATION_2_3` registered, a missing future
migration will now throw loudly in debug instead of deleting the wardrobe. `exportSchema = false` should
also become `true` so future migrations can be tested — but that is a separate change and is **not** applied
here.

**`ui/AppViewModelProvider.kt`** — register the new repository (manual DI, no Hilt):
```kotlin
if (modelClass.isAssignableFrom(WearViewModel::class.java)) {
    val db = AppDatabase.getDatabase(application)
    return WearViewModel(WearRepository(db, db.wardrobeDao(), db.wearDao())) as T
}
```

---

## 4. Feature detail

### 4.1 FEATURE 1 — "Worn today" writes the dead fields

The **only** place `timesWorn` / `lastWorn` are ever written. One transaction, so the counter and the ledger
can never diverge (a crash between two separate `@Update`/`@Insert` calls would corrupt cost-per-wear
permanently):

```kotlin
suspend fun markWorn(itemIds: List<String>, outfitId: String? = null,
                     source: String = WearSource.MANUAL, nowMs: Long = System.currentTimeMillis()) {
    val unique = itemIds.filter { it.isNotBlank() }.distinct()
    if (unique.isEmpty()) return
    val today = WearClock.dayKey(nowMs)

    db.withTransaction {
        val todaysEvents = wearDao.eventsOnDay(today)
        for (id in unique) {
            val already = todaysEvents.firstOrNull { it.itemId == id }
            if (already != null && abs(nowMs - already.wornAt) < dedupeWindowMs) continue   // 5-min double-tap guard
            val item = wardrobeDao.getItemById(id) ?: continue
            wardrobeDao.updateItem(item.copy(timesWorn = item.timesWorn + 1, lastWorn = nowMs))  // <-- the fix
            wearDao.logWear(WearEvent(itemId = id, outfitId = outfitId, wornAt = nowMs,
                                      dayKey = today, source = source))
        }
        if (outfitId != null) wearDao.touchOutfit(outfitId, nowMs)
    }
}
```

**Anti-error design:** the 5-minute `dedupeWindowMs` guard stops a double-tap on "Wear this" from
double-counting a piece and halving its apparent cost-per-wear. `undoDay()` reverses a whole day (or one
item) and floors the counter at 0 via `max(0, timesWorn - 1)`, clearing `lastWorn` — so a mis-tap is
fully recoverable.

### 4.2 FEATURE 2 — price + cost-per-wear

`price` is **nullable on purpose**. Room's `ALTER TABLE ADD COLUMN` cannot add `NOT NULL` without a default,
and more importantly an existing user has never told us what anything cost. `null` = "unpriced", which the
UI renders as `—` / "no price" rather than a fabricated ₱0.00.

```kotlin
fun costPerWear(price: Double?, timesWorn: Int): Double? {
    if (price == null || price <= 0.0) return null
    return price / max(1, timesWorn)          // floor at 1 wear -> unworn coat reads ₱120, not ∞
}
```

The `max(1, …)` floor is deliberate: `price / 0` is `Infinity`, and showing "∞ per wear" is hostile. The
honest reading of an unworn ₱120 coat is **"₱120.00 per wear — you haven't worn it yet."**

A second, ranking-only metric normalises for ownership length, so a coat bought last month isn't judged
against one owned for three years:

```kotlin
fun projectedCostPerWear(item: WardrobeItem, nowMs: Long): Double? {
    val price = item.price ?: return null
    val daysOwned = max(1, WearClock.daysBetween(item.createdAt, nowMs))
    val wearsPerYear = item.timesWorn.toDouble() * 365.0 / daysOwned
    return price / max(1.0, wearsPerYear)
}
```

### 4.3 FEATURE 3 — the "Wear this" CTA

A single drop-in composable, used in **two** places:

```kotlin
@Composable
fun WearThisButton(itemIds: List<String>, viewModel: WearViewModel, outfitId: String? = null,
                   source: String = WearSource.AI_RESULT, modifier: Modifier = Modifier) { ... }
```

- **`ui/AiGeneratorScreen.kt`** → place it directly under the "Stylist recommendation" `Card`
  (in the `if (generatedResult != null)` block, before the `DropdownField("Occasion"…)`).
  Pass `itemIds` from the model's returned `[ID: …]` values, validated against `allItems`.
- **`ui/SavedOutfitsScreen.kt`** → replaces the two `/* TODO */` `IconButton`s with a real list; each saved
  outfit row carries `WearThisButton(outfit.itemIds, viewModel, outfit.id, WearSource.SAVED_OUTFIT)`.

The button self-latches to `"Logged for today ✓"` and turns forest-green on click, so the primary action on
screen can't be fired twice. `source` records *where* the log came from — that is what lets §4.7 report
"how many outfits were actually worn from AI suggestions".

### 4.4 FEATURE 4 — "The Wear Loom" (the unique heatmap)

**Deliberately NOT a GitHub contribution grid.** A square tinted cell says nothing about clothing. The Loom
borrows the app's own **atelier** metaphor — the whole brand (§ Brand Guide) is built from tailor's
materials — and renders wear as **actual weaving**:

```
        M   T   W   T   F   S   S          <- warp labels
       ┌─┬───┬───┬───┬───┬───┬───┬─┐
 22 Jun ╪ ╲ ╱ ╲ ╱ │ │ ╲ ╱ ╲ ╱ ╲ ╱ ╲ ╱    <- weft thread per week, brass bobbin cap on the left
 29 Jun ╪ │ ╲ ╱ ╲ ╱ · · ╲ ╱ ╲ ╱ ╲ ╱ 
  6 Jul ╪ ╲ ╱ · ╲ ╱ │ ╲ ╱ ╲ ╱ ●  ╲ ╱      <- ● basting-red knot = rescued sleeper
   ...
  7 Sep ╪ ╲ ╱ ╲ ╱ ╲ ╱ ╲ ╱ ╲ ╱ ╲ ╱ ╲ ╱    <- today
```

| Element | Meaning |
|---|---|
| **Vertical warp thread** (1 px, `LineLight`) | one per weekday, Monday→Sunday, full height |
| **Horizontal weft thread** (1 px, `GoldLight @ 30%`) | one per week, with a brass **bobbin cap** dot on the left edge |
| **The stitch at each crossing** | the wear count. Two mirrored diagonals (over-/under-stitch) whose **stroke weight grows with wears** |
| **Level 1–4** | `#E7EDE2` mist → `#B9CBA8` sage → `#7E9B64` fern → `#46703F` forest |
| **Level 4 extra** | a translucent forest **cloth patch** behind the stitch — heavy wear builds up fabric |
| **Bare crossing** | a 1 px dash — the warp/weft met, nothing was worn |
| **Pre-tracking** | a small 1.6 dp dot (distinct from a bare crossing: "no data" vs "no wear") |
| **Basting-red knot** (`#C8442C`) at top-right of a cell | **rescued** — a piece dormant 60+ days came back out that day |
| **Future cells** | empty warp only — the loom isn't woven yet |
| Tap / click | `undoDay(dayKey)` |

This is a real data-bearing visual: **green density = healthy rotation, bare warp = a dormant rail, and a
scatter of red knots = the story of a wardrobe being rescued.** The green/basting-red palette is exactly the
palette the brief specifies, and `#C8442C` + `#46703F` sit on the app's ivory `#FAF7F0` without a new
theme.

### 4.5 FEATURE 5 — the underused alarm

```kotlin
fun underused(items, events, nowMs, windowDays = UNDERUSE_DAYS /* 60 */,
              includeLowFrequencyCategories = false): List<UnderusedItem>
```

Three guards, each preventing a specific false positive:

1. **`NEW_ITEM_GRACE_DAYS = 14`** — a garment added yesterday has 0 wears by definition. Flagging it is the
   fastest way to make the alarm feel like a nag and get it dismissed forever.
2. **Low-frequency category filter** — Accessories / Bags / Watches / Jewelry are *supposed* to be worn
   rarely; a watch worn daily is strange, a watch unworn for 60 days is normal. Excluded by default,
   included in the achievement check (which asks "is *anything* dormant?").
3. **Sort: never-worn first, then oldest-dormant** — so the actionable "you bought this and never wore it"
   rows top the list.

`daysSinceLastWear` is `null` for a never-worn piece (there's no date to subtract), and the UI renders
`"Never worn · added 12 Jun"` instead of a fake "0 days".

### 4.6 FEATURE 6 — sustainability badge

Driven **only** by cost-per-wear — the owner's own proxy for how hard a garment works. No lifecycle
inventory is faked:

| CPW | Level | Badge | Tip |
|---|---|---|---|
| ≤ ₱1.00 | 4 | **Circular** | Under ₱1 per wear — this piece is earning its keep. |
| ≤ ₱3.00 | 3 | **Low-impact** | Great rotation. Keep it in the weekly mix. |
| ≤ ₱7.50 | 2 | **Breaking even** | On track — a few more wears and it pays for itself. |
| ≤ ₱20.00 | 1 | **Still settling** | Wear it 2× more this month to bring the cost down. |
| > ₱20.00 | 0 | **High turnover risk** | Rarely worn and expensive per wear — restyle it or pass it on. |
| `null` price | 2 | **Unpriced** | Add a price to score this piece's wear efficiency. |

The **wardrobe-level** badge is the **median** piece badge, not the mean — so one hero coat worn 200 times
cannot mask forty dead garments. The overall hero number stays the **average CPW of priced pieces**.

> **Honesty note on the carbon figure.** `estimatedCo2ePerWear()` returns rough planning constants
> (0.4 / 0.9 / 1.8 / 3.6 / 7.2 kg CO₂e per wear by band). These are **[ASSUMPTION]** design placeholders for
> a future, properly cited lifecycle model — they are **not** measured values and must not be presented to
> users as verified emissions data. The badge's user-facing copy therefore never quotes a CO₂ number; it
> quotes **cost per wear only**. The kg-CO₂e table is exposed for the engineering team and is deliberately
> absent from the screen.

### 4.7 FEATURE 7 — achievements

Eight, each with `progress` (0f..1f) and a human `progressLabel` so a locked badge still shows how close:

| id | Title | Unlock condition |
|---|---|---|
| `first_wear` | **First wear** | ≥ 1 wear event logged |
| `thirty_pieces` | **Wore 30 different pieces** | `COUNT(DISTINCT itemId)` ≥ 30 |
| `most_worn` | **Most-worn piece** | a single piece reaches **10 wears** |
| `full_rotation` | **Full rotation** | *every* owned piece has `timesWorn > 0` |
| `streak_7` | **Seven-day streak** | longest consecutive-day run ≥ 7 |
| `cost_crusher` | **Cost crusher** | any piece at ≤ ₱1.00 per wear |
| `rescue` | **Rescue mission** | **zero** pieces dormant 60+ days (and ≥ 1 wear) |
| `auditor` | **Wardrobe auditor** | prices entered on ≥ 80% of pieces |

`longestStreak()` walks the sorted distinct `dayKey` set and compares each key against
`+1 day` computed through `Calendar` — no `java.time`, no `ChronoUnit`, safe on API 24.

### 4.8 FEATURE 8 — headline stats

```kotlin
data class WearStats(
    val totalPieces: Int, val pricedPieces: Int, val totalWardrobeValue: Double,
    val averageCostPerWear: Double?, val weeklyWearPercentage: Double,
    val distinctPiecesWornEver: Int, val totalWearsLogged: Int, val wearsLast30d: Int,
    val streakDays: Int, val mostWorn: WardrobeItem?, val leastWorn: WardrobeItem?
)
```

| Stat | Formula | Why this formula |
|---|---|---|
| **Average cost / wear** | `mean(price_i / max(1, timesWorn_i))` over **priced** pieces only | Unpriced pieces are excluded, not treated as 0 — including them as ₱0 would silently flatter the average |
| **Total wardrobe value** | `Σ price` over priced pieces | Rendered with the count of priced pieces ("18 priced pieces") so the₱ total is never mistaken for the whole rail |
| **Weekly wear percentage** | `distinctItemsWornLast7d × 100 / totalPieces` | **Distinct pieces**, not wear events — wearing the same tee 5× is not a 5× healthier wardrobe |
| `wearsLast30d` | count of events with `wornAt >= now − 30d` | needs a time index — hence `Index("wornAt")` in §2.1 |
| `leastWorn` | min `timesWorn` among pieces older than the 14-day grace | excludes a garment that arrived this morning |

---

## 5. Sampling strategy

The brief asks for a sampling strategy; here it is at three levels — **temporal windows**, **event
sampling**, and **statistical sampling**. All three are implemented in code.

### 5.1 Temporal windows (what the user sees)

| Window | Constant | Used by | Rationale |
|---|---|---|---|
| **7 days** | `addDays(now, -7)` | weekly wear %, "worn this week" | matches how people plan outfits |
| **30 days** | `addDays(now, -30)` | `wearsLast30d`, "Still settling" tip | one outfit-rotation cycle; long enough to smooth a lazy weekend |
| **12 weeks** | `DEFAULT_WINDOW_WEEKS` | **default loom** | 84 days ≈ 2 quarters of outfits; fits a phone at 7 columns × 44 dp without horizontal scroll being the primary gesture |
| **26 weeks** | `EXTENDED_WINDOW_WEEKS` | loom toggle (`WearHistoryHeader`) | half-year trend for power users — the loom scrolls horizontally |
| **60 days** | `UNDERUSE_DAYS` | underused alarm | ≈ two full seasons of casual wear; short enough to act on, long enough to not fire on a winter coat in August |
| **14 days** | `NEW_ITEM_GRACE_DAYS` | alarm + `leastWorn` | a new garment needs a chance to enter the rotation before it is judged |
| **all time** | `eventsSince(0L)` | achievements, total value | "full rotation" and "30 pieces" are lifetime claims, not windowed ones |

The loom window is anchored to **`startOfWeek(now)`**, Monday-first (`(dow + 5) % 7`), so the newest column
is *this* week and `future = dayStart > todayStart` blanks the days still to come. Anchoring to `now - 84d`
instead would drift the weekday columns as the user opens the app, which would make the loom visually
unstable.

### 5.2 Event sampling — we do **not** sample (and why)

`eventsSince(since)` reads **every** event in the window. There is no down-sampling, no reservoir, no
`LIMIT`, because:

- the corpus is tiny — an aggressive user logs ~5 items × 3 wears/week ≈ **780 rows/year**;
- Room aggregates in SQL (`GROUP BY dayKey`, `GROUP BY itemId`), so 12 weeks × 7 days collapses to ≤ 84
  rows before it ever reaches Compose;
- down-sampling the *ledger* would make cost-per-wear wrong, which is the one number we cannot afford to
  be approximate.

The heatmap is the only thing that is *aggregated* rather than sampled — see §6.

### 5.3 Statistical sampling — synthetic corpus for validation

No real user data exists yet (the fields were dead), so the mock in §7 and the render in §8 were produced
from a **deterministic synthetic corpus**:

- **`random.seed(1709)`** — fixed, so the loom is byte-reproducible across renders;
- **12 weeks × 7 days = 84 cells**;
- weekday base probabilities `P = [0.42, 0.36, 0.40, 0.44, 0.62, 0.72, 0.50]` (Mon…Sun) — a deliberate
  **weekend-heavy** rhythm, so the loom visibly proves it is reading real day-of-week structure rather than
  producing uniform noise;
- **trend** `0.75 + 0.06·week` — a user who wears more over time, so the newest stitches are visibly denser;
- two long-tail injections: a **3-week dormant stretch** (`grid[6..8][Tue] = 0`) and **three
  `rescued` days** — so the red-knot overlay and the Sleeper alarm both have data to render.

This is a **presentation corpus only** — it feeds the UI mock, never the app. Every threshold in §4.6–4.8
is an **[ASSUMPTION]** tuned on it and must be re-fit on real usage before shipping (flagged in §9).

---

## 6. Heatmap data-aggregation logic

Five steps, all inside `WearTracker.buildLoom()` (pure, no I/O — unit-testable with plain JUnit 4):

```
1. ANCHOR      firstWeekStart = addWeeks(startOfWeek(now), -(windowWeeks - 1))
               todayStart     = startOfDay(now)

2. BUCKET      events grouped by event.dayKey  ("YYYY-MM-DD", LOCAL timezone via WearClock)

3. GRID        for w in 0..windowWeeks-1:          # w = week (oldest -> newest)
                 for d in 0..6:                    # d = 0 Mon .. 6 Sun
                   dayStart = addDays(firstWeekStart, w*7 + d)
                   wears    = events[dayKey(dayStart)].size
                   level    = 0 if 0; 1 if 1; 2 if 2; 3 if 3; else 4
                   future        = dayStart > todayStart
                   preTracking   = firstEverWear == null || dayStart < startOfDay(firstEverWear)

4. RESCUE      rescued = any event e on the day where, in that item's OWN sorted event list,
                        the previous wear was >= 60 days earlier
                        (per-item prior lookup, NOT global — see note)

5. EMIT        LoomCell(weekIndex, dayIndex, dayKey, wears, level, rescued, future, preTracking)
```

**Key correctness decisions:**

- **`dayKey` is computed at write time, in the user's local timezone**, and stored as a string. Grouping on
  a stored local-date string (rather than `strftime` over a UTC epoch) is what stops a 11pm wear in Manila
  landing on tomorrow's cell. `WearClock.dayKey()` uses `SimpleDateFormat` with `TimeZone.getDefault()`.
- **`level` saturates at 4+** — level 5, 6, 7 all render as level 4. Beyond "heavy wear" the stitch is
  already at maximum visual weight; more bands would only make the loom noisy. Raw `wears` is retained on
  the cell for tooltips.
- **`rescued` uses each item's *own* prior event**, not a global 60-day scan. A claim like "this was
  rescued" must be about *that garment's* dormancy, not the app's.
- The whole loom is **≤ 182 cells** (26 weeks), so it is computed on the main thread off a single
  `Flow` emission — no paging needed.

**Verification of the render pipeline:** the aggregation was re-implemented identically in
`make_mock.py` and rendered headless; the resulting PNG (§8) shows the planted dormant stretch as a
visible **bare-warp run** in column 3 and the three planted rescue days as **red knots** — i.e. the
aggregation logic is confirmed to be reading the data it's supposed to read, not drawing decoration.

---

## 7. UI mock — "The Wear Loom"

Rendered with matplotlib (`make_mock.py`, deterministic, seed 1709). Two views:

### 7.1 The loom component, annotated
![The Wear Loom heatmap — woven warp/weft with stitch-weight wear cells, brass bobbin caps, basting-red rescue knots](wear_heatmap_mock.png)

Read it as fabric: the **brass bobbin dot** on each row is the week's weft end; the **vertical ivory
threads** are the seven weekdays; each **green stitch** is a worn day, thickening from mist to forest as the
wear count rises; the **forest cloth patch** marks a 4+ wear day. The **bare dashes** forming a vertical run
in the third column are the planted dormant Tuesdays, and the three **red knots** are the rescued pieces.

### 7.2 The full Wear History screen
![StyleDrop Wear History screen — stat strip, Wear Loom, wardrobe-efficiency card, sleepers alarm, benchmarks row](wear_screen_mock.png)

Top to bottom: **stat strip** (avg cost/wear, wardrobe value, worn-this-week, pieces worn, most worn,
wears/30d) → **the Wear Loom** → **wardrobe efficiency** card (Low-impact, 5-segment scale) →
**Sleepers on the rail** alarm block in basting red with per-item "Wear today" actions → **Benchmarks**
achievement chips with progress bars → bottom nav with the **Wear** tab active (marked by the basting-red
underline — the app's existing active-tab motif).

Everything renders on the repo's real tokens: ivory `#FAF7F0` page, white `premiumCard` surfaces,
`LineLight` `#E7E0D2` hairlines, `GoldLight` `#B08A4F` accents, Playfair Display headings + Inter body.

---

## 8. Compile-sanity check

- **No Android SDK / Gradle wrapper JAR in the clone** (`gradlew` and `gradle-wrapper.jar` are untracked —
  only `gradle-wrapper.properties` exists), and no Android SDK in the analysis environment, so a real
  `assembleDebug` **was not run**. This is a static check only; the code is **not claimed to build**.
- **Brace/paren/bracket balance** (strings and comments stripped): `WearTracker.kt` → 0 / 0 / 0 ·
  `wear_screen.kt` → 0 / 0 / 0.
- **Symbol cross-check against the clone** — every theme/repo symbol referenced by the two files exists:
  `premiumCard` (`theme/Modifiers.kt:16`), `MutedTextLight` (`theme/Color.kt:11`), `LineLight` (:12),
  `GoldLight` (:14), `SurfaceAltLight` (:8), `InkSoftLight` (:10), `ItemCategory.emoji()`,
  `WardrobeItem`, `WardrobeDao`, `AppDatabase`. All Compose imports resolve to the BOM the repo pins;
  `drawStopIndicator` on `LinearProgressIndicator` matches Material 3 1.3+ (BOM 2024.09.00 ships 1.7.0 —
  if the team upgrades/back-ports, drop that one named argument).
- **`java.time` audit:** zero usages. All date maths is `java.util.Calendar` / `SimpleDateFormat`, correct
  for `minSdk 24` with no desugaring dependency.

---

## 9. Known gaps / [ASSUMPTION] register

| Item | Status |
|---|---|
| CPW badge bands (₱1 / ₱3 / ₱7.50 / ₱20) | **[ASSUMPTION]** tuned on synthetic data — re-fit on real usage |
| `estimatedCo2ePerWear` constants (0.4–7.2 kg) | **[ASSUMPTION]** planning placeholders, **never shown to users** |
| `UNDERUSE_DAYS = 60` / `NEW_ITEM_GRACE_DAYS = 14` | **[ASSUMPTION]** — 60 is in the brief; 14 is chosen |
| `dedupeWindowMs = 300_000` (5 min) | **[ASSUMPTION]** double-tap guard |
| Loom level saturation at 4+ | **[ASSUMPTION]** design choice for visual clarity |
| Full Gradle build | **NOT RUN** — no Android SDK in the environment |
| `ExportSchema` / schema JSON in VCS | **NOT done** — separate change, flagged in §3.1 |
| `isFavoriteItem` | Still dead. The brief scopes this agent to wear tracking; favourite toggling belongs to the wardrobe-UI agent (#4). |
| Device sync / backup of `wear_events` | Not addressed — `allowBackup="true"` still exposes the DB (see the security register in TECH_STACK.md) |
| Widget / notification for the weekly-wear stat | Not built — no notification plumbing exists in the repo |

---

*StyleDrop v2 — Wear Tracking (Agent #17). Every class name, field name, package path and colour token
above was read from the cloned commit `a53013b`; the two mock images were rendered from a deterministic
seeded corpus, not from real user data.*
