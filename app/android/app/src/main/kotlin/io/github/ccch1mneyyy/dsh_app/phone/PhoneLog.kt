package io.github.ccch1mneyyy.dsh_app.phone

import android.util.Log

/** The phone module's one log tag: what a task decided has to be readable after the fact. */
object PhoneLog {
    private const val TAG = "DshPhone"

    fun i(message: String) {
        Log.i(TAG, message)
    }
}
