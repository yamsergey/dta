package io.yamsergey.dta.sidekick.runtime

import android.os.Handler
import android.os.Looper
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Feature 028 (research R4): runs [block] on the main thread and waits for it (5 s). */
internal object Main {
    fun <T> call(block: () -> T): T {
        if (Looper.myLooper() == Looper.getMainLooper()) return block()
        val latch = CountDownLatch(1)
        var result: Result<T>? = null
        Handler(Looper.getMainLooper()).post {
            result = runCatching(block)
            latch.countDown()
        }
        check(latch.await(5, TimeUnit.SECONDS)) { "the app's main thread did not answer within 5 s (busy or blocked); nothing was changed for sure" }
        return result!!.getOrThrow()
    }
}
