package io.github.ccch1mneyyy.dsh_app.phone.input

import io.github.ccch1mneyyy.dsh_app.phone.background.Proto
import org.junit.Assert.*
import org.junit.Test

/**
 * 0·A: text into a page with no field ids. ASCII goes through `input text` (nothing on screen
 * moves); everything else goes through the clipboard first, because that costs the person nothing;
 * borrowing their input method for one commit is the fallback when the clipboard cannot be pasted.
 */
class TextInjectorTest {
    private class Channel : TypingChannel {
        val typed = mutableListOf<String>()
        val keys = mutableListOf<Int>()
        val imeCalls = mutableListOf<Pair<String, String>>()
        var asciiOk = true
        var imeOk = true
        override fun typeAscii(text: String): Boolean {
            if (asciiOk) typed += text
            return asciiOk
        }
        override fun key(code: Int): Boolean = keys.add(code)
        override fun ime(action: String, component: String): Boolean {
            imeCalls += action to component
            return imeOk
        }
    }

    private class Ime : ImePort {
        var currentId: String? = "com.sohu.inputmethod.sogou.oem/com.sohu.inputmethod.sogou.SogouIME"
        var readyNow = true
        var answer: String? = null
        val commits = mutableListOf<Pair<String, Boolean>>()
        override fun component() = "io.github.ccch1mneyyy.dsh_app/.phone.input.DshImeService"
        override fun current() = currentId
        override fun ready() = readyNow
        override fun commit(text: String, submit: Boolean): String? {
            commits += text to submit
            return answer
        }
    }

    /** Stands in for the accessibility driver's clipboard plus the shell's paste key. */
    private class Paste : PastePort {
        val texts = mutableListOf<String>()
        var clipboardOk = true
        var fieldOk = false
        var keyOk = true
        override fun clipboard(text: String): Boolean {
            if (clipboardOk) texts += text
            return clipboardOk
        }
        override fun fieldReady(): Boolean = fieldOk

        override fun pasteField(): Boolean = fieldOk
        override fun pasteKey(): Boolean = keyOk
    }

    private val channel = Channel()
    private val ime = Ime()
    private val paste = Paste()
    private var clock = 0L
    private val injector = TextInjector(channel, ime, paste, now = { clock }, sleep = { clock += it })

    @Test fun asciiGoesThroughTheShellWithoutTouchingTheKeyboard() {
        assertNull(injector.type("hello123", submit = false))
        assertEquals(listOf("hello123"), channel.typed)
        assertTrue(channel.imeCalls.isEmpty())
        assertTrue(ime.commits.isEmpty())
    }

    @Test fun submitPressesEnter() {
        assertNull(injector.type("abc", submit = true))
        assertEquals(listOf(TypingChannel.ENTER), channel.keys)
    }

    @Test fun chineseBorrowsTheKeyboardThenGivesItBack() {
        paste.clipboardOk = false
        assertNull(injector.type("银行卡", submit = false))
        assertEquals(listOf("银行卡" to false), ime.commits)
        val sogou = "com.sohu.inputmethod.sogou.oem/com.sohu.inputmethod.sogou.SogouIME"
        val ours = ime.component()
        assertEquals(
            listOf(
                Proto.IME_ENABLE to ours,
                Proto.IME_SET to ours,
                Proto.IME_SET to sogou,
                Proto.IME_DISABLE to ours,
            ),
            channel.imeCalls,
        )
        assertTrue(channel.typed.isEmpty())
    }

    @Test fun aRefusedCommitStillGivesTheKeyboardBack() {
        paste.clipboardOk = false
        ime.answer = "应用没有接受这段文字。"
        assertEquals("应用没有接受这段文字。", injector.type("银行卡", submit = false))
        assertEquals(Proto.IME_DISABLE to ime.component(), channel.imeCalls.last())
    }

    @Test fun noEngineSaysWhichSwitchToOpen() {
        channel.asciiOk = false
        paste.clipboardOk = false
        channel.imeOk = false
        val out = injector.type("银行卡", submit = false)
        assertNotNull(out)
        assertTrue(out!!, out.contains("不打扰模式"))
        assertTrue(out, out.contains("自连 ADB"))
    }

    @Test fun aFieldThatNeverTakesTheTextSaysWhatToDo() {
        ime.readyNow = false
        paste.clipboardOk = false
        val out = injector.type("银行卡", submit = false)
        assertNotNull(out)
        assertTrue(out!!, out.contains("tap_xy"))
    }

    @Test fun theInputMethodAlreadyInUseIsNotSwitchedAgain() {
        ime.currentId = ime.component()
        paste.clipboardOk = false
        assertNull(injector.type("银行卡", submit = false))
        assertTrue(channel.imeCalls.isEmpty())
        assertEquals(listOf("银行卡" to false), ime.commits)
    }

    @Test fun chineseGoesThroughTheClipboardWithoutTouchingTheKeyboard() {
        paste.fieldOk = true
        assertNull(injector.type("银行卡", submit = false))
        assertEquals(listOf("银行卡"), paste.texts)
        assertTrue(channel.typed.isEmpty())
        assertTrue(channel.imeCalls.isEmpty())
        assertTrue(ime.commits.isEmpty())
    }

    @Test fun aHiddenFieldGetsThePasteKeyAndAnHonestAnswer() {
        paste.fieldOk = false
        ime.readyNow = false
        val out = injector.type("银行卡", submit = false)
        assertNotNull(out)
        assertTrue(out!!, out.contains("剪贴板"))
        assertTrue(out, out.contains("长按输入框"))
        // the keyboard was borrowed and given back before the paste key was tried
        assertEquals(listOf("enable", "set", "set", "disable"), channel.imeCalls.map { it.first })
        assertTrue(ime.commits.isEmpty())
    }

    @Test fun thePasteKeyStillSubmitsWhenAsked() {
        ime.readyNow = false
        assertNotNull(injector.type("银行卡", submit = true))
        assertEquals(listOf("银行卡"), paste.texts)
        assertEquals(listOf(TypingChannel.ENTER), channel.keys)
    }

    @Test fun aClipboardNobodyAcceptsFallsBackToTheKeyboard() {
        paste.clipboardOk = false
        assertNull(injector.type("银行卡", submit = false))
        assertEquals(listOf("银行卡" to false), ime.commits)
    }

    @Test fun aBorrowedKeyboardBeatsThePasteKeyOnAPicturePage() {
        paste.fieldOk = false
        assertNull(injector.type("银行卡", submit = false))
        assertEquals(listOf("银行卡" to false), ime.commits)
        assertTrue(channel.keys.isEmpty())
        assertTrue(paste.texts.isEmpty())
    }

    @Test fun asciiOnlyIsPrintableAsciiAndNoPlaceholder() {
        assertTrue(TextInjector.asciiOnly("Hello, world 123!~"))
        assertFalse(TextInjector.asciiOnly("银行卡"))
        assertFalse(TextInjector.asciiOnly(""))
        assertFalse(TextInjector.asciiOnly("a%sb"))
        assertFalse(TextInjector.asciiOnly("a\nb"))
        assertFalse(TextInjector.asciiOnly("x".repeat(TextInjector.MAX_TEXT + 1)))
    }
}
