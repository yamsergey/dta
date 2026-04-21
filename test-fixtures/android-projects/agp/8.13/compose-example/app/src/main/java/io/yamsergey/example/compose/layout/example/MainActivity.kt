package io.yamsergey.example.compose.layout.example

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import io.yamsergey.example.compose.layout.example.ui.customtabs.CustomTabsScreen
import io.yamsergey.example.compose.layout.example.ui.fragments.FragmentDemoActivity
import io.yamsergey.example.compose.layout.example.ui.fragments.FragmentsScreen
import io.yamsergey.example.compose.layout.example.ui.network.NetworkScreen
import io.yamsergey.example.compose.layout.example.ui.overlays.OverlaysScreen
import io.yamsergey.example.compose.layout.example.ui.theme.ComposeLayoutExampleTheme
import io.yamsergey.example.compose.layout.example.ui.websocket.WebSocketScreen
import io.yamsergey.example.compose.layout.example.ui.data.DataScreen
import io.yamsergey.example.compose.layout.example.ui.webview.WebViewScreen

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            ComposeLayoutExampleTheme {
                AppNavigation()
            }
        }
    }
}

@Composable
fun AppNavigation() {
    val navController = rememberNavController()

    NavHost(
        navController = navController,
        startDestination = "main"
    ) {
        composable("main") {
            Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                MainContent(
                    modifier = Modifier.padding(innerPadding),
                    onNavigateToNetwork = { navController.navigate("network") },
                    onNavigateToWebSocket = { navController.navigate("websocket") },
                    onNavigateToCustomTabs = { navController.navigate("customtabs") },
                    onNavigateToOverlays = { navController.navigate("overlays") },
                    onNavigateToFragments = { navController.navigate("fragments") },
                    onNavigateToWebView = { navController.navigate("webview") },
                    onNavigateToData = { navController.navigate("data") }
                )
            }
        }
        composable("network") {
            NetworkScreen(
                onNavigateBack = { navController.popBackStack() }
            )
        }
        composable("websocket") {
            WebSocketScreen(
                onNavigateBack = { navController.popBackStack() }
            )
        }
        composable("customtabs") {
            CustomTabsScreen(
                onNavigateBack = { navController.popBackStack() }
            )
        }
        composable("overlays") {
            OverlaysScreen(
                onNavigateBack = { navController.popBackStack() }
            )
        }
        composable("webview") {
            WebViewScreen(
                onNavigateBack = { navController.popBackStack() }
            )
        }
        composable("data") {
            DataScreen(
                onNavigateBack = { navController.popBackStack() }
            )
        }
        composable("fragments") {
            val context = LocalContext.current
            FragmentsScreen(
                onNavigateBack = { navController.popBackStack() },
                onShowFragmentDemo = {
                    context.startActivity(Intent(context, FragmentDemoActivity::class.java))
                }
            )
        }
    }
}

@Composable
fun MainContent(
    modifier: Modifier = Modifier,
    onNavigateToNetwork: () -> Unit = {},
    onNavigateToWebSocket: () -> Unit = {},
    onNavigateToCustomTabs: () -> Unit = {},
    onNavigateToOverlays: () -> Unit = {},
    onNavigateToFragments: () -> Unit = {},
    onNavigateToWebView: () -> Unit = {},
    onNavigateToData: () -> Unit = {}
) {
    var counter by remember { mutableIntStateOf(0) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Header
        Text(
            text = "Compose Layout Example",
            fontSize = 24.sp,
            fontWeight = FontWeight.Bold
        )

        // Network Demo Buttons
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(
                onClick = onNavigateToNetwork,
                modifier = Modifier.weight(1f)
            ) {
                Text("HTTP Demo")
            }
            Button(
                onClick = onNavigateToWebSocket,
                modifier = Modifier.weight(1f)
            ) {
                Text("WebSocket Demo")
            }
        }

        // Custom Tabs Demo Button
        Button(
            onClick = onNavigateToCustomTabs,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Custom Tabs Demo (Chrome)")
        }

        // Overlays Demo Button
        Button(
            onClick = onNavigateToOverlays,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Overlays Demo (Bottom Sheets, Dialogs, Popups)")
        }

        // Fragments Demo Button
        Button(
            onClick = onNavigateToFragments,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Fragments Demo (Overlapping Fragments)")
        }

        // WebView Demo Button
        Button(
            onClick = onNavigateToWebView,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("WebView Demo (Embedded Web Content)")
        }

        // Data Demo Button
        Button(
            onClick = onNavigateToData,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Data Demo (Database + SharedPreferences)")
        }

        // Counter section
        CounterSection(
            counter = counter,
            onIncrement = { counter++ },
            onDecrement = { counter-- }
        )

        // Info cards
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            InfoCard(
                title = "Users",
                value = "1,234",
                modifier = Modifier.weight(1f)
            )
            InfoCard(
                title = "Posts",
                value = "567",
                modifier = Modifier.weight(1f)
            )
        }

        // Item list
        Text(
            text = "Recent Items",
            fontSize = 18.sp,
            fontWeight = FontWeight.Medium
        )

        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.weight(1f)
        ) {
            items(listOf("Apple", "Banana", "Cherry", "Date", "Elderberry")) { item ->
                ListItem(name = item)
            }
        }
    }
}

@Composable
fun CounterSection(
    counter: Int,
    onIncrement: () -> Unit,
    onDecrement: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "Counter: $counter",
                fontSize = 20.sp
            )
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Button(onClick = onDecrement) {
                    Text("-")
                }
                Button(onClick = onIncrement) {
                    Text("+")
                }
            }
        }
    }
}

@Composable
fun InfoCard(title: String, value: String, modifier: Modifier = Modifier) {
    Card(modifier = modifier) {
        Column(
            modifier = Modifier.padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = value,
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = title,
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
fun ListItem(name: String) {
    Card(
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .padding(16.dp)
                .fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(text = name)
            Button(onClick = { }) {
                Text("View")
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
fun MainContentPreview() {
    ComposeLayoutExampleTheme {
        MainContent()
    }
}
