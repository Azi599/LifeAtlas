package com.lifeatlas.app

import org.json.JSONArray
import org.json.JSONObject
import java.math.BigDecimal
import java.text.Normalizer
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

object Offline {
    private data class Vocabulary(val words: List<String>, val kind: String = "", val role: String = "", val unit: String = "", val titles: Set<String> = emptySet(),
        val defaultTitle: String = "", val defaultUnit: String = "", val defaultKind: String = "CHANGE")
    private data class Quantity(val value: BigDecimal, val unit: String)
    private data class Match(val metric: JSONObject, val quantity: Quantity)

    private val vocabulary = listOf(
        Vocabulary(listOf("学习时长","学习时间","学习","学了","自习","复习"),kind="ALLOCATION",defaultTitle="学习时间",defaultUnit="分钟"),
        Vocabulary(listOf("背单词时间","背单词时长","背单词","记单词"),kind="ALLOCATION",defaultTitle="背单词时间",defaultUnit="分钟"),
        Vocabulary(listOf("睡眠时长","睡觉时间","睡眠","睡了","睡觉"),kind="ALLOCATION",defaultTitle="睡眠时长",defaultUnit="小时"),
        Vocabulary(listOf("阅读时长","阅读时间","阅读","看书","读书"),kind="ALLOCATION",defaultTitle="阅读时间",defaultUnit="分钟"),
        Vocabulary(listOf("阅读页数","看书页数","阅读","看书","读了","看了"),unit="页",defaultTitle="阅读页数",defaultUnit="页"),
        Vocabulary(listOf("运动时长","运动时间","运动","锻炼","练了"),kind="ALLOCATION",defaultTitle="运动时长",defaultUnit="分钟"),
        Vocabulary(listOf("跑步时长","跑步时间","跑步","跑了"),kind="ALLOCATION",defaultTitle="跑步时长",defaultUnit="分钟"),
        Vocabulary(listOf("饮水量","喝水量","饮水","喝水","喝了"),unit="毫升",defaultTitle="饮水量",defaultUnit="毫升"),
        Vocabulary(listOf("喝水杯数","饮水杯数","饮水","喝水","喝了"),unit="杯",defaultTitle="饮水杯数",defaultUnit="杯"),
        Vocabulary(listOf("晨间体重","体重","称重","称了","早上量的体重","量的体重"),unit="kg",
            defaultTitle="体重",defaultUnit="kg",defaultKind="SNAPSHOT"),
        Vocabulary(listOf("中午饭","午饭花了","午餐花了","晚餐花了","晚饭花了","早餐花了","午饭","午餐","晚饭","早餐","饮料","餐饮"),role="expense",
            titles=setOf("餐饮","餐饮支出","伙食费","餐费"),defaultTitle="餐饮支出",defaultUnit="元"),
        Vocabulary(listOf("交通费","交通支出","打车费"),role="expense",titles=setOf("交通支出","交通费"),defaultTitle="交通支出",defaultUnit="元"),
        Vocabulary(listOf("步数","走了","步行步数"),unit="步",defaultTitle="步数",defaultUnit="步",defaultKind="SNAPSHOT"),
        Vocabulary(listOf("兼职挣了","兼职赚了","兼职收入"),role="income",titles=setOf("兼职收入"),defaultTitle="兼职收入",defaultUnit="元"),
        Vocabulary(listOf("花了","消费了","支出了","消费","支出"),role="expense"),
        Vocabulary(listOf("收入","进账","赚了","收到了","兼职挣了","兼职赚了","挣了"),role="income")
    )
    private val pages = mapOf(
        "输入" to "workspace","首页" to "workspace","主页" to "workspace",
        "时间轴" to "history","记录" to "history","记录流" to "history","历史记录" to "history",
        "分析" to "analytics","统计" to "analytics","我的" to "settings","设置" to "settings","空间" to "spaces"
    )
    private val createNamed = Regex("^(?:请|帮我)?(?:新建|创建|建个|开个)(?:一个|个)?(?:叫|名为)(.{1,80})的(项目|目录|文件夹)$")
    private val createTyped = Regex("^(?:请|帮我)?(?:新建|创建)(?:一个|个)?(项目|目录|文件夹)[:：]?(.{1,80})$")
    private val createAfterType = Regex("^(?:请|帮我)?(?:新建|创建)(?:一个|个)?(项目|目录|文件夹)[,，]?(?:叫|名为)(.{1,80})$")
    private val createInside = Regex("^在(.+?)(?:下面|目录下|里|下)(?:再)?(?:新建|创建|建)(项目|目录|文件夹)?(.+)$")
    private val addMetric = Regex("^(?:给|在)(.+?)(?:里)?(?:添加|增加|加|新建)(?:一个|个)?([“「\"][^”」\"]+[”」\"])指标(?:[,，]单位(?:是|为)?([^,，]+))?(?:[,，](.+))?$")
    private val metricBounds=Regex("^范围([+-]?[0-9]+(?:[.][0-9]+)?)(?:到|至|~)([+-]?[0-9]+(?:[.][0-9]+)?)(?:[,，](允许小数|只能填整数))?$")
    private val metricPrecision=Regex("^(?:保留|最多)([0-8零一二两三四五六七八])位小数$")
    private val quotedTitle = Regex("[“「\"]([^”」\"]+)[”」\"]")
    private val renameFolder = Regex("^把(.+?)目录改名(?:成|为)(.+)$")
    private val moveFolder = Regex("^把(.+?)目录移动到(.+?)(?:根)?目录(?:[,，]别复制一份)?$")
    private val note = Regex("^(?:(?:记条文字|记录文字|记一条文字|记一句日记|记一条日记|在备注原样保存这句话)[:：])\\s*([\\s\\S]+)$")
    private val purchase = Regex("^(?:刚才|刚刚)?花了?(.+?(?:块钱|元|块))买了(.+)$")
    private val change = Regex("^(?:请|帮我)?把(.+?)(?:改成|改为|修改为|更正为)(.+)$")
    private val remove = Regex("^(?:请|帮我)?删除(.+)$")
    private val startTimer = Regex("^(?:请|帮我)?(?:开始(.+?)计时|给(.+?)开始计时|开始计时(.+))$")
    private val namedTimer = Regex("^(暂停|继续|结束|停止)(?:刚才的)?(.+?)(?:计时)?(?:[,，]告诉我实际有效时长)?$")
    private val timerAction = mapOf("暂停计时" to "time.pause","继续计时" to "time.resume","结束计时" to "time.stop","停止计时" to "time.stop")
    private val recordLead = Regex("^(?:请|帮我|给我)?(?:记录一下|记一笔|记一下|记下|记录|补记|记)")
    private val dateWord = "今天|今日|昨天|昨日|前天|今早|昨早|昨晚|今晚|刚才|刚刚|今儿|上周[一二三四五六日天]|(?:\\d{4}年)?\\d{1,2}月\\d{1,2}日|\\d{4}-\\d{2}-\\d{2}"
    private val dateLead = Regex("^($dateWord)")
    private val dateCorrection = Regex("^(?:我有一笔|把|请把)(.+?)(?:的)?日期(?:写错了[,，]?(?:帮我)?|直接)?(?:改成|改为|更正为)($dateWord)$")
    private val meridiem = "凌晨|早上|上午|中午|下午|傍晚|晚上"
    private val clockNumber = "[0-9零〇一二两三四五六七八九十]{1,3}"
    private val clock = Regex("($meridiem)?($clockNumber)(?:[:：]([0-5]\\d)|点(?:(半)|($clockNumber)分)?)")
    private val intervalClock = "(?:(?:$meridiem)?$clockNumber(?:[:：][0-5]\\d|点(?:半|${clockNumber}分)?))"
    private val interval = Regex("^(?:从)?($intervalClock)(?:到|至|—|~)(?:($dateWord))?($intervalClock)(.+)$")
    private val sleepRange = Regex("^($intervalClock)(?:睡到|睡至)(?:($dateWord))?($intervalClock)$")
    private val namedRange = Regex("^($dateWord)($intervalClock)开始(.+?)[,，]($dateWord)($intervalClock)结束$")
    private val listing = Regex("^把($dateWord)(?:的)?(?:所有)?(.+?)记录列出来(?:让我核对)?$")
    private val dailySpend = Regex("^(?:查查|查一下|查询)?($dateWord)([^,，?？]*?)(?:一共|总共)?(?:花了|支出)(?:多少钱|多少|合计)$|^(?:查查|查一下|查询)?($dateWord)总开销$")
    private val monthCash = Regex("^(?:汇报|统计)([0-9]{1,2})月收入[,、]支出[,、]净现金流(?:[,，]逐项说明口径)?(?:[;；]没数据不许猜)?$")
    private val dailyExpenseChart = Regex("^(?:给我|画|生成|展示)(?:一张)?(本月|这个月|上月|上个月|[0-9]{1,2}月)每天的?(?:支出|开销)(?:趋势图|折线图)(?:[,，].*(?:没记录|缺失|没有记录).*)?$")
    private val listDay = Regex("^(?:列出|查看)($dateWord)(?:的)?(?:所有)?记录$")
    private val createQuoted = Regex("^(?:新建|创建)(?:一个|个)?([“「\"][^”」\"]+[”」\"])(项目|目录|文件夹)$")
    private val localPrefix = Regex("^(?:现在断网|离线)[:：]\\s*")
    private val addChart = Regex("^在(?:首页|分析页?)新增(?:一个)?(?:显示)?(本月|上月)(.+?)的图表$")
    private val layoutChart = Regex("^把(?:首页|分析页?)?([“「\"][^”」\"]+[”」\"])(?:卡片|图表)(隐藏|移到(?:首页|分析页?)最前面)(?:[,，]数据别删)?$")
    private val styleChart = Regex("^把(?:刚才那个|刚才的)?(.+?)图表(?:从(?:折线图|柱状图|热力图))?改成(折线图|柱状图|热力图)$")
    private val openChart = Regex("^打开(?:刚才的)?(.+?)图表(?:[,，]告诉我数据来自哪个指标、哪个日期范围)?$")
    private val writeAndMeasure = Regex("^(.+?)[;；]分别记下并告诉我总占用时间$")
    private val measureExisting = Regex("^($dateWord)(?:的|那段)?(.+?)和(.+?)重叠后[,，]?总占用(?:究竟)?多少(?:分钟|时间)$")
    private val weightedMark = Regex("(\\d+)次([+-]?\\d+(?:\\.\\d+)?)分")
    private val weightedTotal = Regex("^假设有(\\d+)次")
    private val statisticLead = Regex("^(?:请|帮我)?(查看|看一下|查一下|查询|统计|汇总|查)")
    private val rangeLead = Regex("^(最近[0-9]{1,2}天|近[0-9]{1,2}天|最近七天|近七天|本月|这个月|上月|上个月|今天|昨天|前天)")
    private val drawChart = Regex("^(?:请|帮我)?(?:画|生成|展示)(?:一张|一个)?(.+?)(?:的)?(折线图|柱状图|趋势图)$")
    private val measuredBalance = Regex("^(.+?)(?:现在)?(?:的)?(?:实际余额|实测余额)(?:是|为|有)?(.+)$")
    private val transfer = Regex("^从(.+?)(?:转账|转)(.+?)(?:转到|到|至)(.+)$")
    private val balanceQuery = Regex("^(?:自|从)上一次(.+?)余额实测以来[,，]净变动多少[,，]现在推算余额多少$")
    private val number = "半|[+-]?(?:[0-9]+(?:\\.[0-9]+)?|[零〇一二两三四五六七八九十百千万]+(?:点[零〇一二两三四五六七八九0-9]+)?)"
    private val numericTail = Regex("^(?:[:=]|是|为|了|有)?($number)([^0-9零〇一二两三四五六七八九十百千万点.?？,，;；:：!！+\\-]{0,18})$")
    private val cashTail = Regex("^(?:[:=]|是|为|了)?($number)块([0-9零〇一二两三四五六七八九])(?:钱|毛|角)?$")
    private val halfHour = Regex("^(?:[:=]|是|为|了|有)?($number)(?:个)?半(?:小时|钟头)$")
    private val durationPiece = Regex("($number)(小时|分钟|秒)")

    fun plan(input: String, store: AtlasStore): JSONObject? =
        plan(input,store.metrics(),LocalDate.now(),
            records={ metric, date -> store.timeline(metric,date,date,0).take(50) },
            timers={ store.query("SELECT * FROM atlas_timers WHERE state != 'STOPPED' ORDER BY created_at DESC LIMIT 50") },
            spaces={ store.objects(all=true) })

    internal fun plan(
        input: String,
        metrics: List<JSONObject>,
        today: LocalDate,
        records: (String,String)->List<JSONObject> = { _,_ -> emptyList() },
        timers: ()->List<JSONObject> = { emptyList() },
        spaces: ()->List<JSONObject> = { emptyList() }
    ): JSONObject? {
        val text = Normalizer.normalize(input.trim(),Normalizer.Form.NFKC).trimEnd('。','!','！').trim().replaceFirst(localPrefix,"")
        if (text.isEmpty()) return null
        if (text.startsWith("/")) return legacy(text,metrics,today)
        note.matchEntire(input.trim())?.let {
            val content = it.groupValues[1].trim().let { raw ->
                if (input.startsWith("在备注原样保存")) Regex("^“([\\s\\S]+)”[。.!！]?$").matchEntire(raw)?.groupValues?.get(1) ?: raw
                else raw
            }
            return command(input,"atlas.createEvent",obj("text" to content,"date" to today.toString()))
        }
        val request = text.removeSuffix("?").replaceFirst(Regex("^我(?=今天|昨天|今早|今晚)"),"")
            .replace(Regex("(?:[,，](?:但忘了几点|没有具体开始时间|给我记上|帮我记上))$"),"")
        dailySpend.matchEntire(compact(request))?.let { match ->
            val date=match.groupValues[1].ifBlank{match.groupValues[3]}
            val title=match.groupValues[2]
            val day=parseDate(date,today)?.toString() ?: return null
            val ids=if(title.isEmpty()) emptyList() else namedMetrics(title,metrics)
                .filter{JSONObject(it.s("config")).s("role")=="expense"}.map{it.s("id")}
            if(title.isNotEmpty() && ids.isEmpty()) return null
            return command(input,"finance.cashflow",obj("from" to day,"to" to day,"metricIds" to JSONArray(ids),"onlyExpense" to true))
        }
        monthCash.matchEntire(compact(request))?.let { match ->
            val month=match.groupValues[1].toIntOrNull()?.takeIf{it in 1..12} ?: return null
            val period=java.time.YearMonth.of(today.year,month)
            return command(input,"finance.cashflow",obj("from" to period.atDay(1).toString(),"to" to period.atEndOfMonth().toString(),"includeBasis" to true))
        }
        dailyExpenseChart.matchEntire(compact(request))?.let { match ->
            val range=match.groupValues[1]
            val (from,to)=dateRange(range,today) ?: run {
                val month=range.removeSuffix("月").toIntOrNull()?.takeIf{it in 1..12} ?: return null
                val period=java.time.YearMonth.of(today.year,month)
                period.atDay(1) to period.atEndOfMonth()
            }
            return command(input,"finance.dailySpending",obj("from" to from.toString(),"to" to to.toString()))
        }
        drawChart.matchEntire(compact(request))?.let { match ->
            val (subject,type)=match.destructured
            val range=rangeLead.find(subject) ?: return question(input,"图表要查看哪段日期？")
            val (from,to)=dateRange(range.value,today) ?: return null
            val targets=namedMetrics(unquote(subject.drop(range.value.length).trimStart('的')),metrics)
            if(targets.isEmpty()) return question(input,"请选择图表使用的指标")
            return choose(input,"图表使用哪个指标？",targets.map { metric ->
                metric to command(input,"atlas.chart",obj("metricId" to metric.s("id"),"from" to from.toString(),
                    "to" to to.toString(),"chart" to if(type=="柱状图") "bar" else "line"))
            })
        }
        measuredBalance.matchEntire(compact(request))?.let { match ->
            val (rawName,amount)=match.destructured
            val name=unquote(rawName.removeSuffix("现在").removeSuffix("的"))
            val quantity=parseQuantity(amount) ?: return null
            val targets=metrics.filter { metric ->
                val config=JSONObject(metric.s("config"))
                config.s("role")=="balance" && compact(metric.s("title")) in setOf(name,name+"余额") &&
                    Units.currency(config.s("unit"))=="CNY" && acceptsScalar(config)
            }
            if(targets.isEmpty()) return question(input,"要记录哪个账户的实测余额？")
            if(quantity.unit.isNotBlank() && Units.currency(quantity.unit)!="CNY") return null
            val now=java.time.Instant.now().takeIf{it.atZone(ZoneId.systemDefault()).toLocalDate()==today}
            return choose(input,"要记录哪个账户的实测余额？",targets.map { metric ->
                metric to command(input,"atlas.record",obj("metricId" to metric.s("id"),"value" to quantity.value.toPlainString(),
                    "unit" to quantity.unit.ifBlank{"元"},"date" to today.toString(),"occurredAt" to now?.toString()))
            })
        }
        transfer.matchEntire(compact(request))?.let { match ->
            val (source,amount,destination)=match.destructured
            val quantity=parseQuantity(amount) ?: return null
            if(quantity.value.signum()<=0 || Units.currency(quantity.unit)!="CNY") return null
            val from=accountLedgers(source,metrics).singleOrNull()
            val to=accountLedgers(destination,metrics).singleOrNull()
            if(from==null || to==null) return question(input,"请在账户转账中选定转出和转入账户")
            if(from.s("id")==to.s("id")) return question(input,"转出和转入需要是不同的账户")
            val value=Units.convert(quantity.value,quantity.unit,JSONObject(from.s("config")).s("unit"))
            return command(input,"finance.transfer",obj("fromMetricId" to from.s("id"),"toMetricId" to to.s("id"),
                "amount" to value.toPlainString(),"date" to today.toString())).put("review",true)
        }
        balanceQuery.matchEntire(compact(request))?.let { match ->
            val name=unquote(match.groupValues[1])
            val anchors=metrics.filter { metric ->
                JSONObject(metric.s("config")).s("role")=="balance" && compact(metric.s("title")) in setOf(name,name+"余额")
            }
            val anchor=anchors.singleOrNull() ?: return question(input,"请选择已有的实测余额指标")
            val ledgers=accountLedgers(name,metrics)
            if(ledgers.size!=1) return question(input,"请选择与该余额关联的账户流水")
            return command(input,"finance.balance",obj("snapshotMetricId" to anchor.s("id"),
                "changeMetricIds" to JSONArray(ledgers.map{it.s("id")}),"asOf" to today.toString()))
        }
        measureExisting.matchEntire(compact(request))?.let { match ->
            val (date,first,second)=match.destructured
            val ids = listOf(first,second).map { name ->
                namedMetrics(name,metrics).singleOrNull{JSONObject(it.s("config")).s("kind")=="ALLOCATION"}?.s("id")
                    ?: return question(input,"请选择要比较的活动")
            }
            val day=parseDate(date,today)?.toString() ?: return null
            return command(input,"time.occupied",obj("metricIds" to JSONArray(ids),"from" to day,"to" to day))
        }
        if ((request.contains("不知道几点") || request.contains("没有具体开始时间")) &&
            request.contains("重叠") && request.contains("能确定")) {
            return obj("localAnswer" to "只有时长，缺少起止时间，无法确定是否重叠。")
        }
        writeAndMeasure.matchEntire(request)?.let { match ->
            val parts=match.groupValues[1].split(Regex("[,，]")).map(String::trim)
            if(parts.size !in 2..6) return null
            val date=dateLead.find(parts.first())?.value.orEmpty()
            val steps=parts.mapIndexed { index,part->
                val phrase=if(index>0 && dateLead.find(part)==null) date+part else part
                val step=plan(phrase,metrics,today)?.optJSONArray("steps")?.objects()?.singleOrNull() ?: return null
                val args=step.getJSONObject("args")
                if(step.s("actionId")!="atlas.record" || args.s("startAt").isBlank() || args.s("endAt").isBlank()) return null
                step.put("stepId","s${index+1}")
            }
            val refs=JSONArray(steps.map{step->obj("\$ref" to (step.s("stepId")+".id"))})
            return command(input,"time.occupied",JSONObject()).put("steps",JSONArray(steps).put(
                obj("stepId" to "total","actionId" to "time.occupied","args" to obj("recordIds" to refs))))
        }
        listDay.matchEntire(compact(request))?.let { match ->
            val day=parseDate(match.groupValues[1],today)?.toString() ?: return null
            return command(input,"atlas.query",obj("from" to day,"to" to day,"limit" to 100))
        }
        if (text.endsWith("?") && statisticLead.find(text)==null && !request.contains("顺便查")) return null
        val pageName = request.replaceFirst(Regex("^(?:请|帮我)?(?:打开|切换到|切到|进入|去|看看)"),"").removeSuffix("页").trim()
        pages[pageName]?.let { return command(input,"atlas.navigate",obj("page" to it)) }
        if (request.replace(",","") in setOf("帮我改一下刚才那条","刚才那条帮我改一下","改一下刚才那条","修改刚才那条")) {
            return question(input,"你想把刚才那条记录改成什么？")
        }
        val history = when {
            request.matches(Regex("^撤销(?:刚才(?:对[^,，]+的)?修改|刚才的操作)?$")) -> "undo"
            request.matches(Regex("^重做(?:刚才撤销的修改|刚才的操作)?$")) -> "redo"
            request.matches(Regex("^恢复刚才删掉的[^,，]+$")) -> "undo-delete"
            else -> null
        }
        if(history!=null) return obj("localHistory" to history,
            "expectedChange" to if(history=="undo-delete") "delete" else if(request.contains("修改")) "edit" else "",
            "targetHint" to Regex("对(.+?)的修改").find(request)?.groupValues?.get(1)
                ?.replace(Regex("(?:金额|数值|日期|时间)$"),"").orEmpty(),
            "rawInput" to input)
        if(request.contains("候选") && request.contains("别直接写")) {
            val amount=Regex("(?i)([0-9]+(?:\\.[0-9]+)?)\\s*kuai\\b").find(request)?.groupValues?.get(1)
            if(amount!=null) {
                val targets=metrics.filter { metric ->
                    val config=JSONObject(metric.s("config"))
                    config.s("role")=="expense" && Units.currency(config.s("unit"))=="CNY" && acceptsScalar(config)
                }
                val options=targets.map { metric -> obj("title" to metric.s("title"),"plan" to command(input,
                    "atlas.record",obj("metricId" to metric.s("id"),"value" to amount,"unit" to "元","date" to today.toString()))
                    .put("review",true)) }
                return question(input,"识别到 $amount 元，请选择归属",options)
            }
        }
        addChart.matchEntire(request)?.let { match ->
            val (range,name)=match.destructured
            val targets=namedMetrics(name,metrics)
            if(targets.isEmpty()) return question(input,"请选择图表使用的指标")
            val (from,to)=dateRange(range,today) ?: return null
            return choose(input,"图表使用哪个指标？",targets.map{metric->
                val title=range+metric.s("title")
                val view=command(input,"atlas.createView",obj("title" to title,"components" to JSONArray().put(obj(
                    "component" to "chart","metricId" to metric.s("id"),"chart" to "line","from" to from.toString(),"to" to to.toString()
                ))))
                view.getJSONArray("steps").put(obj("stepId" to "s2","actionId" to "atlas.layout","args" to obj("objectId" to obj("\$ref" to "s1.id"),"hidden" to false)))
                metric to view
            })
        }
        layoutChart.matchEntire(request)?.let { match ->
            val (name,action)=match.destructured
            val view=findChart(name,spaces()) ?: return question(input,"请选择要调整的分析图表")
            val hide=action=="隐藏"
            return command(input,"atlas.layout",obj("objectId" to view.s("id"),"hidden" to hide,"first" to !hide))
        }
        styleChart.matchEntire(request)?.let { match ->
            val (name,type)=match.destructured
            val view=findChart(name,spaces()) ?: return question(input,"请选择要更改的图表")
            val chart=when(type){"柱状图"->"bar";"热力图"->"heatmap";else->"line"}
            return command(input,"atlas.setChartType",obj("objectId" to view.s("id"),"chart" to chart))
        }
        openChart.matchEntire(request)?.let { match ->
            val view=findChart(match.groupValues[1],spaces()) ?: return question(input,"请选择要打开的图表")
            return command(input,"atlas.navigate",obj("page" to "spaces","objectId" to view.s("id")))
        }
        createQuoted.matchEntire(request)?.let { match ->
            val (title,type)=match.destructured
            return command(input,"atlas.createObject",obj("type" to if(type=="项目") "PROJECT" else "FOLDER","title" to unquote(title)))
        }
        createAfterType.matchEntire(request)?.let { match ->
            val (type,title) = match.destructured
            return command(input,"atlas.createObject",obj("type" to if(type=="项目") "PROJECT" else "FOLDER","title" to unquote(title)))
        }
        addMetric.matchEntire(compact(request))?.let { match ->
            val (parentName,title,unit,rule)=match.destructured
            val parent=findSpace(parentName,spaces()) ?: return question(input,"请选择要添加指标的目录或项目")
            val config=obj("kind" to "SNAPSHOT","unit" to unit,"aggregate" to "LAST_SNAPSHOT")
            when {
                rule.isBlank() || rule=="允许小数" -> Unit
                rule=="只能填非负整数" -> config.put("minimum","0").put("decimalPlaces",0)
                rule=="只能填整数" -> config.put("decimalPlaces",0)
                metricBounds.matches(rule) -> {
                    val (minimum,maximum,format)=metricBounds.matchEntire(rule)!!.destructured
                    if(minimum.toBigDecimal()>maximum.toBigDecimal()) return question(input,"最小值不能大于最大值")
                    config.put("minimum",minimum).put("maximum",maximum)
                    if(format=="只能填整数") config.put("decimalPlaces",0)
                }
                metricPrecision.matches(rule) -> {
                    val places=InputEvidence.number(metricPrecision.matchEntire(rule)!!.groupValues[1])?.toInt() ?: return null
                    config.put("decimalPlaces",places)
                }
                else -> return null
            }
            return command(input,"atlas.createObject",obj("type" to "METRIC","title" to unquote(title),
                "parentId" to parent.s("id"),"config" to config,"reuseMetric" to true))
        }
        createInside.matchEntire(request)?.let { match ->
            val (parentName,type,tail) = match.destructured
            val titleMatches = quotedTitle.findAll(tail).map { it.groupValues[1].trim() }.toList()
            val remainder = quotedTitle.replace(tail,"").trim()
            if(!remainder.matches(Regex("^(?:和|与|、|[,，])*(?:[0-9一二两三四五六七八九十]+个)?(?:子目录|目录|项目)?$"))) return null
            if(titleMatches.isEmpty() || titleMatches.any { it.isBlank() } || titleMatches.size>8) return null
            val parent = findSpace(parentName,spaces()) ?: return question(input,"请确认上级目录")
            val steps = titleMatches.mapIndexed { index,title ->
                obj("stepId" to "s${index+1}","actionId" to "atlas.createObject","args" to obj(
                    "type" to if(type=="项目") "PROJECT" else "FOLDER","title" to title,"parentId" to parent.s("id")))
            }
            return command(input,"atlas.createObject",JSONObject()).put("steps",JSONArray(steps))
        }
        renameFolder.matchEntire(request)?.let { match ->
            val (oldName,newName) = match.destructured
            val folder=findSpace(oldName,spaces()) ?: return question(input,"请确认要改名的目录")
            return command(input,"atlas.updateObject",obj("id" to folder.s("id"),"title" to unquote(newName)))
        }
        moveFolder.matchEntire(request)?.let { match ->
            val (source,destination)=match.destructured
            val all=spaces()
            val folder=findSpace(source,all) ?: return question(input,"请确认要移动的目录")
            val parent=findSpace(destination,all) ?: return question(input,"请确认目标目录")
            if(folder.s("id")==parent.s("id")) return null
            return command(input,"atlas.updateObject",obj("id" to folder.s("id"),"parentId" to parent.s("id")))
        }
        startTimer.matchEntire(request)?.let { match ->
            val name = unquote(match.groupValues.drop(1).first { it.isNotBlank() })
            val targets = namedMetrics(name,metrics).filter { JSONObject(it.s("config")).s("kind")=="ALLOCATION" }
            if (targets.isEmpty()) return null
            val active = timers().map { it.s("metric_id") }.toSet()
            val choices = targets.filter { it.s("id") !in active }
            if (choices.isEmpty()) return question(input,"这项活动已有计时")
            return choose(input,"为哪项活动计时？",choices.map { it to command(input,"time.start",obj("metricId" to it.s("id"))) })
        }
        val namedTimerMatch = if (request in timerAction) null else namedTimer.matchEntire(request)
        val timerName = namedTimerMatch?.groupValues?.get(2)?.let(::unquote).orEmpty()
        val timerCommand = timerAction[request] ?: namedTimerMatch?.let { timerAction[it.groupValues[1]+"计时"] }
        timerCommand?.let { action ->
            val targetIds = namedMetrics(timerName,metrics).map{it.s("id")}.toSet()
            val active = timers().filter { timer -> (timerName.isBlank() || timer.s("metric_id") in targetIds) && when(action) {
                "time.pause" -> timer.s("state")=="RUNNING"
                "time.resume" -> timer.s("state")=="PAUSED"
                else -> true
            } }
            val options = active.map { timer ->
                obj("title" to (metrics.firstOrNull { it.s("id")==timer.s("metric_id") }?.s("title") ?: "计时"),
                    "plan" to command(input,action,obj("timerId" to timer.s("id"))))
            }
            if(options.isEmpty()) return question(input,"当前没有可操作的计时")
            return if (options.size==1) options.single().getJSONObject("plan") else question(input,"选择计时",options)
        }
        listing.matchEntire(request)?.let { match ->
            val (date,title)=match.destructured
            val day=parseDate(date,today)?.toString() ?: return null
            return choose(input,"查哪个指标？",namedMetrics(title,metrics).map { metric ->
                metric to command(input,"atlas.query",obj("metricId" to metric.s("id"),"from" to day,"to" to day,"limit" to 100))
            })
        }
        if (request.contains("平均") && request.contains("不要记入数据库")) {
            weightedTotal.find(request)?.let { total ->
                val expected=total.groupValues[1].toLongOrNull() ?: return null
                val weights=weightedMark.findAll(request).map { match ->
                    match.groupValues[2].toBigDecimal() to match.groupValues[1].toBigDecimal()
                }.toList()
                val actual=MathEngine.sum(weights.map{it.second})
                if(weights.isEmpty() || actual.compareTo(expected.toBigDecimal())!=0 || expected==0L) {
                    return question(input,"次数合计与题目不一致，请核对")
                }
                val result=MathEngine.weighted(weights)?.stripTrailingZeros()?.toPlainString() ?: return null
                return obj("localAnswer" to "平均分 $result 分（共 $expected 次）")
            }
        }
        namedRange.matchEntire(compact(request))?.let { match ->
            val (day,from,title,endDay,to)=match.destructured
            return intervalPlan(input,metrics,today,parseDate(day,today) ?: return null,"",from,endDay,to,title)
        }
        dateCorrection.matchEntire(compact(request))?.let { match ->
            val (rawName,date)=match.destructured
            val day=parseDate(date,today) ?: return null
            val targets=namedMetrics(unquote(rawName.removeSuffix("的")),metrics)
            val options=targets.flatMap { metric ->
                records(metric.s("id"),"").filter { it.s("start_at").isBlank() }.mapNotNull { row ->
                    val dateBefore=row.s("local_date")
                    val args=obj("id" to row.s("id"),"date" to day.toString())
                    val occurred=row.s("occurred_at")
                    if(occurred.isNotBlank()) {
                        val zone=ZoneId.of(row.s("zone",ZoneId.systemDefault().id))
                        val clock=java.time.Instant.parse(occurred).atZone(zone).toLocalTime()
                        args.put("occurredAt",instant(day,clock,zone)?.toString() ?: return@mapNotNull null)
                    }
                    obj("title" to "${metric.s("title")} · $dateBefore · ${row.s("value","未填写")}",
                        "plan" to command(input,"atlas.editRecord",args).put("review",true))
                }
            }
            if(options.isEmpty()) return question(input,"请先在时间轴选中要改日期的记录")
            return if(options.size==1) options.single().getJSONObject("plan") else question(input,"选择要改日期的记录",options)
        }
        val actions = request.split(Regex("[,，;；、]")).map(String::trim)
        if (actions.size in 2..6 && actions.none { it.isBlank() }) {
            val sharedDate = dateLead.find(actions.first().replaceFirst(recordLead,""))?.value
            val steps=JSONArray()
            val available=metrics.toMutableList()
            val generated=mutableMapOf<String,String>()
            actions.forEachIndexed { index, part ->
                val phrase = part.replaceFirst(Regex("^(?:另外|还|再告诉我|再(?=列出|查看)|顺便(?=查|统计))"),"").trim()
                val evening = phrase.startsWith("晚上又")
                val body = if(evening) phrase.removePrefix("晚上又") else phrase
                val addedDate = if (index>0 && sharedDate!=null && (evening || part.startsWith("另外") || part.startsWith("还")) &&
                    dateLead.find(body.replaceFirst(recordLead,""))==null) sharedDate else ""
                val subplan=plan(addedDate+body,available,today) ?: return null
                val substeps=subplan.optJSONArray("steps")?.objects() ?: return null
                if(subplan.optBoolean("review") || substeps.any{
                    it.s("actionId") !in setOf("atlas.record","atlas.createObject","atlas.query","finance.cashflow")
                }) return null
                val prefix="part${index+1}_"
                substeps.forEach { step ->
                    val id=prefix+step.s("stepId")
                    val args=step.getJSONObject("args")
                    steps.put(step.copyJson().put("stepId",id).put("args",bindCompound(args,prefix,generated)))
                    if(step.s("actionId")=="atlas.createObject" && args.s("type")=="METRIC") {
                        val temporary=uid()
                        generated[temporary]="$id.id"
                        available.add(obj("id" to temporary,"title" to args.s("title"),"config" to args.getJSONObject("config").toString()))
                    }
                }
            }
            if(steps.length() !in 1..60) return null
            return command(input,"atlas.record",JSONObject()).put("steps",steps)
        }
        val named = createNamed.matchEntire(request)
        val typed = createTyped.matchEntire(request)
        if (named!=null || typed!=null) {
            val title = (named?.groupValues?.get(1) ?: typed!!.groupValues[2]).trim().trim('"','“','”','「','」')
            val type = named?.groupValues?.get(2) ?: typed!!.groupValues[1]
            if (title.isBlank()) return null
            return command(input,"atlas.createObject",obj("type" to if(type=="项目") "PROJECT" else "FOLDER","title" to title))
        }
        val editing = change.matchEntire(request)
        val deleting = remove.matchEntire(request)
        if (editing!=null || deleting!=null) {
            val rawSubject = (editing ?: deleting!!).groupValues[1]
            val recent = rawSubject.startsWith("最近一条")
            val subject = rawSubject.removePrefix("最近一条")
            val matchDate = dateLead.find(subject)?.value
            val day = matchDate?.let { parseDate(it,today)?.toString() ?: return null }.orEmpty()
            val name = unquote(subject.drop(matchDate?.length ?: 0).removePrefix("的").removeSuffix("记录"))
            val reference = vocabulary.flatMap { it.words }.sortedByDescending { it.length }.firstNotNullOfOrNull { word ->
                if(name.startsWith(word)) parseQuantity(compact(name.drop(word.length)))?.let{word to it} else null
            }
            val targets = namedMetrics(reference?.first ?: name,metrics)
            if (targets.isEmpty()) return null
            if (!recent && matchDate==null) return question(input,"要${if(editing!=null) "修改" else "删除"}哪一天的记录？")
            val quantity = if (editing==null) null else parseQuantity(compact(editing.groupValues[2])) ?: return null
            val options = targets.flatMap { metric ->
                val config = JSONObject(metric.s("config"))
                if (quantity!=null && config.has("expression")) return@flatMap emptyList()
                val unit = config.s("unit")
                val value = quantity?.let {
                    runCatching { Units.convert(it.value,it.unit.ifBlank{unit},unit) }.getOrNull() ?: return@flatMap emptyList()
                }
                val rows = records(metric.s("id"),day).filter { row ->
                    if(reference==null) true else {
                        val before = reference.second
                        val oldValue = row.s("value").toBigDecimalOrNull()
                        val wanted = runCatching { Units.convert(before.value,before.unit.ifBlank{unit},unit) }.getOrNull()
                        wanted!=null && oldValue!=null && if(config.s("role")=="expense")
                            oldValue.abs().compareTo(wanted.abs())==0 else oldValue.compareTo(wanted)==0
                    }
                }.let { if(recent) it.take(1) else it }
                rows.map { row ->
                    val args = obj("id" to row.s("id"))
                    if (value!=null) args.put("value",value.toPlainString())
                    val action = if(editing!=null) "atlas.editRecord" else "atlas.deleteRecord"
                    obj("title" to listOf(metric.s("title"),row.s("local_date"),row.s("value","未填写"),
                        row.s("occurred_at").ifBlank{row.s("start_at")}.takeIf{it.isNotBlank()}?.let{formatInstant(it,row.s("zone"))}.orEmpty()
                    ).filter{it.isNotBlank()}.joinToString(" · "), "plan" to command(input,action,args).put("review",true))
                }
            }
            if (options.isEmpty()) return question(input,"没有找到符合条件的记录")
            if (options.size==1) return options.single().getJSONObject("plan")
            return question(input,"选择要${if(editing!=null) "修改" else "删除"}的记录",options)
        }
        statisticLead.find(request)?.let { lead ->
            val body = request.drop(lead.value.length).trim()
            val range = rangeLead.find(body)
            val (from,to) = dateRange(range?.value.orEmpty(),today) ?: return null
            val subject = body.drop(range?.value?.length ?: 0).removePrefix("的").trim()
            val suffix = listOf("的平均值","平均值","的总计","总计","的合计","合计","的统计","统计","的记录","记录").firstOrNull { subject.endsWith(it) }
            val name = if (suffix==null) subject else subject.removeSuffix(suffix)
            val matching = namedMetrics(name,metrics)
            if (matching.isEmpty()) return null
            val method = when {
                suffix?.contains("平均")==true -> "MEAN"
                suffix?.contains("总计")==true || suffix?.contains("合计")==true -> "SUM"
                else -> null
            }
            val aggregate = method!=null || lead.groupValues[1] in setOf("统计","汇总") || suffix?.contains("统计")==true
            return choose(input,"查哪个指标？",matching.map { metric ->
                metric to command(input,if(aggregate) "atlas.aggregate" else "atlas.query",
                    obj("metricId" to metric.s("id"),"from" to from.toString(),"to" to to.toString()).apply { method?.let { put("method",it) } })
            })
        }

        var body = compact(request)
        body = body.replaceFirst(recordLead,"")
        val date = dateLead.find(body)
        val day = date?.value?.let { parseDate(it,today) ?: return null } ?: today
        body = body.drop(date?.value?.length ?: 0).replaceFirst(recordLead,"").trimStart(':',',','，')
        purchase.matchEntire(body)?.let { match -> body = "花了"+match.groupValues[1] }
        val dayPart = if(date?.value in setOf("昨晚","今晚")) "晚上" else ""
        sleepRange.matchEntire(body)?.let { match ->
            val (from,endDay,to)=match.destructured
            return intervalPlan(input,metrics,today,day,dayPart,from,endDay,to,"睡眠")
        }
        interval.matchEntire(body)?.let { match ->
            val (from,endDay,to,title)=match.destructured
            return intervalPlan(input,metrics,today,day,dayPart,from,endDay,to,title)
        }
        val startClock = clock.find(body)?.takeIf { it.range.first==0 }
        val occurredAt = startClock?.let {
            val time = localClock(it.value,dayPart) ?: return null
            instant(day,time,ZoneId.systemDefault()) ?: return null
        }
        if (startClock!=null) body = body.drop(startClock.value.length)
        val exact = metrics.mapNotNull { metric ->
            val title = compact(metric.s("title"))
            if (title.isEmpty() || !body.startsWith(title)) return@mapNotNull null
            val q = parseQuantity(body.drop(title.length)) ?: return@mapNotNull null
            title.length to Match(metric,q)
        }
        val found = if (exact.isNotEmpty()) exact.filter { it.first==exact.maxOf { pair -> pair.first } }.map { it.second }
            else vocabulary.flatMap { words ->
                val word = words.words.filter { body.startsWith(it) }.maxByOrNull { it.length } ?: return@flatMap emptyList()
                val q = parseQuantity(body.drop(word.length)) ?: return@flatMap emptyList()
                val targets = metrics.filter { matches(it,words) }
                targets.map { Match(it,q) }
            }.distinctBy { it.metric.s("id") }
        val options = found.mapNotNull { (metric,q) ->
            val config = JSONObject(metric.s("config"))
            val unit = config.s("unit")
            if (q.unit.isNotBlank() && runCatching { Units.convert(q.value,q.unit,unit) }.isFailure) return@mapNotNull null
            if (config.s("kind")=="ALLOCATION" && q.value.signum()<0) return@mapNotNull null
            if (!acceptsScalar(config)) return@mapNotNull null
            metric to command(input,"atlas.record",obj("metricId" to metric.s("id"),"value" to q.value.toPlainString(),
                "unit" to q.unit.ifBlank{unit},"date" to day.toString(),"occurredAt" to occurredAt?.toString()))
        }
        return choose(input,"记到哪个指标？",options) ?: if(found.isEmpty()) {
            firstRecord(input,body,day,occurredAt,metrics)
        } else null
    }

    private fun firstRecord(input:String,body:String,day:LocalDate,occurredAt:java.time.Instant?,metrics:List<JSONObject>):JSONObject? {
        val match=vocabulary.mapNotNull{words->
            if(words.defaultTitle.isBlank()) return@mapNotNull null
            val lead=words.words.filter(body::startsWith).maxByOrNull{it.length} ?: return@mapNotNull null
            val quantity=parseQuantity(body.drop(lead.length)) ?: return@mapNotNull null
            if(quantity.unit.isBlank() || runCatching{Units.convert(quantity.value,quantity.unit,words.defaultUnit)}.isFailure) return@mapNotNull null
            if(words.kind=="ALLOCATION" && quantity.value.signum()<0) return@mapNotNull null
            if(metrics.any{matches(it,words) || it.s("title")==words.defaultTitle}) return@mapNotNull null
            words to quantity
        }.singleOrNull() ?: return null
        val (words,quantity)=match
        val kind=words.kind.ifBlank{words.defaultKind}
        val plan=command(input,"atlas.createObject",obj("type" to "METRIC","title" to words.defaultTitle,"reuseMetric" to true,
            "config" to obj("kind" to kind,"unit" to words.defaultUnit,"role" to words.role,
                "aggregate" to if(kind=="SNAPSHOT") "LAST_SNAPSHOT" else "SUM")))
        plan.getJSONArray("steps").put(obj("stepId" to "s2","actionId" to "atlas.record","args" to obj(
            "metricId" to obj("\$ref" to "s1.id"),"value" to quantity.value.toPlainString(),"unit" to quantity.unit,
            "date" to day.toString(),"occurredAt" to occurredAt?.toString())))
        return plan
    }

    private fun bindCompound(value:Any,prefix:String,generated:Map<String,String>):Any = when(value) {
        is JSONObject -> if(value.has("\$ref")) obj("\$ref" to (prefix+value.s("\$ref"))) else JSONObject().apply{
            value.keys().forEach{key->put(key,bindCompound(value.get(key),prefix,generated))}
        }
        is JSONArray -> JSONArray().apply{for(index in 0 until value.length()) put(bindCompound(value.get(index),prefix,generated))}
        is String -> generated[value]?.let{obj("\$ref" to it)} ?: value
        else -> value
    }

    private fun choose(input:String,question:String,options:List<Pair<JSONObject,JSONObject>>):JSONObject? {
        if (options.isEmpty()) return null
        if (options.size==1) return options.single().second
        return question(input,question,options.map { (metric,plan) ->
            val duplicate = options.count { it.first.s("title")==metric.s("title") }>1
            obj("title" to (metric.s("title")+if(duplicate) " · "+metric.s("id").take(6) else ""),"plan" to plan)
        })
    }

    private fun question(input:String,title:String,options:List<JSONObject> = emptyList()) =
        obj("question" to title,"rawInput" to input,"options" to JSONArray(options.take(8)))

    private fun unquote(value:String) = value.trim().trim('"','“','”','「','」')
    private fun findSpace(title:String,objects:List<JSONObject>):JSONObject? =
        objects.singleOrNull { it.s("type") in setOf("WORKSPACE","FOLDER","PROJECT") && it.s("title")==unquote(title) }

    private fun findChart(title:String,objects:List<JSONObject>):JSONObject? {
        val name=unquote(title)
        if(name.isBlank()) return null
        val charts=objects.filter{row->
            if(row.s("type")!="VIEW") return@filter false
            val config=JSONObject(row.s("config","{}"))
            config.s("component")=="chart" && config.s("chart")!="list" ||
                config.optJSONArray("components")?.objects()?.any{it.s("component")=="chart"}==true
        }
        return charts.singleOrNull{it.s("title")==name} ?: charts.singleOrNull{it.s("title").contains(name)}
    }

    private fun namedMetrics(title:String,metrics:List<JSONObject>):List<JSONObject> {
        val name = compact(title)
        val exact = metrics.filter { compact(it.s("title"))==name }
        return exact.ifEmpty { metrics.filter { metric -> vocabulary.any { name in it.words && matches(metric,it) } } }
    }

    private fun accountLedgers(title:String,metrics:List<JSONObject>):List<JSONObject> {
        val name=unquote(title).removeSuffix("余额")
        if(name.isBlank()) return emptyList()
        val names=setOf(name,name+"流水",name+"账户流水",name+"变动",name+"收支")
        return metrics.filter { metric ->
            val config=JSONObject(metric.s("config"))
            config.s("role")=="accountChange" && Units.currency(config.s("unit"))=="CNY" &&
                compact(metric.s("title")) in names
        }
    }

    private fun command(input:String,id:String,args:JSONObject) =
        obj("title" to input,"rawInput" to input,"requestId" to uid(),"local" to true,
            "steps" to JSONArray().put(obj("stepId" to "s1","actionId" to id,"args" to args)))

    private fun matches(metric:JSONObject,words:Vocabulary):Boolean {
        val config = JSONObject(metric.s("config"))
        if (words.role.isNotBlank()) return config.s("role")==words.role &&
            (words.titles.isEmpty() || compact(metric.s("title")) in words.titles)
        if (compact(metric.s("title")) !in words.words) return false
        if (words.kind.isNotBlank() && config.s("kind")!=words.kind) return false
        val unit = config.s("unit")
        return words.unit.isBlank() || unit==words.unit ||
            (Units.dimension(words.unit)!=null && Units.dimension(unit)==Units.dimension(words.unit))
    }

    private fun intervalPlan(input:String,metrics:List<JSONObject>,today:LocalDate,day:LocalDate,
        dayPart:String,fromText:String,endDayText:String,toText:String,title:String):JSONObject? {
        val startTime = localClock(fromText,dayPart) ?: return null
        val endTime = localClock(toText,if(endDayText in setOf("昨晚","今晚")) "晚上" else "") ?: return null
        val endDay = if (endDayText.isNotEmpty()) parseDate(endDayText,today) ?: return null
            else if (endTime<startTime) day.plusDays(1) else day
        val zone = ZoneId.systemDefault()
        val startAt = instant(day,startTime,zone) ?: return null
        val endAt = instant(endDay,endTime,zone) ?: return null
        if (endAt<startAt) return null
        val name = unquote(title)
        val targets = namedMetrics(name,metrics).ifEmpty {
            namedMetrics(name.replaceFirst(Regex("^(?:复习了|学习了|读了|练习了|做了)"),""),metrics)
        }.filter { metric ->
            val config = JSONObject(metric.s("config"))
            config.s("kind")=="ALLOCATION" && acceptsScalar(config)
        }
        return choose(input,"记到哪个指标？",targets.map { metric ->
            metric to command(input,"atlas.record",obj("metricId" to metric.s("id"),
                "date" to day.toString(),"zone" to zone.id,"startAt" to startAt.toString(),"endAt" to endAt.toString()))
        })
    }

    private fun acceptsScalar(config:JSONObject):Boolean =
        !config.has("expression") && config.optJSONArray("fields")?.objects()?.none{it.optBoolean("required")}!=false

    private fun localClock(text:String,defaultPart:String):LocalTime? {
        val match = clock.matchEntire(text) ?: return null
        val (specifiedPart,hourText,colonMinutes,half,spokenMinutes) = match.destructured
        val part = specifiedPart.ifBlank{defaultPart}
        return runCatching {
            var hour = InputEvidence.number(hourText)?.intValueExact() ?: return null
            if (part in setOf("下午","傍晚","晚上") && hour in 1..11) hour += 12
            if (part=="凌晨" && hour==12) hour = 0
            val minute = when {
                half.isNotBlank() -> 30
                colonMinutes.isNotBlank() -> colonMinutes.toInt()
                spokenMinutes.isNotBlank() -> InputEvidence.number(spokenMinutes)?.intValueExact() ?: return null
                else -> 0
            }
            LocalTime.of(hour,minute)
        }.getOrNull()
    }

    private fun instant(date:LocalDate,time:LocalTime,zone:ZoneId):java.time.Instant? {
        val local = date.atTime(time)
        return zone.rules.getValidOffsets(local).singleOrNull()?.let(local::toInstant)
    }

    private fun parseQuantity(text:String):Quantity? {
        val body=text.removeSuffix("整")
        halfHour.matchEntire(body)?.let {
            val value = InputEvidence.number(it.groupValues[1]) ?: return null
            if(value.signum()<0) return null
            return Quantity(value+BigDecimal("0.5"),"小时")
        }
        val duration = body.trimStart(':','=').removePrefix("是").removePrefix("为").removePrefix("了")
        val pieces = durationPiece.findAll(duration).toList()
        if (pieces.size in 2..3 && pieces.joinToString(""){it.value}==duration && pieces.map{it.groupValues[2]}.distinct().size==pieces.size) {
            val seconds = pieces.map {
                val value = if(it.groupValues[1]=="半") BigDecimal("0.5") else InputEvidence.number(it.groupValues[1]) ?: return null
                if(value.signum()<0) return null
                Units.convert(value,it.groupValues[2],"秒")
            }
            return Quantity(MathEngine.sum(seconds),"秒")
        }
        cashTail.matchEntire(body)?.let {
            val whole = InputEvidence.number(it.groupValues[1]) ?: return null
            val fraction = InputEvidence.number(it.groupValues[2])?.movePointLeft(1) ?: return null
            return Quantity(if(whole.signum()<0) whole-fraction else whole+fraction,"元")
        }
        val (digits,unit) = numericTail.matchEntire(body)?.destructured ?: return null
        val value = if(digits=="半") BigDecimal("0.5") else InputEvidence.number(digits) ?: return null
        return Quantity(value,when(unit){"个小时","钟头"->"小时";"杯水"->"杯";"页书"->"页";else->unit})
    }

    internal fun explicitlyOffline(input:String):Boolean = localPrefix.containsMatchIn(input.trim())

    private fun compact(text:String) = text.filterNot(Char::isWhitespace)
    private fun parseDate(value:String,today:LocalDate):LocalDate? = when(value) {
        "今天","今日","今早","今晚","刚才","刚刚","今儿" -> today
        "昨天","昨日","昨早","昨晚" -> today.minusDays(1)
        "前天" -> today.minusDays(2)
        else -> {
            if(value.startsWith("上周")) {
                val day=when(value.last()){'日','天'->7;else->InputEvidence.number(value.takeLast(1))?.toInt()}
                day?.let{today.minusDays(today.dayOfWeek.value.toLong()-1+7).plusDays(it.toLong()-1)}
            } else {
                val date=Regex("^(?:(\\d{4})年)?(\\d{1,2})月(\\d{1,2})日$").matchEntire(value)
                runCatching {
                    if(date==null) LocalDate.parse(value)
                    else LocalDate.of(date.groupValues[1].toIntOrNull() ?: today.year,
                        date.groupValues[2].toInt(),date.groupValues[3].toInt())
                }.getOrNull()
            }
        }
    }
    private fun dateRange(value:String,today:LocalDate):Pair<LocalDate,LocalDate>? = when(value) {
        "" -> LocalDate.of(1,1,1) to today
        "本月","这个月" -> today.withDayOfMonth(1) to today
        "上月","上个月" -> today.minusMonths(1).withDayOfMonth(1) to today.withDayOfMonth(1).minusDays(1)
        "近七天","最近七天" -> today.minusDays(6) to today
        else -> if(value.startsWith("近") || value.startsWith("最近")) {
            val days=value.filter(Char::isDigit).toLongOrNull()?.takeIf{it in 1..90} 
            days?.let { today.minusDays(it-1) to today }
        } else parseDate(value,today)?.let { it to it }
    }

    private fun legacy(input:String,metrics:List<JSONObject>,today:LocalDate):JSONObject? {
        val tokens=input.split(Regex("\\s+"))
        return when(tokens.first()) {
            "/folder","/project" -> {
                require(tokens.size>=2)
                command(input,"atlas.createObject",obj("type" to if(tokens.first()=="/folder") "FOLDER" else "PROJECT","title" to tokens.drop(1).joinToString(" ")))
            }
            "/record","/query" -> {
                val name=tokens.getOrNull(1).orEmpty()
                val metric=metrics.singleOrNull { it.s("title")==name } ?: error("请使用唯一的指标名称")
                val args=obj("metricId" to metric.s("id"))
                if (tokens.first()=="/query") command(input,"atlas.query",args)
                else command(input,"atlas.record",args.put("value",tokens.getOrNull(2) ?: error("请填写数值"))
                    .put("date",tokens.getOrElse(3){today.toString()}))
            }
            "/event" -> command(input,"atlas.createEvent",obj("text" to tokens.drop(1).joinToString(" ")))
            else -> null
        }
    }
}
