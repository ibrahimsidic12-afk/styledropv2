package com.example.onboarding

/*
 * =============================================================================
 *  styledrop — ONBOARDING + STYLE QUIZ  (single-file, drop-in spec)
 *  Path: app/src/main/java/com/example/onboarding/OnboardingScreens.kt
 *
 *  Written against the LIVE repo (styledropv2 @ a53013b) so it drops in rather
 *  than floats free — every name below was read out of the actual source:
 *
 *    package root      com.example                          (namespace = "com.example")
 *    theme             com.example.ui.theme                 (Color.kt / Type.kt / Modifiers.kt)
 *    wardrobe entity   com.example.models.WardrobeItem      (15 fields + ItemCategory object)
 *    taxonomy          com.example.models.AppConstants      (styles / colorPreferences / fits ...)
 *    persistence       com.example.data.AppDatabase + WardrobeRepository + WardrobeDao
 *    JSON              Moshi 1.15.2                         (already a dependency)
 *    nav               androidx.navigation.compose, string routes, startDestination = "login"
 *
 *  NEW dependency — ONE line. The catalog entry already exists in
 *  gradle/libs.versions.toml (datastorePreferences = "1.1.7") but the
 *  implementation line is commented out in app/build.gradle.kts:
 *
 *      implementation(libs.androidx.datastore.preferences)
 *
 *  NEW manifest entries (CAMERA / location / photo, + FileProvider) are listed
 *  verbatim in ONBOARDING.md §7. Nothing else changes.
 *
 *  Contents
 *    0 · brand accents (local — see BRAND_GUIDE.md §2)
 *    1 · DataStore keys + schema
 *    2 · profile model  (StyleProfile -> ProfileSeed, "later profile_seeds")
 *    3 · domain models  (options, quiz, starter wardrobe, permission state)
 *    4 · content        (every string in this file, in brand voice)
 *    5 · engine + view model
 *    6 · shared UI kit
 *    7 · the five declaration steps
 *    8 · the 12-question style quiz  +  profile reveal
 *    9 · photo tutorial (drag-and-drop on a virtual hanger)
 *   10 · first-item tutorial with the starter wardrobe seed
 *   11 · permission flows, done right
 *   12 · flow host + navigation patch
 * =============================================================================
 */

import android.Manifest
import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.ArrowForward
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.LocationOn
import androidx.compose.material.icons.rounded.PhotoCamera
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInParent
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.room.Entity
import androidx.room.PrimaryKey
import coil.compose.AsyncImage
import com.example.data.AppDatabase
import com.example.data.WardrobeRepository
import com.example.models.AppConstants
import com.example.models.ItemCategory
import com.example.models.WardrobeItem
import com.example.ui.theme.premiumCard
import com.squareup.moshi.JsonClass
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlin.math.roundToInt


/* ═══════════════════════════════════════════════════════════════════════════
 * 0 · BRAND ACCENTS  (local to this file)
 *     These four are BRAND_GUIDE.md §2 additions that are NOT yet in
 *     ui/theme/Color.kt. Declared locally so this file compiles today; when the
 *     brand tokens land in Color.kt, delete these and import instead.
 * ═══════════════════════════════════════════════════════════════════════════ */

private val BastingRed  = Color(0xFFC8442C)   // the signature accent — CTAs, selection
private val MuslinIvorie = Color(0xFFFAF7F0)  // matches BackgroundLight in Color.kt
private val CanvasLine  = Color(0xFFE7E0D2)   // matches LineLight in Color.kt
private val TwillGrey   = Color(0xFF837B6C)   // matches MutedTextLight in Color.kt
private val AgedBrass   = Color(0xFFB08A4F)   // matches GoldLight in Color.kt
private val RivieraBlue = Color(0xFF46688C)
private val FieldMoss   = Color(0xFF77754E)
private val MulledWine  = Color(0xFF7A3E4A)


/* ═══════════════════════════════════════════════════════════════════════════
 * 1 · DATASTORE KEYS + SCHEMA
 *     Store name: styledrop_onboarding  (separate from the Room DB, so a
 *     destructive Room migration can never wipe the user's style profile).
 *     Every key is written the moment the user taps, so a process kill
 *     mid-onboarding resumes exactly where they left off.
 * ═══════════════════════════════════════════════════════════════════════════ */

object OnboardingKeys {
    const val STORE_NAME = "styledrop_onboarding"

    // flow control
    val COMPLETED             = booleanPreferencesKey("onboarding_completed")
    val SKIPPED               = booleanPreferencesKey("onboarding_skipped")
    val STAGE                 = stringPreferencesKey("onboarding_stage")
    val DECLARATION_PAGE      = intPreferencesKey("onboarding_declaration_page")
    val QUIZ_INDEX            = intPreferencesKey("onboarding_quiz_index")

    // step 1 — the measurements
    val GENDER_LENS           = stringPreferencesKey("profile_gender_lens")
    val CLIMATE               = stringPreferencesKey("profile_climate")
    val HEMISPHERE            = stringPreferencesKey("profile_hemisphere")
    val LIFESTYLE             = stringPreferencesKey("profile_lifestyle_csv")
    val GOALS                 = stringPreferencesKey("profile_goals_csv")

    // quiz output
    val QUIZ_ANSWERS          = stringPreferencesKey("quiz_answers_csv")
    val TOP_STYLES            = stringPreferencesKey("profile_top_styles_csv")
    val STYLE_SCORES          = stringPreferencesKey("profile_style_scores_csv")
    val PALETTE               = stringPreferencesKey("profile_palette_csv")
    val FORMALITY_BAND        = stringPreferencesKey("profile_formality_band")
    val PROFILE_JSON          = stringPreferencesKey("profile_json")
    val COMPLETED_AT          = longPreferencesKey("profile_completed_at")
    val SEEDED_FROM_SKIP      = booleanPreferencesKey("profile_seeded_from_skip")

    // tutorials
    val PHOTO_TUTORIAL_DONE   = booleanPreferencesKey("tutorial_photo_done")
    val FIRST_ITEM_DONE       = booleanPreferencesKey("tutorial_first_item_done")
    val SEED_INSTALLED        = booleanPreferencesKey("seed_wardrobe_installed")
}

val Context.onboardingStore: DataStore<Preferences> by preferencesDataStore(name = OnboardingKeys.STORE_NAME)


object OnboardingStageName {
    const val DECLARATIONS = "declarations"
    const val QUIZ         = "quiz"
    const val REVEAL       = "reveal"
    const val PHOTO        = "photo_tutorial"
    const val FIRST_ITEM   = "first_item_tutorial"
    const val PERMISSIONS  = "permissions"
}

enum class OnboardingStage { DECLARATIONS, QUIZ, REVEAL, PHOTO_TUTORIAL, FIRST_ITEM, PERMISSIONS }


/* ═══════════════════════════════════════════════════════════════════════════
 * 2 · PROFILE MODEL
 *     StyleProfile is the onboarding's single output object. It lives in
 *     DataStore now; ProfileSeed is its future home in Room ("later
 *     profile_seeds") — the mapper between them is StyleProfile.toSeed().
 * ═══════════════════════════════════════════════════════════════════════════ */

@JsonClass(generateAdapter = false)   // serialized by Moshi's KotlinJsonAdapterFactory
data class StyleProfile(
    val genderLens: String,
    val climate: String,
    val hemisphere: String,
    val lifestyle: List<String>,
    val goals: List<String>,
    /** questionId -> lookId, exactly as tapped. */
    val quizAnswers: Map<String, String>,
    /** style key -> accumulated weight (keys are AppConstants.styles entries). */
    val styleScores: Map<String, Int>,
    /** top 3, descending — the headline of the reveal screen. */
    val topStyles: List<String>,
    /** AppConstants.colorPreferences entries, descending. */
    val paletteAffinity: List<String>,
    /** "Relaxed" | "Balanced" | "Sharp" */
    val formalityBand: String,
    val seededFromSkip: Boolean,
    val completedAt: Long
) {
    fun toSeed(): ProfileSeed = ProfileSeed(
        genderLens = genderLens,
        climate = climate,
        hemisphere = hemisphere,
        lifestyleCsv = lifestyle.joinToString("|"),
        goalsCsv = goals.joinToString("|"),
        topStylesCsv = topStyles.joinToString("|"),
        paletteCsv = paletteAffinity.joinToString("|"),
        formalityBand = formalityBand,
        styleScoresCsv = styleScores.entries.joinToString("|") { "${it.key}=${it.value}" },
        seededFromSkip = seededFromSkip,
        createdAt = completedAt
    )
}

/**
 * The Room table this profile is eventually promoted to. Declared here so the
 * shape ships with the onboarding spec; add `ProfileSeed::class` to
 * AppDatabase.entities() when the sync pass lands (that IS a schema bump —
 * do it after Agent #06's real Migration replaces fallbackToDestructiveMigration).
 */
@Entity(tableName = "profile_seeds")
data class ProfileSeed(
    @PrimaryKey val id: String = "local",
    val genderLens: String,
    val climate: String,
    val hemisphere: String,
    val lifestyleCsv: String,
    val goalsCsv: String,
    val topStylesCsv: String,
    val paletteCsv: String,
    val formalityBand: String,
    val styleScoresCsv: String,
    val seededFromSkip: Boolean,
    val createdAt: Long,
    val syncedAt: Long? = null
)


/* ═══════════════════════════════════════════════════════════════════════════
 * 3 · DOMAIN MODELS
 * ═══════════════════════════════════════════════════════════════════════════ */

/** A single tappable choice on a declaration step. */
data class OnboardingOption(
    val id: String,
    val title: String,
    val caption: String,
    val emoji: String,
    val palette: List<Color>
)

/** One tap-target inside a quiz question. */
data class QuizLook(
    val id: String,
    val label: String,
    val caption: String,
    /** Rendered as a look plate when photoUrl is null — zero bundled assets needed. */
    val palette: List<Color>,
    /** Optional CDN photo (Coil). Falls back to the plate when null or on error. */
    val photoUrl: String? = null,
    /** style key -> weight, keys drawn from AppConstants.styles. */
    val styles: Map<String, Int>,
    /** palette key -> weight, keys drawn from AppConstants.colorPreferences. */
    val paletteWeights: Map<String, Int>,
    /** 1 (very relaxed) .. 5 (very sharp) */
    val formality: Int
)

data class QuizQuestion(
    val id: String,
    val prompt: String,
    val helper: String,
    val eyebrow: String,
    val looks: List<QuizLook>
)

/** One row of the sample wardrobe the first-item tutorial seeds. */
data class SeedItem(
    val category: String,     // an ItemCategory constant
    val type: String,
    val colorName: String,    // an AppConstants.colorPreferences entry
    val colorHex: String,     // what lands in WardrobeItem.color
    val style: String,        // an AppConstants.styles entry
    val fit: String,          // an AppConstants.fits entry
    val pattern: String,      // an AppConstants.patterns entry
    val season: String,       // an AppConstants.seasons entry
    val brand: String,
    val note: String          // the one-line lesson this item teaches
)

/** Everything the flow renders from. Held in the ViewModel, mirrored to DataStore. */
data class OnboardingUiState(
    val stage: OnboardingStage = OnboardingStage.DECLARATIONS,
    val declarationPage: Int = 0,
    val genderLens: String? = null,
    val climate: String? = null,
    val hemisphere: String = "north",
    val lifestyle: Set<String> = emptySet(),
    val goals: Set<String> = emptySet(),
    val quizAnswers: Map<String, String> = emptyMap(),
    val quizIndex: Int = 0,
    val profile: StyleProfile? = null,
    val photoTutorialDone: Boolean = false,
    val firstItemTutorialDone: Boolean = false,
    val seedInstalled: Boolean = false,
    val loading: Boolean = true
) {
    val deckComplete: Boolean
        get() = genderLens != null && climate != null && lifestyle.isNotEmpty() && goals.isNotEmpty()

    val currentQuestion: QuizQuestion?
        get() = OnboardingContent.quiz.getOrNull(quizIndex)

    val answeredCount: Int get() = quizAnswers.size
    val quizSize: Int get() = OnboardingContent.quiz.size
    val quizComplete: Boolean get() = answeredCount == quizSize
}

/** Three-state permission model — "done right" means never a bare true/false. */
enum class PermissionUiState { NOT_APPLICABLE, NOT_ASKED, GRANTED, DENIED, DENIED_FOREVER }

class PermissionController(
    val state: PermissionUiState,
    val request: () -> Unit,
    val openSettings: () -> Unit
)


/* ═══════════════════════════════════════════════════════════════════════════
 * 4 · CONTENT — every user-facing string in this file lives here, in the
 *     styledrop voice (BRAND_GUIDE.md §4: sartorial, precise, warm. Sentence
 *     case, never ALL CAPS, at most one exclamation point per screen).
 * ═══════════════════════════════════════════════════════════════════════════ */

object OnboardingContent {

    // ── 4.1 declarations ────────────────────────────────────────────────────
    val genderLens = listOf(
        OnboardingOption("women", "Women's fit", "Cut, drape and sizing for a women's line.",
            "👚", listOf(MulledWine, Color(0xFFE8C4B8), MuslinIvorie)),
        OnboardingOption("men", "Men's fit", "Cut, drape and sizing for a men's line.",
            "👔", listOf(Color(0xFF1B1A17), RivieraBlue, MuslinIvorie)),
        OnboardingOption("everything", "Show me everything", "Both rails, no filter. Some of the best looks cross over.",
            "🧵", listOf(AgedBrass, FieldMoss, MuslinIvorie)),
        OnboardingOption("unspecified", "Rather not say", "We'll cut for an open, unisex fitting.",
            "○", listOf(TwillGrey, CanvasLine, MuslinIvorie))
    )

    val climates = listOf(
        OnboardingOption("tropical", "Hot all year", "Tropical — heat, humidity, sudden rain.",
            "🌴", listOf(Color(0xFF4E7A5A), Color(0xFFE8C4B8), MuslinIvorie)),
        OnboardingOption("warm", "Warm, mild winters", "Light layers carry you through January.",
            "🌤", listOf(AgedBrass, Color(0xFFC19A6B), MuslinIvorie)),
        OnboardingOption("temperate", "Four real seasons", "A coat, a tee and everything between.",
            "🍂", listOf(FieldMoss, MulledWine, MuslinIvorie)),
        OnboardingOption("cold", "Long, hard winters", "Outerwear is the headline, not an afterthought.",
            "❄️", listOf(RivieraBlue, Color(0xFFF3EEE2), MuslinIvorie))
    )

    val hemispheres = listOf("north" to "Northern hemisphere", "south" to "Southern hemisphere")

    val lifestyle = listOf(
        OnboardingOption("student", "Student", "Lectures, campus, a lot of walking.", "🎒", listOf(RivieraBlue, Color(0xFFE8C4B8), MuslinIvorie)),
        OnboardingOption("office", "Office & meetings", "Rooms that expect a collar.", "💼", listOf(Color(0xFF1B1A17), RivieraBlue, MuslinIvorie)),
        OnboardingOption("creative", "Creative studio", "Where the brief is loose and the look is not.", "🎨", listOf(MulledWine, FieldMoss, MuslinIvorie)),
        OnboardingOption("remote", "Working from home", "Comfort that still holds a line.", "🏡", listOf(Color(0xFFC19A6B), MuslinIvorie, CanvasLine)),
        OnboardingOption("onfeet", "On my feet all day", "Shifts, studios, sites. Shoes matter most.", "👟", listOf(FieldMoss, TwillGrey, MuslinIvorie)),
        OnboardingOption("travel", "Travel often", "One carry-on, five climates.", "✈️", listOf(RivieraBlue, AgedBrass, MuslinIvorie)),
        OnboardingOption("social", "Social & events", "Dinners, parties, openings.", "🥂", listOf(MulledWine, AgedBrass, MuslinIvorie)),
        OnboardingOption("active", "Gym & outdoors", "Movement first, style second.", "🏃", listOf(Color(0xFF4E7A5A), Color(0xFF1B1A17), MuslinIvorie))
    )

    val goals = listOf(
        OnboardingOption("wearmore", "Dress better with what I own", "The rail is fuller than the rotation.", "♻️", listOf(FieldMoss, MuslinIvorie)),
        OnboardingOption("shopbetter", "Buy less, choose better", "Fewer pieces, sharper decisions.", "🧷", listOf(AgedBrass, MuslinIvorie)),
        OnboardingOption("capsule", "Build a capsule", "A line of pieces that all speak to each other.", "📐", listOf(Color(0xFF1B1A17), MuslinIvorie)),
        OnboardingOption("variety", "Stop repeating outfits", "Same rail, new combinations.", "🔀", listOf(RivieraBlue, MuslinIvorie)),
        OnboardingOption("signature", "Find my signature", "One look that arrives before you do.", "✒️", listOf(MulledWine, MuslinIvorie)),
        OnboardingOption("work", "Look sharper at work", "Confidence with a collar on it.", "📎", listOf(RivieraBlue, MuslinIvorie)),
        OnboardingOption("travel", "Pack lighter", "A carry-on that dresses you for a week.", "🧳", listOf(AgedBrass, MuslinIvorie)),
        OnboardingOption("rotation", "Wear what I forget", "Bring the back of the rail forward.", "🔁", listOf(TwillGrey, MuslinIvorie))
    )

    // ── 4.2 the quiz — 12 questions, 40 looks ───────────────────────────────
    private fun look(
        id: String, label: String, caption: String, palette: List<Color>,
        styles: Map<String, Int>, paletteWeights: Map<String, Int>, formality: Int,
        photoUrl: String? = null
    ) = QuizLook(id, label, caption, palette, photoUrl, styles, paletteWeights, formality)

    val quiz: List<QuizQuestion> = listOf(

        // Q1 — the brief's own example
        QuizQuestion(
            id = "q01_brunch", eyebrow = "Sunday, 11 a.m.",
            prompt = "Pick the look you'd wear to brunch.",
            helper = "The table is outside. The coffee is good. Go with your gut.",
            looks = listOf(
                look("a", "Soft and easy",
                    "Washed linen shirt, wide trousers, clean white sneakers.",
                    listOf(Color(0xFFF3EEE2), Color(0xFFC19A6B), Color(0xFFFFFFFF)),
                    mapOf("Minimalist" to 3, "Casual" to 2), mapOf("Beige" to 3, "White" to 2), 2),
                look("b", "Put together",
                    "Knitted polo, tailored chinos, suede loafers.",
                    listOf(Color(0xFF1B1A17), Color(0xFF7A3E4A), Color(0xFFC19A6B)),
                    mapOf("Old Money" to 3, "Smart Casual" to 2), mapOf("Navy" to 2, "Brown" to 3), 4),
                look("c", "Loud on purpose",
                    "Oversized graphic tee, baggy denim, chunky trainers.",
                    listOf(Color(0xFF1B1A17), Color(0xFF46688C), Color(0xFFB08A4F)),
                    mapOf("Streetwear" to 3, "Y2K" to 2), mapOf("Black" to 3, "Gray" to 1), 1),
                look("d", "Quietly different",
                    "Cropped technical jacket, wide pleated trousers, minimal runners.",
                    listOf(Color(0xFF35322A), Color(0xFF77754E), Color(0xFF1B1A17)),
                    mapOf("Techwear" to 3, "Minimalist" to 2), mapOf("Olive" to 3, "Black" to 2), 3)
            )
        ),

        QuizQuestion(
            id = "q02_work", eyebrow = "Monday, 9 a.m.",
            prompt = "What are you wearing into the office?",
            helper = "However your week actually looks.",
            looks = listOf(
                look("a", "A full suit, and it fits",
                    "Shoulders clean, trousers breaking once.",
                    listOf(Color(0xFF1B1A17), Color(0xFF46688C), Color(0xFFFFFFFF)),
                    mapOf("Formal" to 3, "Old Money" to 2), mapOf("Navy" to 3, "White" to 2), 5),
                look("b", "Soft tailoring",
                    "Unstructured blazer, knit, wide trousers.",
                    listOf(Color(0xFF7A3E4A), Color(0xFFC19A6B), Color(0xFFF3EEE2)),
                    mapOf("Smart Casual" to 3, "Minimalist" to 2), mapOf("Brown" to 3, "Beige" to 2), 4),
                look("c", "Clean and unfussy",
                    "Fine knit, straight leg, plain leather shoes.",
                    listOf(Color(0xFF837B6C), Color(0xFF1B1A17), Color(0xFFF3EEE2)),
                    mapOf("Minimalist" to 4, "Smart Casual" to 2), mapOf("Gray" to 3, "Black" to 2), 4),
                look("d", "Nobody dressed me",
                    "Heavy hoodie, cargo trousers, the good trainers.",
                    listOf(Color(0xFF1B1A17), Color(0xFF77754E), Color(0xFF837B6C)),
                    mapOf("Streetwear" to 3, "Grunge" to 2), mapOf("Black" to 3, "Olive" to 2), 1)
            )
        ),

        QuizQuestion(
            id = "q03_evening", eyebrow = "Saturday, 8 p.m.",
            prompt = "A friend's birthday dinner. Your move.",
            helper = "Somewhere with low light and a long menu.",
            looks = listOf(
                look("a", "All black, all business",
                    "Slim roll-neck, dark trousers, sharp boots.",
                    listOf(Color(0xFF1B1A17), Color(0xFF16150F), Color(0xFF7A3E4A)),
                    mapOf("Minimalist" to 3, "Old Money" to 2), mapOf("Black" to 4), 4),
                look("b", "Silk somewhere",
                    "Slip dress or open shirt, heels or loafers.",
                    listOf(Color(0xFF7A3E4A), Color(0xFFE8C4B8), Color(0xFF1B1A17)),
                    mapOf("Formal" to 3, "Vintage" to 2), mapOf("Brown" to 2, "Beige" to 2, "Black" to 1), 5),
                look("c", "Denim and a good jacket",
                    "Selvedge denim, leather jacket, boots.",
                    listOf(Color(0xFF46688C), Color(0xFF1B1A17), Color(0xFFC19A6B)),
                    mapOf("Vintage" to 3, "Grunge" to 2), mapOf("Navy" to 2, "Black" to 3), 3),
                look("d", "Whatever's loudest",
                    "Metallic top, straight jeans, platform shoes.",
                    listOf(Color(0xFFE8C4B8), Color(0xFF46688C), Color(0xFFB08A4F)),
                    mapOf("Y2K" to 4, "Streetwear" to 1), mapOf("Gray" to 2, "White" to 2), 2)
            )
        ),

        QuizQuestion(
            id = "q04_weekend", eyebrow = "Two free days",
            prompt = "Which weekend uniform feels right?",
            helper = "No plans, no audience.",
            looks = listOf(
                look("a", "Sweats and good socks",
                    "Matching set, heavy cotton, worn trainers.",
                    listOf(Color(0xFF837B6C), Color(0xFFF3EEE2), Color(0xFF1B1A17)),
                    mapOf("Casual" to 3, "Sporty" to 2), mapOf("Gray" to 3, "White" to 1), 1),
                look("b", "Denim on denim",
                    "Chore jacket, straight jeans, cap.",
                    listOf(Color(0xFF46688C), Color(0xFFC19A6B), Color(0xFF1B1A17)),
                    mapOf("Vintage" to 3, "Casual" to 2), mapOf("Navy" to 3, "Brown" to 2), 2),
                look("c", "Technical and tidy",
                    "Shell jacket, tapered cargos, trail shoes.",
                    listOf(Color(0xFF35322A), Color(0xFF77754E), Color(0xFF837B6C)),
                    mapOf("Techwear" to 4, "Sporty" to 2), mapOf("Olive" to 4), 2),
                look("d", "Ribbed knit and wide denim",
                    "Knit vest, high-waist, small shoulder bag.",
                    listOf(Color(0xFFF1ECE1), Color(0xFF46688C), Color(0xFFE8C4B8)),
                    mapOf("Korean" to 3, "Minimalist" to 2), mapOf("White" to 3, "Beige" to 2), 3)
            )
        ),

        QuizQuestion(
            id = "q05_outerwear", eyebrow = "First cold morning",
            prompt = "Which coat leaves the rail?",
            helper = "One coat, the whole season.",
            looks = listOf(
                look("a", "Wool overcoat",
                    "Camel, knee length, belted or not.",
                    listOf(Color(0xFFC19A6B), Color(0xFF1B1A17), Color(0xFFF3EEE2)),
                    mapOf("Old Money" to 4, "Formal" to 2), mapOf("Beige" to 3, "Brown" to 2), 5),
                look("b", "Puffer, properly big",
                    "Matte shell, high collar, functional.",
                    listOf(Color(0xFF1B1A17), Color(0xFF35322A), Color(0xFF837B6C)),
                    mapOf("Streetwear" to 3, "Techwear" to 2), mapOf("Black" to 4), 2),
                look("c", "Leather, broken in",
                    "Moto cut, worn edges, ages well.",
                    listOf(Color(0xFF1B1A17), Color(0xFF7A3E4A), Color(0xFF837B6C)),
                    mapOf("Grunge" to 4, "Vintage" to 2), mapOf("Black" to 4), 3),
                look("d", "Hooded parka, quiet colour",
                    "Olive or stone, relaxed shoulder.",
                    listOf(Color(0xFF77754E), Color(0xFFF1ECE1), Color(0xFF1B1A17)),
                    mapOf("Minimalist" to 3, "Techwear" to 2), mapOf("Olive" to 3, "Beige" to 2), 2)
            )
        ),

        QuizQuestion(
            id = "q06_shoes", eyebrow = "One pair, six months",
            prompt = "Choose carefully. These will be seen daily.",
            helper = "Comfort is part of the look.",
            looks = listOf(
                look("a", "Clean white leather",
                    "Low profile, no logo, wiped weekly.",
                    listOf(Color(0xFFFFFFFF), Color(0xFFF3EEE2), Color(0xFF1B1A17)),
                    mapOf("Minimalist" to 3, "Casual" to 3), mapOf("White" to 4), 2),
                look("b", "Suede loafers",
                    "Brown or tan, worn with everything.",
                    listOf(Color(0xFFC19A6B), Color(0xFF1B1A17), Color(0xFFF3EEE2)),
                    mapOf("Old Money" to 3, "Smart Casual" to 3), mapOf("Brown" to 4), 4),
                look("c", "Chunky trainers",
                    "Statement sole, colour in the details.",
                    listOf(Color(0xFF837B6C), Color(0xFF46688C), Color(0xFFB08A4F)),
                    mapOf("Streetwear" to 4, "Y2K" to 2), mapOf("Gray" to 3), 1),
                look("d", "Leather boots",
                    "Chelsea or lace-up, a little scuffed.",
                    listOf(Color(0xFF1B1A17), Color(0xFF35322A), Color(0xFF7A3E4A)),
                    mapOf("Grunge" to 3, "Vintage" to 3), mapOf("Black" to 4), 3)
            )
        ),

        QuizQuestion(
            id = "q07_palette", eyebrow = "Colour",
            prompt = "Pick the palette that feels like you.",
            helper = "This sets the default filter across your whole rail.",
            looks = listOf(
                look("a", "Ink and nothing else",
                    "Black, charcoal, off-white. Never a loud idea.",
                    listOf(Color(0xFF1B1A17), Color(0xFF35322A), Color(0xFFF3EEE2)),
                    mapOf("Minimalist" to 4, "Grunge" to 2), mapOf("Black" to 5), 3),
                look("b", "Ivory and sand",
                    "Cream, beige, oatmeal. Soft and expensive-looking.",
                    listOf(Color(0xFFFAF7F0), Color(0xFFE7E0D2), Color(0xFFC19A6B)),
                    mapOf("Minimalist" to 3, "Old Money" to 3), mapOf("White" to 3, "Beige" to 4), 4),
                look("c", "Denim and olive",
                    "Washed indigo, field green, worn brown.",
                    listOf(Color(0xFF46688C), Color(0xFF77754E), Color(0xFFC19A6B)),
                    mapOf("Casual" to 3, "Vintage" to 3), mapOf("Navy" to 4, "Olive" to 4), 2),
                look("d", "One accent, worn hard",
                    "A neutral base with a single red or burgundy.",
                    listOf(MulledWine, Color(0xFF1B1A17), Color(0xFFF3EEE2)),
                    mapOf("Smart Casual" to 3, "Formal" to 3), mapOf("Brown" to 3, "Black" to 3), 4)
            )
        ),

        QuizQuestion(
            id = "q08_fit", eyebrow = "Silhouette",
            prompt = "How do you like your clothes to sit?",
            helper = "Keys are AppConstants.fits — this feeds every future suggestion.",
            looks = listOf(
                look("a", "Oversized", "Volume on purpose, shoulders dropped.",
                    listOf(Color(0xFF1B1A17), Color(0xFF837B6C), Color(0xFFF1ECE1)),
                    mapOf("Streetwear" to 4, "Y2K" to 2), mapOf("Black" to 2, "Gray" to 2), 1),
                look("b", "Slim", "Close to the body, clean line.",
                    listOf(Color(0xFF1B1A17), Color(0xFF46688C), Color(0xFFF3EEE2)),
                    mapOf("Minimalist" to 3, "Formal" to 3), mapOf("Black" to 3, "Navy" to 2), 4),
                look("c", "Regular", "Exactly what the label says.",
                    listOf(Color(0xFFC19A6B), Color(0xFFF1ECE1), Color(0xFF1B1A17)),
                    mapOf("Casual" to 4, "Smart Casual" to 2), mapOf("Beige" to 3, "Brown" to 2), 3),
                look("d", "Relaxed", "Room to move, nothing sloppy.",
                    listOf(Color(0xFF77754E), Color(0xFFF3EEE2), Color(0xFF837B6C)),
                    mapOf("Korean" to 3, "Techwear" to 3), mapOf("Olive" to 3, "White" to 2), 2)
            )
        ),

        QuizQuestion(
            id = "q09_pattern", eyebrow = "Surface",
            prompt = "Choose the surface you'd wear most.",
            helper = "Keys are AppConstants.patterns.",
            looks = listOf(
                look("a", "Plain", "Texture does the talking, not print.",
                    listOf(Color(0xFFF1ECE1), Color(0xFF837B6C), Color(0xFF1B1A17)),
                    mapOf("Minimalist" to 4), mapOf("Beige" to 3, "Gray" to 2), 3),
                look("b", "Striped", "Pinstripe or breton, a classic two-way.",
                    listOf(Color(0xFFFFFFFF), Color(0xFF46688C), Color(0xFF1B1A17)),
                    mapOf("Old Money" to 3, "Smart Casual" to 3), mapOf("White" to 3, "Navy" to 3), 4),
                look("c", "Graphic", "Type, art, a whole statement on the chest.",
                    listOf(Color(0xFF1B1A17), Color(0xFFB08A4F), Color(0xFF46688C)),
                    mapOf("Streetwear" to 4, "Y2K" to 2), mapOf("Black" to 4), 1),
                look("d", "Checked or camo", "A pattern that commits.",
                    listOf(Color(0xFF77754E), Color(0xFFC19A6B), Color(0xFF1B1A17)),
                    mapOf("Grunge" to 3, "Vintage" to 3, "Techwear" to 1), mapOf("Olive" to 4, "Brown" to 2), 2)
            )
        ),

        QuizQuestion(
            id = "q10_still", eyebrow = "Casting",
            prompt = "Which of these could be a still from your wardrobe?",
            helper = "Style families. Pick the frame you'd live in.",
            looks = listOf(
                look("a", "Streetwear", "Sneakers, hoodie, low-slung denim, attitude.",
                    listOf(Color(0xFF1B1A17), Color(0xFF837B6C), Color(0xFF46688C)),
                    mapOf("Streetwear" to 5), mapOf("Black" to 3, "Gray" to 2), 1),
                look("b", "Minimalist", "Few pieces, exact proportions, no ornament.",
                    listOf(Color(0xFFF3EEE2), Color(0xFF1B1A17), Color(0xFFE7E0D2)),
                    mapOf("Minimalist" to 5), mapOf("White" to 3, "Black" to 3), 3),
                look("c", "Old Money", "Camel, navy, cashmere, quiet wealth.",
                    listOf(Color(0xFFC19A6B), Color(0xFF46688C), Color(0xFFF1ECE1)),
                    mapOf("Old Money" to 5), mapOf("Beige" to 3, "Navy" to 3), 5),
                look("d", "Korean", "Soft volume, pastel knits, clean lines.",
                    listOf(Color(0xFFF1ECE1), Color(0xFFE8C4B8), Color(0xFF837B6C)),
                    mapOf("Korean" to 5, "Minimalist" to 1), mapOf("Beige" to 3, "White" to 3), 3)
            )
        ),

        QuizQuestion(
            id = "q11_travel", eyebrow = "Five days, one carry-on",
            prompt = "What goes in the bag?",
            helper = "This is how we learn to build a capsule for you.",
            looks = listOf(
                look("a", "One colour story",
                    "Three tops, two trousers, all interchangeable.",
                    listOf(Color(0xFF46688C), Color(0xFFF3EEE2), Color(0xFF1B1A17)),
                    mapOf("Minimalist" to 3, "Smart Casual" to 3), mapOf("Navy" to 3, "White" to 3), 3),
                look("b", "One good jacket and tees",
                    "The jacket does the work. Everything else is plain.",
                    listOf(Color(0xFF1B1A17), Color(0xFFF1ECE1), Color(0xFFC19A6B)),
                    mapOf("Old Money" to 3, "Vintage" to 2), mapOf("Black" to 3, "Beige" to 2), 3),
                look("c", "Technical everything",
                    "Packable shell, quick-dry layers, trail shoes.",
                    listOf(Color(0xFF35322A), Color(0xFF77754E), Color(0xFF837B6C)),
                    mapOf("Techwear" to 4, "Sporty" to 3), mapOf("Olive" to 4, "Black" to 2), 2),
                look("d", "Photos over practicality",
                    "Loud shirts, sunglasses, whatever fits the feed.",
                    listOf(Color(0xFFE8C4B8), Color(0xFFB08A4F), Color(0xFF46688C)),
                    mapOf("Y2K" to 4, "Streetwear" to 1), mapOf("Beige" to 2, "Gray" to 2), 2)
            )
        ),

        QuizQuestion(
            id = "q12_signature", eyebrow = "Last one",
            prompt = "Someone describes you to a friend. Which line do you hope they say?",
            helper = "Your answer shapes the tone of every styling note we write.",
            looks = listOf(
                look("a", "\"Always looks expensive.\"", "Quiet, exact, considered.",
                    listOf(Color(0xFFC19A6B), Color(0xFF1B1A17), Color(0xFFF1ECE1)),
                    mapOf("Old Money" to 4, "Formal" to 2), mapOf("Beige" to 3, "Black" to 2), 5),
                look("b", "\"Effortless, never tries.\"", "Simple pieces, perfect proportions.",
                    listOf(Color(0xFFF3EEE2), Color(0xFF837B6C), Color(0xFF1B1A17)),
                    mapOf("Minimalist" to 4, "Casual" to 2), mapOf("White" to 3, "Gray" to 3), 3),
                look("c", "\"Always the best-dressed one.\"", "Risk, colour, presence.",
                    listOf(Color(0xFF7A3E4A), Color(0xFFB08A4F), Color(0xFF1B1A17)),
                    mapOf("Smart Casual" to 3, "Vintage" to 2, "Y2K" to 2), mapOf("Brown" to 3, "Black" to 2), 4),
                look("d", "\"Very now.\"", "Trends, worn with confidence.",
                    listOf(Color(0xFF46688C), Color(0xFFB08A4F), Color(0xFFE8C4B8)),
                    mapOf("Streetwear" to 3, "Techwear" to 2, "Y2K" to 2), mapOf("Gray" to 3), 2)
            )
        )
    )

    // ── 4.3 the starter wardrobe seeded by tutorial 2 ───────────────────────
    val starterWardrobe = listOf(
        SeedItem(ItemCategory.TOP, "Oversized Cotton T-Shirt", "White", "#FFFFFF",
            "Minimalist", "Oversized", "Plain", "All Season", "StyleDrop Starter",
            "The plain top every look is built on."),
        SeedItem(ItemCategory.TOP, "Merino Knit", "Navy", "#1B2A4A",
            "Smart Casual", "Regular", "Plain", "Spring/Fall", "StyleDrop Starter",
            "One knit turns a t-shirt and jeans into something with an occasion."),
        SeedItem(ItemCategory.BOTTOM, "Straight-Leg Denim", "Navy", "#4A6A96",
            "Casual", "Relaxed", "Plain", "All Season", "StyleDrop Starter",
            "Straight, not skinny. It sits with everything above it."),
        SeedItem(ItemCategory.BOTTOM, "Pleated Trouser", "Beige", "#C19A6B",
            "Minimalist", "Relaxed", "Plain", "Spring/Fall", "StyleDrop Starter",
            "Pleats give you an evening you didn't have to plan."),
        SeedItem(ItemCategory.SHOES, "Low Leather Sneaker", "White", "#F2F2F2",
            "Minimalist", "Regular", "Plain", "All Season", "StyleDrop Starter",
            "Clean white carries a full outfit on its own."),
        SeedItem(ItemCategory.SHOES, "Suede Loafer", "Brown", "#7B5230",
            "Smart Casual", "Regular", "Plain", "Spring/Fall", "StyleDrop Starter",
            "The one upgrade that reads as \"took an interest\"."),
        SeedItem(ItemCategory.OUTERWEAR, "Wool Overcoat", "Beige", "#B09165",
            "Old Money", "Relaxed", "Plain", "Winter", "StyleDrop Starter",
            "Camel over a plain base is the whole quiet-luxury idea."),
        SeedItem(ItemCategory.OUTERWEAR, "Bomber Jacket", "Black", "#1B1A17",
            "Streetwear", "Regular", "Plain", "All Season", "StyleDrop Starter",
            "The casual answer when the coat feels like too much."),
        SeedItem(ItemCategory.ACCESSORY, "Leather Belt", "Brown", "#6E4A2B",
            "Smart Casual", "Regular", "Plain", "All Season", "StyleDrop Starter",
            "Match it to the shoe and the outfit suddenly agrees with itself."),
        SeedItem(ItemCategory.ACCESSORY, "Wool Cap", "Olive", "#77754E",
            "Casual", "Regular", "Plain", "All Season", "StyleDrop Starter",
            "The cheapest way to make a plain look intentional.")
    )

    const val SEED_BRAND_MARKER = "StyleDrop Starter"

    // ── 4.4 copy deck (also tabulated in ONBOARDING.md §4) ──────────────────
    object Copy {
        const val WELCOME_EYEBROW = "Your atelier, in your pocket"
        const val WELCOME_TITLE   = "Let's take your measurements."
        const val WELCOME_BODY    =
            "No tape measure. Just five quick questions, then a short fitting quiz so we know " +
            "what you actually reach for. Everything stays on this phone."
        const val WELCOME_CTA     = "Begin the fitting"
        const val WELCOME_SKIP    = "Skip — start me on defaults"

        const val G_LENS_TITLE    = "Who are we cutting for?"
        const val G_LENS_BODY     = "This only sets how garments are named and sized. You can change it later."
        const val CLIMATE_TITLE   = "What does your weather do?"
        const val CLIMATE_BODY    = "We read the sky before we read the rail."
        const val LIFESTYLE_TITLE = "Where does a normal week take you?"
        const val LIFESTYLE_BODY  = "Choose everything that applies."
        const val GOALS_TITLE     = "What should this wardrobe fix?"
        const val GOALS_BODY      = "Pick the ones that matter. We'll hold you to them gently."

        const val QUIZ_TITLE      = "First, the fitting."
        const val QUIZ_BODY       = "Twelve questions. Tap the look you'd actually wear — not the one you'd admire."
        const val REVEAL_TITLE    = "Here's your fitting profile."
        const val REVEAL_BODY     = "Read the profile. If a line feels wrong, retake any answer."

        const val PHOTO_TITLE     = "Hang your first photo."
        const val PHOTO_BODY      = "Drag the photo onto the hanger. That's the whole trick — good light, plain background, garment flat."
        const val PHOTO_PROMPT    = "Drag the photo up onto the hanger"
        const val PHOTO_TAP_HINT  = "Or tap it, if dragging isn't your thing."
        const val PHOTO_DONE      = "Hung. That's exactly how every piece goes in."
        const val PHOTO_TIPS      = listOf(
            "Plain background — a wall, a bed sheet, a door.",
            "Good light on the front. Never a flash.",
            "Garment flat or on the hanger, cuffs showing.",
            "One piece per photo. We'll do the sorting."
        )

        const val FIRST_ITEM_TITLE = "Now a real one."
        const val FIRST_ITEM_BODY  =
            "We've hung ten starter pieces on your rail so nothing feels empty. " +
            "Keep them, or take them off once yours are in."
        const val FIRST_ITEM_CTA   = "Open my atelier"
        const val FIRST_ITEM_KEEP  = "Keep the starters"
        const val FIRST_ITEM_CLEAR = "Remove the starters"

        const val PERM_TITLE  = "Before the finish"
        const val PERM_BODY   = "Three permissions. Each one asked once, each one explained, and none of them required."

        const val ERROR_LOOSE = "A loose thread. Give it a moment and try again."
    }

    object Analytics {
        val events = linkedMapOf(
            "onboarding_started" to "flow entered (any entry point)",
            "onboarding_step_viewed" to "props: step_id, index, total",
            "onboarding_step_completed" to "props: step_id, dwell_ms, selections",
            "quiz_question_answered" to "props: question_id, look_id, position",
            "quiz_question_changed" to "props: question_id, from_look_id, to_look_id",
            "quiz_completed" to "props: answered, total, duration_ms",
            "style_profile_generated" to "props: top_styles[], palette[], formality_band, seeded_from_skip",
            "photo_tutorial_completed" to "props: attempts, method (drag|tap)",
            "seed_wardrobe_installed" to "props: item_count",
            "permission_prompted" to "props: permission, screen",
            "permission_granted" to "props: permission, screen",
            "permission_denied" to "props: permission, screen, rationale_shown",
            "permission_denied_forever" to "props: permission, screen",
            "permission_settings_opened" to "props: permission",
            "onboarding_skipped" to "props: at_step, at_index",
            "onboarding_completed" to "props: duration_ms, seeded_from_skip, seed_items"
        )
    }

    /** Legacy photo permission — only ever requested below API 33. */
    val legacyPhotoPermission: String
        get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            Manifest.permission.READ_MEDIA_IMAGES else Manifest.permission.READ_EXTERNAL_STORAGE
}
/* ═══════════════════════════════════════════════════════════════════════════
 * 5 · ENGINE + VIEW MODEL
 * ═══════════════════════════════════════════════════════════════════════════ */

/**
 * Pure, deterministic, dependency-free. Turns 12 taps into a StyleProfile.
 * No clock, no network, no randomness — same answers always give the same
 * profile, which is what makes the reveal screen trustworthy.
 */
object StyleProfileEngine {

    /** What a skip produces: everything the app needs, nothing the user gave. */
    fun defaultProfile(now: Long = System.currentTimeMillis()): StyleProfile = StyleProfile(
        genderLens = "unspecified",
        climate = "temperate",
        hemisphere = "north",
        lifestyle = listOf("office"),
        goals = listOf("wearmore"),
        quizAnswers = emptyMap(),
        styleScores = mapOf("Minimalist" to 4, "Casual" to 3, "Smart Casual" to 2),
        topStyles = listOf("Minimalist", "Casual", "Smart Casual"),
        paletteAffinity = listOf("Black", "White", "Navy"),
        formalityBand = "Balanced",
        seededFromSkip = true,
        completedAt = now
    )

    fun build(
        deck: DeckAnswers,
        answers: Map<String, String>,
        now: Long = System.currentTimeMillis()
    ): StyleProfile {
        val scores = LinkedHashMap<String, Int>()
        val palette = LinkedHashMap<String, Int>()
        val formality = mutableListOf<Int>()

        answers.forEach { (questionId, lookId) ->
            val look = OnboardingContent.quiz
                .firstOrNull { it.id == questionId }
                ?.looks?.firstOrNull { it.id == lookId }
                ?: return@forEach
            look.styles.forEach { (k, w) -> scores[k] = (scores[k] ?: 0) + w }
            look.paletteWeights.forEach { (k, w) -> palette[k] = (palette[k] ?: 0) + w }
            formality += look.formality
        }

        val topStyles = scores.entries
            .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
            .map { it.key }
            .take(3)
            .ifEmpty { listOf("Minimalist", "Casual") }

        val paletteAffinity = palette.entries
            .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
            .map { it.key }
            .take(3)
            .ifEmpty { listOf("Black", "White") }

        val avg = if (formality.isEmpty()) 3.0 else formality.average()
        val band = when {
            avg < 2.4 -> "Relaxed"
            avg > 3.6 -> "Sharp"
            else      -> "Balanced"
        }

        return StyleProfile(
            genderLens = deck.genderLens ?: "unspecified",
            climate = deck.climate ?: "temperate",
            hemisphere = deck.hemisphere,
            lifestyle = deck.lifestyle.toList().sorted(),
            goals = deck.goals.toList().sorted(),
            quizAnswers = answers.toMap(),
            styleScores = scores.entries
                .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
                .associate { it.key to it.value },
            topStyles = topStyles,
            paletteAffinity = paletteAffinity,
            formalityBand = band,
            seededFromSkip = false,
            completedAt = now
        )
    }

    /** "Minimalist · Old Money · Korean" — the reveal headline. */
    fun headline(profile: StyleProfile): String =
        if (profile.topStyles.isEmpty()) "Still being cut" else profile.topStyles.joinToString("  ·  ")
}

data class DeckAnswers(
    val genderLens: String?,
    val climate: String?,
    val hemisphere: String,
    val lifestyle: Set<String>,
    val goals: Set<String>
)


class OnboardingViewModel(app: Application) : AndroidViewModel(app) {

    private val store = app.onboardingStore
    private val repo = WardrobeRepository(AppDatabase.getDatabase(app).wardrobeDao())
    private val moshi: Moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()
    private val profileAdapter = moshi.adapter(StyleProfile::class.java)

    private val _state = MutableStateFlow(OnboardingUiState())
    val state: StateFlow<OnboardingUiState> = _state.asStateFlow()

    private val _toast = MutableStateFlow<String?>(null)
    val toast: StateFlow<String?> = _toast.asStateFlow()

    init { restore() }

    // ── restore: a process kill mid-flow must not restart the fitting ────────
    private fun restore() {
        viewModelScope.launch {
            val p = store.data.first()
            val stage = when (p[OnboardingKeys.STAGE]) {
                OnboardingStageName.QUIZ       -> OnboardingStage.QUIZ
                OnboardingStageName.REVEAL     -> OnboardingStage.REVEAL
                OnboardingStageName.PHOTO      -> OnboardingStage.PHOTO_TUTORIAL
                OnboardingStageName.FIRST_ITEM -> OnboardingStage.FIRST_ITEM
                OnboardingStageName.PERMISSIONS -> OnboardingStage.PERMISSIONS
                else                           -> OnboardingStage.DECLARATIONS
            }
            _state.value = _state.value.copy(
                stage = stage,
                declarationPage = p[OnboardingKeys.DECLARATION_PAGE] ?: 0,
                genderLens = p[OnboardingKeys.GENDER_LENS],
                climate = p[OnboardingKeys.CLIMATE],
                hemisphere = p[OnboardingKeys.HEMISPHERE] ?: "north",
                lifestyle = (p[OnboardingKeys.LIFESTYLE] ?: "").splitCsv(),
                goals = (p[OnboardingKeys.GOALS] ?: "").splitCsv(),
                quizIndex = (p[OnboardingKeys.QUIZ_INDEX] ?: 0)
                    .coerceIn(0, (OnboardingContent.quiz.size - 1).coerceAtLeast(0)),
                quizAnswers = parseAnswers(p[OnboardingKeys.QUIZ_ANSWERS] ?: ""),
                profile = p[OnboardingKeys.PROFILE_JSON]?.let { runCatching { profileAdapter.fromJson(it) }.getOrNull() },
                photoTutorialDone = p[OnboardingKeys.PHOTO_TUTORIAL_DONE] ?: false,
                firstItemTutorialDone = p[OnboardingKeys.FIRST_ITEM_DONE] ?: false,
                seedInstalled = p[OnboardingKeys.SEED_INSTALLED] ?: false,
                loading = false
            )
        }
    }

    // ── declarations ────────────────────────────────────────────────────────
    fun setGenderLens(id: String) = persist { it.copy(genderLens = id) } { OnboardingKeys.GENDER_LENS to id }
    fun setClimate(id: String) = persist { it.copy(climate = id) } { OnboardingKeys.CLIMATE to id }
    fun setHemisphere(h: String) = persist { it.copy(hemisphere = h) } { OnboardingKeys.HEMISPHERE to h }

    fun toggleLifestyle(id: String) {
        val next = _state.value.lifestyle.toggle(id, minOne = OnboardingContent.lifestyle.map { it.id })
        persist { it.copy(lifestyle = next) } { OnboardingKeys.LIFESTYLE to next.joinToString("|") }
    }

    fun toggleGoal(id: String) {
        val next = _state.value.goals.toggle(id, minOne = OnboardingContent.goals.map { it.id })
        persist { it.copy(goals = next) } { OnboardingKeys.GOALS to next.joinToString("|") }
    }

    fun goToPage(index: Int) {
        val clamped = index.coerceIn(0, DECLARATION_STEPS - 1)
        persist { it.copy(declarationPage = clamped) } { OnboardingKeys.DECLARATION_PAGE to clamped }
    }

    fun nextPage(onFinish: () -> Unit) {
        val s = _state.value
        if (s.declarationPage >= DECLARATION_STEPS - 1) {
            persist {
                it.copy(stage = OnboardingStage.QUIZ, quizIndex = it.quizIndex)
            } { OnboardingKeys.STAGE to OnboardingStageName.QUIZ }
            onFinish()
        } else {
            goToPage(s.declarationPage + 1)
        }
    }

    fun previousPage(onExit: () -> Unit) {
        val s = _state.value
        if (s.declarationPage == 0) onExit() else goToPage(s.declarationPage - 1)
    }

    // ── quiz ────────────────────────────────────────────────────────────────
    fun answer(questionId: String, lookId: String) {
        val next = _state.value.quizAnswers.toMutableMap().apply { put(questionId, lookId) }
        persist { it.copy(quizAnswers = next) } { OnboardingKeys.QUIZ_ANSWERS to encodeAnswers(next) }
    }

    fun goToQuestion(index: Int) {
        val clamped = index.coerceIn(0, (OnboardingContent.quiz.size - 1).coerceAtLeast(0))
        persist { it.copy(quizIndex = clamped) } { OnboardingKeys.QUIZ_INDEX to clamped }
    }

    fun nextQuestion(onFinished: () -> Unit) {
        val s = _state.value
        if (s.quizIndex >= OnboardingContent.quiz.size - 1) {
            finishQuiz()
            onFinished()
        } else {
            goToQuestion(s.quizIndex + 1)
        }
    }

    fun previousQuestion(onExit: () -> Unit) {
        val s = _state.value
        if (s.quizIndex == 0) {
            persist { it.copy(stage = OnboardingStage.DECLARATIONS) } {
                OnboardingKeys.STAGE to OnboardingStageName.DECLARATIONS
            }
        } else {
            goToQuestion(s.quizIndex - 1)
        }
    }

    private fun finishQuiz() {
        val s = _state.value
        val profile = StyleProfileEngine.build(
            deck = DeckAnswers(s.genderLens, s.climate, s.hemisphere, s.lifestyle, s.goals),
            answers = s.quizAnswers
        )
        persist { it.copy(stage = OnboardingStage.REVEAL, profile = profile) } {
            OnboardingKeys.STAGE to OnboardingStageName.REVEAL
            OnboardingKeys.PROFILE_JSON to profileAdapter.toJson(profile)
            OnboardingKeys.TOP_STYLES to profile.topStyles.joinToString("|")
            OnboardingKeys.STYLE_SCORES to profile.styleScores.entries.joinToString("|") { "${it.key}=${it.value}" }
            OnboardingKeys.PALETTE to profile.paletteAffinity.joinToString("|")
            OnboardingKeys.FORMALITY_BAND to profile.formalityBand
            OnboardingKeys.COMPLETED_AT to profile.completedAt
            OnboardingKeys.SEEDED_FROM_SKIP to profile.seededFromSkip
        }
    }

    fun retakeQuiz() {
        persist { it.copy(stage = OnboardingStage.QUIZ, quizIndex = 0, quizAnswers = emptyMap()) } {
            OnboardingKeys.STAGE to OnboardingStageName.QUIZ
            OnboardingKeys.QUIZ_INDEX to 0
            OnboardingKeys.QUIZ_ANSWERS to ""
        }
    }

    // ── tutorials ───────────────────────────────────────────────────────────
    fun toPhotoTutorial() = persist {
        it.copy(stage = OnboardingStage.PHOTO_TUTORIAL)
    } { OnboardingKeys.STAGE to OnboardingStageName.PHOTO }

    fun completePhotoTutorial() = persist { it.copy(photoTutorialDone = true, stage = OnboardingStage.FIRST_ITEM) } {
        OnboardingKeys.PHOTO_TUTORIAL_DONE to true
        OnboardingKeys.STAGE to OnboardingStageName.FIRST_ITEM
    }

    fun completeFirstItemTutorial() = persist {
        it.copy(firstItemTutorialDone = true, stage = OnboardingStage.PERMISSIONS)
    } {
        OnboardingKeys.FIRST_ITEM_DONE to true
        OnboardingKeys.STAGE to OnboardingStageName.PERMISSIONS
    }

    fun toPermissions() = persist { it.copy(stage = OnboardingStage.PERMISSIONS) } {
        OnboardingKeys.STAGE to OnboardingStageName.PERMISSIONS
    }

    /** Idempotent: tapping "Keep the starters" twice cannot double the rail. */
    fun installSeed() {
        viewModelScope.launch {
            val p = store.data.first()
            if (p[OnboardingKeys.SEED_INSTALLED] == true) return@launch
            SeedWardrobe.install(repo)
            store.edit { it[OnboardingKeys.SEED_INSTALLED] = true }
            _state.value = _state.value.copy(seedInstalled = true)
        }
    }

    fun clearSeed() {
        viewModelScope.launch {
            SeedWardrobe.remove(repo)
            store.edit { it[OnboardingKeys.SEED_INSTALLED] = false }
            _state.value = _state.value.copy(seedInstalled = false)
        }
    }

    // ── finish / skip ───────────────────────────────────────────────────────
    fun complete() {
        viewModelScope.launch {
            store.edit {
                it[OnboardingKeys.COMPLETED] = true
                it[OnboardingKeys.SKIPPED] = false
            }
        }
    }

    fun skipToDefaults() {
        val profile = StyleProfileEngine.defaultProfile()
        persist { it.copy(profile = profile, stage = OnboardingStage.REVEAL, quizAnswers = emptyMap()) } {
            OnboardingKeys.SKIPPED to true
            OnboardingKeys.PROFILE_JSON to profileAdapter.toJson(profile)
            OnboardingKeys.TOP_STYLES to profile.topStyles.joinToString("|")
            OnboardingKeys.PALETTE to profile.paletteAffinity.joinToString("|")
            OnboardingKeys.FORMALITY_BAND to profile.formalityBand
            OnboardingKeys.SEEDED_FROM_SKIP to true
            OnboardingKeys.COMPLETED_AT to profile.completedAt
        }
        installSeed()
        _toast.value = profile.headline().let { "Rails set to $it. Adjust any time in Profile." }
    }

    fun consumeToast() { _toast.value = null }

    /** Lets the user start over from Profile → Appearance/Settings. */
    fun resetAll() {
        viewModelScope.launch {
            store.edit { it.clear() }
            clearSeed()
            _state.value = OnboardingUiState(loading = false)
        }
    }

    // ── plumbing ────────────────────────────────────────────────────────────
    private fun persist(
        mutate: (OnboardingUiState) -> OnboardingUiState,
        writes: (androidx.datastore.preferences.core.MutablePreferences) -> Unit
    ) {
        _state.value = mutate(_state.value)
        viewModelScope.launch { store.edit { writes(it) } }
    }

    companion object {
        const val DECLARATION_STEPS = 5
        val declarationLabels = listOf("welcome", "gender", "climate", "lifestyle", "goals")
    }

    private fun String.splitCsv(): Set<String> =
        if (isBlank()) emptySet() else split("|").filter { it.isNotBlank() }.toSet()

    private fun encodeAnswers(map: Map<String, String>): String =
        map.entries.joinToString("|") { "${it.key}=${it.value}" }

    private fun parseAnswers(raw: String): Map<String, String> =
        if (raw.isBlank()) emptyMap()
        else raw.split("|").mapNotNull { chunk ->
            val i = chunk.indexOf('=')
            if (i <= 0) null else chunk.substring(0, i) to chunk.substring(i + 1)
        }.toMap()

    private fun Set<String>.toggle(id: String, minOne: List<String>): Set<String> =
        if (contains(id) && size > 1) this - id else if (contains(id)) this else this + id
}


/** Writes/removes the sample wardrobe. Never touches a user's own pieces. */
object SeedWardrobe {

    suspend fun install(repo: WardrobeRepository) {
        OnboardingContent.starterWardrobe.forEachIndexed { index, seed ->
            repo.insert(
                WardrobeItem(
                    id = "seed-${index + 1}",
                    imageUrl = "",            // rendered by the silhouette fallback — see ONBOARDING.md §6
                    category = seed.category,
                    type = seed.type,
                    color = seed.colorName,
                    secondaryColor = seed.colorHex,
                    style = seed.style,
                    fit = seed.fit,
                    pattern = seed.pattern,
                    season = seed.season,
                    brand = OnboardingContent.SEED_BRAND_MARKER,
                    timesWorn = 0,
                    isFavoriteItem = false,
                    lastWorn = null,
                    createdAt = System.currentTimeMillis() - (OnboardingContent.starterWardrobe.size - index) * 1000L
                )
            )
        }
    }

    suspend fun remove(repo: WardrobeRepository) {
        OnboardingContent.starterWardrobe.indices.forEach { repo.delete("seed-${it + 1}") }
    }
}


/* ═══════════════════════════════════════════════════════════════════════════
 * 6 · SHARED UI KIT
 *     Seven primitives carry the whole flow. Each one is a pure function of
 *     its arguments, so a Roborazzi screenshot test can pin every state.
 * ═══════════════════════════════════════════════════════════════════════════ */

private val Gutter = 20.dp
private val CardRadius = 20.dp

@Composable
private fun OnboardingScaffold(
    stepLabel: String,
    stepIndex: Int,
    stepCount: Int,
    onBack: (() -> Unit)?,
    skipLabel: String?,
    onSkip: (() -> Unit)?,
    progress: Float,
    content: @Composable (Modifier) -> Unit
) {
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            Column(Modifier.padding(horizontal = Gutter, vertical = 8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (onBack != null) {
                        IconButton(onClick = onBack) {
                            Icon(
                                Icons.AutoMirrored.Rounded.ArrowBack,
                                contentDescription = "Back a step"
                            )
                        }
                    } else {
                        Spacer(Modifier.size(48.dp))
                    }
                    Spacer(Modifier.weight(1f))
                    Text(
                        text = "Step ${stepIndex + 1} of $stepCount",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                StitchProgress(
                    progress = progress,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = if (onBack != null) 12.dp else 0.dp)
                )
                if (skipLabel != null && onSkip != null) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        TextButton(onClick = onSkip) {
                            Text(skipLabel, style = MaterialTheme.typography.bodySmall, color = TwillGrey)
                        }
                    }
                }
            }
        }
    ) { padding ->
        content(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .padding(horizontal = Gutter)
        )
    }
}

/** The basting stitch: completed work is solid, outstanding work is dashed. */
@Composable
private fun StitchProgress(progress: Float, modifier: Modifier = Modifier) {
    val animated by animateFloatAsState(
        targetValue = progress.coerceIn(0f, 1f),
        animationSpec = tween(durationMillis = 320),
        label = "stitch"
    )
    Canvas(modifier = modifier.height(10.dp)) {
        val segments = 22
        val gap = size.width / segments
        val dashLen = gap * 0.62f
        val filled = (segments * animated).roundToInt()
        repeat(segments) { i ->
            val x = i * gap
            if (i < filled) {
                drawLine(
                    color = BastingRed,
                    start = Offset(x, size.height / 2f),
                    end = Offset(x + dashLen, size.height / 2f),
                    strokeWidth = 3f
                )
            } else {
                val path = Path().apply {
                    moveTo(x, size.height / 2f)
                    lineTo(x + dashLen, size.height / 2f)
                }
                drawPath(path, color = CanvasLine, style = Stroke(width = 2f))
            }
        }
    }
}

@Composable
private fun Eyebrow(text: String, modifier: Modifier = Modifier) = Text(
    text = text,
    modifier = modifier,
    style = MaterialTheme.typography.labelLarge,
    color = BastingRed
)

@Composable
private fun StepTitle(title: String, body: String, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(title, style = MaterialTheme.typography.displayMedium)
        Spacer(Modifier.height(8.dp))
        Text(body, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun BrandPrimaryButton(
    text: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
    modifier: Modifier = Modifier
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier
            .fillMaxWidth()
            .height(52.dp),
        shape = RoundedCornerShape(16.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = BastingRed,
            contentColor = Color.White,
            disabledContainerColor = BastingRed.copy(alpha = 0.35f),
            disabledContentColor = Color.White.copy(alpha = 0.8f)
        )
    ) {
        Text(text, style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
private fun BrandGhostButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    OutlinedButton(
        onClick = onClick,
        modifier = modifier
            .fillMaxWidth()
            .height(52.dp),
        shape = RoundedCornerShape(16.dp)
    ) {
        Text(text, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurface)
    }
}

/**
 * A choice tile. Uses `selectable` + Role.RadioButton so TalkBack announces
 * "selected, radio button" — this is the a11y-correct pattern (see
 * A11Y_I18N.md item #1 in the sibling deliverable).
 */
@Composable
private fun OptionTile(
    option: OnboardingOption,
    selected: Boolean,
    multi: Boolean,
    onClick: () -> Unit
) {
    val shape = RoundedCornerShape(CardRadius)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(MaterialTheme.colorScheme.surface)
            .border(
                width = if (selected) 2.dp else 1.dp,
                color = if (selected) BastingRed else MaterialTheme.colorScheme.outline.copy(alpha = 0.5f),
                shape = shape
            )
            .selectable(selected = selected, role = if (multi) Role.Checkbox else Role.RadioButton, onClick = onClick)
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        LookPlate(
            palette = option.palette,
            modifier = Modifier
                .size(54.dp)
                .clip(RoundedCornerShape(12.dp))
        )
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(option.title, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(2.dp))
            Text(
                option.caption,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(Modifier.width(10.dp))
        SelectionMark(selected, multi)
    }
}

@Composable
private fun SelectionMark(selected: Boolean, multi: Boolean) {
    val shape = if (multi) RoundedCornerShape(6.dp) else CircleShape
    Box(
        modifier = Modifier
            .size(24.dp)
            .clip(shape)
            .background(if (selected) BastingRed else Color.Transparent)
            .border(
                width = if (selected) 0.dp else 1.5.dp,
                color = if (selected) BastingRed else MaterialTheme.colorScheme.outline,
                shape = shape
            ),
        contentAlignment = Alignment.Center
    ) {
        if (selected) {
            Icon(
                Icons.Rounded.Check,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(16.dp)
            )
        }
    }
}

/**
 * The zero-asset garment plate. When a real photo exists it is drawn with Coil;
 * otherwise the palette is rendered as a soft cloth gradient with a single-line
 * garment silhouette over it. Every look in the quiz is legible with zero
 * bundled images, and swaps to photography by filling in `photoUrl` only.
 */
@Composable
fun LookPlate(
    palette: List<Color>,
    modifier: Modifier = Modifier,
    photoUrl: String? = null,
    silhouette: GarmentSilhouette = GarmentSilhouette.Blazer,
    contentDescription: String? = null
) {
    Box(modifier) {
        if (photoUrl.isNullOrBlank()) {
            Canvas(Modifier.fillMaxSize()) {
                val colors = palette.ifEmpty { listOf(MuslinIvorie, CanvasLine) }
                drawRect(
                    brush = Brush.linearGradient(
                        colors = colors,
                        start = Offset.Zero,
                        end = Offset(size.width, size.height)
                    )
                )
                silhouettePath(silhouette, size)
                    ?.let { drawPath(it, color = Color(0x551B1A17), style = Stroke(width = size.minDimension * 0.035f)) }
            }
        } else {
            AsyncImage(
                model = photoUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        }
        if (contentDescription != null) {
            Box(
                Modifier
                    .fillMaxSize()
                    .semantics { this.contentDescription = contentDescription }
            )
        }
    }
}

enum class GarmentSilhouette { Blazer, Trouser, Shoe, Coat, Hat }

/** One-line pattern-drafting sketches — pure Path arithmetic, no vector assets. */
private fun silhouettePath(kind: GarmentSilhouette, size: Size): Path? {
    val w = size.width
    val h = size.height
    val p = Path()
    return when (kind) {
        GarmentSilhouette.Blazer -> {
            p.moveTo(w * 0.28f, h * 0.30f)
            p.lineTo(w * 0.72f, h * 0.30f)
            p.lineTo(w * 0.78f, h * 0.72f)
            p.lineTo(w * 0.22f, h * 0.72f)
            p.close()
            p.moveTo(w * 0.50f, h * 0.30f)
            p.lineTo(w * 0.50f, h * 0.48f)
            p
        }
        GarmentSilhouette.Trouser -> {
            p.moveTo(w * 0.34f, h * 0.22f)
            p.lineTo(w * 0.66f, h * 0.22f)
            p.lineTo(w * 0.68f, h * 0.80f)
            p.lineTo(w * 0.54f, h * 0.80f)
            p.lineTo(w * 0.50f, h * 0.50f)
            p.lineTo(w * 0.46f, h * 0.80f)
            p.lineTo(w * 0.32f, h * 0.80f)
            p.close()
            p
        }
        GarmentSilhouette.Shoe -> {
            p.moveTo(w * 0.20f, h * 0.62f)
            p.lineTo(w * 0.46f, h * 0.46f)
            p.lineTo(w * 0.80f, h * 0.56f)
            p.lineTo(w * 0.84f, h * 0.68f)
            p.lineTo(w * 0.20f, h * 0.68f)
            p.close()
            p
        }
        GarmentSilhouette.Coat -> {
            p.moveTo(w * 0.30f, h * 0.26f)
            p.lineTo(w * 0.70f, h * 0.26f)
            p.lineTo(w * 0.74f, h * 0.82f)
            p.lineTo(w * 0.26f, h * 0.82f)
            p.close()
            p
        }
        GarmentSilhouette.Hat -> {
            p.moveTo(w * 0.18f, h * 0.68f)
            p.lineTo(w * 0.82f, h * 0.68f)
            p
        }
    }
}

/** The virtual hanger. Used by the photo tutorial and as an empty-state motif. */
@Composable
fun HangerRail(
    modifier: Modifier = Modifier,
    occupied: Boolean = false,
    garmentPalette: List<Color> = listOf(MuslinIvorie, CanvasLine)
) {
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        val railY = h * 0.30f
        val hookR = h * 0.10f
        val ink = Color(0xFF1B1A17)
        val stroke = size.minDimension * 0.018f

        // the rail — dashed basting stitch across the rail line
        val segments = 18
        val seg = w / segments
        repeat(segments) { i ->
            val x = i * seg
            drawLine(
                color = if (i < segments / 2) AgedBrass else CanvasLine,
                start = Offset(x, railY),
                end = Offset(x + seg * 0.6f, railY),
                strokeWidth = stroke
            )
        }

        // the hanger — hook, then the one continuous shoulder line
        val hook = Path().apply {
            moveTo(w * 0.5f, railY)
            lineTo(w * 0.5f, railY - hookR * 1.4f)
            cubicTo(
                w * 0.5f, railY - hookR * 2.4f,
                w * 0.66f, railY - hookR * 2.4f,
                w * 0.62f, railY - hookR * 1.1f
            )
        }
        drawPath(hook, color = ink, style = Stroke(width = stroke))

        val shoulder = Path().apply {
            moveTo(w * 0.5f, railY)
            lineTo(w * 0.18f, railY + h * 0.16f)
            lineTo(w * 0.82f, railY + h * 0.16f)
        }
        drawPath(shoulder, color = ink, style = Stroke(width = stroke))

        // the thread escapes the rail and ends in the drop
        drawLine(
            color = ink,
            start = Offset(w * 0.5f, railY),
            end = Offset(w * 0.5f, railY + h * 0.30f),
            strokeWidth = stroke
        )
        drawCircle(BastingRed, radius = size.minDimension * 0.024f, center = Offset(w * 0.5f, railY + h * 0.35f))

        // the garment itself, drawn only once the photo has been hung
        if (occupied) {
            val garmentTop = railY + h * 0.16f
            val path = Path().apply {
                moveTo(w * 0.30f, garmentTop)
                lineTo(w * 0.70f, garmentTop)
                lineTo(w * 0.76f, garmentTop + h * 0.34f)
                lineTo(w * 0.24f, garmentTop + h * 0.34f)
                close()
            }
            drawPath(path, brush = Brush.linearGradient(garmentPalette, Offset(w * 0.3f, garmentTop), Offset(w * 0.76f, garmentTop + h * 0.34f)))
            drawPath(path, color = ink.copy(alpha = 0.35f), style = Stroke(width = stroke))
        }
    }
}

/** Stitched care label — the brand's chip primitive. */
@Composable
fun CareLabel(text: String, modifier: Modifier = Modifier, tint: Color = MaterialTheme.colorScheme.onSurfaceVariant) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(4.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f))
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text, style = MaterialTheme.typography.bodySmall, color = tint)
    }
}

@Composable
private fun RailStrip(items: List<String>, accent: Color) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items.forEach { CareLabel(it, tint = accent) }
    }
}

/** A NBSP-free sentence-case bullet list; used by the tutorial screens. */
@Composable
private fun BulletList(lines: List<String>, modifier: Modifier = Modifier) {
    Column(modifier) {
        lines.forEach { line ->
            Row(Modifier.padding(vertical = 6.dp)) {
                Text("—", style = MaterialTheme.typography.bodyLarge, color = BastingRed)
                Spacer(Modifier.width(10.dp))
                Text(line, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun ToastBanner(message: String?, onDismiss: () -> Unit) {
    AnimatedVisibility(visible = message != null, enter = fadeIn(), exit = fadeOut()) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(Gutter),
            shape = RoundedCornerShape(14.dp)
        ) {
            Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Info, contentDescription = null, tint = BastingRed, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(10.dp))
                Text(
                    message.orEmpty(),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f)
                )
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Rounded.Close, contentDescription = "Dismiss", modifier = Modifier.size(18.dp))
                }
            }
        }
    }
}


/* ═══════════════════════════════════════════════════════════════════════════
 * 7 · THE FIVE DECLARATION STEPS
 *     welcome → gender lens → climate → lifestyle → goals
 * ═══════════════════════════════════════════════════════════════════════════ */

@Composable
fun OnboardingWelcomeStep(onBegin: () -> Unit, onSkip: () -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .padding(horizontal = Gutter)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.Center
    ) {
        Spacer(Modifier.height(24.dp))
        HangerRail(
            modifier = Modifier
                .fillMaxWidth()
                .height(200.dp),
            occupied = false
        )
        Spacer(Modifier.height(28.dp))
        Eyebrow(OnboardingContent.Copy.WELCOME_EYEBROW)
        Spacer(Modifier.height(10.dp))
        Text(OnboardingContent.Copy.WELCOME_TITLE, style = MaterialTheme.typography.displayLarge)
        Spacer(Modifier.height(12.dp))
        Text(
            OnboardingContent.Copy.WELCOME_BODY,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(22.dp))
        RailStrip(listOf("5 declarations", "12 quiz looks", "your rail, your rules"), BastingRed)
        Spacer(Modifier.height(28.dp))
        BrandPrimaryButton(OnboardingContent.Copy.WELCOME_CTA, onBegin)
        Spacer(Modifier.height(8.dp))
        BrandGhostButton(OnboardingContent.Copy.WELCOME_SKIP, onSkip)
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
fun OnboardingGenderLensStep(
    selectedId: String?,
    onSelect: (String) -> Unit,
    onNext: () -> Unit,
    onBack: () -> Unit,
    onSkipStep: () -> Unit
) {
    OnboardingScaffold(
        stepLabel = "gender", stepIndex = 1, stepCount = 5,
        onBack = onBack, skipLabel = null, onSkip = onSkipStep, progress = 0.30f
    ) { inner ->
        Column(inner.verticalScroll(rememberScrollState())) {
            Spacer(Modifier.height(12.dp))
            Eyebrow("Declaration 1 of 4")
            Spacer(Modifier.height(8.dp))
            StepTitle(OnboardingContent.Copy.G_LENS_TITLE, OnboardingContent.Copy.G_LENS_BODY)
            Spacer(Modifier.height(20.dp))
            OnboardingContent.genderLens.forEach { option ->
                OptionTile(
                    option = option,
                    selected = option.id == selectedId,
                    multi = false,
                    onClick = { onSelect(option.id) }
                )
                Spacer(Modifier.height(10.dp))
            }
            Spacer(Modifier.height(16.dp))
            BrandPrimaryButton("Continue", onNext, enabled = selectedId != null)
            Spacer(Modifier.height(28.dp))
        }
    }
}

@Composable
fun OnboardingClimateStep(
    selectedId: String?,
    hemisphere: String,
    onSelect: (String) -> Unit,
    onHemisphere: (String) -> Unit,
    onNext: () -> Unit,
    onBack: () -> Unit
) {
    OnboardingScaffold(
        stepLabel = "climate", stepIndex = 2, stepCount = 5,
        onBack = onBack, skipLabel = null, onSkip = null, progress = 0.50f
    ) { inner ->
        Column(inner.verticalScroll(rememberScrollState())) {
            Spacer(Modifier.height(12.dp))
            Eyebrow("Declaration 2 of 4")
            Spacer(Modifier.height(8.dp))
            StepTitle(OnboardingContent.Copy.CLIMATE_TITLE, OnboardingContent.Copy.CLIMATE_BODY)
            Spacer(Modifier.height(20.dp))
            OnboardingContent.climates.forEach { option ->
                OptionTile(option, option.id == selectedId, multi = false, onClick = { onSelect(option.id) })
                Spacer(Modifier.height(10.dp))
            }
            Spacer(Modifier.height(18.dp))
            RailStrip(listOf("Hemisphere decides the season"), AgedBrass)
            Spacer(Modifier.height(12.dp))
            OnboardingContent.hemispheres.forEach { (id, label) ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(46.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(if (hemisphere == id) BastingRed.copy(alpha = 0.10f) else Color.Transparent)
                        .border(
                            1.dp,
                            if (hemisphere == id) BastingRed else MaterialTheme.colorScheme.outline.copy(alpha = 0.5f),
                            RoundedCornerShape(12.dp)
                        )
                        .selectable(hemisphere == id, role = Role.RadioButton) { onHemisphere(id) }
                        .padding(horizontal = 14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(label, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    SelectionMark(hemisphere == id, multi = false)
                }
                Spacer(Modifier.height(8.dp))
            }
            Spacer(Modifier.height(10.dp))
            BrandPrimaryButton("Continue", onNext, enabled = selectedId != null)
            Spacer(Modifier.height(28.dp))
        }
    }
}

@Composable
fun OnboardingLifestyleStep(
    selected: Set<String>,
    onToggle: (String) -> Unit,
    onNext: () -> Unit,
    onBack: () -> Unit
) {
    OnboardingScaffold(
        stepLabel = "lifestyle", stepIndex = 3, stepCount = 5,
        onBack = onBack, skipLabel = null, onSkip = null, progress = 0.70f
    ) { inner ->
        Column(inner.verticalScroll(rememberScrollState())) {
            Spacer(Modifier.height(12.dp))
            Eyebrow("Declaration 3 of 4")
            Spacer(Modifier.height(8.dp))
            StepTitle(OnboardingContent.Copy.LIFESTYLE_TITLE, OnboardingContent.Copy.LIFESTYLE_BODY)
            Spacer(Modifier.height(20.dp))
            OnboardingContent.lifestyle.forEach { option ->
                OptionTile(option, option.id in selected, multi = true, onClick = { onToggle(option.id) })
                Spacer(Modifier.height(10.dp))
            }
            Spacer(Modifier.height(16.dp))
            BrandPrimaryButton("Continue", onNext, enabled = selected.isNotEmpty())
            Spacer(Modifier.height(6.dp))
            Text(
                if (selected.isEmpty()) "Pick at least one — the week has to go somewhere." else "${selected.size} selected",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(28.dp))
        }
    }
}

@Composable
fun OnboardingGoalsStep(
    selected: Set<String>,
    onToggle: (String) -> Unit,
    onNext: () -> Unit,
    onBack: () -> Unit
) {
    OnboardingScaffold(
        stepLabel = "goals", stepIndex = 4, stepCount = 5,
        onBack = onBack, skipLabel = null, onSkip = null, progress = 0.90f
    ) { inner ->
        Column(inner.verticalScroll(rememberScrollState())) {
            Spacer(Modifier.height(12.dp))
            Eyebrow("Declaration 4 of 4")
            Spacer(Modifier.height(8.dp))
            StepTitle(OnboardingContent.Copy.GOALS_TITLE, OnboardingContent.Copy.GOALS_BODY)
            Spacer(Modifier.height(20.dp))
            OnboardingContent.goals.forEach { option ->
                OptionTile(option, option.id in selected, multi = true, onClick = { onToggle(option.id) })
                Spacer(Modifier.height(10.dp))
            }
            Spacer(Modifier.height(16.dp))
            BrandPrimaryButton("To the fitting", onNext, enabled = selected.isNotEmpty())
            Spacer(Modifier.height(28.dp))
        }
    }
}
/* ═══════════════════════════════════════════════════════════════════════════
 * 8 · THE STYLE QUIZ  +  PROFILE REVEAL
 *     12 questions · 48 looks · 3 taps per screen · 1 honest profile
 * ═══════════════════════════════════════════════════════════════════════════ */

@Composable
fun OnboardingQuizScreen(
    question: QuizQuestion,
    index: Int,
    total: Int,
    selectedLookId: String?,
    onAnswer: (String) -> Unit,
    onNext: () -> Unit,
    onBack: () -> Unit,
    onSkipRest: () -> Unit
) {
    val progress = index.toFloat() / total.toFloat()

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            Column(Modifier.padding(horizontal = Gutter, vertical = 8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Previous question")
                    }
                    Spacer(Modifier.weight(1f))
                    Text(
                        "${index + 1} of $total",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = onSkipRest) {
                        Text("Skip the rest", style = MaterialTheme.typography.bodySmall, color = TwillGrey)
                    }
                }
                StitchProgress(progress, Modifier.fillMaxWidth().padding(start = 12.dp))
            }
        }
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Gutter)
        ) {
            Spacer(Modifier.height(8.dp))
            Eyebrow(question.eyebrow)
            Spacer(Modifier.height(10.dp))
            Text(question.prompt, style = MaterialTheme.typography.displayMedium)
            Spacer(Modifier.height(6.dp))
            Text(
                question.helper,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(18.dp))

            // 2×2 look grid — the image pick
            question.looks.chunked(2).forEach { row ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    row.forEach { look ->
                        LookCard(
                            look = look,
                            selected = look.id == selectedLookId,
                            onClick = { onAnswer(look.id) },
                            modifier = Modifier.weight(1f)
                        )
                    }
                    if (row.size == 1) Spacer(Modifier.weight(1f))
                }
                Spacer(Modifier.height(12.dp))
            }

            Spacer(Modifier.height(6.dp))
            Text(
                if (selectedLookId == null) "Nothing picked yet — go with your first instinct."
                else "Pinned. Change it any time before the next question.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(14.dp))
            BrandPrimaryButton(
                text = if (index == total - 1) "See my fitting" else "Next look",
                onClick = onNext,
                enabled = selectedLookId != null
            )
            Spacer(Modifier.height(28.dp))
        }
    }
}

@Composable
private fun LookCard(
    look: QuizLook,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val shape = RoundedCornerShape(CardRadius)
    val borderW = if (selected) 2.dp else 1.dp
    Column(
        modifier = modifier
            .clip(shape)
            .background(MaterialTheme.colorScheme.surface)
            .border(
                borderW,
                if (selected) BastingRed else MaterialTheme.colorScheme.outline.copy(alpha = 0.45f),
                shape
            )
            .clickable(onClick = onClick)
            .padding(10.dp)
    ) {
        Box(Modifier.fillMaxWidth().aspectRatio(1f)) {
            LookPlate(
                palette = look.palette,
                photoUrl = look.photoUrl,
                silhouette = silhouetteFor(look),
                modifier = Modifier
                    .fillMaxSize()
                    .clip(RoundedCornerShape(12.dp)),
                contentDescription = "${look.label}. ${look.caption}"
            )
            if (selected) {
                Box(
                    Modifier
                        .align(Alignment.TopEnd)
                        .padding(6.dp)
                        .size(26.dp)
                        .clip(CircleShape)
                        .background(BastingRed),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Rounded.Check, contentDescription = null, tint = Color.White, modifier = Modifier.size(16.dp))
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        Text(look.label, style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(3.dp))
        Text(
            look.caption,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            look.palette.take(3).forEach { c ->
                Box(
                    Modifier
                        .size(14.dp)
                        .clip(CircleShape)
                        .background(c)
                        .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.4f), CircleShape)
                )
            }
        }
    }
}

/** Maps a look to the one-line garment drawing that best represents it. */
private fun silhouetteFor(look: QuizLook): GarmentSilhouette = when {
    look.id == "q05_outerwear" || look.styles.containsKey("Old Money") -> GarmentSilhouette.Coat
    look.id == "q06_shoes" -> GarmentSilhouette.Shoe
    look.id == "q08_fit" || look.id == "q09_pattern" -> GarmentSilhouette.Trouser
    look.styles.containsKey("Streetwear") -> GarmentSilhouette.Blazer
    else -> GarmentSilhouette.Blazer
}

@Composable
fun OnboardingRevealScreen(
    profile: StyleProfile,
    onContinue: () -> Unit,
    onRetake: () -> Unit
) {
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = Gutter)
    ) {
        Spacer(Modifier.height(28.dp))
        HangerRail(
            modifier = Modifier.fillMaxWidth().height(150.dp),
            occupied = true,
            garmentPalette = paletteColors(profile.paletteAffinity)
        )
        Spacer(Modifier.height(22.dp))
        Eyebrow("Your fitting profile")
        Spacer(Modifier.height(10.dp))
        Text(OnboardingContent.Copy.REVEAL_TITLE, style = MaterialTheme.typography.displayLarge)
        Spacer(Modifier.height(10.dp))
        Text(
            OnboardingContent.Copy.REVEAL_BODY,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(20.dp))

        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(CardRadius)
        ) {
            Column(Modifier.padding(16.dp)) {
                Text("Your line", style = MaterialTheme.typography.labelLarge, color = BastingRed)
                Spacer(Modifier.height(6.dp))
                Text(
                    StyleProfileEngine.headline(profile),
                    style = MaterialTheme.typography.headlineMedium
                )
                Spacer(Modifier.height(14.dp))

                Text("Palette", style = MaterialTheme.typography.labelLarge, color = BastingRed)
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    profile.paletteAffinity.forEach { name ->
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Box(
                                Modifier
                                    .size(34.dp)
                                    .clip(CircleShape)
                                    .background(colorFor(name))
                                    .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.5f), CircleShape)
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(name, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
                Spacer(Modifier.height(16.dp))

                Text("Formality", style = MaterialTheme.typography.labelLarge, color = BastingRed)
                Spacer(Modifier.height(6.dp))
                Text(profile.formalityBand, style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(16.dp))

                Text("The measurements", style = MaterialTheme.typography.labelLarge, color = BastingRed)
                Spacer(Modifier.height(8.dp))
                RailStrip(
                    listOf(
                        "fit: ${profile.genderLens}",
                        "climate: ${profile.climate}",
                        "${profile.hemisphere}ern hemisphere",
                        "week: ${profile.lifestyle.joinToString(", ")}",
                        "goals: ${profile.goals.joinToString(", ")}"
                    ),
                    AgedBrass
                )
            }
        }

        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            profile.topStyles.forEach { style ->
                CareLabel(style, tint = MaterialTheme.colorScheme.onSurface)
            }
        }
        Spacer(Modifier.height(10.dp))
        Text(
            profile.lifestyle.take(3).joinToString(" · ") { it.replaceFirstChar { c -> c.uppercase() } }
                .ifBlank { "Week not set" } + " — every suggestion will be filtered through this.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(Modifier.height(22.dp))
        BrandPrimaryButton("Teach me to hang a photo", onContinue)
        Spacer(Modifier.height(8.dp))
        BrandGhostButton("Retake the fitting quiz", onRetake)
        Spacer(Modifier.height(28.dp))
    }
}

/** AppConstants.colorPreferences name -> a swatch colour, for the reveal chips. */
private fun colorFor(name: String): Color = when (name) {
    "Black"  -> Color(0xFF1B1A17)
    "White"  -> Color(0xFFF7F5F0)
    "Gray"   -> Color(0xFF8A8A8A)
    "Beige"  -> Color(0xFFC19A6B)
    "Navy"   -> Color(0xFF1B2A4A)
    "Brown"  -> Color(0xFF6E4A2B)
    "Olive"  -> Color(0xFF77754E)
    else     -> Color(0xFF6B6355)
}

private fun paletteColors(names: List<String>): List<Color> =
    names.take(3).map { colorFor(it) }.ifEmpty { listOf(MuslinIvorie, CanvasLine) }


/* ═══════════════════════════════════════════════════════════════════════════
 * 9 · PHOTO TUTORIAL — drag-and-drop onto a virtual hanger
 *     Real pointerInput drag with a live detach/re-attach spring, plus a tap
 *     fallback for anyone who can't drag (WCAG 2.5.7 dragging movements).
 * ═══════════════════════════════════════════════════════════════════════════ */

@Composable
fun PhotoTutorialScreen(
    done: Boolean,
    onComplete: () -> Unit,
    onBack: () -> Unit,
    onOpenPicker: () -> Unit
) {
    var dragOffset by remember { mutableStateOf(Offset.Zero) }
    var dropped by rememberSaveable { mutableStateOf(false) }
    var hangZone by remember { mutableStateOf(Rect.Zero) }
    var attempts by remember { mutableIntStateOf(0) }

    val landed = dropped || done
    val spring by animateFloatAsState(
        targetValue = if (landed) 1f else 0f,
        animationSpec = spring(dampingRatio = 0.55f, stiffness = 320f),
        label = "hang"
    )

    val hint = when {
        landed -> OnboardingContent.Copy.PHOTO_DONE
        attempts > 0 -> "Closer. Aim for the hanger's crossbar."
        else -> OnboardingContent.Copy.PHOTO_PROMPT
    }

    OnboardingScaffold(
        stepLabel = "photo", stepIndex = 5, stepCount = 5,
        onBack = onBack, skipLabel = null, onSkip = null, progress = 0.75f
    ) { inner ->
        Column(
            inner.verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(Modifier.height(10.dp))
            Eyebrow("The fitting, hands-on")
            Spacer(Modifier.height(8.dp))
            StepTitle(OnboardingContent.Copy.PHOTO_TITLE, OnboardingContent.Copy.PHOTO_BODY)
            Spacer(Modifier.height(18.dp))

            // ── the drop surface ────────────────────────────────────────────
            Box(
                Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f)
                    .clip(RoundedCornerShape(24.dp))
                    .background(
                        Brush.verticalGradient(listOf(MuslinIvorie, Color(0xFFF1ECE1)))
                    )
                    .border(
                        width = if (landed) 2.dp else 1.dp,
                        color = if (landed) BastingRed else CanvasLine,
                        shape = RoundedCornerShape(24.dp)
                    )
                    .onGloballyPositioned { coords ->
                        val b = coords.boundsInParent()
                        hangZone = Rect(
                            left = b.left + b.width * 0.18f,
                            top = b.top + b.height * 0.16f,
                            right = b.right - b.width * 0.18f,
                            bottom = b.top + b.height * 0.62f
                        )
                    }
            ) {
                HangerRail(
                    modifier = Modifier.fillMaxSize(),
                    occupied = landed,
                    garmentPalette = listOf(Color(0xFFC19A6B), Color(0xFF1B1A17))
                )

                // the loose photo card the user drags
                Box(
                    Modifier
                        .align(Alignment.BottomCenter)
                        .offset {
                            IntOffset(
                                (dragOffset.x * (1f - spring)).roundToInt(),
                                ((dragOffset.y - 120f * spring) * (1f - spring)).roundToInt()
                            )
                        }
                        .padding(bottom = 22.dp)
                        .size(width = 108.dp, height = 132.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(MaterialTheme.colorScheme.surface)
                        .border(
                            1.dp,
                            if (landed) CanvasLine else AgedBrass,
                            RoundedCornerShape(14.dp)
                        )
                        .pointerInput(landed) {
                            if (landed) return@pointerInput
                            detectDragGestures(
                                onDragEnd = {
                                    attempts += 1
                                    // Released inside the hang zone? Then it's hung.
                                    val releasedAt = Offset(
                                        hangZone.center.x + dragOffset.x,
                                        hangZone.center.y + dragOffset.y
                                    )
                                    if (hangZone.contains(releasedAt) || dragOffset.y < -60f) {
                                        dropped = true
                                    }
                                    dragOffset = Offset.Zero
                                },
                                onDrag = { change, drag ->
                                    change.consume()
                                    dragOffset += drag
                                }
                            )
                        }
                        .clickable(enabled = !landed) {
                            attempts += 1
                            dropped = true
                        }
                        .semantics {
                            contentDescription =
                                "Sample garment photo. Drag it up onto the hanger, or double-tap to hang it."
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            Icons.Rounded.CheckCircle,
                            contentDescription = null,
                            tint = AgedBrass,
                            modifier = Modifier.size(30.dp)
                        )
                        Spacer(Modifier.height(8.dp))
                        Text("your photo", style = MaterialTheme.typography.bodySmall, color = TwillGrey)
                    }
                }

                if (!landed) {
                    Box(Modifier.align(Alignment.TopCenter).padding(top = 12.dp)) {
                        CareLabel("drag me up onto the hanger", tint = BastingRed)
                    }
                }
            }

            Spacer(Modifier.height(12.dp))
            Text(
                hint,
                style = MaterialTheme.typography.bodyMedium,
                color = if (landed) BastingRed else MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(4.dp))
            Text(
                OnboardingContent.Copy.PHOTO_TAP_HINT,
                style = MaterialTheme.typography.bodySmall,
                color = TwillGrey,
                textAlign = TextAlign.Center
            )

            Spacer(Modifier.height(18.dp))
            BulletList(OnboardingContent.Copy.PHOTO_TIPS)

            Spacer(Modifier.height(18.dp))
            BrandPrimaryButton(
                text = if (landed) "That's the trick — next" else "Hang it for me",
                onClick = if (landed) onComplete else { { attempts += 1; dropped = true } }
            )
            Spacer(Modifier.height(8.dp))
            BrandGhostButton("Use a real photo instead", onOpenPicker)
            Spacer(Modifier.height(28.dp))
        }
    }
}


/* ═══════════════════════════════════════════════════════════════════════════
 * 10 · FIRST-ITEM TUTORIAL with the sample wardrobe seed
 * ═══════════════════════════════════════════════════════════════════════════ */

@Composable
fun FirstItemTutorialScreen(
    seedInstalled: Boolean,
    seedCount: Int,
    onInstallSeed: () -> Unit,
    onClearSeed: () -> Unit,
    onFinish: () -> Unit,
    onBack: () -> Unit
) {
    var previewId by rememberSaveable { mutableStateOf<String?>(null) }

    OnboardingScaffold(
        stepLabel = "first-item", stepIndex = 5, stepCount = 5,
        onBack = onBack, skipLabel = null, onSkip = null, progress = 0.95f
    ) { inner ->
        Column(inner.verticalScroll(rememberScrollState())) {
            Spacer(Modifier.height(10.dp))
            Eyebrow("The rail, filled")
            Spacer(Modifier.height(8.dp))
            StepTitle(OnboardingContent.Copy.FIRST_ITEM_TITLE, OnboardingContent.Copy.FIRST_ITEM_BODY)
            Spacer(Modifier.height(16.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CareLabel("$seedCount starter pieces", tint = BastingRed)
                CareLabel(if (seedInstalled) "on your rail" else "not yet hung", tint = TwillGrey)
            }
            Spacer(Modifier.height(16.dp))

            // ── the sample rail ────────────────────────────────────────────
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(340.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(OnboardingContent.starterWardrobe, key = { "${it.category}-${it.type}" }) { seed ->
                    val open = previewId == seed.type
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(16.dp))
                            .background(MaterialTheme.colorScheme.surface)
                            .border(
                                1.dp,
                                if (open) BastingRed else MaterialTheme.colorScheme.outline.copy(alpha = 0.4f),
                                RoundedCornerShape(16.dp)
                            )
                            .clickable { previewId = if (open) null else seed.type }
                            .padding(12.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            LookPlate(
                                palette = listOf(Color(android.graphics.Color.parseColor(seed.colorHex)), MuslinIvorie),
                                silhouette = silhouetteForCategory(seed.category),
                                modifier = Modifier
                                    .size(56.dp)
                                    .clip(RoundedCornerShape(10.dp))
                            )
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(seed.type, style = MaterialTheme.typography.titleMedium)
                                Spacer(Modifier.height(2.dp))
                                Text(
                                    "${ItemCategory.emoji(seed.category)}  ${seed.category} · ${seed.colorName} · ${seed.fit}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Text(seed.style, style = MaterialTheme.typography.bodySmall, color = AgedBrass)
                        }
                        AnimatedVisibility(visible = open) {
                            Column {
                                Spacer(Modifier.height(10.dp))
                                HorizontalDivider(color = CanvasLine)
                                Spacer(Modifier.height(8.dp))
                                Text(seed.note, style = MaterialTheme.typography.bodyMedium)
                                Spacer(Modifier.height(6.dp))
                                RailStrip(listOf(seed.pattern, seed.season, seed.brand), TwillGrey)
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(16.dp))
            BrandGhostButton(
                text = if (seedInstalled) OnboardingContent.Copy.FIRST_ITEM_CLEAR
                else OnboardingContent.Copy.FIRST_ITEM_KEEP,
                onClick = { if (seedInstalled) onClearSeed() else onInstallSeed() }
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "Tap any piece to see why we hung it. They're marked \"${OnboardingContent.SEED_BRAND_MARKER}\" so you can remove them in one go later.",
                style = MaterialTheme.typography.bodySmall,
                color = TwillGrey
            )
            Spacer(Modifier.height(16.dp))

            Spacer(Modifier.height(14.dp))
            Text("Now the house rules.", style = MaterialTheme.typography.headlineMedium)
            Spacer(Modifier.height(8.dp))
            BulletList(
                listOf(
                    "One piece per photo. We sort the rest.",
                    "Only use the pieces you actually own — that's what makes the suggestions honest.",
                    "Rate a look after wearing it. The stylist learns from the rating, not from guesses."
                )
            )
            Spacer(Modifier.height(20.dp))
            BrandPrimaryButton(OnboardingContent.Copy.FIRST_ITEM_CTA, onFinish)
            Spacer(Modifier.height(28.dp))
        }
    }
}


private fun silhouetteForCategory(category: String): GarmentSilhouette = when (category) {
    ItemCategory.SHOES   -> GarmentSilhouette.Shoe
    ItemCategory.BOTTOM  -> GarmentSilhouette.Trouser
    ItemCategory.OUTERWEAR -> GarmentSilhouette.Coat
    ItemCategory.ACCESSORY -> GarmentSilhouette.Hat
    else                 -> GarmentSilhouette.Blazer
}


/* ═══════════════════════════════════════════════════════════════════════════
 * 11 · PERMISSION FLOWS, DONE RIGHT
 *     Rules implemented here:
 *       · never asked at app launch — only at point of use
 *       · explained in the user's own words BEFORE the system dialog
 *       · three states, not two: granted / denied / denied-forever
 *       · "denied forever" routes to app settings, never re-prompts into a void
 *       · photo access uses the Android Photo Picker, which needs NO permission
 *         on API 33+ — so on modern devices the photos permission is
 *         NOT_APPLICABLE and we say so rather than requesting something we
 *         don't need
 *       · skipping a permission never blocks the flow
 * ═══════════════════════════════════════════════════════════════════════════ */

@Composable
private fun rememberPermission(permission: String?): PermissionController {
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() }

    // A null permission means the platform doesn't need one (API 33+ + Photo Picker),
    // so we report NOT_APPLICABLE and never string the user along with a fake ask.
    if (permission == null) {
        return remember {
            PermissionController(PermissionUiState.NOT_APPLICABLE, request = {}, openSettings = {})
        }
    }

    fun grantedNow(): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    fun deniedForever(): Boolean {
        if (activity == null) return false
        if (grantedNow()) return false
        // No rationale available after a denial == the user chose "Don't allow" twice.
        return !ActivityCompat.shouldShowRequestPermissionRationale(activity, permission)
    }

    var state by remember { mutableStateOf(derive(grantedNow(), deniedForever())) }

    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        state = if (isGranted) PermissionUiState.GRANTED
        else if (deniedForever()) PermissionUiState.DENIED_FOREVER
        else PermissionUiState.DENIED
    }

    return PermissionController(
        state = state,
        // Called only from behind the rationale card, so the system dialog is
        // never the user's first sight of the request.
        request = { launcher.launch(permission) },
        openSettings = {
            context.startActivity(
                Intent(
                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.fromParts("package", context.packageName, null)
                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    )
}

private fun derive(granted: Boolean, deniedForever: Boolean): PermissionUiState = when {
    granted -> PermissionUiState.GRANTED
    deniedForever -> PermissionUiState.DENIED_FOREVER
    else -> PermissionUiState.NOT_ASKED
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

@Composable
private fun PermissionRow(
    icon: ImageVector,
    title: String,
    why: String,
    controller: PermissionController,
    optionalNote: String
) {
    var showRationale by rememberSaveable { mutableStateOf(false) }

    val statusLabel = when (controller.state) {
        PermissionUiState.GRANTED -> "Allowed"
        PermissionUiState.NOT_ASKED -> "Not asked"
        PermissionUiState.DENIED -> "Not now"
        PermissionUiState.DENIED_FOREVER -> "Off in system settings"
        PermissionUiState.NOT_APPLICABLE -> "Not needed here"
    }
    val statusColor = when (controller.state) {
        PermissionUiState.GRANTED -> FieldMoss
        PermissionUiState.DENIED_FOREVER -> MulledWine
        else -> TwillGrey
    }

    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surface)
            .border(1.dp, CanvasLine, RoundedCornerShape(16.dp))
            .padding(14.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = null, tint = AgedBrass, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(2.dp))
                Text(why, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            CareLabel(statusLabel, tint = statusColor)
            Spacer(Modifier.weight(1f))
            when (controller.state) {
                PermissionUiState.GRANTED -> Icon(
                    Icons.Rounded.CheckCircle,
                    contentDescription = "Allowed",
                    tint = FieldMoss,
                    modifier = Modifier.size(20.dp)
                )
                PermissionUiState.DENIED_FOREVER -> TextButton(onClick = controller.openSettings) {
                    Icon(Icons.Rounded.Settings, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Open settings", style = MaterialTheme.typography.labelLarge)
                }
                PermissionUiState.NOT_APPLICABLE -> Text(optionalNote, style = MaterialTheme.typography.bodySmall, color = TwillGrey)
                else -> TextButton(onClick = { showRationale = true }) {
                    Text("Allow", style = MaterialTheme.typography.labelLarge, color = BastingRed)
                }
            }
        }
        AnimatedVisibility(visible = showRationale) {
            Column(Modifier.padding(top = 10.dp)) {
                HorizontalDivider(color = CanvasLine)
                Spacer(Modifier.height(10.dp))
                Text(
                    "We only need this the moment you use the feature — never in the background. " +
                        "You can say no and still use everything else.",
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = { showRationale = false; controller.request() },
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = BastingRed, contentColor = Color.White)
                    ) { Text("Got it — ask me", style = MaterialTheme.typography.labelLarge) }
                    TextButton(onClick = { showRationale = false }) {
                        Text("Not now", style = MaterialTheme.typography.labelLarge, color = TwillGrey)
                    }
                }
            }
        }
    }
}

@Composable
fun PermissionStepScreen(
    onFinish: () -> Unit,
    onBack: () -> Unit
) {
    val photosPermission = remember {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) null // Photo Picker needs none
        else OnboardingContent.legacyPhotoPermission
    }

    val camera = rememberPermission(Manifest.permission.CAMERA)
    val location = rememberPermission(Manifest.permission.ACCESS_COARSE_LOCATION)
    val photos = rememberPermission(photosPermission)

    OnboardingScaffold(
        stepLabel = "permissions", stepIndex = 5, stepCount = 5,
        onBack = onBack, skipLabel = "Not now — finish", onSkip = onFinish, progress = 1f
    ) { inner ->
        Column(inner.verticalScroll(rememberScrollState())) {
            Spacer(Modifier.height(10.dp))
            Eyebrow("Finishing touches")
            Spacer(Modifier.height(8.dp))
            StepTitle(OnboardingContent.Copy.PERM_TITLE, OnboardingContent.Copy.PERM_BODY)
            Spacer(Modifier.height(18.dp))

            PermissionRow(
                icon = Icons.Rounded.PhotoCamera,
                title = "Camera",
                why = "So you can photograph a piece straight onto the hanger instead of hunting for an old photo.",
                controller = camera,
                optionalNote = ""
            )
            Spacer(Modifier.height(10.dp))

            PermissionRow(
                icon = Icons.Rounded.PhotoLibrary,
                title = "Photos",
                why = "Only if you'd rather pull an existing picture. The Android photo picker lets you share one image without handing over the library.",
                controller = photos,
                optionalNote = "Picker needs no permission on this Android version"
            )
            Spacer(Modifier.height(10.dp))

            PermissionRow(
                icon = Icons.Rounded.LocationOn,
                title = "Approximate location",
                why = "Reads the local weather so a suggestion isn't a coat in a heatwave. Coarse only — never a precise fix. You can set the city by hand instead.",
                controller = location,
                optionalNote = ""
            )

            Spacer(Modifier.height(18.dp))
            RailStrip(listOf("asked once", "at the moment of use", "never in the background"), AgedBrass)
            Spacer(Modifier.height(20.dp))
            BrandPrimaryButton("Open my atelier", onFinish)
            Spacer(Modifier.height(28.dp))
        }
    }
}


/* ═══════════════════════════════════════════════════════════════════════════
 * 12 · FLOW HOST + NAVIGATION PATCH
 *
 * In MainActivity.kt, replace:
 *     NavHost(navController = navController, startDestination = "login") { ... }
 * with the two destinations below. `login` and `main_shell` are unchanged, so
 * the existing route graph and all five tabs keep working.
 * ═══════════════════════════════════════════════════════════════════════════ */

@Composable
fun OnboardingHost(
    onFinished: () -> Unit,
    viewModel: OnboardingViewModel
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val toast by viewModel.toast.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val realPhotoPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        // The real picker is wired to AddItemScreen; here we only prove the
        // hand-off, matching that screen's existing contract.
        if (uri != null) viewModel.completePhotoTutorial()
    }

    if (state.loading) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            LinearProgressIndicator(color = BastingRed)
        }
        return
    }

    Box(Modifier.fillMaxSize()) {
        when (state.stage) {

            OnboardingStage.DECLARATIONS -> when (state.declarationPage) {
                0 -> OnboardingWelcomeStep(
                    onBegin = { viewModel.goToPage(1) },
                    onSkip = {
                        viewModel.skipToDefaults()
                        scope.launch { viewModel.complete() }
                    }
                )
                1 -> OnboardingGenderLensStep(
                    selectedId = state.genderLens,
                    onSelect = viewModel::setGenderLens,
                    onNext = { viewModel.nextPage {} },
                    onBack = { viewModel.previousPage {} },
                    onSkipStep = { viewModel.goToPage(2) }
                )
                2 -> OnboardingClimateStep(
                    selectedId = state.climate,
                    hemisphere = state.hemisphere,
                    onSelect = viewModel::setClimate,
                    onHemisphere = viewModel::setHemisphere,
                    onNext = { viewModel.nextPage {} },
                    onBack = { viewModel.previousPage {} }
                )
                3 -> OnboardingLifestyleStep(
                    selected = state.lifestyle,
                    onToggle = viewModel::toggleLifestyle,
                    onNext = { viewModel.nextPage {} },
                    onBack = { viewModel.previousPage {} }
                )
                else -> OnboardingGoalsStep(
                    selected = state.goals,
                    onToggle = viewModel::toggleGoal,
                    onNext = { viewModel.nextPage {} },
                    onBack = { viewModel.previousPage {} }
                )
            }

            OnboardingStage.QUIZ -> {
                val q = state.currentQuestion
                if (q == null) {
                    viewModel.toPhotoTutorial()
                } else {
                    OnboardingQuizScreen(
                        question = q,
                        index = state.quizIndex,
                        total = state.quizSize,
                        selectedLookId = state.quizAnswers[q.id],
                        onAnswer = { lookId -> viewModel.answer(q.id, lookId) },
                        onNext = { viewModel.nextQuestion {} },
                        onBack = { viewModel.previousQuestion {} },
                        onSkipRest = {
                            // Skip → minimal defaults, but keep whatever was already answered
                            viewModel.toPhotoTutorial()
                        }
                    )
                }
            }

            OnboardingStage.REVEAL -> {
                val profile = state.profile ?: StyleProfileEngine.defaultProfile()
                OnboardingRevealScreen(
                    profile = profile,
                    onContinue = { viewModel.toPhotoTutorial() },
                    onRetake = viewModel::retakeQuiz
                )
            }

            OnboardingStage.PHOTO_TUTORIAL -> PhotoTutorialScreen(
                done = state.photoTutorialDone,
                onComplete = {
                    viewModel.installSeed()
                    viewModel.completePhotoTutorial()
                },
                onBack = { viewModel.goToQuestion(OnboardingContent.quiz.size - 1) },
                onOpenPicker = { realPhotoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
            )

            OnboardingStage.FIRST_ITEM -> FirstItemTutorialScreen(
                seedInstalled = state.seedInstalled,
                seedCount = OnboardingContent.starterWardrobe.size,
                onInstallSeed = viewModel::installSeed,
                onClearSeed = viewModel::clearSeed,
                onFinish = viewModel::completeFirstItemTutorial,
                onBack = viewModel::toPhotoTutorial
            )

            OnboardingStage.PERMISSIONS -> PermissionStepScreen(
                onFinish = {
                    scope.launch {
                        viewModel.complete()
                        onFinished()
                    }
                },
                onBack = viewModel::toPhotoTutorial
            )
        }

        ToastBanner(toast) { viewModel.consumeToast() }
    }
}

/* ---------------------------------------------------------------------------
 * NAVIGATION PATCH — MainActivity.kt
 *
 *   val app = LocalContext.current.applicationContext as Application
 *   val onboardingVm: OnboardingViewModel = viewModel(factory = viewModelFactory {
 *       initializer { OnboardingViewModel(app) }
 *   })
 *
 *   NavHost(navController = navController, startDestination = "onboarding") {
 *       composable("onboarding") {
 *           OnboardingHost(
 *               viewModel = onboardingVm,
 *               onFinished = {
 *                   navController.navigate("main_shell") {
 *                       popUpTo("onboarding") { inclusive = true }
 *                   }
 *               }
 *           )
 *       }
 *       composable("login") { LoginScreen(onLoginClick = { ... }) }
 *       composable("main_shell") { MainShell(...) }
 *       // remaining routes unchanged
 *   }
 *
 * Login already offers "Skip for now — Continue as Guest", which routes
 * straight to main_shell and leaves onboarding_completed = false. Open the
 * Profile tab's "Redo the fitting" row to launch the onboarding route again —
 * the DataStore state means a returning user resumes rather than restarts.
 * ------------------------------------------------------------------------- */
