package com.example.ui.wardrobe

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.example.models.WardrobeItem
import com.example.ui.theme.premiumCard
import com.example.ui.theme.seasonGlyph
import com.example.ui.theme.wardrobeColorHex

/**
 * GarmentCard — the atomic unit of the closet grid.
 * An ivory surface card (20dp radius, hairline border, 8dp ambient shadow) that shows:
 * thumbnail -> type (titleMedium) -> brand · color · fit · fabric (one caption line) ->
 * a 3-dot color swatch row + season glyph + wear count chip. Long-press starts drag-select.
 */
@Composable
fun GarmentCard(
    item: WardrobeItem,
    onClick: () -> Unit,
    onLongPress: () -> Unit,
    onToggleFavorite: () -> Unit,
    modifier: Modifier = Modifier,
    selectionState: CardSelectionState = CardSelectionState.None,
    secondaryColors: List<String> = emptyList(),
    showNewBadge: Boolean = false,
    showStatChip: Boolean = false,
    statChipLabel: String = ""
) {
    val selected = selectionState == CardSelectionState.Selected
    val borderColor by animateColorAsState(
        targetValue = if (selected) Color(0xFFB08A4F) else MaterialTheme.colorScheme.outline.copy(alpha = 0.4f),
        label = "cardBorder"
    )
    val borderWidth = if (selected) 2.dp else 1.dp

    Column(
        modifier = modifier
            .premiumCard(RoundedCornerShape(20.dp))
            .border(borderWidth, borderColor, RoundedCornerShape(20.dp))
            .clickable(onClick = onClick)
            .combinedClickableCompat(onLongPress = onLongPress)
            .padding(8.dp)
    ) {
        Box {
            AsyncImage(
                model = item.imageUrl,
                contentDescription = "${item.type} — ${item.color}",
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f)
                    .clip(RoundedCornerShape(14.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
            )

            // Favorite heart, top-right over the photo
            Icon(
                imageVector = if (item.isFavoriteItem) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                contentDescription = if (item.isFavoriteItem) "Remove favorite" else "Add favorite",
                tint = if (item.isFavoriteItem) Color(0xFFB5533C) else Color.White.copy(alpha = 0.9f),
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(6.dp)
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.25f))
                    .padding(4.dp)
                    .size(16.dp)
                    .clickable(onClick = onToggleFavorite)
            )

            if (showNewBadge) {
                Badge(
                    containerColor = MaterialTheme.colorScheme.secondary,
                    contentColor = MaterialTheme.colorScheme.onSecondary,
                    modifier = Modifier.align(Alignment.TopStart).padding(6.dp)
                ) { Text("NEW") }
            }
        }

        Spacer(Modifier.height(8.dp))

        Text(
            text = item.type,
            style = MaterialTheme.typography.titleMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            text = listOf(item.brand.ifEmpty { null }, item.color, item.fit, item.fabric)
                .filterNotNull().joinToString(" · "),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )

        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            // Color swatches: primary + up to 2 secondary colors
            ColorDot(item.color)
            secondaryColors.take(2).forEach { secondary ->
                Spacer(Modifier.width(4.dp))
                ColorDot(secondary)
            }
            Spacer(Modifier.weight(1f))
            Text(seasonGlyph(item.season), style = MaterialTheme.typography.bodySmall)
            if (showStatChip) {
                Spacer(Modifier.width(6.dp))
                Text(
                    text = statChipLabel,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/** Selection visual states for multi-select / drag mode. */
enum class CardSelectionState { None, Selected, Dimmed }

@Composable
private fun ColorDot(hexColorName: String) {
    Box(
        modifier = Modifier
            .size(12.dp)
            .clip(CircleShape)
            .background(Color(wardrobeColorHex(hexColorName)))
            .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape)
    )
}

/** Bridges combinedClickable so tap opens the item and long-press starts drag-select. */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
private fun Modifier.combinedClickableCompat(
    onClick: () -> Unit,
    onLongPress: () -> Unit
): Modifier = this.combinedClickable(onClick = onClick, onLongClick = onLongPress)
