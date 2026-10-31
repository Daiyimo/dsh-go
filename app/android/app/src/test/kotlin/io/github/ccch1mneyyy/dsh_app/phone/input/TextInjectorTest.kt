package io.github.ccch1mneyyy.dsh_app.phone.input

import io.github.ccch1mneyyy.dsh_app.phone.background.Proto
import org.junit.Assert.*
import org.junit.Test

/**
 * 0·A: text into a page with no field ids. ASCII goes through `input text` (nothing on screen
 * moves); Chinese borrows the person's input method for one commit and hands it straight back.
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

    private val channel = Channel()
    private val ime = Ime()
    private var clock = 0L
    private val injector = TextInjector(channel, ime, now = { clock }, sleep = { clock += it })

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
        ime.answer = "应用没有接受这段文字。"
        assertEquals("应用没有接受这段文字。", injector.type("银行卡", submit = false))
        assertEquals(Proto.IME_DISABLE to ime.component(), channel.imeCalls.last())
    }

    @Test fun noEngineSaysWhichSwitchToOpen() {
        channel.asciiOk = false
        channel.imeOk = false
        val out = injector.type("银行卡", submit = false)
        assertNotNull(out)
        assertTrue(out!!, out.contains("不打扰模式"))
        assertTrue(out, out.contains("自连 ADB"))
    }

    @Test fun aFieldThatNeverTakesTheTextSaysWhatToDo() {
        ime.readyNow = false
        val out = injector.type("银行卡", submit = false)
        assertNotNull(out)
        assertTrue(out!!, out.contains("tap_xy"))
    }

    @Test fun theInputMethodAlreadyInUseIsNotSwitchedAgain() {
        ime.currentId = ime.component()
        assertNull(injector.type("银行卡", submit = false))
        assertTrue(channel.imeCalls.isEmpty())
        assertEquals(listOf("银行卡" to false), ime.commits)
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
