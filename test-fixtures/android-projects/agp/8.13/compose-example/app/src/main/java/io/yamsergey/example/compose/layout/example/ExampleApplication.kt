package io.yamsergey.example.compose.layout.example

import android.app.Application
import android.content.Context
import android.util.Log

class ExampleApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        io.yamsergey.example.compose.layout.example.data.PrefsManager.seedDefaults(this)
        seedDatabase()
    }

    private fun seedDatabase() {
        val db = io.yamsergey.example.compose.layout.example.data.NoteDatabase.getInstance(this)
        Thread {
            try {
                kotlinx.coroutines.runBlocking {
                    if (db.noteDao().count() == 0) {
                        db.noteDao().insert(io.yamsergey.example.compose.layout.example.data.Note(
                            title = "Welcome", content = "This is a demo note for testing DTA data inspection."))
                        db.noteDao().insert(io.yamsergey.example.compose.layout.example.data.Note(
                            title = "Shopping List", content = "Milk, eggs, bread, butter", isPinned = true))
                        db.noteDao().insert(io.yamsergey.example.compose.layout.example.data.Note(
                            title = "Meeting Notes", content = "Discuss Q3 roadmap with team"))
                        Log.d("ExampleApp", "Seeded demo notes")
                    }
                }
            } catch (e: Exception) {
                Log.w("ExampleApp", "Failed to seed notes: ${e.message}")
            }
        }.start()
    }

    override fun attachBaseContext(base: Context) {
        // Configure sidekick if available (auto-injected by DTA plugin/run_app)
        try {
            val sidekickClass = Class.forName("io.yamsergey.dta.sidekick.Sidekick")
            val configClass = Class.forName("io.yamsergey.dta.sidekick.SidekickConfig")
            val builderClass = Class.forName("io.yamsergey.dta.sidekick.SidekickConfig\$Builder")

            val builder = configClass.getMethod("builder").invoke(null)
            builderClass.getMethod("enableDebugLogging").invoke(builder)
            val config = builderClass.getMethod("build").invoke(builder)
            sidekickClass.getMethod("configure", configClass).invoke(null, config)
            Log.d("ExampleApp", "Sidekick configured")
        } catch (e: ClassNotFoundException) {
            // Sidekick not injected — running without DTA, that's fine
        } catch (e: Exception) {
            Log.w("ExampleApp", "Failed to configure sidekick: ${e.message}")
        }
        super.attachBaseContext(base)
    }
}
