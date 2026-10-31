package io.github.ccch1mneyyy.dsh_app.phone.input

import android.content.Context
import io.github.ccch1mneyyy.dsh_app.phone.background.Proto

/** What the injector needs from the shell-uid engine ([io.github.ccch1mneyyy.dsh_app.phone.background.BackgroundControl]). */
interface TypingChannel {
    fun typeAscii(text: String): Boolean
    fun key(code: Int): Boolean
    fun ime(action: String, component: String): Boolean

    companion object {
        const val ENTER = 66
    }
}

/** What it needs from this app's own input method ([DshIme]). */
interface ImePort {
    fun component(): String
    /** The input method the person is using right now. */
    fun current(): String?
    fun ready(): Boolean
    fun commit(text: String, submit: Boolean): String?
}

/**
 * 0·A: text into a page the accessibility tree cannot describe (微信, games).
 *
 * Two ways, both with the shell uid the 不打扰模式 engine already is, and neither asks the person to
 * change their keyboard:
 *
 * - `input text` for ASCII: it types into whatever field has focus, so nothing moves and nothing
 *   flashes. `input keyevent 66` presses the enter a search field wants.
 * - an input method for everything else: `input text` cannot carry a single Chinese character (it
 *   throws on this ROM), so the only route left is an `InputConnection`. The engine selects this
 *   app's input method for the length of one commit and puts the person's keyboard back right
 *   after — the person's own keyboard stays the one they use.
 *
 * When neither way is available (no Shizuku / self-ADB), the model gets a sentence saying so rather
 * than a silently empty field.
 */
class TextInjector(
    private val channel: TypingChannel,
    private val ime: ImePort,
    /** Uptime in ms; a seam so the wait below is testable without a device. */
    private val now: () -> Long = { System.nanoTime() / 1_000_000 },
    private val sleep: (Long) -> Unit = { Thread.sleep(it) },
) {
    /** null when the text is in, else the sentence the model gets (// l10n:keep agent tool result). */
    fun type(text: String, submit: Boolean): String? {
        if (text.isEmpty()) return "type 需要 text。"
        if (text.length > MAX_TEXT) return "一次最多输入 $MAX_TEXT 字，分段输入。"
        if (asciiOnly(text) && channel.typeAscii(text)) {
            if (submit) channel.key(TypingChannel.ENTER)
            return null
        }
        return handover(text, submit)
    }

    /**
     * Borrow the input method for one commit. The person's keyboard is selected again in [finally],
     * so a failed commit never leaves them without a keyboard.
     */
    private fun handover(text: String, submit: Boolean): String? {
        val ours = ime.component()
        val previous = ime.current()
        val borrowed = previous != ours
        if (borrowed) {
            if (!channel.ime(Proto.IME_ENABLE, ours)) return NO_ENGINE
            if (!channel.ime(Proto.IME_SET, ours)) {
                channel.ime(Proto.IME_DISABLE, ours)
                return NO_ENGINE
            }
        }
        try {
            val deadline = now() + WAIT_MS
            var last: String? = null
            while (now() < deadline) {
                if (ime.ready()) {
                    val err = ime.commit(text, submit)
                    if (err == null) return null
                    last = err
                }
                sleep(POLL_MS)
            }
            // l10n:keep agent tool result
            return last ?: "文字没有送进输入框：先用 tap_xy 点一下输入框（让光标在里面），再 type。"
        } finally {
            if (borrowed) {
                if (previous != null) channel.ime(Proto.IME_SET, previous)
                channel.ime(Proto.IME_DISABLE, ours)
            }
        }
    }

    companion object {
        const val MAX_TEXT = 2_000

        /** How long the borrowed input method gets to bind and settle on the field. */
        private const val WAIT_MS = 2_500L
        private const val POLL_MS = 60L

        private const val NO_ENGINE =
            "这个页面没有输入框编号，文字要经特权通道送进去：先在 设置 › 手机操控 › 进阶 开通 不打扰模式 或 自连 ADB（也可以用 DSH 输入法 手动顶上）。现在两条都不通。"

        /**
         * `input text` types keys, so only printable ASCII arrives; `%s` reaches it as a space, and
         * anything else has to go through an input method.
         */
        fun asciiOnly(text: String): Boolean =
            text.isNotEmpty() && text.length <= MAX_TEXT &&
                text.all { it.code in 0x20..0x7E } && !text.contains("%s")
    }
}

/** The real input method: this app's [DshIme]. */
class DshImePort(private val app: Context) : ImePort {
    override fun component(): String = DshIme.component(app)
    override fun current(): String? = DshIme.current(app)
    override fun ready(): Boolean = DshIme.ready()
    override fun commit(text: String, submit: Boolean): String? = DshIme.commit(text, submit)
}
