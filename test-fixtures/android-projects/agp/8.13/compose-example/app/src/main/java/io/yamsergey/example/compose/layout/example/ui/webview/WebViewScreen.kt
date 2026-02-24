package io.yamsergey.example.compose.layout.example.ui.webview

import android.annotation.SuppressLint
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView

/**
 * Demo screen that embeds a WebView inside a Compose layout.
 * Used to test the WebView DOM integration in the layout tree inspector.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WebViewScreen(onNavigateBack: () -> Unit) {
    val urls = listOf(
        "https://example.com" to "Example.com",
        "https://developer.android.com" to "Android Docs",
        "https://jsonplaceholder.typicode.com" to "JSONPlaceholder"
    )
    var selectedUrl by remember { mutableStateOf(urls[0].first) }
    var currentTitle by remember { mutableStateOf("Loading...") }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("WebView Demo") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize()
        ) {
            // URL selector chips
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                urls.forEach { (url, label) ->
                    FilterChip(
                        selected = selectedUrl == url,
                        onClick = { selectedUrl = url },
                        label = { Text(label) }
                    )
                }
            }

            // Current URL info
            Text(
                text = currentTitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
            )

            // WebView
            WebViewContent(
                url = selectedUrl,
                onTitleChanged = { currentTitle = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            )
        }
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun WebViewContent(
    url: String,
    onTitleChanged: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    var webView by remember { mutableStateOf<WebView?>(null) }

    // Navigate when URL changes
    LaunchedEffect(url) {
        webView?.loadUrl(url)
    }

    AndroidView(
        factory = { context ->
            WebView(context).apply {
                settings.javaScriptEnabled = true
                webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView?, loadedUrl: String?) {
                        onTitleChanged(view?.title ?: loadedUrl ?: "")
                    }
                }
                webView = this
                loadUrl(url)
            }
        },
        modifier = modifier
    )
}
