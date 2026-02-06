package io.yamsergey.example.compose.layout.example.ui.customtabs

import android.content.Context
import android.net.Uri
import androidx.browser.customtabs.CustomTabColorSchemeParams
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Demo screen for Chrome Custom Tabs.
 *
 * This screen allows testing Custom Tab launches which can be intercepted
 * by the ADT Sidekick's Custom Tabs adapter.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CustomTabsScreen(
    onNavigateBack: () -> Unit
) {
    val context = LocalContext.current
    val primaryColor = MaterialTheme.colorScheme.primary

    // Predefined URLs to test
    val testUrls = remember {
        listOf(
            TestUrl("GitHub", "https://github.com", "Popular code hosting platform"),
            TestUrl("Google", "https://www.google.com", "Search engine"),
            TestUrl("JSONPlaceholder", "https://jsonplaceholder.typicode.com", "Fake REST API for testing"),
            TestUrl("HTTPBin", "https://httpbin.org", "HTTP request/response testing"),
            TestUrl("Example.com", "https://example.com", "Simple example domain"),
            TestUrl("Hacker News", "https://news.ycombinator.com", "Tech news aggregator"),
        )
    }

    var customUrl by remember { mutableStateOf("https://") }
    var launchCount by remember { mutableIntStateOf(0) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Custom Tabs Demo") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Info card
            item {
                Card(
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = "Chrome Custom Tabs",
                            fontWeight = FontWeight.Bold,
                            fontSize = 18.sp
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "Custom Tabs open web content in a Chrome tab that's part of your app. " +
                                    "ADT Sidekick can intercept these launches and optionally capture " +
                                    "network traffic via Chrome DevTools Protocol (CDP).",
                            fontSize = 14.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "Tabs launched: $launchCount",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }

            // Custom URL input
            item {
                Card(
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = "Custom URL",
                            fontWeight = FontWeight.Bold,
                            fontSize = 16.sp
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        OutlinedTextField(
                            value = customUrl,
                            onValueChange = { customUrl = it },
                            label = { Text("Enter URL") },
                            placeholder = { Text("https://example.com") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Button(
                            onClick = {
                                if (customUrl.isNotBlank() && customUrl.startsWith("http")) {
                                    launchCustomTab(context, customUrl, primaryColor.toArgb())
                                    launchCount++
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                            enabled = customUrl.isNotBlank() && customUrl.startsWith("http")
                        ) {
                            Text("Open Custom URL")
                        }
                    }
                }
            }

            // Header for preset URLs
            item {
                Text(
                    text = "Preset URLs",
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }

            // Preset URL buttons
            items(testUrls) { testUrl ->
                TestUrlCard(
                    testUrl = testUrl,
                    onLaunch = {
                        launchCustomTab(context, testUrl.url, primaryColor.toArgb())
                        launchCount++
                    }
                )
            }

            // Batch launch section
            item {
                Card(
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = "Batch Launch",
                            fontWeight = FontWeight.Bold,
                            fontSize = 16.sp
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "Launch multiple Custom Tabs in sequence to test event capture.",
                            fontSize = 14.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Button(
                            onClick = {
                                // Launch first 3 URLs with slight delay
                                testUrls.take(3).forEachIndexed { index, url ->
                                    android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                                        launchCustomTab(context, url.url, primaryColor.toArgb())
                                        launchCount++
                                    }, index * 500L)
                                }
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Launch 3 Tabs (500ms apart)")
                        }
                    }
                }
            }

            // Redirect test section
            item {
                Card(
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = "Redirect Tests",
                            fontWeight = FontWeight.Bold,
                            fontSize = 16.sp
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "Test redirect handling. Each redirect should appear as a separate request in the inspector.",
                            fontSize = 14.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Button(
                            onClick = {
                                // HTTP to HTTPS redirect
                                launchCustomTab(context, "http://github.com", primaryColor.toArgb())
                                launchCount++
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("HTTP → HTTPS (github.com)")
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        Button(
                            onClick = {
                                // Multiple redirects via httpbin
                                launchCustomTab(context, "https://httpbin.org/redirect/3", primaryColor.toArgb())
                                launchCount++
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("3 Chained Redirects (httpbin)")
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        Button(
                            onClick = {
                                // Redirect to different domain
                                launchCustomTab(context, "https://httpbin.org/redirect-to?url=https://example.com", primaryColor.toArgb())
                                launchCount++
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Cross-Domain Redirect")
                        }
                    }
                }
            }

            // Custom headers section
            item {
                Card(
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = "Custom Headers",
                            fontWeight = FontWeight.Bold,
                            fontSize = 16.sp
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "Launch with custom HTTP headers. These should be visible in the intercepted Custom Tab event.",
                            fontSize = 14.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Button(
                            onClick = {
                                launchCustomTabWithHeaders(
                                    context,
                                    "https://httpbin.org/headers",
                                    primaryColor.toArgb(),
                                    mapOf(
                                        "X-Custom-Header" to "test-value-123",
                                        "X-App-Name" to "compose-example",
                                        "X-Request-ID" to "ct-${System.currentTimeMillis()}"
                                    )
                                )
                                launchCount++
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Open HTTPBin with Custom Headers")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TestUrlCard(
    testUrl: TestUrl,
    onLaunch: () -> Unit
) {
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
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = testUrl.name,
                    fontWeight = FontWeight.Medium,
                    fontSize = 16.sp
                )
                Text(
                    text = testUrl.description,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = testUrl.url,
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.outline
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
            Button(onClick = onLaunch) {
                Text("Open")
            }
        }
    }
}

private data class TestUrl(
    val name: String,
    val url: String,
    val description: String
)

/**
 * Launches a URL in Chrome Custom Tabs.
 */
private fun launchCustomTab(context: Context, url: String, toolbarColor: Int) {
    val colorSchemeParams = CustomTabColorSchemeParams.Builder()
        .setToolbarColor(toolbarColor)
        .build()

    val customTabsIntent = CustomTabsIntent.Builder()
        .setDefaultColorSchemeParams(colorSchemeParams)
        .setShowTitle(true)
        .setUrlBarHidingEnabled(true)
        .build()

    customTabsIntent.launchUrl(context, Uri.parse(url))
}

/**
 * Launches a URL in Chrome Custom Tabs with custom headers.
 */
private fun launchCustomTabWithHeaders(
    context: Context,
    url: String,
    toolbarColor: Int,
    headers: Map<String, String>
) {
    val colorSchemeParams = CustomTabColorSchemeParams.Builder()
        .setToolbarColor(toolbarColor)
        .build()

    val builder = CustomTabsIntent.Builder()
        .setDefaultColorSchemeParams(colorSchemeParams)
        .setShowTitle(true)
        .setUrlBarHidingEnabled(true)

    val customTabsIntent = builder.build()

    // Add custom headers via intent extras
    val headersBundle = android.os.Bundle()
    headers.forEach { (key, value) ->
        headersBundle.putString(key, value)
    }
    customTabsIntent.intent.putExtra(android.provider.Browser.EXTRA_HEADERS, headersBundle)

    customTabsIntent.launchUrl(context, Uri.parse(url))
}
