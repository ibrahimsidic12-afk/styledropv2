package com.example.ui

import android.app.Application
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.example.models.AppConstants
import com.example.models.ItemCategory
import com.example.models.WardrobeItem
import com.example.security.BiometricGate
import com.example.security.SecurePhotoStore
import com.example.ui.theme.premiumCard
import androidx.fragment.app.FragmentActivity
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ItemDetailScreen(
    itemId: String,
    viewModel: WardrobeViewModel = viewModel(
        factory = AppViewModelProvider(LocalContext.current.applicationContext as Application)
    ),
    onNavigateBack: () -> Unit
) {
    val context = LocalContext.current
    val activity = context as? FragmentActivity
    val biometricGate = remember(activity) { activity?.let { BiometricGate(it) } }
    val coroutineScope = rememberCoroutineScope()
    var item by remember { mutableStateOf<WardrobeItem?>(null) }
    var editing by remember { mutableStateOf(false) }
    var showDeleteDialog by remember { mutableStateOf(false) }

    LaunchedEffect(itemId) {
        item = viewModel.getItemById(itemId)
    }

    if (item == null) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        return
    }

    var category by remember(item) { mutableStateOf(item!!.category) }
    var type by remember(item) { mutableStateOf(item!!.type) }
    var color by remember(item) { mutableStateOf(item!!.color) }
    var pattern by remember(item) { mutableStateOf(item!!.pattern) }
    var style by remember(item) { mutableStateOf(item!!.style) }
    var fit by remember(item) { mutableStateOf(item!!.fit) }
    var season by remember(item) { mutableStateOf(item!!.season) }

    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text("Delete item?") },
            text = { Text("This will remove the item from your wardrobe.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        // SECURITY (Agent #14) item 6: destructive + irreversible action
                        // behind a Keystore-bound biometric/credential gate. Fail closed.
                        val gate = biometricGate
                        if (gate == null || !gate.isAvailable()) {
                            showDeleteDialog = false
                            return@TextButton
                        }
                        gate.authenticate(
                            reason = "Authenticate to remove this piece",
                            onSuccess = {
                                viewModel.deleteItem(itemId)
                                SecurePhotoStore.delete(context, item?.imageUrl)
                                showDeleteDialog = false
                                onNavigateBack()
                            },
                            onUnavailable = { showDeleteDialog = false }
                        )
                    }
                ) {
                    Text("Delete")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(item!!.type, style = MaterialTheme.typography.displayMedium) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    titleContentColor = MaterialTheme.colorScheme.onBackground
                ),
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.Rounded.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = {
                        if (editing) {
                            val updatedItem = item!!.copy(
                                category = category,
                                type = type.ifBlank { "Item" },
                                color = color.ifBlank { "Black" },
                                pattern = pattern,
                                style = style,
                                fit = fit,
                                season = season
                            )
                            viewModel.updateItem(updatedItem)
                            item = updatedItem
                            editing = false
                        } else {
                            editing = true
                        }
                    }) {
                        Icon(if (editing) Icons.Rounded.Check else Icons.Rounded.Edit, contentDescription = "Edit")
                    }
                    IconButton(onClick = { showDeleteDialog = true }) {
                        Icon(Icons.Rounded.Delete, contentDescription = "Delete")
                    }
                }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(horizontal = 20.dp)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(modifier = Modifier.height(16.dp))

            AsyncImage(
                // SECURITY (Agent #14) item 4: decrypt the EncryptedFile into
                // app-private cache for rendering; legacy raw-Uri rows fall back.
                model = SecurePhotoStore.resolveForDisplay(context, item!!.imageUrl),
                contentDescription = item!!.type,
                modifier = Modifier
                    .size(220.dp)
                    .shadow(4.dp, RoundedCornerShape(24.dp), spotColor = Color(0x14000000))
                    .clip(RoundedCornerShape(24.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentScale = ContentScale.Crop
            )

            Spacer(modifier = Modifier.height(24.dp))

            if (!editing) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .premiumCard()
                        .padding(horizontal = 20.dp, vertical = 8.dp)
                ) {
                    InfoRow("Category", "${ItemCategory.emoji(item!!.category)} ${item!!.category}")
                    HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f))
                    InfoRow("Type", item!!.type)
                    HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f))
                    InfoRow("Color", item!!.color)
                    HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f))
                    InfoRow("Pattern", item!!.pattern)
                    HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f))
                    InfoRow("Style", item!!.style)
                    HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f))
                    InfoRow("Fit", item!!.fit)
                    HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f))
                    InfoRow("Season", item!!.season)
                    if (item!!.brand.isNotEmpty()) {
                        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f))
                        InfoRow("Brand", item!!.brand)
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f))
                    InfoRow("Worn", "${item!!.timesWorn} times")
                }
                Spacer(modifier = Modifier.height(32.dp))
            } else {
                DropdownField("Category", category, ItemCategory.all) { category = it }
                Spacer(modifier = Modifier.height(12.dp))
                OutlinedTextField(
                    value = type,
                    onValueChange = { type = it },
                    label = { Text("Type") },
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(12.dp))
                OutlinedTextField(
                    value = color,
                    onValueChange = { color = it },
                    label = { Text("Color") },
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(12.dp))
                DropdownField("Pattern", pattern, AppConstants.patterns) { pattern = it }
                Spacer(modifier = Modifier.height(12.dp))
                DropdownField("Style", style, AppConstants.styles) { style = it }
                Spacer(modifier = Modifier.height(12.dp))
                DropdownField("Fit", fit, AppConstants.fits) { fit = it }
                Spacer(modifier = Modifier.height(12.dp))
                DropdownField("Season", season, AppConstants.seasons) { season = it }
                Spacer(modifier = Modifier.height(32.dp))
            }
        }
    }
}

@Composable
fun InfoRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}
