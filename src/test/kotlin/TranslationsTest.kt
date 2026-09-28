package fr.nimby.placement.tool

import kotlin.test.*
import nimby.tr

class TranslationsTest {
    @Test fun referencesPreserveUnicodeAndNamedValues() {
        val value = tr("summary", "name" to "Été \"A\"", "count" to 3)
        assertTrue(value.contains("Été"))
        assertTrue(value.contains("\\\"A\\\""))
        assertTrue(value.contains("\"count\":\"3\""))
        assertTrue(value.encodeToByteArray().size <= 256)
    }
    @Test fun badArgumentsFailBeforePublishingAControl() {
        assertFailsWith<IllegalArgumentException> { tr("") }
        assertFailsWith<IllegalArgumentException> { tr("has spaces") }
        assertFailsWith<IllegalArgumentException> { tr("ok", "n" to 1, "n" to 2) }
        assertFailsWith<IllegalArgumentException> { tr("ok", "n" to "\u0000") }
        assertFailsWith<IllegalArgumentException> { tr("ok", "n" to "é".repeat(128)) }
        assertFailsWith<IllegalArgumentException> { tr("ok", *Array(9) { "n$it" to it }) }
    }
}
