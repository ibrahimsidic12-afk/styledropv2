package com.example.ui.wardrobe

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.models.ItemCategory

/**
 * FilterRail / FilterSheet content — shared by the tablet rail and the phone bottom sheet.
 * Sections: Color (swatch chips), Season, Formality, Fabric. Chips inside a section are
 * OR-ed; sections are AND-ed. "Clear all" resets everything.
 */
@Composable
fun FilterRail(
    filterState: FilterState,
    onFilterChanged: (FilterState) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier.padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Filters", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.weight(1f))
            TextButton(onClick = { onFilterChanged(filterState.clearAll()) }) { Text("Clear all") }
        }

        FilterSectionTitle("Color")
        FlowRowish {
            listOf("Black", "White", "Gray", "Beige", "Navy", "Brown", "Olive").forEach { colorName ->
                SelectableChip(
                    label = colorName,
                    swatch = colorName,
                    selected = colorName in filterState.colors,
                    onToggle = { onFilterChanged(filterState.toggle(FilterSection.COLOR, colorName)) }
                )
            }
        }

        FilterSectionTitle("Season")
        FlowRowish {
            listOf("All Season", "Summer", "Winter", "Spring/Fall").forEach { season ->
                SelectableChip(
                    label = season,
                    selected = season in filterState.seasons,
                    onToggle = { onFilterChanged(filterState.toggle(FilterSection.SEASON, season)) }
                )
            }
        }

        FilterSectionTitle("Formality")
        FlowRowish {
            listOf("Casual", "Smart Casual", "Formal", "Athleisure").forEach { level ->
                SelectableChip(
                    label = level,
                    selected = level in filterState.formalities,
                    onToggle = { onFilterChanged(filterState.toggle(FilterSection.FORMALITY, level)) }
                )
            }
        }

        FilterSectionTitle("Fabric")
        FlowRowish {
            listOf("Cotton", "Linen", "Wool", "Denim", "Leather", "Silk", "Cashmere", "Technical").forEach { fabric ->
                SelectableChip(
                    label = fabric,
                    selected = fabric in filterState.fabrics,
                    onToggle = { onFilterChanged(filterState.toggle(FilterSection.FABRIC, fabric)) }
                )
            }
        }
    }
}

@Composable
private fun FilterSectionTitle(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleLarge,
        modifier = Modifier.padding(top = 20.dp, bottom = 8.dp)
    )
}

/** Simple FlowRow wrapper (keeps the file on stable APIs). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FlowRowish(content: @Composable () -> Unit) {
    androidx.compose.foundation.layout.FlowRow(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
        content = { content() }
    )
}

@Composable
private fun SelectableChip(
    label: String,
    selected: Boolean,
    onToggle: () -> Unit,
    swatch: String? = null
) {
    FilterChip(
        selected = selected,
        onClick = onToggle,
        label = { Text(label) },
        leadingIcon = if (swatch != null) {
            { SwatchDot(swatch) }
        } else null
    )
}

@Composable
private fun SwatchDot(colorName: String) {
    androidx.compose.foundation.layout.Box(
        Modifier
            .size(12.dp)
            .clip(androidx.compose.foundation.shape.CircleShape)
            .background(androidx.compose.ui.graphics.Color(wardrobeColorHex(colorName)))
    )
}

// ─────────────────────────── EMPTY STATES ───────────────────────────

/** 1) "Just getting started" — zero items in the entire wardrobe. */
@Composable
fun FirstItemEmptyState(onAddItemClick: () -> Unit) {
    EmptyStateScaffold(
        glyph = "🧥",
        title = "Your closet awaits",
        body = "Add your first piece and let your AI stylist do the rest. Snap a photo — " +
            "StyleDrop identifies the type, color, fabric and season for you.",
        ctaLabel = "Add your first item",
        onCta = onAddItemClick,
        secondaryLabel = "Watch a 30-second intro"
    )
}

/** 2) "Building the wardrobe" — a category tab with zero items but a non-empty wardrobe. */
@Composable
fun EmptyTabState(tabName: String, onAddItemClick: () -> Unit) {
    EmptyStateScaffold(
        glyph = ItemCategory.emoji(tabName),
        title = "No $tabName yet",
        body = "You have pieces in other categories. Every $tabName you add unlocks " +
            "new outfit combinations in the AI generator.",
        ctaLabel = "Add $tabName",
        onCta = onAddItemClick
    )
}

/** 2b) "No matches" — items exist but filters hide them all. */
@Composable
fun NoMatchesState(onClearFilters: () -> Unit) {
    EmptyStateScaffold(
        glyph = "🔍",
        title = "No matches",
        body = "Nothing in your closet matches these filters. Try removing one — " +
            "or clear everything and start again.",
        ctaLabel = "Clear all filters",
        onCta = onClearFilters
    )
}

@Composable
private fun EmptyStateScaffold(
    glyph: String,
    title: String,
    body: String,
    ctaLabel: String,
    onCta: () -> Unit,
    secondaryLabel: String? = null
) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(glyph, style = MaterialTheme.typography.displayLarge)
        Spacer(Modifier.height(12.dp))
        Text(title, style = MaterialTheme.typography.headlineMedium, textAlign = TextAlign.Center)
        Spacer(Modifier.height(8.dp))
        Text(
            body,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(20.dp))
        Button(onClick = onCta, shape = androidx.compose.foundation.shape.RoundedCornerShape(14.dp)) {
            Text(ctaLabel)
        }
        if (secondaryLabel != null) {
            Spacer(Modifier.height(6.dp))
            TextButton(onClick = { /* open intro */ }) { Text(secondaryLabel) }
        }
    }
}
