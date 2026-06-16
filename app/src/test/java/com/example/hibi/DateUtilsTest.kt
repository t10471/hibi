package com.example.hibi

import com.example.hibi.util.DateUtils
import org.junit.Assert.assertEquals
import org.junit.Test

class DateUtilsTest {

    @Test
    fun today_label_isToday() {
        assertEquals("今日", DateUtils.daysAgoLabel(DateUtils.todayIso()))
    }

    @Test
    fun millisRoundTrip_preservesIsoDate() {
        val iso = "2025-01-15"
        val millis = DateUtils.utcMillisFromIso(iso)
        assertEquals(iso, DateUtils.isoFromUtcMillis(millis))
    }

    @Test
    fun daysAgo_isNonNegative() {
        assertEquals(0L, DateUtils.daysAgo(DateUtils.todayIso()))
    }
}
