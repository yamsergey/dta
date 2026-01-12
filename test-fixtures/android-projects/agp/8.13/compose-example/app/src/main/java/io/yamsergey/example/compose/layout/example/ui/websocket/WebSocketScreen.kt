package io.yamsergey.example.compose.layout.example.ui.websocket

import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.neovisionaries.ws.client.WebSocket
import com.neovisionaries.ws.client.WebSocketAdapter
import com.neovisionaries.ws.client.WebSocketFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocketListener
import org.java_websocket.client.WebSocketClient
import org.java_websocket.handshake.ServerHandshake
import java.net.URI
import java.util.concurrent.TimeUnit

private const val TAG = "WebSocketScreen"

// Public WebSocket echo servers for testing
private const val ECHO_SERVER_URL = "wss://ws.postman-echo.com/raw"

/**
 * Represents a WebSocket message in the UI
 */
data class WsMessage(
    val text: String,
    val isSent: Boolean,
    val timestamp: Long = System.currentTimeMillis()
)

/**
 * Connection state for each WebSocket type
 */
enum class ConnectionState {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    ERROR
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WebSocketScreen(
    onNavigateBack: () -> Unit
) {
    var selectedTab by remember { mutableIntStateOf(0) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("WebSocket Demo") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            // Tab Row for different WebSocket implementations
            TabRow(selectedTabIndex = selectedTab) {
                Tab(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    text = { Text("OkHttp") }
                )
                Tab(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    text = { Text("Java-WS") }
                )
                Tab(
                    selected = selectedTab == 2,
                    onClick = { selectedTab = 2 },
                    text = { Text("NV-WS") }
                )
            }

            // Content based on selected tab
            when (selectedTab) {
                0 -> OkHttpWebSocketContent()
                1 -> JavaWebSocketContent()
                2 -> NvWebSocketContent()
            }
        }
    }
}

// =============================================================================
// OkHttp WebSocket
// =============================================================================

@Composable
private fun OkHttpWebSocketContent() {
    val scope = rememberCoroutineScope()
    var connectionState by remember { mutableStateOf(ConnectionState.DISCONNECTED) }
    var messages by remember { mutableStateOf(listOf<WsMessage>()) }
    var messageInput by remember { mutableStateOf("") }
    var webSocket by remember { mutableStateOf<okhttp3.WebSocket?>(null) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    val client = remember {
        OkHttpClient.Builder()
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    DisposableEffect(Unit) {
        onDispose {
            webSocket?.close(1000, "Screen closed")
        }
    }

    WebSocketUI(
        title = "OkHttp WebSocket",
        connectionState = connectionState,
        messages = messages,
        messageInput = messageInput,
        errorMessage = errorMessage,
        onMessageInputChange = { messageInput = it },
        onConnect = {
            connectionState = ConnectionState.CONNECTING
            errorMessage = null

            val request = Request.Builder()
                .url(ECHO_SERVER_URL)
                .build()

            webSocket = client.newWebSocket(request, object : WebSocketListener() {
                override fun onOpen(ws: okhttp3.WebSocket, response: Response) {
                    Log.i(TAG, "OkHttp WebSocket opened")
                    connectionState = ConnectionState.CONNECTED
                    messages = messages + WsMessage("[Connected to server]", false)
                }

                override fun onMessage(ws: okhttp3.WebSocket, text: String) {
                    Log.i(TAG, "OkHttp received: $text")
                    messages = messages + WsMessage(text, false)
                }

                override fun onClosing(ws: okhttp3.WebSocket, code: Int, reason: String) {
                    Log.i(TAG, "OkHttp WebSocket closing: $code $reason")
                    ws.close(1000, null)
                }

                override fun onClosed(ws: okhttp3.WebSocket, code: Int, reason: String) {
                    Log.i(TAG, "OkHttp WebSocket closed: $code $reason")
                    connectionState = ConnectionState.DISCONNECTED
                    messages = messages + WsMessage("[Disconnected: $code $reason]", false)
                }

                override fun onFailure(ws: okhttp3.WebSocket, t: Throwable, response: Response?) {
                    Log.e(TAG, "OkHttp WebSocket error", t)
                    connectionState = ConnectionState.ERROR
                    errorMessage = t.message
                    messages = messages + WsMessage("[Error: ${t.message}]", false)
                }
            })
        },
        onDisconnect = {
            webSocket?.close(1000, "User disconnected")
            webSocket = null
        },
        onSendMessage = {
            if (messageInput.isNotBlank() && webSocket != null) {
                val sent = webSocket?.send(messageInput) ?: false
                if (sent) {
                    messages = messages + WsMessage(messageInput, true)
                    messageInput = ""
                }
            }
        }
    )
}

// =============================================================================
// Java-WebSocket
// =============================================================================

@Composable
private fun JavaWebSocketContent() {
    val scope = rememberCoroutineScope()
    var connectionState by remember { mutableStateOf(ConnectionState.DISCONNECTED) }
    var messages by remember { mutableStateOf(listOf<WsMessage>()) }
    var messageInput by remember { mutableStateOf("") }
    var wsClient by remember { mutableStateOf<WebSocketClient?>(null) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    DisposableEffect(Unit) {
        onDispose {
            wsClient?.close()
        }
    }

    WebSocketUI(
        title = "Java-WebSocket",
        connectionState = connectionState,
        messages = messages,
        messageInput = messageInput,
        errorMessage = errorMessage,
        onMessageInputChange = { messageInput = it },
        onConnect = {
            connectionState = ConnectionState.CONNECTING
            errorMessage = null

            scope.launch(Dispatchers.IO) {
                try {
                    val client = object : WebSocketClient(URI(ECHO_SERVER_URL)) {
                        override fun onOpen(handshakedata: ServerHandshake?) {
                            Log.i(TAG, "Java-WS opened")
                            connectionState = ConnectionState.CONNECTED
                            messages = messages + WsMessage("[Connected to server]", false)
                        }

                        override fun onMessage(message: String?) {
                            Log.i(TAG, "Java-WS received: $message")
                            message?.let {
                                messages = messages + WsMessage(it, false)
                            }
                        }

                        override fun onClose(code: Int, reason: String?, remote: Boolean) {
                            Log.i(TAG, "Java-WS closed: $code $reason (remote=$remote)")
                            connectionState = ConnectionState.DISCONNECTED
                            messages = messages + WsMessage("[Disconnected: $code $reason]", false)
                        }

                        override fun onError(ex: Exception?) {
                            Log.e(TAG, "Java-WS error", ex)
                            connectionState = ConnectionState.ERROR
                            errorMessage = ex?.message
                            messages = messages + WsMessage("[Error: ${ex?.message}]", false)
                        }
                    }

                    wsClient = client
                    client.connect()
                } catch (e: Exception) {
                    Log.e(TAG, "Java-WS connect error", e)
                    connectionState = ConnectionState.ERROR
                    errorMessage = e.message
                }
            }
        },
        onDisconnect = {
            wsClient?.close()
            wsClient = null
        },
        onSendMessage = {
            if (messageInput.isNotBlank() && wsClient?.isOpen == true) {
                wsClient?.send(messageInput)
                messages = messages + WsMessage(messageInput, true)
                messageInput = ""
            }
        }
    )
}

// =============================================================================
// NV-WebSocket
// =============================================================================

@Composable
private fun NvWebSocketContent() {
    val scope = rememberCoroutineScope()
    var connectionState by remember { mutableStateOf(ConnectionState.DISCONNECTED) }
    var messages by remember { mutableStateOf(listOf<WsMessage>()) }
    var messageInput by remember { mutableStateOf("") }
    var webSocket by remember { mutableStateOf<WebSocket?>(null) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    DisposableEffect(Unit) {
        onDispose {
            webSocket?.disconnect()
        }
    }

    WebSocketUI(
        title = "NV-WebSocket",
        connectionState = connectionState,
        messages = messages,
        messageInput = messageInput,
        errorMessage = errorMessage,
        onMessageInputChange = { messageInput = it },
        onConnect = {
            connectionState = ConnectionState.CONNECTING
            errorMessage = null

            scope.launch(Dispatchers.IO) {
                try {
                    val factory = WebSocketFactory()
                    val ws = factory.createSocket(ECHO_SERVER_URL)

                    ws.addListener(object : WebSocketAdapter() {
                        override fun onConnected(
                            websocket: WebSocket?,
                            headers: MutableMap<String, MutableList<String>>?
                        ) {
                            Log.i(TAG, "NV-WS connected")
                            connectionState = ConnectionState.CONNECTED
                            messages = messages + WsMessage("[Connected to server]", false)
                        }

                        override fun onTextMessage(websocket: WebSocket?, text: String?) {
                            Log.i(TAG, "NV-WS received: $text")
                            text?.let {
                                messages = messages + WsMessage(it, false)
                            }
                        }

                        override fun onDisconnected(
                            websocket: WebSocket?,
                            serverCloseFrame: com.neovisionaries.ws.client.WebSocketFrame?,
                            clientCloseFrame: com.neovisionaries.ws.client.WebSocketFrame?,
                            closedByServer: Boolean
                        ) {
                            val code = serverCloseFrame?.closeCode ?: clientCloseFrame?.closeCode ?: 1000
                            val reason = serverCloseFrame?.closeReason ?: clientCloseFrame?.closeReason ?: "unknown"
                            Log.i(TAG, "NV-WS disconnected: $code $reason")
                            connectionState = ConnectionState.DISCONNECTED
                            messages = messages + WsMessage("[Disconnected: $code $reason]", false)
                        }

                        override fun onError(websocket: WebSocket?, cause: com.neovisionaries.ws.client.WebSocketException?) {
                            Log.e(TAG, "NV-WS error", cause)
                            connectionState = ConnectionState.ERROR
                            errorMessage = cause?.message
                            messages = messages + WsMessage("[Error: ${cause?.message}]", false)
                        }
                    })

                    webSocket = ws
                    ws.connect()
                } catch (e: Exception) {
                    Log.e(TAG, "NV-WS connect error", e)
                    connectionState = ConnectionState.ERROR
                    errorMessage = e.message
                }
            }
        },
        onDisconnect = {
            webSocket?.disconnect()
            webSocket = null
        },
        onSendMessage = {
            if (messageInput.isNotBlank() && webSocket?.isOpen == true) {
                webSocket?.sendText(messageInput)
                messages = messages + WsMessage(messageInput, true)
                messageInput = ""
            }
        }
    )
}

// =============================================================================
// Shared UI Component
// =============================================================================

@Composable
private fun WebSocketUI(
    title: String,
    connectionState: ConnectionState,
    messages: List<WsMessage>,
    messageInput: String,
    errorMessage: String?,
    onMessageInputChange: (String) -> Unit,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    onSendMessage: () -> Unit
) {
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    // Auto-scroll to bottom when new messages arrive
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.size - 1)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        // Status card
        Card(
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(16.dp)
            ) {
                Text(
                    text = title,
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp
                )
                Spacer(modifier = Modifier.height(8.dp))

                Row(
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(12.dp)
                            .background(
                                color = when (connectionState) {
                                    ConnectionState.CONNECTED -> Color.Green
                                    ConnectionState.CONNECTING -> Color.Yellow
                                    ConnectionState.ERROR -> Color.Red
                                    ConnectionState.DISCONNECTED -> Color.Gray
                                },
                                shape = MaterialTheme.shapes.small
                            )
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = connectionState.name,
                        fontSize = 14.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                errorMessage?.let {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Error: $it",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.error
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = onConnect,
                        enabled = connectionState == ConnectionState.DISCONNECTED ||
                                connectionState == ConnectionState.ERROR
                    ) {
                        Text("Connect")
                    }
                    OutlinedButton(
                        onClick = onDisconnect,
                        enabled = connectionState == ConnectionState.CONNECTED ||
                                connectionState == ConnectionState.CONNECTING
                    ) {
                        Text("Disconnect")
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Server URL info
        Text(
            text = "Echo Server: $ECHO_SERVER_URL",
            fontSize = 12.sp,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(modifier = Modifier.height(8.dp))

        // Messages list
        Card(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
        ) {
            if (messages.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "No messages yet.\nConnect and send a message!",
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    items(messages) { message ->
                        MessageBubble(message)
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Message input
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = messageInput,
                onValueChange = onMessageInputChange,
                modifier = Modifier.weight(1f),
                placeholder = { Text("Type a message...") },
                singleLine = true,
                enabled = connectionState == ConnectionState.CONNECTED
            )
            Spacer(modifier = Modifier.width(8.dp))
            IconButton(
                onClick = onSendMessage,
                enabled = connectionState == ConnectionState.CONNECTED && messageInput.isNotBlank()
            ) {
                Icon(
                    Icons.AutoMirrored.Filled.Send,
                    contentDescription = "Send"
                )
            }
        }
    }
}

@Composable
private fun MessageBubble(message: WsMessage) {
    val backgroundColor = if (message.isSent) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        MaterialTheme.colorScheme.surfaceVariant
    }

    val alignment = if (message.isSent) Alignment.End else Alignment.Start

    Box(
        modifier = Modifier.fillMaxWidth(),
        contentAlignment = if (message.isSent) Alignment.CenterEnd else Alignment.CenterStart
    ) {
        Card(
            colors = CardDefaults.cardColors(containerColor = backgroundColor),
            modifier = Modifier.widthIn(max = 280.dp)
        ) {
            Column(
                modifier = Modifier.padding(8.dp)
            ) {
                Text(
                    text = if (message.isSent) "You" else "Server",
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = message.text,
                    fontSize = 14.sp
                )
            }
        }
    }
}
