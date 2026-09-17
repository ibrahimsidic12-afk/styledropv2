package com.example.ui.wardrobe

import com.example.models.WardrobeItem

/** The four sort orders exposed in the wardrobe toolbar. */
enum class SortOption(val label: String) {
    RECENTLY_ADDED("Recently added"),
    MOST_WORN("Most worn"),
    BY_COLOR("By color"),
    BY_SEASON("By season")
}

/** Filter sidebar sections — chips inside one section are OR-ed, sections are AND-ed. */
enum class FilterSection(val title: String) {
    COLOR("Color"),
    SEASON("Season"),
    FORMALITY("Formality"),
    FABRIC("Fabric")
}

data class FilterState(
    val colors: Set<String> = emptySet(),
    val seasons: Set<String> = emptySet(),
    val formalities: Set<String> = emptySet(),
    val fabrics: Set<String> = emptySet(),
    val favoritesOnly: Boolean = false
) {
    val isActive: Boolean
        get() = colors.isNotEmpty() || seasons.isNotEmpty() || formalities.isNotEmpty() ||
            fabrics.isNotEmpty() || favoritesOnly

    fun matches(item: WardrobeItem): Boolean {
        if (favoritesOnly && !item.isFavoriteItem) return false
        if (colors.isNotEmpty() && item.color !in colors && item.secondaryColor !in colors) return false
        if (seasons.isNotEmpty() && item.season !in seasons) return false
        if (formalities.isNotEmpty() && item.formality !in formalities) return false
        if (fabrics.isNotEmpty() && item.fabric !in fabrics) return false
        return true
    }

    fun toggle(section: FilterSection, value: String): FilterState = when (section) {
        FilterSection.COLOR -> copy(colors = colors.toggle(value))
        FilterSection.SEASON -> copy(seasons = seasons.toggle(value))
        FilterSection.FORMALITY -> copy(formalities = formalities.toggle(value))
        FilterSection.FABRIC -> copy(fabrics = fabrics.toggle(value))
    }

    fun clearAll(): FilterState = copy(
        colors = emptySet(),
        seasons = emptySet(),
        formalities = emptySet(),
        fabrics = emptySet()
    )
}

private fun Set<String>.toggle(value: String): Set<String> =
    if (contains(value)) minus(value) else plus(value)

private val seasonOrder = listOf("All Season", "Spring/Fall", "Summer", "Winter")

private val colorFamilyOrder = listOf("Black", "White", "Gray", "Beige", "Brown", "Olive", "Navy")

private fun Int.orMax(): Int = if (this < 0) Int.MAX_VALUE else this

/** Applies the chosen sort order; stable, so items with equal keys keep their relative order. */
fun List<WardrobeItem>.sortedByOption(option: SortOption): List<WardrobeItem> = when (option) {
    SortOption.RECENTLY_ADDED -> sortedByDescending { it.createdAt }
    SortOption.MOST_WORN -> sortedWith(
        compareByDescending<WardrobeItem> { it.timesWorn }
            .thenByDescending { it.lastWorn ?: 0L }
    )
    SortOption.BY_COLOR -> sortedWith(
        compareBy<WardrobeItem> { colorFamilyOrder.indexOf(it.color).orMax() }
            .thenBy { it.color }
            .thenBy { it.type }
    )
    SortOption.BY_SEASON -> sortedWith(
        compareBy<WardrobeItem> { seasonOrder.indexOf(it.season).orMax() }
            .thenByDescending { it.createdAt }
    )
}

/**
 * Display swatch hex for the color chips / card dots. Represents the palette already
 * shipped in AppConstants.colorPreferences (Any, Black, White, Gray, Beige, Navy, Brown, Olive).
 */
fun wardrobeColorHex(colorName: String): Long = when (colorName) {
    "Black" -> 0xFF1B1A17
    "White" -> 0xFFFFFFFF
    "Gray" -> 0xFF9A9384
    "Beige" -> 0xFFE8DCC8
    "Brown" -> 0xFF8B5E3C
    "Olive" -> 0xFF6B6B3A
    "Navy" -> 0xFF22304A
    else -> 0xFF6B6355 // AccentLight
}

/** Season glyphs reused on cards, chips and empty states. */
fun seasonGlyph(season: String): String = when (season) {
    "Summer" -> "☀️"
    "Winter" -> "❄️"
    "Spring/Fall" -> "🍂"
    else -> "✦" // All Season
}
