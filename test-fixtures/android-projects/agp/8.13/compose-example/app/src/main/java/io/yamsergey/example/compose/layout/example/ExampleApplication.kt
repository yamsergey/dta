package io.yamsergey.example.compose.layout.example

import android.app.Application
import android.content.Context
import io.yamsergey.dta.sidekick.Sidekick
import io.yamsergey.dta.sidekick.SidekickConfig

class ExampleApplication : Application() {
    override fun attachBaseContext(base: Context) {
        // Configure sidekick with debug logging BEFORE ContentProvider initializes it
        Sidekick.configure(
            SidekickConfig.builder()
                .enableDebugLogging()
                .build()
        )
        super.attachBaseContext(base)
    }
}
