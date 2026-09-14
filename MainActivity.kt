package com.example.goldnewsai

import android.app.Activity
import android.graphics.*
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

private data class NewsItem(val title: String, val url: String)
private data class GoldMarket(
    val spotUsdOz: Double,
    val change1hPct: Double?,
    val change1dPct: Double?,
    val change1wPct: Double?,
    val updatedAt: String
)
private data class Analysis(
    val score: Float,
    val confidence: Int,
    val label: String,
    val reason: String,
    val drivers: List<String>
)
private enum class Horizon { HOUR, DAY, WEEK }

class MainActivity : Activity() {
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var statusText: TextView
    private lateinit var marketText: TextView
    private lateinit var newsText: TextView
    private lateinit var analysisText: TextView
    private lateinit var dial1h: SignalDialView
    private lateinit var dial1d: SignalDialView
    private lateinit var dial1w: SignalDialView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.BLACK
        window.navigationBarColor = Color.BLACK

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 20, 24, 24)
            setBackgroundColor(Color.rgb(10, 10, 11))
        }
        root.addView(text("GoldNewsAI", 28f, true, Color.WHITE))
        root.addView(text("黄金市场 · 新闻 + 行情本地智能分析", 14f, false, Color.LTGRAY))

        val refresh = Button(this).apply {
            text = "立即刷新新闻与行情"
            setOnClickListener { refreshAll() }
        }
        root.addView(refresh, LinearLayout.LayoutParams(-1, 52).apply { topMargin = 8; bottomMargin = 8 })

        statusText = text("● 正在连接公开数据…", 13f, false, Color.LTGRAY)
        root.addView(statusText)
        marketText = text("黄金行情：等待数据…", 14f, false, Color.WHITE)
        root.addView(marketText)

        root.addView(text("智能风向", 20f, true, Color.WHITE))
        root.addView(text("不要求你填写 Gemini API。App 直接读取公开新闻与公开黄金现货数据，然后在手机本地进行事件分类、方向、强度、时间周期和行情动量融合。", 12f, false, Color.GRAY))

        dial1h = SignalDialView(this, "1小时")
        dial1d = SignalDialView(this, "1天")
        dial1w = SignalDialView(this, "1周")
        root.addView(dial1h, LinearLayout.LayoutParams(-1, 235))
        root.addView(dial1d, LinearLayout.LayoutParams(-1, 235))
        root.addView(dial1w, LinearLayout.LayoutParams(-1, 235))

        root.addView(text("综合分析", 20f, true, Color.WHITE))
        analysisText = text("等待数据…", 13f, false, Color.LTGRAY)
        root.addView(analysisText)

        root.addView(text("财联社重点新闻", 20f, true, Color.WHITE))
        newsText = text("正在获取新闻…", 14f, false, Color.LTGRAY)
        root.addView(newsText)
        root.addView(text("数据说明：黄金价格使用公开 XAU/USD 现货数据源；新闻使用财联社公开网页。公开数据可能延迟、变化或暂时不可用。当前版本用于信息整理和研究展示，不构成投资建议，也不提供买卖指令。", 12f, false, Color.GRAY))

        setContentView(ScrollView(this).apply { addView(root) })
        refreshAll()
        handler.postDelayed(object : Runnable {
            override fun run() {
                refreshAll()
                handler.postDelayed(this, 60_000L)
            }
        }, 60_000L)
    }

    private fun text(s: String, size: Float, bold: Boolean, color: Int): TextView = TextView(this).apply {
        text = s
        textSize = size
        setTextColor(color)
        setPadding(0, 7, 0, 7)
        if (bold) setTypeface(null, Typeface.BOLD)
    }

    private fun refreshAll() {
        statusText.text = "● 正在获取新闻 + 黄金行情…"
        Thread {
            val news = runCatching { CailianNewsSource.fetch() }.getOrElse { emptyList() }
            val market = runCatching { GoldMarketSource.fetch() }.getOrNull()
            handler.post {
                if (news.isEmpty()) {
                    statusText.text = "● 新闻暂时获取失败；行情${if (market == null) "也" else ""}可用"
                    newsText.text = "暂时没有读取到财联社公开新闻。"
                } else {
                    statusText.text = "● 数据已更新 · 新闻 ${news.size} 条 · ${System.currentTimeMillis().toClockText()}"
                    newsText.text = news.take(12).mapIndexed { i, n -> "${i + 1}. ${n.title}" }.joinToString("\n\n")
                }
                if (market != null) {
                    marketText.text = buildString {
                        append("XAU/USD：${String.format(Locale.US, "%.2f", market.spotUsdOz)} USD/oz")
                        market.change1hPct?.let { append("   1H ${formatPct(it)}") }
                        market.change1dPct?.let { append("   1D ${formatPct(it)}") }
                        market.change1wPct?.let { append("   1W ${formatPct(it)}") }
                    }
                } else marketText.text = "黄金行情：暂时无法获取"

                val a1h = MarketSignalEngine.analyze(news, market, Horizon.HOUR)
                val a1d = MarketSignalEngine.analyze(news, market, Horizon.DAY)
                val a1w = MarketSignalEngine.analyze(news, market, Horizon.WEEK)
                dial1h.setAnalysis(a1h); dial1d.setAnalysis(a1d); dial1w.setAnalysis(a1w)
                val drivers = (a1h.drivers + a1d.drivers + a1w.drivers).distinct().take(6)
                analysisText.text = buildString {
                    append("1小时：${a1h.reason}，置信度 ${a1h.confidence}%\n")
                    append("1天：${a1d.reason}，置信度 ${a1d.confidence}%\n")
                    append("1周：${a1w.reason}，置信度 ${a1w.confidence}%\n")
                    if (drivers.isNotEmpty()) {
                        append("\n主要驱动：\n")
                        drivers.forEach { append("• $it\n") }
                    }
                    append("\n算法说明：新闻事件与行情动量分别评分，再按周期融合；不会因为单条新闻直接把指针打满。")
                }
            }
        }.start()
    }

    private fun formatPct(v: Double): String = String.format(Locale.US, "%+.2f%%", v)
}

private class SignalDialView(context: android.content.Context, private val horizon: String) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var analysis = Analysis(0f, 0, "等待数据", "等待数据", emptyList())
    fun setAnalysis(value: Analysis) { analysis = value; invalidate() }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat(); val h = height.toFloat(); val cx = w / 2f; val cy = h * 0.59f
        val r = (w.coerceAtMost(h * 2.2f) * 0.36f).coerceAtLeast(105f)
        paint.style = Paint.Style.FILL; paint.color = Color.rgb(18,18,20)
        canvas.drawRoundRect(0f,0f,w,h,22f,22f,paint)
        paint.style = Paint.Style.STROKE; paint.strokeWidth = 18f; paint.strokeCap = Paint.Cap.BUTT; paint.color = Color.rgb(55,55,58)
        val oval = RectF(cx-r,cy-r,cx+r,cy+r); canvas.drawArc(oval,200f,140f,false,paint)
        val sweep = ((analysis.score + 100f)/200f)*140f
        paint.color = when { analysis.score < -18 -> Color.rgb(205,75,130); analysis.score > 18 -> Color.rgb(75,170,120); else -> Color.rgb(150,150,155) }
        canvas.drawArc(oval,200f,sweep,false,paint)
        paint.style = Paint.Style.FILL; paint.textAlign = Paint.Align.CENTER; paint.color = Color.WHITE; paint.textSize = 19f; paint.typeface = Typeface.DEFAULT_BOLD
        canvas.drawText(horizon,cx,30f,paint)
        paint.textSize=13f; paint.typeface=Typeface.DEFAULT; paint.color=Color.GRAY
        canvas.drawText("偏弱",cx-r+18f,cy-r-3f,paint); canvas.drawText("中性",cx,cy-r-3f,paint); canvas.drawText("偏强",cx+r-18f,cy-r-3f,paint)
        val angle = Math.toRadians((270.0 + analysis.score*0.7).coerceIn(200.0,340.0))
        val nx=cx+(r-26f)*cos(angle).toFloat(); val ny=cy+(r-26f)*sin(angle).toFloat()
        paint.color=Color.WHITE; paint.strokeWidth=6f; paint.strokeCap=Paint.Cap.ROUND; canvas.drawLine(cx,cy,nx,ny,paint); canvas.drawCircle(cx,cy,9f,paint)
        paint.textSize=25f; paint.typeface=Typeface.DEFAULT_BOLD
        paint.color=when { analysis.score < -18 -> Color.rgb(225,90,120); analysis.score > 18 -> Color.rgb(85,190,130); else -> Color.LTGRAY }
        canvas.drawText(analysis.label,cx,cy+53f,paint)
        paint.textSize=12f; paint.typeface=Typeface.DEFAULT; paint.color=Color.GRAY
        canvas.drawText("置信度 ${analysis.confidence}% · ${analysis.reason}",cx,h-16f,paint)
    }
}

private object MarketSignalEngine {
    private data class Rule(val name:String,val direction:Int,val strength:Float,val horizons:Set<Horizon>,val words:List<String>)
    private val rules = listOf(
        Rule("降息/宽松预期",1,16f,setOf(Horizon.HOUR,Horizon.DAY,Horizon.WEEK),listOf("降息","降准","宽松","宽松预期")),
        Rule("避险/地缘风险",1,15f,setOf(Horizon.HOUR,Horizon.DAY,Horizon.WEEK),listOf("避险","地缘","战争","冲突","袭击","制裁")),
        Rule("美元偏弱",1,14f,setOf(Horizon.HOUR,Horizon.DAY),listOf("美元走弱","美元下跌","美元指数下跌")),
        Rule("收益率回落",1,14f,setOf(Horizon.HOUR,Horizon.DAY,Horizon.WEEK),listOf("收益率下降","美债收益率下降","实际利率下降")),
        Rule("央行购金",1,13f,setOf(Horizon.DAY,Horizon.WEEK),listOf("央行购金","央行增持黄金","官方储备黄金")),
        Rule("通胀压力",1,9f,setOf(Horizon.DAY,Horizon.WEEK),listOf("通胀上升","通胀超预期","通胀压力")),
        Rule("加息/鹰派",-1,16f,setOf(Horizon.HOUR,Horizon.DAY,Horizon.WEEK),listOf("加息","鹰派","紧缩")),
        Rule("美元偏强",-1,14f,setOf(Horizon.HOUR,Horizon.DAY),listOf("美元走强","美元上涨","美元指数上涨")),
        Rule("收益率回升",-1,14f,setOf(Horizon.HOUR,Horizon.DAY,Horizon.WEEK),listOf("收益率上升","美债收益率上升","实际利率上升")),
        Rule("风险偏好回升",-1,9f,setOf(Horizon.HOUR,Horizon.DAY),listOf("风险偏好","风险资产上涨","避险情绪降温")),
        Rule("黄金自身走弱",-1,8f,setOf(Horizon.HOUR,Horizon.DAY),listOf("黄金下跌","金价下跌")),
        Rule("黄金自身走强",1,8f,setOf(Horizon.HOUR,Horizon.DAY),listOf("黄金上涨","金价上涨"))
    )

    fun analyze(items:List<NewsItem>, market:GoldMarket?, horizon:Horizon):Analysis {
        val titles=items.map{normalize(it.title)}.distinct().take(20)
        var newsScore=0f; var matched=0; val drivers=mutableMapOf<String,Float>()
        titles.forEachIndexed{index,title->
            val w=1f-(index.coerceAtMost(9)*0.055f)
            rules.forEach{r-> if(r.horizons.contains(horizon)&&r.words.any{title.contains(it)}){ val c=r.direction*r.strength*w; newsScore+=c; matched++; drivers[r.name]=(drivers[r.name]?:0f)+abs(c)}}
        }
        newsScore=(newsScore.coerceIn(-100f,100f))*0.72f
        val momentum = when(horizon){
            Horizon.HOUR -> market?.change1hPct?.let{(it*18.0).coerceIn(-28.0,28.0)?.toFloat()}
            Horizon.DAY -> market?.change1dPct?.let{(it*11.0).coerceIn(-24.0,24.0)?.toFloat()}
            Horizon.WEEK -> market?.change1wPct?.let{(it*4.5).coerceIn(-20.0,20.0)?.toFloat()}
        } ?: 0f
        val score=(newsScore+momentum).coerceIn(-100f,100f)
        if(momentum!=0f) drivers["黄金行情动量"] = abs(momentum.toDouble()).toFloat()
        val confidence=(32+matched*7+(if(market!=null)15 else 0)+(titles.size.coerceAtMost(10)*2)-(if(matched==0)10 else 0)).coerceIn(20,94)
        val label=when{score<=-55->"偏弱";score<=-18->"略偏弱";score<18->"中性";score<55->"略偏强";else->"偏强"}
        val reason=when{score>=55->"多项利多因素集中";score>=18->"利多因素占优";score<=-55->"多项利空因素集中";score<=-18->"利空因素占优";else->"多空因素接近"}
        val top=drivers.entries.sortedByDescending{it.value}.take(4).map{it.key}
        return Analysis(score,confidence,label,reason,top)
    }
    private fun normalize(s:String)=s.lowercase(Locale.ROOT).replace(" ","").replace("　","")
}

private object GoldMarketSource {
    private const val SPOT="https://xaus.com/api/v1/spot?currency=USD&unit=oz"
    private const val INTRADAY="https://xaus.com/api/v1/intraday?symbol=xau&hours=48"
    private const val HISTORY="https://xaus.com/api/v1/history"
    fun fetch():GoldMarket {
        val spot=get(SPOT); val intraday=get(INTRADAY); val history=get(HISTORY)
        val price=Regex("\\\"spot_usd_oz\\\"\\s*:\\s*([0-9.]+)").find(spot)?.groupValues?.get(1)?.toDoubleOrNull()
            ?: Regex("\\\"price\\\"\\s*:\\s*([0-9.]+)").find(spot)?.groupValues?.get(1)?.toDoubleOrNull()
            ?: error("no spot price")
        val points=Regex("\\\"t\\\"\\s*:\\s*([0-9]+).*?\\\"p\\\"\\s*:\\s*([0-9.]+)").findAll(intraday).mapNotNull{m->m.groupValues[1].toLongOrNull()?.let{t->m.groupValues[2].toDoubleOrNull()?.let{p->t to p}}}.sortedBy{it.first}.toList()
        val oneHour=percentFromMinutes(points,60)
        val oneDay=percentFromMinutes(points,24*60)
        val closes=Regex("\\\"c\\\"\\s*:\\s*([0-9.]+)").findAll(history).mapNotNull{it.groupValues[1].toDoubleOrNull()}.toList()
        val oneWeek=if(closes.size>=2){ ((closes.last()/closes[maxOf(0,closes.size-6)])-1)*100.0 } else null
        val updated=Regex("\\\"updated_at\\\"\\s*:\\s*\\\"([^\\\"]+)").find(spot)?.groupValues?.get(1) ?: ""
        return GoldMarket(price,oneHour,oneDay,oneWeek,updated)
    }
    private fun percentFromMinutes(points:List<Pair<Long,Double>>, minutes:Int):Double? {
        if(points.size<2) return null
        val last=points.last(); val target=last.first-minutes*60_000L
        val base=points.minByOrNull{abs(it.first-target)} ?: return null
        if(base.second==0.0) return null
        return (last.second/base.second-1.0)*100.0
    }
    private fun get(url:String):String {
        val c=(URL(url).openConnection() as HttpURLConnection).apply{requestMethod="GET";connectTimeout=12000;readTimeout=12000;setRequestProperty("User-Agent","GoldNewsAI/5.0 Android") ;setRequestProperty("Accept","application/json")}
        return c.inputStream.bufferedReader(Charsets.UTF_8).use{it.readText()}
    }
}

private object CailianNewsSource {
    private const val HOME="https://m.cls.cn/"
    fun fetch():List<NewsItem>{
        val c=(URL(HOME).openConnection() as HttpURLConnection).apply{requestMethod="GET";connectTimeout=12000;readTimeout=12000;setRequestProperty("User-Agent","Mozilla/5.0 (Android) GoldNewsAI/5.0");setRequestProperty("Accept","text/html,application/xhtml+xml")}
        val html=c.inputStream.bufferedReader(Charsets.UTF_8).use{it.readText()}
        val regex=Regex("href=\\\"(/detail/\\d+)\\\"[^>]*>(.*?)</a>",RegexOption.IGNORE_CASE)
        return regex.findAll(html).mapNotNull{m->val title=m.groupValues[2].replace(Regex("<[^>]+>"),"").replace("&nbsp;"," ").replace("&amp;","&").trim();if(title.length<6)null else NewsItem(title,"https://www.cls.cn${m.groupValues[1]}")}.distinctBy{it.title}.take(30).toList()
    }
}
private fun Long.toClockText():String{val total=(this/60000L)%(24L*60L);return String.format(Locale.getDefault(),"%02d:%02d",total/60L,total%60L)}
