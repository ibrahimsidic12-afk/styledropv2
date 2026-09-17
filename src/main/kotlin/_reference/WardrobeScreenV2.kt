package com.example.ui.wardrobe

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ArrowDropDown
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.*
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import com.example.models.ItemCategory
import com.example.models.WardrobeItem

/**
 * WardrobeScreenV2 — the core closet UI.
 * Layout top-to-bottom: header ("Wardrobe" in Playfair Display) with filter + sort actions,
 * 7 scrollable category tabs with live counts, adaptive garment grid (2–4 columns),
 * an inline filter rail (tablet / expanded width) or modal filter sheet (compact),
 * a dropdown sort menu, and per-tab empty states.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun WardrobeScreenV2(
    items: List<WardrobeItem>,
    activeTab: String,
    onTabSelected: (String) -> Unit,
    filterState: FilterState,
    onFilterChanged: (FilterState) -> Unit,
    sortOption: SortOption,
    onSortSelected: (SortOption) -> Unit,
    onAddItemClick: () -> Unit,
    onItemClick: (WardrobeItem) -> Unit,
    modifier: Modifier = Modifier
) {
    val windowWidth = androidx.compose.ui.platform.LocalWindowInfo.current.containerWidth
    val useFilterRail = windowWidth >= 840 // expanded breakpoint: rail instead of sheet

    var showFilterSheet by remember { mutableStateOf(false) }
    var showSortMenu by remember { mutableStateOf(false) }

    val visibleItems = items
        .filter { ItemCategory.tabOf(it.category) == activeTab && filterState.matches(it) }
        .sortedByOption(sortOption)

    Scaffold(
        modifier = modifier,
        containerColor = MaterialTheme.colorScheme.background,
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = onAddItemClick,
                shape = RoundedCornerShape(16.dp),
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
                icon = { Icon(Icons.Rounded.Add, contentDescription = null) },
                text = { Text("Add Item") }
            )
        }
    ) { padding ->
        Row(Modifier.fillMaxSize().padding(padding)) {

            // ---- Filter rail (expanded width only) -------------------------------
            if (useFilterRail) {
                FilterRail(
                    filterState = filterState,
                    onFilterChanged = onFilterChanged,
                    modifier = Modifier.width(280.dp).fillMaxHeight()
                )
                VerticalDivider(color = MaterialTheme.colorScheme.outline)
            }

            Column(Modifier.weight(1f)) {

                // ---- Header ------------------------------------------------------
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Wardrobe", style = MaterialTheme.typography.displayMedium, modifier = Modifier.weight(1f))
                    // Sort
                    Box {
                        FilterChip(
                            selected = true,
                            onClick = { showSortMenu = true },
                            label = { Text(sortOption.label) },
                            leadingIcon = { Icon(Icons.Rounded.ArrowDropDown, contentDescription = null) }
                        )
                        DropdownMenu(expanded = showSortMenu, onDismissRequest = { showSortMenu = false }) {
                            SortOption.entries.forEach { option ->
                                DropdownMenuItem(
                                    text = { Text(option.label) },
                                    trailingIcon = {
                                        if (option == sortOption) Icon(Icons.Rounded.Check, contentDescription = "Selected")
                                    },
                                    onClick = { onSortSelected(option); showSortMenu = false }
                                )
                            }
                        }
                    }
                    Spacer(Modifier.width(8.dp))
                    // Filters
                    BadgedBox(badge = {
                        if (filterState.isActive) Badge { Text(filterState.activeCount().toString()) }
                    }) {
                        FilterChip(
                            selected = filterState.isActive,
                            onClick = {
                                if (useFilterRail) { /* rail already visible */ } else showFilterSheet = true
                            },
                            label = { Text("Filters") },
                            leadingIcon = { Icon(Icons.Rounded.Tune, contentDescription = null) }
                        )
                    }
                }

                // ---- Category tabs (7) -------------------------------------------
                ScrollableTabRow(
                    selectedTabIndex = ItemCategory.tabs.indexOf(activeTab).coerceAtLeast(0),
                    edgePadding = 16.dp,
                    containerColor = MaterialTheme.colorScheme.background,
                    indicator = { positions ->
                        TabRowDefaults.SecondaryIndicator(
                            Modifier.tabIndicatorOffset(positions[ItemCategory.tabs.indexOf(activeTab).coerceAtLeast(0)]),
                            color = MaterialTheme.colorScheme.secondary
                        )
                    }
                ) {
                    ItemCategory.tabs.forEach { tab ->
                        val count = items.count { ItemCategory.tabOf(it.category) == tab }
                        Tab(
                            selected = tab == activeTab,
                            onClick = { onTabSelected(tab) },
                            text = {
                                Text(
                                    "${ItemCategory.display(tab)}  $count",
                                    style = MaterialTheme.typography.titleMedium,
                                    color = if (tab == activeTab) MaterialTheme.colorScheme.primary
                                            else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        )
                    }
                }

                // ---- Active-filter chips -----------------------------------------
                if (filterState.isActive) {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)
                    ) {
                        AssistChip(
                            onClick = { onFilterChanged(filterState.clearAll()) },
                            label = { Text("Clear all") },
                            leadingIcon = { Icon(Icons.Rounded.Close, contentDescription = null) }
                        )
                        filterState.allSelectedValues().forEach { value ->
                            AssistChip(onClick = {}, label = { Text(value) })
                        }
                    }
                }

                // ---- Grid or empty state -----------------------------------------
                if (visibleItems.isEmpty()) {
                    if (items.isEmpty()) {
                        FirstItemEmptyState(onAddItemClick = onAddItemClick)
                    } else if (items.none { ItemCategory.tabOf(it.category) == activeTab }) {
                        EmptyTabState(
                            tabName = ItemCategory.display(activeTab),
                            onAddItemClick = { onTabSelected(activeTab); onAddItemClick() }
                        )
                    } else {
                        NoMatchesState(onClearFilters = { onFilterChanged(filterState.clearAll()) })
                    }
                } else {
                    LazyVerticalGrid(
                        columns = GridCells.Adaptive(minSize = 160.dp),
                        contentPadding = PaddingValues(16.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier.fillMaxSize()
                    ) {
                        items(visibleItems, key = { it.id }) { item ->
                            GarmentCard(
                                item = item,
                                onClick = { onItemClick(item) },
                                onLongPress = { /* enter multi-select */ },
                                onToggleFavorite = { /* ViewModel call */ },
                                showNewBadge = System.currentTimeMillis() - item.createdAt < 7L * 24 * 60 * 60 * 1000,
                                showStatChip = sortOption == SortOption.MOST_WORN,
                                statChipLabel = if (sortOption == SortOption.MOST_WORN) "· ${item.timesWorn}×" else ""
                            )
                        }
                    }
                }
            }
        }

        // ---- Filter sheet (compact width) ---------------------------------------
        if (showFilterSheet) {
            ModalBottomSheet(onDismissRequest = { showFilterSheet = false }) {
                FilterRail(
                    filterState = filterState,
                    onFilterChanged = onFilterChanged,
                    modifier = Modifier.fillMaxWidth().padding(bottom = 32.dp)
                )
            }
        }
    }
}

private fun FilterState.allSelectedValues(): List<String> =
    (colors + seasons + formalities + fabrics).toList()

private fun FilterState.activeCount(): Int = colors.size + seasons.size + formalities.size + fabrics.size
