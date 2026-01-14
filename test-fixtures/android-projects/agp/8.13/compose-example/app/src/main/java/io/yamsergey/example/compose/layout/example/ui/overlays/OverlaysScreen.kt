package io.yamsergey.example.compose.layout.example.ui.overlays

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog

/**
 * Screen for testing overlay UI components like bottom sheets, dialogs, and popups.
 * These components create separate windows which may not be captured by the standard
 * compose tree inspection that only looks at the activity's decor view.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OverlaysScreen(
    onNavigateBack: () -> Unit
) {
    var showBottomSheet by remember { mutableStateOf(false) }
    var showDialog by remember { mutableStateOf(false) }
    var showAlertDialog by remember { mutableStateOf(false) }
    var showDropdownMenu by remember { mutableStateOf(false) }
    var showFullScreenDialog by remember { mutableStateOf(false) }

    val sheetState = rememberModalBottomSheetState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Overlays Demo") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    // Dropdown menu in app bar
                    Box {
                        IconButton(onClick = { showDropdownMenu = true }) {
                            Icon(Icons.Default.MoreVert, contentDescription = "More options")
                        }
                        DropdownMenu(
                            expanded = showDropdownMenu,
                            onDismissRequest = { showDropdownMenu = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text("Option 1 - Settings") },
                                onClick = { showDropdownMenu = false }
                            )
                            DropdownMenuItem(
                                text = { Text("Option 2 - Help") },
                                onClick = { showDropdownMenu = false }
                            )
                            HorizontalDivider()
                            DropdownMenuItem(
                                text = { Text("Option 3 - About") },
                                onClick = { showDropdownMenu = false }
                            )
                        }
                    }
                }
            )
        }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Info card
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer
                    )
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = "Overlay Testing",
                            fontWeight = FontWeight.Bold,
                            fontSize = 18.sp
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "Test various overlay components to verify they appear " +
                                    "in the compose tree. Each overlay type creates a separate " +
                                    "window that may require special handling to capture.",
                            fontSize = 14.sp
                        )
                    }
                }
            }

            // Bottom Sheet section
            item {
                Text(
                    text = "Bottom Sheets",
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp
                )
            }

            item {
                Button(
                    onClick = { showBottomSheet = true },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Show Modal Bottom Sheet")
                }
            }

            // Dialog section
            item {
                Text(
                    text = "Dialogs",
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }

            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = { showDialog = true },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Custom Dialog")
                    }
                    Button(
                        onClick = { showAlertDialog = true },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Alert Dialog")
                    }
                }
            }

            item {
                Button(
                    onClick = { showFullScreenDialog = true },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Show Full Screen Dialog")
                }
            }

            // Dropdown section
            item {
                Text(
                    text = "Dropdowns & Menus",
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }

            item {
                ExposedDropdownExample()
            }

            // Tooltip section
            item {
                Text(
                    text = "Tooltips",
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }

            item {
                TooltipExample()
            }

            // Info about current overlays
            item {
                Card(
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = "Active Overlays Status",
                            fontWeight = FontWeight.Medium
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text("Bottom Sheet: ${if (showBottomSheet) "OPEN" else "closed"}")
                        Text("Custom Dialog: ${if (showDialog) "OPEN" else "closed"}")
                        Text("Alert Dialog: ${if (showAlertDialog) "OPEN" else "closed"}")
                        Text("Dropdown Menu: ${if (showDropdownMenu) "OPEN" else "closed"}")
                        Text("Full Screen Dialog: ${if (showFullScreenDialog) "OPEN" else "closed"}")
                    }
                }
            }
        }
    }

    // Modal Bottom Sheet
    if (showBottomSheet) {
        ModalBottomSheet(
            onDismissRequest = { showBottomSheet = false },
            sheetState = sheetState
        ) {
            BottomSheetContent(
                onDismiss = { showBottomSheet = false }
            )
        }
    }

    // Custom Dialog
    if (showDialog) {
        Dialog(onDismissRequest = { showDialog = false }) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
            ) {
                Column(
                    modifier = Modifier.padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = "Custom Dialog",
                        fontWeight = FontWeight.Bold,
                        fontSize = 20.sp
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = "This is a custom dialog using the Dialog composable. " +
                                "It creates a separate window for the dialog content."
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Dialog Item 1",
                        modifier = Modifier.padding(8.dp)
                    )
                    Text(
                        text = "Dialog Item 2",
                        modifier = Modifier.padding(8.dp)
                    )
                    Text(
                        text = "Dialog Item 3",
                        modifier = Modifier.padding(8.dp)
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Button(onClick = { showDialog = false }) {
                        Text("Close Dialog")
                    }
                }
            }
        }
    }

    // Alert Dialog
    if (showAlertDialog) {
        AlertDialog(
            onDismissRequest = { showAlertDialog = false },
            title = { Text("Alert Dialog Title") },
            text = {
                Column {
                    Text("This is an AlertDialog component.")
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("It also creates a separate window.")
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("Can you see this in the compose tree?")
                }
            },
            confirmButton = {
                TextButton(onClick = { showAlertDialog = false }) {
                    Text("Confirm")
                }
            },
            dismissButton = {
                TextButton(onClick = { showAlertDialog = false }) {
                    Text("Dismiss")
                }
            }
        )
    }

    // Full Screen Dialog
    if (showFullScreenDialog) {
        FullScreenDialogContent(
            onDismiss = { showFullScreenDialog = false }
        )
    }
}

@Composable
private fun BottomSheetContent(
    onDismiss: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "Bottom Sheet Content",
            fontWeight = FontWeight.Bold,
            fontSize = 20.sp
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = "This content is inside a ModalBottomSheet. " +
                    "It creates a separate window/layer for rendering."
        )
        Spacer(modifier = Modifier.height(16.dp))

        // List of items in bottom sheet
        listOf("Sheet Item 1", "Sheet Item 2", "Sheet Item 3", "Sheet Item 4").forEach { item ->
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp)
            ) {
                Text(
                    text = item,
                    modifier = Modifier.padding(16.dp)
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))
        Button(onClick = onDismiss) {
            Text("Close Bottom Sheet")
        }
        Spacer(modifier = Modifier.height(32.dp))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ExposedDropdownExample() {
    var expanded by remember { mutableStateOf(false) }
    var selectedOption by remember { mutableStateOf("Select an option") }
    val options = listOf("Option A", "Option B", "Option C", "Option D")

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it }
    ) {
        OutlinedTextField(
            value = selectedOption,
            onValueChange = {},
            readOnly = true,
            label = { Text("Exposed Dropdown") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier
                .menuAnchor()
                .fillMaxWidth()
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(option) },
                    onClick = {
                        selectedOption = option
                        expanded = false
                    }
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TooltipExample() {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        TooltipBox(
            positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
            tooltip = {
                PlainTooltip {
                    Text("This is a plain tooltip")
                }
            },
            state = rememberTooltipState()
        ) {
            Button(onClick = {}) {
                Text("Plain Tooltip")
            }
        }

        TooltipBox(
            positionProvider = TooltipDefaults.rememberRichTooltipPositionProvider(),
            tooltip = {
                RichTooltip(
                    title = { Text("Rich Tooltip") },
                    action = {
                        TextButton(onClick = {}) {
                            Text("Action")
                        }
                    }
                ) {
                    Text("This is a rich tooltip with more content and an action button.")
                }
            },
            state = rememberTooltipState()
        ) {
            Button(onClick = {}) {
                Text("Rich Tooltip")
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FullScreenDialogContent(
    onDismiss: () -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("Full Screen Dialog") },
                    navigationIcon = {
                        IconButton(onClick = onDismiss) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Close")
                        }
                    }
                )
            }
        ) { padding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(16.dp)
            ) {
                Text(
                    text = "Full Screen Dialog Content",
                    fontWeight = FontWeight.Bold,
                    fontSize = 24.sp
                )
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = "This dialog takes up the full screen and contains " +
                            "its own Scaffold with TopAppBar."
                )
                Spacer(modifier = Modifier.height(16.dp))

                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("Card inside full screen dialog")
                        Text("Item 1")
                        Text("Item 2")
                        Text("Item 3")
                    }
                }

                Spacer(modifier = Modifier.weight(1f))

                Button(
                    onClick = onDismiss,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Close Full Screen Dialog")
                }
            }
        }
    }
}
