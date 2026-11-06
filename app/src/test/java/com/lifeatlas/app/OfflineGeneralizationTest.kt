package com.lifeatlas.app

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

class OfflineGeneralizationTest {
    private val today=LocalDate.parse("2027-03-18")

    private fun metric(id:String,title:String,unit:String,alias:String="",parent:String="",role:String=""):JSONObject =
        obj("id" to id,"title" to title,"parent_id" to parent,"config" to obj(
            "kind" to if(role=="expense") "CHANGE" else "SNAPSHOT","unit" to unit,"role" to role,
            "aliases" to JSONArray(listOf(alias).filter{it.isNotBlank()})
        ).toString())

    private fun steps(plan:JSONObject)=plan.getJSONArray("steps").objects()

    @Test fun explicitProjectAndAliasResolveWithoutDomainSpecificWords() {
        val first=metric("incubator","舱体表面温度","℃","探针读数","chamber")
        val second=metric("station","舱体表面温度","摄氏度","探针读数","outdoor")
        val spaces=listOf(obj("id" to "chamber","type" to "PROJECT","title" to "孵化实验"),
            obj("id" to "outdoor","type" to "PROJECT","title" to "室外对照"))
        val scoped=Offline.plan("在「孵化实验」里记前天探针读数18.4摄氏度",listOf(first,second),today,spaces={spaces})!!
        val args=steps(scoped).single().getJSONObject("args")
        assertEquals("incubator",args.s("metricId"))
        assertEquals("2027-03-16",args.s("date"))
        assertEquals("18.4",args.s("value"))
        val ambiguous=Offline.plan("前天探针读数18.4摄氏度",listOf(second,first),today)!!
        assertFalse(ambiguous.has("steps"))
        assertEquals(2,ambiguous.getJSONArray("options").length())
    }

    @Test fun quotedPunctuationIsNotAnActionSeparator() {
        val salinity=metric("salinity","潮池、盐度","%")
        val length=metric("length","幼苗宽度","厘米")
        val metrics=listOf(salinity,length)
        val plan=Offline.plan("昨日“潮池、盐度”3.4%，另外幼苗宽度0.03米",metrics,today)!!
        val args=steps(plan).map{it.getJSONObject("args")}
        assertEquals(listOf("salinity","length"),args.map{it.s("metricId")})
        assertEquals(listOf("2027-03-17","2027-03-17"),args.map{it.s("date")})
        assertEquals(0,Units.convert(args[1].s("value").toBigDecimal(),args[1].s("unit"),"厘米")
            .compareTo("3".toBigDecimal()))
        val reversed=Offline.plan("昨日测得3.4%，记入“潮池、盐度”",metrics,today)!!
        val record=steps(reversed).single().getJSONObject("args")
        assertEquals("salinity",record.s("metricId"))
        assertEquals("2027-03-17",record.s("date"))
        assertEquals("3.4",record.s("value"))
        assertNull(Offline.plan("昨日“潮池、盐度3.4%，另外幼苗宽度0.03米",metrics,today))
        assertNull(Offline.plan("昨日“潮池、盐度”3.4%，另外把所有日志变成图片",metrics,today))
    }

    @Test fun exactNameWinsOverAnotherMetricsAlias() {
        val exact=metric("exact","水槽高度","毫米")
        val alias=metric("alias","台架高度","毫米","水槽高度")
        for(metrics in listOf(listOf(alias,exact),listOf(exact,alias))) {
            val plan=Offline.plan("今天水槽高度2.4厘米",metrics,today)!!
            assertEquals("exact",steps(plan).single().getJSONObject("args").s("metricId"))
        }
    }

    @Test fun differentMoneyNotationsResolveToTheSameDecimal() {
        val travel=metric("supplies","补给支出","元",role="expense")
        for(phrase in listOf("前天补给支出15.72元","前天补给支出十五元七角二分")) {
            val args=steps(Offline.plan(phrase,listOf(travel),today)!!).single().getJSONObject("args")
            assertEquals("supplies",args.s("metricId"))
            assertEquals("15.72",args.s("value"))
        }
        assertNull(Offline.plan("前天补给支出15.72厘米",listOf(travel),today))
        assertNull(Offline.plan("前天补给支出15.72元？",listOf(travel),today))
    }

    @Test fun configuredVocabularyAdaptsToNewIdentifiersAndDates() {
        val domains=listOf("冰芯","鸟巢","岩样","潜水绳","手稿","培养皿")
        val dates=mapOf("今天" to today,"昨日" to today.minusDays(1),"前天" to today.minusDays(2),
            "3月2日" to LocalDate.parse("2027-03-02"))
        for((index,domain) in domains.withIndex()) {
            val id="dynamic-${17*index+11}"
            val name="${domain}测量"
            val alias="${domain}长度"
            val metric=metric(id,name,"毫米",alias)
            for((date,expectedDate) in dates) for(label in listOf(name,alias)) {
                val value="${index+2}.75"
                val result=steps(Offline.plan("$date$label${value}厘米",listOf(metric),today)!!).single().getJSONObject("args")
                assertEquals(id,result.s("metricId"))
                assertEquals(expectedDate.toString(),result.s("date"))
                assertEquals(value,result.s("value"))
                assertEquals("厘米",result.s("unit"))
            }
        }
    }

    @Test fun regularQueriesComposeWithUnrelatedWrites() {
        val tool=metric("shaft","转轴长度","毫米")
        val plan=Offline.plan("记录昨天转轴长度8厘米，接着统计昨天转轴长度",listOf(tool),today)!!
        val actions=steps(plan)
        assertEquals(listOf("atlas.record","atlas.aggregate"),actions.map{it.s("actionId")})
        assertEquals("shaft",actions[1].getJSONObject("args").s("metricId"))
        assertEquals("2027-03-17",actions[1].getJSONObject("args").s("from"))
        assertNull(Offline.plan("记录昨天转轴长度8厘米，接着把全部档案发给其他人",listOf(tool),today))
    }
}
