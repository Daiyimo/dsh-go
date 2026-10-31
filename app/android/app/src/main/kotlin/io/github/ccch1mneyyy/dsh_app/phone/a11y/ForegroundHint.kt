package io.github.ccch1mneyyy.dsh_app.phone.a11y

/**
 * The app DSH itself just opened, remembered for a while.
 *
 * A page that hides its tree (WeChat's real UI, a game) cannot be named by
 * [TreeCapture.foregroundPackage] - that answers unknown there, on purpose. Right after `open` the
 * engine still knows which app it started, and this keeps a pure-GUI task latched instead of
 * telling the model it is looking at the launcher or at DSH itself.
 */
class ForegroundHint(private val keepMs: Long = 30_000L) {
    private var packageName: String? = null
    private var at = 0L

    /** [name] was just launched by us. */
    fun opened(name: String, now: Long) {
        packageName = name
        at = now
    }

    /** The foreground package, falling back to the app we opened while it stays unreadable. */
    fun seen(readable: String?, now: Long): String? {
        if (readable != null) {
            if (readable != packageName) clear()
            return readable
        }
        val opened = packageName ?: return null
        return if (now - at <= keepMs) opened else null
    }

    fun clear() {
        packageName = null
        at = 0L
    }
}
