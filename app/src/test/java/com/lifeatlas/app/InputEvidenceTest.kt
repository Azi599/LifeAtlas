package com.lifeatlas.app

import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

class InputEvidenceTest {
    private val day = LocalDate.parse("2026-11-06")
    private val weight = obj("config" to obj("unit" to "kg", "kind" to "SNAPSHOT").toString())

    @Test fun spokenNumberRejectsValueCopiedFromPreviousRecord() {
        val evidence = InputEvidence("今天称出来六十五公斤整，给我记到晨间体重。", day)
        val args = obj("value" to "65.2", "date" to day.toString())
        try {
            evidence.prepareRecord(args, weight)
            fail("65.2 was absent from the current instruction")
        } catch (failure: IllegalArgumentException) {
            assertTrue(failure.message.orEmpty().contains("65 kg"))
        }
        evidence.prepareRecord(args.put("value", "65"), weight)
    }

    @Test fun colloquialMoneyAndDurationRemainSeparateAndDoNotAcquireInventedTimes() {
        val evidence = InputEvidence("昨天晚饭花了18块5，另外学了半小时。", day)
        val expense = obj("config" to obj("unit" to "元", "kind" to "CHANGE", "role" to "expense").toString())
        val study = obj("config" to obj("unit" to "分钟", "kind" to "ALLOCATION").toString())
        val spent = obj("value" to "-18.50", "date" to "2026-11-05", "occurredAt" to "2026-11-05T00:00:00Z")
        evidence.prepareRecord(spent, expense, singleRecord = false)
        assertFalse(spent.has("occurredAt"))
        evidence.prepareRecord(obj("value" to "30", "date" to "2026-11-05"), study, singleRecord = false)
    }

    @Test fun statedClocksDetermineTheCrossMidnightInterval() {
        val evidence = InputEvidence("昨天晚上23点40分睡着，今天零点20分起来，记一下这次睡眠。", day)
        val metric = obj("config" to obj("unit" to "分钟", "kind" to "ALLOCATION").toString())
        val args = obj("date" to "2026-11-06", "zone" to "Asia/Shanghai", "startAt" to "0:20", "endAt" to "0:20", "value" to "120")
        evidence.prepareRecord(args, metric)
        assertEquals("2026-11-05", args.s("date"))
        assertEquals("2026-11-05T15:40:00Z", args.s("startAt"))
        assertEquals("2026-11-05T16:20:00Z", args.s("endAt"))
        assertFalse(args.has("value"))
    }

    @Test fun explicitReportRangeCanBeAppliedWithoutMakingNewFacts() {
        val args = obj("title" to "两日支出核对")
        InputEvidence("今天买面包6元，再汇总昨天到今天的生活支出", day).fillReportRange(args)
        assertEquals("2026-11-05", args.s("from"))
        assertEquals("2026-11-06", args.s("to"))
    }
}
