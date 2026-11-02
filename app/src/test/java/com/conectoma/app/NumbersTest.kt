package com.conectoma.app

import com.conectoma.app.core.Numbers
import org.junit.Assert.assertEquals
import org.junit.Test

class NumbersTest {

    @Test
    fun groupsThousandsWithDots() {
        assertEquals("3.750.000", Numbers.grouped(3_750_000))
        assertEquals("34.813", Numbers.grouped(34_813))
        assertEquals("0", Numbers.grouped(0))
    }

    @Test
    fun neverProducesAnInvalidFormat() {
        // regression: "%,d".replace(',', '.') used to become "%.d" and throw
        val values = listOf(Int.MIN_VALUE, -1, 0, 1, 999, 1_000, Int.MAX_VALUE)
        for (v in values) {
            val text = Numbers.grouped(v)
            assertEquals(true, text.isNotEmpty())
        }
    }

    @Test
    fun percentIsTruncated() {
        assertEquals("75%", Numbers.percent(0.757f))
        assertEquals("0%", Numbers.percent(0f))
        assertEquals("100%", Numbers.percent(1f))
    }
}
