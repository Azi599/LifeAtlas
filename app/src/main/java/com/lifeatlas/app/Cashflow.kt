package com.lifeatlas.app

import org.json.JSONArray
import org.json.JSONObject
import java.math.BigDecimal
import java.time.LocalDate

internal fun AtlasStore.cashflow(from: String,to: String,metricIds: Set<String> = emptySet()):List<JSONObject> {
    require(LocalDate.parse(from)<=LocalDate.parse(to)) {"日期范围有误"}
    val finance = query("SELECT id,title,config FROM atlas_objects WHERE type='METRIC' AND archived=0 ORDER BY title,id").filter { metricIds.isEmpty() || it.s("id") in metricIds }
        .map { it to JSONObject(it.s("config")) }
        .filter { (_, config) -> config.s("role") in setOf("income","expense") }
        .groupBy { (_, config) -> Units.currency(config.s("unit")) }
    return finance.mapNotNull { (currency,group) ->
        var income = BigDecimal.ZERO
        var expense = BigDecimal.ZERO
        var incomeCount = 0L
        var expenseCount = 0L
        var incomeMissing = 0L
        var expenseMissing = 0L
        val spending = JSONArray()
        group.forEach { (metric,config) ->
            val stats = aggregate(metric.s("id"),from,to,"SUM")
            val count = stats.optLong("recordCount")
            val missing = stats.optLong("missingCount")
            val raw = stats.s("value").toBigDecimalOrNull()
            val amount = raw?.let { if(currency=="CNY") Units.convert(it,config.s("unit"),"CNY") else it }
            if (config.s("role")=="income") {
                incomeCount += count
                incomeMissing += missing
                if(amount!=null) income += amount
            } else {
                expenseCount += count
                expenseMissing += missing
                if(amount!=null) {
                    expense -= amount
                    if (amount.signum()<0) spending.put(obj("title" to metric.s("title"),"value" to amount.negate().toPlainString()))
                }
            }
        }
        if(incomeCount+expenseCount==0L) return@mapNotNull null
        obj("unit" to if(currency=="CNY") "元" else currency,"from" to from,"to" to to,
            "income" to income.takeIf{incomeCount>incomeMissing}?.stripTrailingZeros()?.toPlainString(),
            "expense" to expense.takeIf{expenseCount>expenseMissing}?.stripTrailingZeros()?.toPlainString(),
            "net" to (income-expense).takeIf{incomeMissing+expenseMissing==0L}?.stripTrailingZeros()?.toPlainString(),
            "incomeCount" to incomeCount,"expenseCount" to expenseCount,
            "missingCount" to incomeMissing+expenseMissing,"spending" to spending)
    }
}
