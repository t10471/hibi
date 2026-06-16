package com.example.hibi.util

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

/** java.time is available down to minSdk 24 thanks to core library desugaring. */
object DateUtils {

    private val display = DateTimeFormatter.ofPattern("yyyy/M/d")

    fun todayIso(): String = LocalDate.now().toString()

    fun daysAgo(iso: String): Long {
        val date = runCatching { LocalDate.parse(iso) }.getOrNull() ?: return 0
        return ChronoUnit.DAYS.between(date, LocalDate.now()).coerceAtLeast(0)
    }

    /** "今日" / "昨日" / "N日前" */
    fun daysAgoLabel(iso: String): String = when (val d = daysAgo(iso)) {
        0L -> "今日"
        1L -> "昨日"
        else -> "${d}日前"
    }

    fun formatDate(iso: String): String =
        runCatching { LocalDate.parse(iso).format(display) }.getOrDefault(iso)

    fun formatTime(hour: Int, minute: Int): String =
        "%02d:%02d".format(hour, minute)

    /** Material DatePicker works in UTC millis at start-of-day. */
    fun isoFromUtcMillis(millis: Long): String =
        Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate().toString()

    fun utcMillisFromIso(iso: String): Long =
        (runCatching { LocalDate.parse(iso) }.getOrNull() ?: LocalDate.now())
            .atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

    fun todayUtcMillis(): Long =
        LocalDate.now().atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

    /** 直近 [count] 日分の ISO 日付（古い順、最後が今日）。 */
    fun recentDays(count: Int): List<String> {
        val today = LocalDate.now()
        return (count - 1 downTo 0).map { today.minusDays(it.toLong()).toString() }
    }

    /** [days] 日前の ISO 日付。古いデータの剪定の下限に使う。 */
    fun isoDaysAgo(days: Int): String =
        LocalDate.now().minusDays(days.toLong()).toString()

    /** 曜日（日本語1文字）。 */
    fun weekdayJa(iso: String): String =
        when (runCatching { LocalDate.parse(iso).dayOfWeek }.getOrNull()) {
            DayOfWeek.MONDAY -> "月"
            DayOfWeek.TUESDAY -> "火"
            DayOfWeek.WEDNESDAY -> "水"
            DayOfWeek.THURSDAY -> "木"
            DayOfWeek.FRIDAY -> "金"
            DayOfWeek.SATURDAY -> "土"
            DayOfWeek.SUNDAY -> "日"
            null -> ""
        }

    /** "M/D" 形式。 */
    fun monthDay(iso: String): String =
        runCatching { LocalDate.parse(iso).let { "${it.monthValue}/${it.dayOfMonth}" } }.getOrDefault(iso)
}
