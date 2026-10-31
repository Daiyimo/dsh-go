package io.github.ccch1mneyyy.dsh_app.phone

import android.os.SystemClock

/**
 * Notices that the system stopped this whole process.
 *
 * A frozen app is not slow: it does not run at all. `elapsedRealtime` keeps ticking while it is
 * stopped, so a one-second tick that arrives seconds late is the freeze itself. On this ROM
 * (PZ261A / StepOS) the freezer takes the app about 3 s after it stops being visible, and a task
 * that keeps working on a fullscreen app then answers every call "超时" the moment it is thawed.
 *
 * Logs only — it exists so that a 30-70 s call can be told apart from a slow device.
 */
object FreezeWatch {
    private const val TICK_MS = 1_000L
    private const val LOUD_MS = 3_000L

    @Volatile private var started = false

    fun start() {
        if (started) return
        started = true
        val thread = Thread({
            var last = SystemClock.elapsedRealtime()
            while (true) {
                try {
                    Thread.sleep(TICK_MS)
                } catch (_: InterruptedException) {
                    return@Thread
                }
                val now = SystemClock.elapsedRealtime()
                val gap = now - last
                if (gap >= LOUD_MS) PhoneLog.i("frozen ${gap}ms (the system stopped this app)")
                last = now
            }
        }, "dsh-freeze-watch")
        thread.isDaemon = true
        thread.start()
    }
}
