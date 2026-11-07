package com.lifeatlas.app

import org.json.JSONArray
import org.json.JSONObject
import java.math.BigDecimal
import java.math.MathContext
import java.time.LocalDate
import java.time.temporal.ChronoUnit

internal fun AtlasStore.dailySpending(from:String,to:String):JSONObject = synchronized(this) {
    val first=LocalDate.parse(from)
    val last=LocalDate.parse(to)
    val span=ChronoUnit.DAYS.between(first,last)
    require(span in 0..365){"请选择一年以内的日期范围"}
    val totals=mutableMapOf<String,BigDecimal>()
    val incomplete=mutableSetOf<String>()
    metrics().forEach { metric ->
        val config=JSONObject(metric.s("config"))
        val unit=config.s("unit")
        if(config.s("role")!="expense" || Units.currency(unit)!="CNY") return@forEach
        dailySeries(metric.s("id"),from,to,"SUM").forEach { point ->
            val date=point.s("date")
            if(point.optLong("missingCount")>0) incomplete.add(date)
            point.s("value").toBigDecimalOrNull()?.let{amount->
                totals[date]=totals.getOrDefault(date,BigDecimal.ZERO)-Units.convert(amount,unit,"元")
            }
        }
    }
    val points=JSONArray()
    var empty=0
    for(offset in 0..span) {
        val day=first.plusDays(offset).toString()
        val value=totals[day]
        if(value==null && day !in incomplete) empty++
        points.put(obj("date" to day,"value" to value?.takeUnless{day in incomplete}?.stripTrailingZeros()?.toPlainString()))
    }
    obj("points" to points,"chart" to "line","unit" to "元","from" to from,"to" to to,
        "dailySpending" to true,"emptyDays" to empty,"incompleteDays" to incomplete.size)
}

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
            "net" to (income-expense).takeIf{
                incomeCount>0 && expenseCount>0 && incomeMissing+expenseMissing==0L
            }?.stripTrailingZeros()?.toPlainString(),
            "incomeCount" to incomeCount,"expenseCount" to expenseCount,
            "missingExpenseCount" to expenseMissing,
            "missingCount" to incomeMissing+expenseMissing,"spending" to spending)
    }
}

internal fun AtlasStore.spendingShare(from:String,to:String,metricIds:Set<String>):JSONObject {
    require(metricIds.isNotEmpty()){"请选择支出类别"}
    val total=cashflow(from,to).singleOrNull{it.s("unit")=="元"}
    val selected=cashflow(from,to,metricIds).singleOrNull{it.s("unit")=="元"}
    val denominator=total?.s("expense")?.toBigDecimalOrNull()
    val complete=total!=null && total.optLong("missingExpenseCount")==0L
    val amount=if(complete) selected?.s("expense")?.toBigDecimalOrNull() else null
    val percent=if(amount!=null && denominator!=null && denominator.signum()>0)
        amount.multiply(BigDecimal("100")).divide(denominator,MathContext.DECIMAL128).toPlainString()
        else null
    return obj("spendingShare" to true,"from" to from,"to" to to,"percentage" to percent,
        "selected" to amount?.stripTrailingZeros()?.toPlainString(),"total" to denominator?.stripTrailingZeros()?.toPlainString(),
        "reason" to when {
            total==null -> "所选日期没有支出记录"
            !complete -> "有支出金额待补充"
            selected==null -> "该类别在所选日期没有支出记录"
            amount==null -> "该类别的支出金额待补充"
            denominator==null || denominator.signum()<=0 -> "全部支出净额不大于零"
            else -> null
        })
}

internal fun AtlasStore.duplicateExpenseGroups(date:String):List<List<JSONObject>> {
    LocalDate.parse(date)
    val configs=metrics().map{it.s("id") to JSONObject(it.s("config"))}
        .filter{(_,config)->config.s("role")=="expense" && Units.currency(config.s("unit"))=="CNY"}
    val matching=mutableMapOf<BigDecimal,MutableList<JSONObject>>()
    for((id,config) in configs) {
        query("SELECT id,metric_id,value,fields_json FROM atlas_facts WHERE metric_id=? AND deleted=0 AND local_date=? AND value IS NOT NULL",
            arrayOf(id,date)).forEach { row ->
            val fields=JSONObject(row.s("fields_json"))
            if(fields.s("flow") !in setOf("transfer","refund")) {
                val value=resolvedRecordValue(decimal(row.s("value")),fields,config)
                if(value!=null) {
                    val amount=Units.convert(value,config.s("unit"),"元").abs().stripTrailingZeros()
                    matching.getOrPut(amount){mutableListOf()}.add(row.put("amount",amount.toPlainString()))
                }
            }
        }
    }
    return matching.values.filter{it.size>=2}
}

internal fun AtlasStore.recentTransferAudit():JSONObject {
    val latest=query("SELECT result FROM atlas_operations WHERE undone=0 AND patches!='[]' ORDER BY created_at DESC LIMIT 1")
        .firstOrNull()?.let{JSONObject(it.s("result")).optJSONObject("steps")}
    val receipt=latest?.let{steps->steps.keys().asSequence().mapNotNull(steps::optJSONObject).singleOrNull{it.has("transferAmount")}}
        ?: return obj("transferAudit" to true,"message" to "最近保存的操作没有单笔转账，请先在时间轴定位")
    val rows=query("SELECT metric_id,value,fields_json FROM atlas_facts WHERE event_id=? AND deleted=0",arrayOf(receipt.s("eventId")))
    if(rows.size!=2 || rows.any{JSONObject(it.s("fields_json")).s("flow")!="transfer"})
        return obj("transferAudit" to true,"message" to "转账的两条关联流水需要核对")
    val amounts=rows.map { row ->
        val config=config(row.s("metric_id"))
        val fields=JSONObject(row.s("fields_json"))
        val value=resolvedRecordValue(row.s("value").toBigDecimalOrNull(),fields,config)
        value?.let{Units.convert(it,config.s("unit"),receipt.s("unit"))}
    }
    val valid=amounts.all{it!=null} && MathEngine.sum(amounts.filterNotNull()).signum()==0
    return obj("transferAudit" to true,"message" to if(valid) "两条流水净额为 0，均排除在收入和支出统计之外"
        else "两条流水净额需要核对","date" to receipt.s("date"))
}
