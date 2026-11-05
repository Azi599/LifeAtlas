package com.lifeatlas.app

import org.junit.Assert.*
import org.junit.Test
import org.json.JSONObject
import java.math.BigDecimal
import java.time.*
class MathEngineTest {
    @Test fun expenseSignRegression(){assertEquals(BigDecimal("-25.80"),MathEngine.cashflow(BigDecimal.ZERO,BigDecimal("25.80")))}
    @Test fun weightedMeanUsesWeights(){assertEquals(0,MathEngine.weighted(listOf(BigDecimal("10") to BigDecimal("1"),BigDecimal("20") to BigDecimal("3")))!!.compareTo(BigDecimal("17.5")))}
    @Test fun medianKeepsDistribution(){assertEquals(0,MathEngine.median(listOf(BigDecimal("1"),BigDecimal("2"),BigDecimal("100"),BigDecimal("3")))!!.compareTo(BigDecimal("2.5")))}
    @Test fun emptyValuesStayUnknown(){assertNull(MathEngine.mean(emptyList()));assertNull(MathEngine.median(emptyList()));assertNull(MathEngine.pearson(listOf(1.0 to 2.0,1.0 to 3.0,1.0 to 4.0)))}
    @Test fun overlappingActivitiesCountPhysicalUnion(){assertEquals(10800L,MathEngine.union(listOf(0L to 10800L,3600L to 7200L)))}
    @Test fun transferPreservesTotal(){assertEquals(BigDecimal.ZERO,MathEngine.sum(listOf(BigDecimal("-500"),BigDecimal("500"))))}
    @Test fun snapshotAnchorsOnlyLaterChanges(){assertEquals(BigDecimal("1015"),MathEngine.snapshotBalance(BigDecimal("1000"),listOf(BigDecimal("20"),BigDecimal("-5"))))}
    @Test fun midnightUsesLocalDates(){val parts=MathEngine.splitByDate(Instant.parse("2026-11-05T15:00:00Z"),Instant.parse("2026-11-05T17:00:00Z"),ZoneId.of("Asia/Shanghai"));assertEquals(3600L,parts[LocalDate.parse("2026-11-05")]);assertEquals(3600L,parts[LocalDate.parse("2026-11-06")])}
    @Test fun expressionIsDeterministic(){assertEquals(0,Expressions.eval(JSONObject("""{"op":"divide","args":[{"field":"ml"},{"const":"1000"}]}"""),mapOf("ml" to BigDecimal("1500"))).compareTo(BigDecimal("1.5")))}
    @Test(expected=IllegalArgumentException::class) fun expressionBudgetStopsDeepTree(){var e=JSONObject("""{"const":"1"}""");repeat(30){e=obj("op" to "add","args" to org.json.JSONArray().put(e))};Expressions.eval(e,emptyMap())}
}
