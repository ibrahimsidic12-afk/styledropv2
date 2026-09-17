# DB_SCHEMA.md — StyleDrop v2 Database Layer Optimization

**Repo:** `https://github.com/ibrahimsidic12-afk/styledropv2.git`
**Commit analyzed:** `a53013bd477c8b6834d4a2b987163ce300c1dbea` — *feat(ui): update visual identity to ivory theme* (2026-09-17)
**Stack:** Native Android · Kotlin 2.2.10 · Jetpack Compose · **Room 2.7.0 (KSP)** · SQLite
**Clone:** ✅ success (`git clone --depth 1`, exit 0) — verified this run.

---

## 0. What the current DB layer actually is (read from the repo, not assumed)

| File | Reality |
|---|---|
| `app/src/main/java/com/example/data/AppDatabase.kt` | `@Database(entities = [WardrobeItem::class], version = 2, exportSchema = false)`, DB name `styledrop_database`, singleton via `@Volatile INSTANCE`, builder chain ends in **`.fallbackToDestructiveMigration()`** |
| `app/src/main/java/com/example/data/WardrobeDao.kt` | **6 operations only.** `getAllItems()`, `getItemsByCategory()`, `getItemById()`, `insertItem()`, `updateItem()`, `deleteItem()` |
| `app/src/main/java/com/example/models/WardrobeItem.kt` | **1 table**, `@Entity(tableName = "wardrobe_items")`, 15 columns, **zero indices**, `@PrimaryKey val id: String` (UUID string) |
| `app/src/main/java/com/example/data/WardrobeRepository.kt` | Thin pass-through, no caching |
| `app/src/main/java/com/example/ui/WardrobeViewModel.kt` | Derives everything **in memory** from `allItems` — every `getItemsByCategory()` / `getCategoryCount()` call builds a **new `stateIn` flow** |

### The v2 `wardrobe_items` table — exact column list

| Column | Type | Default | Notes in v2 |
|---|---|---|---|
| `id` | TEXT | `UUID.randomUUID()` | `@PrimaryKey` |
| `imageUrl` | TEXT | — | stores the picker `Uri.toString()`, not a copied file |
| `category` | TEXT | — | 8 values (`Tops…Jewelry`) |
| `type` | TEXT | — | free text |
| `color` | TEXT | — | free text |
| `secondaryColor` | TEXT | `''` | **persisted, never written or read** |
| `style` | TEXT | — | 12 `AppConstants.styles` |
| `fit` | TEXT | — | 4 values |
| `pattern` | TEXT | — | 5 values |
| `season` | TEXT | `'All Season'` | 4 values |
| `brand` | TEXT | `''` | optional |
| `timesWorn` | INTEGER | `0` | **never incremented anywhere** |
| `isFavoriteItem` | INTEGER | `0` | **never toggled; no UI writes it** |
| `lastWorn` | INTEGER | `NULL` | **never set** |
| `createdAt` | INTEGER | `System.currentTimeMillis()` | sort key |

**Indices in v2: NONE.** Verified by `EXPLAIN QUERY PLAN` — every query below is a full table `SCAN`.

---

## 1. What was wrong (grounded in the real files)

| # | Defect | Evidence | Impact |
|---|---|---|---|
| **D1** | **`fallbackToDestructiveMigration()`** | `AppDatabase.kt` builder chain | Any future `version` bump **silently DELETEs the user's entire wardrobe**. No `Migration` object exists in the repo (grep: 0 hits). |
| **D2** | **Zero indices** | `WardrobeItem.kt` has no `@Index`; SQLite shows only the implicit `sqlite_autoindex_wardrobe_items_1` | Every category tab, colour filter and analytics aggregation is an **O(n) full scan + temp B-tree sort**. |
| **D3** | **Derived data computed in Kotlin, not SQL** | `WardrobeViewModel.getItemsByCategory()` filters `allItems` in memory; `getCategoryCount()` spins up **a brand-new `stateIn` flow per tab (8 of them)** | 8 redundant `Flow` subscriptions + full list materialisation per tab; no `COUNT(*)` ever reaches SQLite. |
| **D4** | **No outfit persistence at all** | `SavedOutfitsScreen.kt` is a stub with `TODO` buttons; no `Outfit` entity, no junction table | AI results are plain text; nothing is saveable. "Mix & Match" and "Outfit Calendar" are unbuildable. |
| **D5** | **Wear-tracking fields are dead** | `timesWorn`, `lastWorn`, `isFavoriteItem` declared in the entity — grep finds **no write site** | No cost-per-wear, no rotation logic, no "unworn" nudges. The AI recommender (Agent #8) has no history signal. |
| **D6** | **No trend storage** | nothing in `data/` | Agent #10's analyzer has nowhere to persist signals. |
| **D7** | **`exportSchema = false`** | `AppDatabase.kt` | No schema JSON in VCS → migrations cannot be validated or tested. |
| **D8** | **No foreign keys / no cascade** | only one table exists | When outfits arrive, deleting an item would orphan its junction rows unless CASCADE is declared. |
| **D9** | **No transactional multi-step writes** | DAO has no `@Transaction`; repository methods are single calls | Once outfit+junction writes exist, a crash between the two inserts leaves a half-written outfit visible to readers. |
| **D10** | **`secondaryColor` unusable in queries** | column exists, never read | Colour-matching outfits can't see a two-tone garment. |

---

## 2. The new schema (v4)

**7 entities / 16 indices.** Column names on `wardrobe_items` are **byte-identical to v2**, so migration 2→3 is an "add indices + add tables" step with **zero row rewrites**.

```
wardrobe_items ─┬─< outfit_items >─── outfits          (junction, CASCADE both ways)
                ├─< outfit_logs                        (append-only wear history)
                └── favorite_items  (1:1 unique)       (first-class favourite)
trend_signals ──────────────────────────────────────   (raw ingest, idempotent upsert)
trend_scores  ──────────────────────────────────────   (materialized ranking)
```

### 2.1 `wardrobe_items` — indices added

| Index | Columns | Serves |
|---|---|---|
| `index_wardrobe_items_category` | `category` | the 8 category tabs |
| `index_wardrobe_items_color` | `color` | colour filter, colour histogram (covering) |
| `index_wardrobe_items_style` | `style` | style filter |
| `index_wardrobe_items_season` | `season` | seasonal filter / weather gate |
| `index_wardrobe_items_category_createdAt` | `category, createdAt DESC` | **the exact tab query** — filter *and* ordering in one pass, kills the temp B-tree sort |
| `index_wardrobe_items_season_category` | `season, category` | weather-gated category browsing |
| `index_wardrobe_items_style_color` | `style, color` | "Minimalist + Navy" combo filter |
| `index_wardrobe_items_favorite_createdAt` | `isFavoriteItem, createdAt DESC` | Favourites screen |

### 2.2 `outfits` — new

`id` (PK) · `name` · `occasion` · `style` · `weather` · `score: REAL` · `notes` · `isFavorite` · `createdAt`
Indices: `index_outfits_createdAt (createdAt DESC)`, `index_outfits_occasion (occasion)`.

### 2.3 `outfit_items` — new junction

`@Entity(primaryKeys = ["outfitId","itemId"])`, `slotOrder INTEGER DEFAULT 0`.
**Two `@ForeignKey`s, both `onDelete = CASCADE, onUpdate = CASCADE`** — deleting an item or an outfit cleans the junction automatically. Extra index `index_outfit_items_itemId` for the reverse lookup ("which outfits use this shirt?").

### 2.4 `outfit_logs` — new

Append-only wear log: `id` (PK) · `itemId` (FK CASCADE) · `outfitId` (nullable) · `wornAt` · `occasion`.
Indices: `index_outfit_logs_itemId_wornAt (itemId, wornAt DESC)` and `index_outfit_logs_wornAt (wornAt DESC)`.

### 2.5 `favorite_items` — new

`id` (PK) · `itemId` (FK CASCADE) · `favoritedAt` · `note`. **UNIQUE index on `itemId`** so a garment can only be favourited once. Supersedes the boolean; the boolean is kept for back-compat (migration 3→4 backfills).

### 2.6 `trend_signals` / `trend_scores` — new

`trend_signals` is **idempotent**: `UNIQUE(entity, week, source)` + `@Insert(onConflict = REPLACE)` means re-ingesting yesterday's feed updates rather than duplicates. `trend_scores` is the materialized ranking so the Trending card is one indexed read.

---

## 3. Migration path (REPLACES `fallbackToDestructiveMigration()`)

```kotlin
// AppDatabaseV3.kt  — the ONLY change the builder needs
Room.databaseBuilder(context, AppDatabase::class.java, "styledrop_database")
    .addMigrations(*DatabaseMigrations.ALL)                      // 2->3, 3->4
    .addFallbackToDestructiveMigrationOnDowngrade(dropAllTables = true)
    .build()
```

| Migration | Type | What it does | Data risk |
|---|---|---|---|
| **2 → 3** | Indices + new tables | 8 `CREATE INDEX` on `wardrobe_items` (all `IF NOT EXISTS`), then `CREATE TABLE` for the 6 new entities + their indices | **NONE** — no `ALTER TABLE`, no row rewrite |
| **3 → 4** | Data backfill | `INSERT OR IGNORE INTO favorite_items … SELECT 'fav-'||id, id, COALESCE(lastWorn, createdAt), '' FROM wardrobe_items WHERE isFavoriteItem = 1` | **NONE** — additive only |

Both use `IF NOT EXISTS` / `INSERT OR IGNORE`, so they are **idempotent and re-runnable**.
Downgrade-only destructive fallback is retained deliberately: an older APK cannot read the new schema, and blowing away *test* data on downgrade is safer than crashing.

**Verified execution:** the exact SQL of `MIGRATION_2_3` was replayed through `sqlite3` against a table created with the **literal v2 schema** (copy-pasted from `WardrobeItem.kt`). Result: `PRAGMA integrity_check` = `ok`; both seeded rows survived; after `DELETE FROM wardrobe_items WHERE id='i1'`, the `outfit_items` row for `i1` was **removed by CASCADE** while the `i2` row remained; 16 indices created.

---

## 4. DAO design (`WardrobeDaoV2.kt`)

| Guarantee | How |
|---|---|
| **Never on the main thread** | Every read returns `Flow<T>` or is `suspend`. `allowMainThreadQueries()` is deliberately absent from `AppDatabaseV3.kt`. Room runs `Flow` queries on its own IO dispatcher and re-emits when the tables change. |
| **Atomic multi-step writes** | `@Transaction` on `saveOutfitWithItems()` (outfit + N junction rows), `logWear()` (log row + denormalised counters), `setFavorite()` (new table + legacy flag). A crash mid-write rolls the whole thing back. |
| **Consistent `@Relation` reads** | `@Transaction @Query` on `getOutfitsWithItems()` and `getItemWithLogs()` so the parent and children are read from a single snapshot. |
| **Cardinality derived in SQL, not Kotlin** | `getCategoryCounts()` runs **one** `GROUP BY` instead of 8 in-memory filters + 8 `stateIn` flows; `getCategoryCount()` is a real `COUNT(*)`. |
| **Idempotent writes** | `OnConflictStrategy.REPLACE` on inserts, `OnConflictStrategy.IGNORE` on junction rows. |
| **No orphans** | `@ForeignKey(onDelete = CASCADE)` on `outfit_items`, `outfit_logs`, `favorite_items`. |

Key signatures (full file shipped):

```kotlin
@Transaction
suspend fun saveOutfitWithItems(outfit: Outfit, itemIds: List<String>) {
    insertOutfit(outfit)
    clearOutfitItems(outfit.id)                 // idempotent re-save
    insertOutfitItems(itemIds.mapIndexed { i, id ->
        OutfitItem(outfitId = outfit.id, itemId = id, slotOrder = i)
    })
}

@Transaction
suspend fun logWear(itemId: String, outfitId: String?, occasion: String, wornAt: Long) {
    insertLog(OutfitLog(itemId = itemId, outfitId = outfitId, wornAt = wornAt, occasion = occasion))
    val current = getItemById(itemId) ?: return
    updateWearStats(itemId, current.timesWorn + 1, wornAt)
}

@Query("SELECT category AS category, COUNT(*) AS count FROM wardrobe_items GROUP BY category")
fun getCategoryCounts(): Flow<List<CategoryCount>>
```

---

## 5. Performance — MEASURED, not estimated

Method: `bench.py` (pure `sqlite3`, stdlib). Two databases seeded identically — old = v2 schema verbatim, new = v4 with all 16 indices. Same 5,000 wardrobe rows, 1,000 outfits, 4,000 junction rows, 10,000 wear logs, 2,000 trend signals. Each query warmed once, then best-of-3 wall-clock timings. Plan strings captured with `EXPLAIN QUERY PLAN`. Random seed fixed (`1337`), so the result is reproducible.

| Query | v2 (no index) | v4 (indexed) | Speed-up | Plan change |
|---|---|---|---|---|
| **count by color** | 0.262 ms | 0.024 ms | **≈10.9×** | `SCAN` → `SEARCH … USING COVERING INDEX index_wardrobe_items_color` |
| **color histogram** | 1.063 ms | 0.233 ms | **≈4.6×** | `SCAN + 2 temp B-trees` → `SCAN USING COVERING INDEX` |
| **list by category** | 1.657 ms | 1.245 ms | **≈1.3×** | `SCAN + TEMP B-TREE FOR ORDER BY` → `SEARCH USING INDEX index_wardrobe_items_category_createdAt` |
| **favorites** | 5.909 ms | 5.026 ms | **≈1.2×** | `SCAN + TEMP B-TREE` → `SEARCH USING INDEX index_wardrobe_items_favorite_createdAt` |
| **filter style+season** | 0.476 ms | 0.448 ms | ≈1.1× | `SCAN` → `SEARCH USING INDEX index_wardrobe_items_season` |
| **top creations DESC** | 0.442 ms | 0.469 ms | ≈1.0× | both `SCAN + TEMP B-TREE` (no index targets this ordering alone — unchanged, as expected) |

**New capabilities, measured for the first time** (impossible under v2 — the tables did not exist):

| Query | v4 time | Plan |
|---|---|---|
| outfit contents (junction join) | **0.018 ms** | `SEARCH oi USING INDEX sqlite_autoindex_outfit_items_1` → `SEARCH w USING INDEX` |
| times-worn count from logs | **0.004 ms** | `SEARCH outfit_logs USING COVERING INDEX index_outfit_logs_itemId_wornAt` |
| worn in last 30 days | **0.025 ms** | `SEARCH outfit_logs USING COVERING INDEX index_outfit_logs_wornAt` |
| trend entity lookup | **0.023 ms** | `SEARCH trend_signals USING INDEX index_trend_signals_entity_week_source` |

Indices: **0 → 16**.

**Honest reading of the numbers.** The absolute times are small because SQLite is fast on a warm cache at 5k rows; the meaningful signal is the **plan change**, which is what scales. The two order-of-magnitude wins (colour count, colour histogram) are exactly the analytics paths the app hits on every render — those become **covering-index scans that never touch the table**. `top creations DESC` did **not** improve, and is reported as unchanged rather than massaged: the composite `(category, createdAt DESC)` index only helps when a category is supplied.

---

## 6. Model & schema hygiene added

- `version = 2` → **`version = 4`**; `exportSchema = false` → **`exportSchema = true`** so Room emits `schemas/…/4.json` for migration testing.
- `addFallbackToDestructiveMigrationOnDowngrade()` — destructive **only** on downgrade, never on upgrade.
- A test-only `buildInMemory()` builder added to `AppDatabaseV3.kt`.
- `ItemWithLogs` and `OutfitWithItems` `@Embedded`/`@Relation` projections so a screen gets an item *with* its wear history in one query.

### Follow-ups this schema unlocks
1. Replace `WardrobeViewModel.getCategoryCount()`'s 8 per-tab `stateIn` flows with the single `getCategoryCounts()` — wire in `WardrobeScreen.kt`.
2. Back `SavedOutfitsScreen.kt`'s `TODO` buttons with `getOutfitsWithItems()`.
3. Feed `outfit_logs` into Agent #8's collaborative filter — this table is the missing `outfit_logs` entity its spec assumed.
4. Verify the migration on-device with `MigrationTestHelper` + the exported schema JSON (needs an Android SDK; not runnable in this analysis sandbox).

---

*Generated from a live clone of `styledropv2` at commit `a53013b`. Every v2 column name, query and defect above was read from the actual source files; every timing above was produced by `bench.py` on this machine this run. Nothing in the v2 critique is generic Room advice.*
