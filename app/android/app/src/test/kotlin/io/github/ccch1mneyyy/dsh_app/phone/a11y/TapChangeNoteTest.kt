package io.github.ccch1mneyyy.dsh_app.phone.a11y

import org.junit.Assert.*
import org.junit.Test

/**
 * What a tap that did register is told back: how much the picture moved, not only that it did.
 *
 * 19:57:16: (500,118) scored 0.46, then (520,118) scored 0.46 — the model walked a page out and back
 * and burned six turns on one strip of a 炉石 picture before the repeat guard stopped it. A model
 * told "0.46, a whole page" has the one signal that stops it tapping the same button again.
 */
class TapChangeNoteTest {
    @Test fun aBigChangeIsReportedAsANewPage() {
        val note = MainScreenDriver.changeNote(0.46f)
        assertTrue(note, note.contains("0.46"))
        assertTrue(note, note.contains("翻了页"))
        assertTrue(note, note.contains("不要在原地再点一次"))
    }

    @Test fun aSmallChangeIsReportedAsSomethingElse() {
        // A highlight, a spinner, a focus ring: the page is still the page, so say so.
        val note = MainScreenDriver.changeNote(0.15f)
        assertTrue(note, note.contains("0.15"))
        assertTrue(note, note.contains("不要在原地重复点"))
        assertFalse(note, note.contains("翻了页"))
    }

    @Test fun theBoundaryBetweenTheTwoIsThePageThreshold() {
        assertTrue(MainScreenDriver.changeNote(MainScreenDriver.CHANGED_PAGE), MainScreenDriver.changeNote(MainScreenDriver.CHANGED_PAGE).contains("翻了页"))
        assertFalse(MainScreenDriver.changeNote(MainScreenDriver.CHANGED_PAGE - 0.01f), MainScreenDriver.changeNote(MainScreenDriver.CHANGED_PAGE - 0.01f).contains("翻了页"))
    }
}
