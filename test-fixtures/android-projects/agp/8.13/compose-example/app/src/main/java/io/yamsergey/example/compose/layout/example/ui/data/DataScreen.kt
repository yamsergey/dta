package io.yamsergey.example.compose.layout.example.ui.data

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.yamsergey.example.compose.layout.example.data.Note
import io.yamsergey.example.compose.layout.example.data.NoteDatabase
import io.yamsergey.example.compose.layout.example.data.PrefsManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DataScreen(onNavigateBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var notes by remember { mutableStateOf<List<Note>>(emptyList()) }
    var plainPrefs by remember { mutableStateOf<Map<String, *>>(emptyMap<String, Any>()) }
    var encryptedPrefs by remember { mutableStateOf<Map<String, *>>(emptyMap<String, Any>()) }
    var noteText by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            notes = NoteDatabase.getInstance(context).noteDao().getAll()
            plainPrefs = PrefsManager.getPlainPrefs(context).all
            encryptedPrefs = try {
                PrefsManager.getEncryptedPrefs(context).all
                    .filterKeys { !it.startsWith("__androidx_security") }
            } catch (e: Exception) {
                mapOf("error" to e.message)
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Data Demo") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.padding(padding).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Database section
            item {
                Text("SQLite Database (Room)", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                Text("notes.db — ${notes.size} notes", fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            items(notes) { note ->
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(note.title, fontWeight = FontWeight.Medium)
                        Text(note.content, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (note.isPinned) Text("📌 Pinned", fontSize = 12.sp)
                    }
                }
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = noteText,
                        onValueChange = { noteText = it },
                        label = { Text("New note") },
                        modifier = Modifier.weight(1f)
                    )
                    Button(onClick = {
                        if (noteText.isNotBlank()) {
                            scope.launch {
                                withContext(Dispatchers.IO) {
                                    NoteDatabase.getInstance(context).noteDao().insert(
                                        Note(title = noteText, content = "Added from UI")
                                    )
                                    notes = NoteDatabase.getInstance(context).noteDao().getAll()
                                }
                                noteText = ""
                            }
                        }
                    }) { Text("Add") }
                }
            }

            // Plain SharedPreferences
            item {
                Spacer(Modifier.height(8.dp))
                Text("SharedPreferences (Plain)", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                Text("app_settings", fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            items(plainPrefs.entries.toList()) { (key, value) ->
                PrefsRow(key, value)
            }

            // Encrypted SharedPreferences
            item {
                Spacer(Modifier.height(8.dp))
                Text("SharedPreferences (Encrypted)", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                Text("secure_data — decrypted in-process via MasterKey", fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            items(encryptedPrefs.entries.toList()) { (key, value) ->
                PrefsRow(key, value)
            }

            item {
                Button(
                    onClick = {
                        PrefsManager.getPlainPrefs(context).edit()
                            .putInt("refresh_interval_seconds",
                                PrefsManager.getPlainPrefs(context).getInt("refresh_interval_seconds", 30) + 1)
                            .apply()
                        scope.launch {
                            withContext(Dispatchers.IO) {
                                plainPrefs = PrefsManager.getPlainPrefs(context).all
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Increment refresh_interval_seconds")
                }
            }
        }
    }
}

@Composable
private fun PrefsRow(key: String, value: Any?) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(12.dp).fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(key, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
            Text(
                value?.toString() ?: "null",
                fontFamily = FontFamily.Monospace,
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.weight(1f)
            )
        }
    }
}
