package io.github.ccch1mneyyy.dsh_app.phone

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * The entry point the phone core calls (`PhoneEnv.keepAwake`); the work is done by [FreezeShield].
 *
 * What used to be here was an AlarmManager chain. Measured in `dsh-phone.log`, Doze pushed its
 * alarms out by a minute and a half (`dumpsys alarm` → `policyWhenElapsed: interactive=+1m32s`), so
 * the flag it was meant to hold had always expired by the time the vendor freezer looked — the
 * freezes kept landing (15.6 s and 42.3 s inside a single 微信 task).
 */
object KeepAwake {
    fun note(app: Context) = FreezeShield.note(app)
}

/** Kept so the manifest receiver of the old alarm chain still has somewhere to point. */
class KeepAwakeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) = FreezeShield.note(context)
}
