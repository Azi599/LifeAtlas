package com.lifeatlas.app

import org.json.JSONArray
import org.json.JSONObject
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/** Literal quantities and clock readings from a typed request, independent of its subject. */
internal class InputEvidence(input: String, private val today: LocalDate) {
    private val text = input.replace('：', ':')
    private val numeral = "[0-9零〇一二两三四五六七八九十百千万点.]+"
    private val unitPattern = Units.knownUnits().sortedByDescending { it.length }.joinToString("|") { Regex.escape(it) }
    private val colloquialMoney = Regex("($numeral)块([0-9零〇一二两三四五六七八九])(?![0-9零〇一二两三四五六七八九])")
    private val datePattern = "(?:\\d{4}-\\d{2}-\\d{2}|今天|今日|昨晚|昨早|昨天|前天|今早|今晚|次日|明天)"
    private val dateMentions = Regex(datePattern).findAll(text).toList()
    private val hasNow = Regex("现在|刚刚|此刻").containsMatchIn(text)
    private data class Quantity(val value: BigDecimal, val unit: String)
    private data class Clock(val time: LocalTime, val date: LocalDate?)

    private val quantities: List<Quantity> = buildList {
        val colloquial = colloquialMoney.findAll(text).toList()
        for (match in colloquial) {
            val whole = number(match.groupValues[1]) ?: continue
            val fraction = number(match.groupValues[2]) ?: continue
            add(Quantity(whole + fraction.movePointLeft(1), "元"))
        }
        val other = Regex("(半|[+-]?$numeral)\\s*($unitPattern)")
        for (match in other.findAll(text)) {
            if (colloquial.any { match.range.first in it.range }) continue
            val value = if (match.groupValues[1] == "半") BigDecimal("0.5") else number(match.groupValues[1])
            if (value != null) add(Quantity(value, match.groupValues[2]))
        }
    }
    private val clocks: List<Clock> = run {
        val matches = mutableListOf<Pair<Int, LocalTime>>()
        for (match in Regex("(?<!\\d)([0-2]?\\d):([0-5]\\d)(?::([0-5]\\d))?").findAll(text)) {
            val hour = match.groupValues[1].toInt()
            if (hour <= 23) matches.add(match.range.first to LocalTime.of(hour, match.groupValues[2].toInt(), match.groupValues[3].toIntOrNull() ?: 0))
        }
        for (match in Regex("(凌晨|早上|上午|中午|下午|傍晚|晚上)?([0-9零〇一二两三四五六七八九十]{1,3})点(?:($numeral)分|半)?").findAll(text)) {
            val suffix = text.drop(match.range.last + 1).firstOrNull()
            if (suffix != null && (suffix.isDigit() || suffix in digits)) continue
            var hour = number(match.groupValues[2])?.toInt() ?: continue
            val minute = if (match.value.endsWith("半")) 30 else number(match.groupValues[3])?.toInt() ?: 0
            if (match.groupValues[1] in setOf("下午", "傍晚", "晚上") && hour in 1..11) hour += 12
            if (hour in 0..23 && minute in 0..59) matches.add(match.range.first to LocalTime.of(hour, minute))
        }
        matches.sortedBy { it.first }.map { (index, time) ->
            val date = dateMentions.lastOrNull { it.range.last < index }?.value?.let(::date)
            Clock(time, date)
        }
    }

    fun hints(): JSONObject = obj(
        "quantities" to JSONArray(quantities.map { obj("value" to it.value.toPlainString(), "unit" to it.unit) }),
        "clocks" to JSONArray(clocks.map { obj("date" to it.date?.toString(), "time" to it.time.toString()) })
    )

    fun prepareRecord(args: JSONObject, metric: JSONObject?, singleRecord: Boolean = true) {
        val config = metric?.let { JSONObject(it.s("config", "{}")) } ?: JSONObject()
        val unit = args.s("unit").ifBlank { config.s("unit") }
        val stated = quantitiesFor(unit)
        if (stated.size == 1) {
            val value = args.s("value").toBigDecimalOrNull()
            val expected = stated.single()
            val matches = if (config.s("role") in setOf("expense", "income")) {
                value?.abs()?.compareTo(expected.abs()) == 0
            } else value?.compareTo(expected) == 0
            require(matches) {
                "原句给出的数值是 ${stated.single().stripTrailingZeros().toPlainString()} $unit，请保留原句的数值"
            }
        }
        val day = LocalDate.parse(args.getString("date"))
        val dates = dateMentions.mapNotNull { date(it.value) }.distinct()
        if (dates.size == 1) require(day == dates.single()) { "记录日期与原句不一致" }
        if (clocks.isEmpty() && !hasNow) {
            listOf("occurredAt", "startAt", "endAt").forEach(args::remove)
            return
        }
        val zone = ZoneId.of(args.s("zone", ZoneId.systemDefault().id))
        if (singleRecord && clocks.size == 2 && config.s("kind") == "ALLOCATION" &&
            (args.has("startAt") || args.has("endAt"))) {
            val first = clocks.first()
            val last = clocks.last()
            val startDay = first.date ?: day
            val endDay = last.date ?: if (last.time < first.time) startDay.plusDays(1) else startDay
            args.put("date", startDay.toString())
            args.put("startAt", instant(startDay, first.time, zone))
            args.put("endAt", instant(endDay, last.time, zone))
            args.remove("occurredAt")
            if (stated.isEmpty()) args.remove("value")
        } else {
            for (key in listOf("occurredAt", "startAt", "endAt")) {
                val value = args.s(key)
                if (value.isBlank()) continue
                val clock = parseClock(value)
                if (clock != null) {
                    require(clocks.any { it.time == clock }) { "时刻不在原句中，请核对发生时间" }
                    val actualDay = clocks.filter { it.time == clock }.mapNotNull { it.date }.distinct().singleOrNull() ?: day
                    args.put(key, instant(actualDay, clock, zone))
                } else if (clocks.isNotEmpty()) {
                    val timestamp = java.time.Instant.parse(value).atZone(zone)
                    require(clocks.any { it.time == timestamp.toLocalTime() && (it.date == null || it.date == timestamp.toLocalDate()) }) {
                        "时刻不在原句中，请核对发生时间"
                    }
                }
            }
        }
    }

    fun fillReportRange(args: JSONObject) {
        val range = Regex("($datePattern)\\s*(?:到|至|—|～)\\s*($datePattern)").find(text) ?: return
        val from = date(range.groupValues[1]) ?: return
        val to = date(range.groupValues[2]) ?: return
        if (args.s("from").isBlank()) args.put("from", from.toString())
        if (args.s("to").isBlank()) args.put("to", to.toString())
    }

    private fun quantitiesFor(unit: String): List<BigDecimal> = quantities.mapNotNull { quantity ->
        runCatching { Units.convert(quantity.value, quantity.unit, unit).stripTrailingZeros() }.getOrNull()
    }.distinct()

    private fun date(value: String): LocalDate? = when (value) {
        "今天", "今日", "今早", "今晚" -> today
        "昨天", "昨晚", "昨早" -> today.minusDays(1)
        "前天" -> today.minusDays(2)
        "明天" -> today.plusDays(1)
        else -> runCatching { LocalDate.parse(value) }.getOrNull()
    }

    private fun parseClock(value: String): LocalTime? {
        val match = Regex("^(\\d{1,2}):(\\d{2})(?::(\\d{2}))?$").matchEntire(value) ?: return null
        return LocalTime.of(match.groupValues[1].toInt(), match.groupValues[2].toInt(), match.groupValues[3].toIntOrNull() ?: 0)
    }

    private fun instant(day: LocalDate, time: LocalTime, zone: ZoneId): String {
        val local = day.atTime(time)
        val offsets = zone.rules.getValidOffsets(local)
        require(offsets.size == 1) { "请核对时区和时刻" }
        return local.toInstant(offsets.single()).toString()
    }

    companion object {
        private val digits = mapOf('零' to 0, '〇' to 0, '一' to 1, '二' to 2, '两' to 2, '三' to 3,
            '四' to 4, '五' to 5, '六' to 6, '七' to 7, '八' to 8, '九' to 9)
        private val scales = mapOf('十' to 10, '百' to 100, '千' to 1000)

        private fun number(value: String): BigDecimal? {
            value.toBigDecimalOrNull()?.let { return it }
            if (value.isEmpty()) return null
            val parts = value.split('点', '.')
            if (parts.size > 2) return null
            val whole = parts[0]
            val integer = if (whole.all { it in digits || it.isDigit() }) {
                whole.map { digits[it]?.toString() ?: it.toString() }.joinToString("").toBigDecimalOrNull()
            } else {
                var total = 0L
                var group = 0L
                var digit: Int? = null
                for (c in whole) {
                    val d = digits[c] ?: c.digitToIntOrNull()
                    val scale = scales[c]
                    when {
                        d != null -> digit = d
                        scale != null -> { group += (digit ?: 1) * scale; digit = null }
                        c == '万' -> { total += (group + (digit ?: 0)) * 10000; group = 0; digit = null }
                        else -> return null
                    }
                }
                (total + group + (digit ?: 0)).toBigDecimal()
            } ?: return null
            if (parts.size == 1) return integer
            val fraction = parts[1].map { digits[it]?.toString() ?: it.digitToIntOrNull()?.toString() ?: return null }.joinToString("")
            return integer + ("0.$fraction".toBigDecimalOrNull() ?: return null)
        }
    }
}
