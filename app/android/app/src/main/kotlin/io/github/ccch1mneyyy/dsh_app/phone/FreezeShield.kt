package io.github.ccch1mneyyy.dsh_app.phone

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.view.WindowManager

/**
 * Holds this ROM's freezer off while a phone task runs.
 *
 * Measured: StepOSFreeze schedules a freeze three seconds after the uid stops being usable
 * (`updateFreezeLock: uid 10247 … recordFlag={} … scheduled freeze in 3000ms` → `freeze due to
 * none flags`), and a frozen process answers the next tool call 15-42 s late — 57.8 s of a 71 s
 * 微信 task, `freezeLevel=0.942`. The vendor honours three things and only these: an activity of
 * ours (`outcome=cancel_pending detail=activity …/.MainActivity`), a provider access
 * (`detail=add flag: FLAG_USING_PROVIDER … authority …shizuku`) and a broadcast being delivered
 * (`detail=add flag: FLAG_USING_BROADCAST … action …KEEP_AWAKE hasTimeout true`). The battery
 * whitelist the settings page writes is not one of them: the package is already listed in
 * `cmd deviceidle whitelist` and the freezes kept landing.
 *
 * The old AlarmManager chain could not hold a flag long enough — Doze reported
 * `policyWhenElapsed: … interactive=+1m32s` for it. So this keeps the flag held from an ordinary
 * thread instead: a broadcast of our own every [BEAT_MS], a provider access, and a one-pixel
 * invisible window (to find out whether a visible window of ours counts as `FLAG_IS_VISIBLE`).
 */
object FreezeShield {
    private const val BEAT_MS = 1_200L

    /** No tool call for this long: the task is over, stop beating. */
    private const val GIVE_UP_MS = 600_000L

    private const val ACTION = "io.github.ccch1mneyyy.dsh_app.FREEZE_SHIELD"
    private const val PROVIDER = "io.github.ccch1mneyyy.dsh_app.shizuku"

    private var lastCall = 0L
    private var beats = 0
    private var thread: Thread? = null
    private var window: View? = null
    private var windowTried = false
    private var providerOk: Boolean? = null

    /** Called on every tool call: (re)starts the shield when it is not beating. */
    @Synchronized
    fun note(app: Context) {
        lastCall = SystemClock.elapsedRealtime()
        if (thread != null) return
        val name = "dsh-freeze-shield"
        val t = Thread({ run(app) }, name).apply { isDaemon = true }
        thread = t
        PhoneLog.i("freeze shield: starting (a beat every ${BEAT_MS}ms holds the freezer off)")
        t.start()
    }

    private fun run(app: Context) {
        showWindow(app)
        while (true) {
            val quiet = SystemClock.elapsedRealtime() - lastCall
            if (quiet > GIVE_UP_MS) break
            beats++
            beat(app)
            Thread.sleep(BEAT_MS)
        }
        runCatching { hideWindow(app) }
        PhoneLog.i("freeze shield: stopped after $beats beats")
        synchronized(this) { thread = null }
    }

    private fun beat(app: Context) {
        // A broadcast nobody receives is never delivered, so AMS never records
        // FLAG_USING_BROADCAST: measured on 2026-11-01, the first version of this loop sent
        // Intent(ACTION).setPackage(pkg) into the void and every freeze still landed.
        runCatching { app.sendBroadcast(Intent(app, ShieldBeat::class.java).setAction(ACTION)) }
        if (providerOk != true) {
            val client = runCatching { app.contentResolver.acquireContentProviderClient(PROVIDER) }.getOrNull()
            providerOk = client != null
            runCatching { client?.close() }
            PhoneLog.i("freeze shield: provider beat ok=$providerOk ($beats)")
        }
        if (beats % 20 == 0) PhoneLog.i("freeze shield: $beats beats")
    }

    /** A one-pixel, almost transparent window: it costs nothing and tests FLAG_IS_VISIBLE. */
    private fun showWindow(app: Context) {
        if (windowTried) return
        windowTried = true
        runCatching {
            val wm = app.getSystemService(WindowManager::class.java) ?: return
            val view = View(app)
            val params = WindowManager.LayoutParams(
                1, 1,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT,
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                x = 0
                y = 0
                alpha = 0.01f
            }
            // addView needs a looper: on the shield thread it threw "Can't create handler inside
            // thread … that has not called Looper.prepare()" and no window ever appeared.
            Handler(Looper.getMainLooper()).post {
                runCatching {
                    wm.addView(view, params)
                    window = view
                    PhoneLog.i("freeze shield: window up")
                }.onFailure {
                    PhoneLog.i("freeze shield: no window (${it.javaClass.simpleName}: ${it.message})")
                }
            }
        }.onFailure { PhoneLog.i("freeze shield: no window (${it.javaClass.simpleName}: ${it.message})") }
    }

    private fun hideWindow(app: Context) {
        val view = window ?: return
        window = null
        app.getSystemService(WindowManager::class.java)?.removeView(view)
        PhoneLog.i("freeze shield: window down")
    }
}

/**
 * The delivery target of the shield's own broadcast.
 *
 * It does nothing on purpose: the point is the delivery itself, because that is what makes the
 * vendor record `FLAG_USING_BROADCAST` and keep the freeze pending. It must NOT touch
 * [FreezeShield.note], which refreshes the deadline — a broadcast to ourselves would then keep the
 * shield alive for ever.
 */
class ShieldBeat : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) = Unit
}
