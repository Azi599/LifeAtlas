package com.lifeatlas.app

import org.json.JSONArray
import org.json.JSONObject
import java.math.BigDecimal
import java.text.Normalizer
import java.time.LocalDate

object Offline {
    private data class Vocabulary(val words: List<String>, val kind: String = "", val role: String = "", val unit: String = "")
    private data class Quantity(val value: BigDecimal, val unit: String)
    private data class Match(val metric: JSONObject, val quantity: Quantity)

    private val vocabulary = listOf(
        Vocabulary(listOf("学习时长","学习时间","学习","学了","自习","复习"),kind="ALLOCATION"),
        Vocabulary(listOf("睡眠时长","睡觉时间","睡眠","睡了","睡觉"),kind="ALLOCATION"),
        Vocabulary(listOf("阅读时长","阅读时间","阅读","看书","读书"),kind="ALLOCATION"),
        Vocabulary(listOf("阅读页数","看书页数","阅读","看书","读了","看了"),unit="页"),
        Vocabulary(listOf("运动时长","运动时间","运动","锻炼","练了"),kind="ALLOCATION"),
        Vocabulary(listOf("跑步时长","跑步时间","跑步","跑了"),kind="ALLOCATION"),
        Vocabulary(listOf("饮水量","喝水量","饮水","喝水","喝了"),unit="毫升"),
        Vocabulary(listOf("晨间体重","体重","称重","称了"),unit="kg"),
        Vocabulary(listOf("午餐花了","晚餐花了","晚饭花了","早餐花了","花了","消费了","支出了","消费","支出"),role="expense"),
        Vocabulary(listOf("收入","进账","赚了","收到了"),role="income")
    )
    private val pages = mapOf(
        "输入" to "workspace","首页" to "workspace","主页" to "workspace",
        "时间轴" to "history","记录" to "history","记录流" to "history","历史记录" to "history",
        "分析" to "analytics","统计" to "analytics","我的" to "settings","设置" to "settings","空间" to "spaces"
    )
    private val createNamed = Regex("^(?:请|帮我)?(?:新建|创建|建个|开个)(?:一个|个)?(?:叫|名为)(.{1,80})的(项目|目录|文件夹)$")
    private val createTyped = Regex("^(?:请|帮我)?(?:新建|创建)(?:一个|个)?(项目|目录|文件夹)[:：]?(.{1,80})$")
    private val change = Regex("^(?:请|帮我)?把(.+?)(?:改成|改为|修改为|更正为)(.+)$")
    private val remove = Regex("^(?:请|帮我)?删除(.+)$")
    private val startTimer = Regex("^(?:请|帮我)?(?:开始(.+?)计时|给(.+?)开始计时)$")
    private val timerAction = mapOf("暂停计时" to "time.pause","继续计时" to "time.resume","结束计时" to "time.stop","停止计时" to "time.stop")
    private val recordLead = Regex("^(?:请|帮我|给我)?(?:记录一下|记一笔|记一下|记下|记录|补记)")
    private val dateLead = Regex("^(今天|今日|昨天|昨日|前天|今早|昨早|昨晚|今晚|刚才|刚刚|\\d{4}-\\d{2}-\\d{2})")
    private val statisticLead = Regex("^(?:请|帮我)?(查看|看一下|查一下|查询|统计|汇总|查)")
    private val rangeLead = Regex("^(最近[0-9]{1,2}天|近[0-9]{1,2}天|最近七天|近七天|本月|这个月|上月|上个月|今天|昨天|前天)")
    private val number = "半|[+-]?(?:[0-9]+(?:\\.[0-9]+)?|[零〇一二两三四五六七八九十百千万]+(?:点[零〇一二两三四五六七八九0-9]+)?)"
    private val numericTail = Regex("^(?:[:=]|是|为|了|有)?($number)([^0-9零〇一二两三四五六七八九十百千万点.?？,，;；:：!！+\\-]{0,18})$")
    private val cashTail = Regex("^(?:[:=]|是|为|了)?($number)块([0-9零〇一二两三四五六七八九])(?:钱)?$")

    fun plan(input: String, store: AtlasStore): JSONObject? =
        plan(input,store.metrics(),LocalDate.now(),
            records={ metric, date -> store.timeline(metric,date,date,0).take(50) },
            timers={ store.query("SELECT * FROM atlas_timers WHERE state != 'STOPPED' ORDER BY created_at DESC LIMIT 50") })

    internal fun plan(
        input: String,
        metrics: List<JSONObject>,
        today: LocalDate,
        records: (String,String)->List<JSONObject> = { _,_ -> emptyList() },
        timers: ()->List<JSONObject> = { emptyList() }
    ): JSONObject? {
        val text = Normalizer.normalize(input.trim(),Normalizer.Form.NFKC).trimEnd('。','!','！').trim()
        if (text.isEmpty()) return null
        if (text.startsWith("/")) return legacy(text,metrics,today)
        if (text.endsWith("?") && statisticLead.find(text)==null) return null
        val request = text.removeSuffix("?")
        val pageName = request.replaceFirst(Regex("^(?:请|帮我)?(?:打开|切换到|切到|进入|去|看看)"),"").removeSuffix("页").trim()
        pages[pageName]?.let { return command(input,"atlas.navigate",obj("page" to it)) }
        Regex("^(?:记条文字|记录文字|记一条文字)[:：]\\s*(.+)$").matchEntire(input.trim())?.let {
            return command(input,"atlas.createEvent",obj("text" to it.groupValues[1],"date" to today.toString()))
        }
        if (request.replace(",","") in setOf("帮我改一下刚才那条","刚才那条帮我改一下","改一下刚才那条","修改刚才那条")) {
            return question(input,"你想把刚才那条记录改成什么？")
        }
        val actions = request.split(Regex("[,，;；]")).map(String::trim)
        if (actions.size in 2..6 && actions.none { it.isBlank() }) {
            val sharedDate = dateLead.find(actions.first().replaceFirst(recordLead,""))?.value
            val steps = actions.mapIndexed { index, part ->
                val phrase = part.removePrefix("另外").removePrefix("还").trim()
                val addedDate = if (index>0 && sharedDate!=null && (part.startsWith("另外") || part.startsWith("还")) &&
                    dateLead.find(phrase.replaceFirst(recordLead,""))==null) sharedDate else ""
                val step = plan(addedDate+phrase,metrics,today)?.optJSONArray("steps")?.optJSONObject(0) ?: return null
                if (step.s("actionId")!="atlas.record") return null
                step.put("stepId","s${index+1}")
            }
            return command(input,"atlas.record",JSONObject()).put("steps",JSONArray(steps))
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
            if (!recent && matchDate==null) return question(input,"要${if(editing!=null) "修改" else "删除"}哪一天的记录？")
            val day = matchDate?.let { parseDate(it,today)?.toString() ?: return null }.orEmpty()
            val name = subject.drop(matchDate?.length ?: 0).removePrefix("的").removeSuffix("记录")
            val targets = namedMetrics(name,metrics)
            if (targets.isEmpty()) return null
            val quantity = if (editing==null) null else parseQuantity(compact(editing.groupValues[2])) ?: return null
            val options = targets.flatMap { metric ->
                val config = JSONObject(metric.s("config"))
                if (quantity!=null && config.has("expression")) return@flatMap emptyList()
                val unit = config.s("unit")
                val value = quantity?.let {
                    runCatching { Units.convert(it.value,it.unit.ifBlank{unit},unit) }.getOrNull() ?: return@flatMap emptyList()
                }
                val rows = records(metric.s("id"),day).let { if(recent) it.take(1) else it }
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
        startTimer.matchEntire(request)?.let { match ->
            val name = match.groupValues[1].ifBlank { match.groupValues[2] }
            val targets = namedMetrics(name,metrics).filter { JSONObject(it.s("config")).s("kind")=="ALLOCATION" }
            if (targets.isEmpty()) return null
            val active = timers().map { it.s("metric_id") }.toSet()
            val choices = targets.filter { it.s("id") !in active }
            if (choices.isEmpty()) return question(input,"这项活动已有计时")
            return choose(input,"为哪项活动计时？",choices.map { it to command(input,"time.start",obj("metricId" to it.s("id"))) })
        }
        timerAction[request]?.let { action ->
            val active = timers().filter { timer -> when(action) {
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
                metrics.filter { matches(it,words) }.map { Match(it,q) }
            }.distinctBy { it.metric.s("id") }
        val options = found.mapNotNull { (metric,q) ->
            val config = JSONObject(metric.s("config"))
            val unit = config.s("unit")
            if (q.unit.isNotBlank() && runCatching { Units.convert(q.value,q.unit,unit) }.isFailure) return@mapNotNull null
            if (config.s("kind")=="ALLOCATION" && q.value.signum()<0) return@mapNotNull null
            val fields = config.optJSONArray("fields")
            if (config.has("expression") || fields?.objects()?.any { it.optBoolean("required") }==true) return@mapNotNull null
            metric to command(input,"atlas.record",obj("metricId" to metric.s("id"),"value" to q.value.toPlainString(),
                "unit" to q.unit.ifBlank{unit},"date" to day.toString()))
        }
        return choose(input,"记到哪个指标？",options)
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

    private fun namedMetrics(title:String,metrics:List<JSONObject>):List<JSONObject> {
        val name = compact(title)
        val exact = metrics.filter { compact(it.s("title"))==name }
        return exact.ifEmpty { metrics.filter { metric -> vocabulary.any { name in it.words && matches(metric,it) } } }
    }

    private fun command(input:String,id:String,args:JSONObject) =
        obj("title" to input,"rawInput" to input,"requestId" to uid(),"local" to true,
            "steps" to JSONArray().put(obj("stepId" to "s1","actionId" to id,"args" to args)))

    private fun matches(metric:JSONObject,words:Vocabulary):Boolean {
        val config = JSONObject(metric.s("config"))
        if (words.role.isNotBlank()) return config.s("role")==words.role
        if (compact(metric.s("title")) !in words.words) return false
        if (words.kind.isNotBlank() && config.s("kind")!=words.kind) return false
        val unit = config.s("unit")
        return words.unit.isBlank() || unit==words.unit ||
            (Units.dimension(words.unit)!=null && Units.dimension(unit)==Units.dimension(words.unit))
    }

    private fun parseQuantity(text:String):Quantity? {
        val body=text.removeSuffix("整")
        cashTail.matchEntire(body)?.let {
            val whole = InputEvidence.number(it.groupValues[1]) ?: return null
            val fraction = InputEvidence.number(it.groupValues[2])?.movePointLeft(1) ?: return null
            return Quantity(if(whole.signum()<0) whole-fraction else whole+fraction,"元")
        }
        val (digits,unit) = numericTail.matchEntire(body)?.destructured ?: return null
        val value = if(digits=="半") BigDecimal("0.5") else InputEvidence.number(digits) ?: return null
        return Quantity(value,when(unit){"个小时","钟头"->"小时";else->unit})
    }

    private fun compact(text:String) = text.filterNot(Char::isWhitespace)
    private fun parseDate(value:String,today:LocalDate):LocalDate? = when(value) {
        "今天","今日","今早","今晚","刚才","刚刚" -> today
        "昨天","昨日","昨早","昨晚" -> today.minusDays(1)
        "前天" -> today.minusDays(2)
        else -> runCatching { LocalDate.parse(value) }.getOrNull()
    }
    private fun dateRange(value:String,today:LocalDate):Pair<LocalDate,LocalDate>? = when(value) {
        "" -> LocalDate.of(1,1,1) to today
        "本月","这个月" -> today.withDayOfMonth(1) to today
        "上月","上个月" -> today.minusMonths(1).withDayOfMonth(1) to today.withDayOfMonth(1).minusDays(1)
        "近七天","最近七天" -> today.minusDays(6) to today
        else -> if(value.startsWith("近")) {
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
