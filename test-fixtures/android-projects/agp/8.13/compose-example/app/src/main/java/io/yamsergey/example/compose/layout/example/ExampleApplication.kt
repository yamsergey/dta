package io.yamsergey.example.compose.layout.example

import android.app.Application
import android.content.Context
import android.util.Log
import io.yamsergey.dta.sidekick.Sidekick
import io.yamsergey.dta.sidekick.SidekickConfig
import io.yamsergey.dta.sidekick.mock.MockHttpResponse
import io.yamsergey.dta.sidekick.mock.MockWebSocketMessage
import io.yamsergey.dta.sidekick.mock.adapter.HttpMockAdapter
import io.yamsergey.dta.sidekick.mock.adapter.WebSocketMockAdapter

class ExampleApplication : Application() {
    override fun attachBaseContext(base: Context) {
        // Custom HTTP mock adapter that modifies response body
        val httpAdapter = HttpMockAdapter { transaction, proposedResponse ->
            Log.d("MockAdapter", "HTTP Adapter called! URL: ${transaction.request.url}")
            // Replace the response body with our custom text
            proposedResponse.withBody("{\"message\": \"Hello HTTP Mock Response Adapter\"}")
        }

        // Custom WebSocket mock adapter that replaces all message bodies
        val webSocketAdapter = WebSocketMockAdapter { originalMessage, proposedMock ->
            Log.d("MockAdapter", "WS Adapter called! Original: ${originalMessage.textPayload}")
            // Replace the message body with our custom text
            MockWebSocketMessage.textMessage("Hello Mock Response Adapter").build()
        }

        // Configure sidekick with debug logging BEFORE ContentProvider initializes it
        Sidekick.configure(
            SidekickConfig.builder()
                .enableDebugLogging()
                .httpMockAdapter(httpAdapter)
                .webSocketMockAdapter(webSocketAdapter)
                .build()
        )
        super.attachBaseContext(base)
    }
}
