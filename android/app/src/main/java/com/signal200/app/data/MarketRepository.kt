package com.signal200.app.data

import android.content.Context
import com.signal200.app.domain.ChartPoint
import com.signal200.app.domain.BetaRegime
import com.signal200.app.domain.BetaSnapshot
import com.signal200.app.domain.BetaState
import com.signal200.app.domain.IntradayQuote
import com.signal200.app.domain.MarketSnapshot
import com.signal200.app.domain.MarketSessionFilter
import com.signal200.app.domain.PriceRow
import com.signal200.app.domain.Regime
import com.signal200.app.domain.RegimeState
import com.signal200.app.domain.StrategyEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

class MarketRepository(context: Context) {
    private val preferences = context.getSharedPreferences("market_cache", Context.MODE_PRIVATE)

    suspend fun fetchSnapshot(): MarketSnapshot = withContext(Dispatchers.IO) {
        val cached = cachedSnapshot()
        val results = coroutineScope {
            // ponytail: 5년 창이면 현재 사이클을 재구성한다. 사이클이 5년을 넘으면 일봉을 영구 저장한다.
            val tqqq = async { fetchChart("TQQQ", "5y", completedSessionsOnly = true) }
            val qqq = async {
                runCatching { fetchChart("QQQ", "5y", completedSessionsOnly = true) }.getOrNull()
            }
            val spym = async { runCatching { fetchChart("SPYM", "5d").last().close }.getOrNull() }
            val sgov = async { runCatching { fetchChart("SGOV", "5d").last().close }.getOrNull() }
            val fx = async { runCatching { fetchChart("KRW=X", "5d").last().close }.getOrNull() }
            val intraday = async { runCatching { fetchIntradayQuote() }.getOrNull() }
            SnapshotParts(
                tqqq = tqqq.await(),
                qqq = qqq.await(),
                spym = spym.await() ?: cached?.spymPrice ?: 0.0,
                sgov = sgov.await() ?: cached?.sgovPrice ?: 0.0,
                usdKrw = fx.await() ?: cached?.usdKrw ?: 0.0,
                intraday = intraday.await()
            )
        }

        val snapshot = StrategyEngine.buildSnapshot(
            rows = results.tqqq,
            spymPrice = results.spym,
            sgovPrice = results.sgov,
            usdKrw = results.usdKrw,
            qqqRows = results.qqq,
            intraday = results.intraday
        )
        saveSnapshot(snapshot)
        snapshot
    }

    fun cachedSnapshot(): MarketSnapshot? {
        val raw = preferences.getString("snapshot", null) ?: return null
        return runCatching { snapshotFromJson(JSONObject(raw)).copy(fromCache = true) }.getOrNull()
    }

    private fun fetchChart(
        symbol: String,
        range: String,
        completedSessionsOnly: Boolean = false
    ): List<PriceRow> {
        val encoded = java.net.URLEncoder.encode(symbol, "UTF-8")
        val endpoint =
            "https://query1.finance.yahoo.com/v8/finance/chart/$encoded" +
                "?range=$range&interval=1d&events=div%2Csplits&includeAdjustedClose=true"
        val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 12_000
            readTimeout = 12_000
            setRequestProperty("User-Agent", "Mozilla/5.0 Signal200-Android/0.5")
            setRequestProperty("Accept", "application/json")
        }

        try {
            if (connection.responseCode !in 200..299) {
                error("$symbol 데이터 응답 오류 (${connection.responseCode})")
            }
            val raw = connection.inputStream.bufferedReader().use { it.readText() }
            val result = JSONObject(raw)
                .getJSONObject("chart")
                .getJSONArray("result")
                .getJSONObject(0)
            val timestamps = result.getJSONArray("timestamp")
            val closes = result
                .getJSONObject("indicators")
                .getJSONArray("quote")
                .getJSONObject(0)
                .getJSONArray("close")
            val meta = result.getJSONObject("meta")
            val timezone = meta.optString("exchangeTimezoneName", "America/New_York")
            val regularSession = meta.optJSONObject("currentTradingPeriod")
                ?.optJSONObject("regular")
            val regularStart = regularSession?.optLong("start", 0L) ?: 0L
            val regularEnd = regularSession?.optLong("end", 0L) ?: 0L
            val nowSeconds = System.currentTimeMillis() / 1000L
            val formatter = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply {
                timeZone = TimeZone.getTimeZone(timezone)
            }

            return buildList {
                for (index in 0 until minOf(timestamps.length(), closes.length())) {
                    if (closes.isNull(index)) continue
                    val timestamp = timestamps.getLong(index)
                    if (completedSessionsOnly && !MarketSessionFilter.isCompletedDailyBar(
                            barTimestampSeconds = timestamp,
                            regularSessionStartSeconds = regularStart,
                            regularSessionEndSeconds = regularEnd,
                            nowSeconds = nowSeconds
                        )
                    ) continue
                    val close = closes.optDouble(index, Double.NaN)
                    if (!close.isFinite()) continue
                    add(
                        PriceRow(
                            date = formatter.format(Date(timestamp * 1000L)),
                            close = close
                        )
                    )
                }
            }.also {
                require(it.isNotEmpty()) { "$symbol 종가 데이터가 비어 있습니다." }
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun fetchIntradayQuote(): IntradayQuote {
        val endpoint =
            "https://query1.finance.yahoo.com/v8/finance/chart/TQQQ" +
                "?range=1d&interval=1m&includePrePost=true&events=div%2Csplits"
        val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 12_000
            readTimeout = 12_000
            setRequestProperty("User-Agent", "Mozilla/5.0 Signal200-Android/0.5")
            setRequestProperty("Accept", "application/json")
        }

        try {
            if (connection.responseCode !in 200..299) {
                error("TQQQ 장중 데이터 응답 오류 (${connection.responseCode})")
            }
            val raw = connection.inputStream.bufferedReader().use { it.readText() }
            val result = JSONObject(raw)
                .getJSONObject("chart")
                .getJSONArray("result")
                .getJSONObject(0)
            val meta = result.getJSONObject("meta")
            val timestamps = result.optJSONArray("timestamp") ?: JSONArray()
            val closes = result
                .getJSONObject("indicators")
                .getJSONArray("quote")
                .getJSONObject(0)
                .optJSONArray("close") ?: JSONArray()

            var latestIndex = minOf(timestamps.length(), closes.length()) - 1
            while (latestIndex >= 0 && closes.isNull(latestIndex)) latestIndex -= 1

            val timestamp = if (latestIndex >= 0) {
                timestamps.getLong(latestIndex)
            } else {
                meta.optLong("regularMarketTime", 0L)
            }
            val price = if (latestIndex >= 0) {
                closes.optDouble(latestIndex, Double.NaN)
            } else {
                meta.optDouble("regularMarketPrice", Double.NaN)
            }
            require(price.isFinite() && timestamp > 0) { "TQQQ 장중 체결 데이터가 비어 있습니다." }

            val periods = meta.optJSONObject("currentTradingPeriod")
            val pre = periods?.optJSONObject("pre")
            val regular = periods?.optJSONObject("regular")
            val post = periods?.optJSONObject("post")
            val session = when {
                inPeriod(timestamp, regular) -> "정규장"
                inPeriod(timestamp, pre) -> "프리마켓"
                inPeriod(timestamp, post) -> "애프터마켓"
                else -> "장 마감"
            }

            return IntradayQuote(
                price = price,
                timestamp = timestamp * 1000L,
                previousClose = meta.optDouble(
                    "chartPreviousClose",
                    meta.optDouble("previousClose", price)
                ),
                session = session,
                delayedBySeconds = meta.optInt("exchangeDataDelayedBy", 0)
            )
        } finally {
            connection.disconnect()
        }
    }

    private fun inPeriod(timestamp: Long, period: JSONObject?): Boolean {
        if (period == null) return false
        val start = period.optLong("start", Long.MAX_VALUE)
        val end = period.optLong("end", Long.MIN_VALUE)
        return timestamp in start..end
    }

    private fun saveSnapshot(snapshot: MarketSnapshot) {
        preferences.edit()
            .putString("snapshot", snapshotToJson(snapshot).toString())
            .apply()
    }

    private fun snapshotToJson(snapshot: MarketSnapshot) = JSONObject().apply {
        put("state", snapshot.regime.state.name)
        put("label", snapshot.regime.label)
        put("reason", snapshot.regime.reason)
        put("latestDate", snapshot.latestDate)
        put("close", snapshot.close)
        put("sma200", snapshot.sma200)
        put("distance", snapshot.distance)
        put("spymPrice", snapshot.spymPrice)
        put("sgovPrice", snapshot.sgovPrice)
        put("usdKrw", snapshot.usdKrw)
        put("fetchedAt", snapshot.fetchedAt)
        put("source", snapshot.source)
        snapshot.beta?.let { beta ->
            put("beta", JSONObject().apply {
                put("state", beta.regime.state.name)
                put("label", beta.regime.label)
                put("action", beta.regime.action)
                put("reason", beta.regime.reason)
                put("latestDate", beta.latestDate)
                put("qqqClose", beta.qqqClose)
                put("sma200", beta.sma200)
                put("upperBand", beta.upperBand)
                put("lowerBand", beta.lowerBand)
                put("distance", beta.distance)
                beta.cycleStartDate?.let { put("cycleStartDate", it) }
                beta.tqqqPeak?.let { put("tqqqPeak", it) }
                beta.tqqqDrawdown?.let { put("tqqqDrawdown", it) }
                beta.averageEntryPrice?.let { put("averageEntryPrice", it) }
                beta.profitRate?.let { put("profitRate", it) }
                put("completedMilestones", JSONArray(beta.completedMilestones))
                put("triggeredMilestones", JSONArray(beta.triggeredMilestones))
                beta.trailingStopDate?.let { put("trailingStopDate", it) }
                put("intradayTrailingStop", beta.intradayTrailingStop)
            })
        }
        snapshot.intraday?.let { quote ->
            put("intraday", JSONObject().apply {
                put("price", quote.price)
                put("timestamp", quote.timestamp)
                put("previousClose", quote.previousClose)
                put("session", quote.session)
                put("delayedBySeconds", quote.delayedBySeconds)
            })
        }
        put("chart", JSONArray().apply {
            snapshot.chart.forEach { point ->
                put(JSONObject().apply {
                    put("date", point.date)
                    put("close", point.close)
                    put("sma200", point.sma200)
                })
            }
        })
    }

    private fun snapshotFromJson(json: JSONObject): MarketSnapshot {
        val chartArray = json.getJSONArray("chart")
        val chart = buildList {
            for (index in 0 until chartArray.length()) {
                val point = chartArray.getJSONObject(index)
                add(
                    ChartPoint(
                        date = point.getString("date"),
                        close = point.getDouble("close"),
                        sma200 = point.getDouble("sma200")
                    )
                )
            }
        }
        val intraday = json.optJSONObject("intraday")?.let { quote ->
            IntradayQuote(
                price = quote.getDouble("price"),
                timestamp = quote.getLong("timestamp"),
                previousClose = quote.getDouble("previousClose"),
                session = quote.getString("session"),
                delayedBySeconds = quote.optInt("delayedBySeconds", 0)
            )
        }
        val beta = json.optJSONObject("beta")?.let { item ->
            BetaSnapshot(
                regime = BetaRegime(
                    state = BetaState.valueOf(item.getString("state")),
                    label = item.getString("label"),
                    action = item.getString("action"),
                    reason = item.getString("reason")
                ),
                latestDate = item.getString("latestDate"),
                qqqClose = item.getDouble("qqqClose"),
                sma200 = item.getDouble("sma200"),
                upperBand = item.getDouble("upperBand"),
                lowerBand = item.getDouble("lowerBand"),
                distance = item.getDouble("distance"),
                cycleStartDate = item.optString("cycleStartDate").takeIf { it.isNotBlank() },
                tqqqPeak = item.optNullableDouble("tqqqPeak"),
                tqqqDrawdown = item.optNullableDouble("tqqqDrawdown"),
                averageEntryPrice = item.optNullableDouble("averageEntryPrice"),
                profitRate = item.optNullableDouble("profitRate"),
                completedMilestones = item.doubleList("completedMilestones"),
                triggeredMilestones = item.doubleList("triggeredMilestones"),
                trailingStopDate = item.optString("trailingStopDate").takeIf { it.isNotBlank() },
                intradayTrailingStop = item.optBoolean("intradayTrailingStop", false)
            )
        }
        return MarketSnapshot(
            regime = Regime(
                state = RegimeState.valueOf(json.getString("state")),
                label = json.getString("label"),
                reason = json.getString("reason")
            ),
            latestDate = json.getString("latestDate"),
            close = json.getDouble("close"),
            sma200 = json.getDouble("sma200"),
            distance = json.getDouble("distance"),
            spymPrice = json.getDouble("spymPrice"),
            sgovPrice = json.getDouble("sgovPrice"),
            usdKrw = json.optDouble("usdKrw", 0.0),
            chart = chart,
            beta = beta,
            intraday = intraday,
            fetchedAt = json.getLong("fetchedAt"),
            source = json.optString("source", "Yahoo Finance 비공식 Chart API"),
            fromCache = true
        )
    }

    private data class SnapshotParts(
        val tqqq: List<PriceRow>,
        val qqq: List<PriceRow>?,
        val spym: Double,
        val sgov: Double,
        val usdKrw: Double,
        val intraday: IntradayQuote?
    )

    private fun JSONObject.optNullableDouble(key: String): Double? =
        if (has(key) && !isNull(key)) optDouble(key).takeIf { it.isFinite() } else null

    private fun JSONObject.doubleList(key: String): List<Double> {
        val array = optJSONArray(key) ?: return emptyList()
        return (0 until array.length()).mapNotNull { index ->
            array.optDouble(index, Double.NaN).takeIf { it.isFinite() }
        }
    }
}
