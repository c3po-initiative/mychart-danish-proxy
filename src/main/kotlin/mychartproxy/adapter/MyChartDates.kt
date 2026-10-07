package mychartproxy.adapter

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeFormatterBuilder
import java.time.format.DateTimeParseException
import java.util.Locale

/**
 * Normalises the mix of date representations MyChart returns into the ISO-8601
 * offset date-times that dhroxy's shared mappers parse with `OffsetDateTime.parse`.
 *
 * MyChart's `*ISO`/`instant` fields are already ISO; display fields (`formattedDateNoted`,
 * referral `start`/`end`, …) are locale-formatted. Unparseable input yields null so a
 * mapper never fails on a display string.
 */
class MyChartDates(private val zone: ZoneId) {

    fun toIsoOffset(raw: String?): String? {
        val value = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        tryParse { OffsetDateTime.parse(value) }?.let { return it.toString() }
        tryParse { LocalDateTime.parse(value).atZone(zone).toOffsetDateTime() }?.let { return it.toString() }
        tryParse { LocalDate.parse(value).atStartOfDay(zone).toOffsetDateTime() }?.let { return it.toString() }
        for (fmt in DATE_TIME_FORMATS) {
            tryParse { LocalDateTime.parse(value, fmt).atZone(zone).toOffsetDateTime() }?.let { return it.toString() }
        }
        for (fmt in DATE_FORMATS) {
            tryParse { LocalDate.parse(value, fmt).atStartOfDay(zone).toOffsetDateTime() }?.let { return it.toString() }
        }
        return null
    }

    fun toOffsetDateTime(raw: String?): OffsetDateTime? = toIsoOffset(raw)?.let { OffsetDateTime.parse(it) }

    private inline fun <T> tryParse(block: () -> T): T? = try {
        block()
    } catch (_: DateTimeParseException) {
        null
    }

    companion object {
        private val DA = Locale.forLanguageTag("da-DK")

        private fun ci(pattern: String): DateTimeFormatter =
            DateTimeFormatterBuilder().parseCaseInsensitive().appendPattern(pattern).toFormatter(DA)

        private val DATE_FORMATS = listOf(
            "d-M-uuuu", "d.M.uuuu", "d/M/uuuu",
            "d. MMM uuuu", "d. MMMM uuuu", "d MMM uuuu", "d MMMM uuuu"
        ).map(::ci)

        private val DATE_TIME_FORMATS = listOf(
            "d-M-uuuu H:mm", "d.M.uuuu H:mm", "d/M/uuuu H:mm",
            "d-M-uuuu 'kl.' H:mm", "d.M.uuuu 'kl.' H.mm", "d. MMM uuuu 'kl.' H:mm"
        ).map(::ci)
    }
}
