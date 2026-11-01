package io.github.ccch1mneyyy.dsh_app.phone

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * The phone module's one log tag: what a task decided has to be readable after the fact.
 *
 * `adb logcat` on this phone rolls over in minutes — about as long as it takes a person to notice
 * that a task went wrong — so the same lines also land in a small file in the app's external files
 * directory, which adb can read:
 *
 *     adb pull /sdcard/Android/data/io.github.ccch1mneyyy.dsh_app/files/dsh-phone.log
 *
 * Only decisions are written there (tool, outcome, screen id, refusal reason). Nothing the phone
 * showed, and nothing the person typed, ever goes into it.
 */
object PhoneLog {
    private const val TAG = "DshPhone"
    private const val NAME = "dsh-phone.log"
    private const val PREVIOUS = "dsh-phone.1.log"

    /** Kept out of the string literals: the tools that edit this file mangle escapes. */
    private const val NEWLINE = "\n"

    /** Rotate once past this, so a long session cannot fill the person's storage. */
    private const val KEEP = 512 * 1024

    private val writer = Executors.newSingleThreadExecutor { r -> Thread(r, "dsh-phone-log").apply { isDaemon = true } }
    private val stamp = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)

    @Volatile private var file: File? = null

    /** Called once from the phone module's owner; without it the file stays off. */
    fun attach(context: Context) {
        if (file != null) return
        file = runCatching { File(context.getExternalFilesDir(null) ?: context.filesDir, NAME) }.getOrNull()
    }

    fun i(message: String) {
        Log.i(TAG, message)
        val out = file ?: return
        writer.execute { runCatching { write(out, stamp.format(Date()) + " " + message + NEWLINE) } }
    }

    private fun write(out: File, line: String) {
        if (out.length() > KEEP) {
            val previous = File(out.parentFile, PREVIOUS)
            previous.delete()
            out.renameTo(previous)
        }
        out.appendText(line)
    }
}
