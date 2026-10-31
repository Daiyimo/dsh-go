package io.github.ccch1mneyyy.dsh_app.phone

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.SystemClock

/**
 * Keeps this ROM's freezer off the app while a phone task runs.
 *
 * Measured: StepOSFreeze schedules a freeze three seconds after the uid stops being usable, and a
 * frozen process answers every tool call tens of seconds late (53.7 s, 74.1 s, 96.0 s, 97.4 s in the
 * logs) because the tool's own deadline runs out while it is stopped. Only a handful of things hold
 * the freeze off, and one of them is a broadcast being delivered to the uid
 * (`[BLOCK_FREEZE] … detail=add flag: FLAG_USING_BROADCAST`). So while the agent is working, an
 * alarm hands the app a broadcast of its own every couple of seconds, which keeps the freeze
 * pending — nothing else is done with it, and it stops on its own when the task goes quiet.
 */
object KeepAwake {
    private const val ACTION = "io.github.ccch1mneyyy.dsh_app.KEEP_AWAKE"

    /** While calls are coming in this often, the freeze must not land in between. */
    private const val BUSY_MS = 2_000L

    /** Calls are rare (the model is thinking): the freeze may land, but not stay long. */
    private const val IDLE_MS = 15_000L

    /** No call for this long: the task is over, stop waking the phone up. */
    private const val GIVE_UP_MS = 600_000L

    private var lastCall = 0L
    private var running = false

    /** Called on every tool call: (re)starts the guard when it is not running. */
    @Synchronized
    fun note(app: Context) {
        lastCall = SystemClock.elapsedRealtime()
        if (running) return
        running = schedule(app, lastCall + BUSY_MS)
        PhoneLog.i("keep awake: starting (this ROM freezes a backgrounded task)")
    }

    /** One alarm: schedule the next while the task is alive, otherwise stop. */
    @Synchronized
    fun tick(app: Context) {
        val now = SystemClock.elapsedRealtime()
        val quiet = now - lastCall
        if (quiet > GIVE_UP_MS) {
            running = false
            PhoneLog.i("keep awake: stopped (no call for ${quiet / 1000}s)")
            return
        }
        running = schedule(app, now + if (quiet < 60_000L) BUSY_MS else IDLE_MS)
    }

    private fun schedule(app: Context, at: Long): Boolean = runCatching {
        val alarms = app.getSystemService(AlarmManager::class.java) ?: return false
        alarms.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, intent(app))
        true
    }.getOrDefault(false)

    private fun intent(app: Context): PendingIntent = PendingIntent.getBroadcast(
        app,
        0,
        Intent(app, KeepAwakeReceiver::class.java).setAction(ACTION),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )
}

/** The alarm's other end: an empty broadcast is the whole point (it is what holds the freeze off). */
class KeepAwakeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) = KeepAwake.tick(context)
}
