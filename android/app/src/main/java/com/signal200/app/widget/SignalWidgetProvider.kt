package com.signal200.app.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.View
import android.widget.RemoteViews
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.signal200.app.MainActivity
import com.signal200.app.R
import com.signal200.app.data.MarketRepository
import com.signal200.app.data.PortfolioStore
import com.signal200.app.domain.MarketSnapshot
import com.signal200.app.domain.PortfolioTrade
import com.signal200.app.domain.RegimeState
import com.signal200.app.domain.StrategyEngine
import com.signal200.app.domain.TradeType
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.max

class SignalWidgetProvider : AppWidgetProvider() {
    override fun onEnabled(context: Context) {
        SignalWidgetScheduler.start(context)
    }

    override fun onDisabled(context: Context) {
        SignalWidgetScheduler.stop(context)
    }

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        updateAll(context, MarketRepository(context).cachedSnapshot())
        showRefreshing(context)
        SignalWidgetScheduler.refresh(context)
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action == ACTION_REFRESH) {
            showRefreshing(context)
            SignalWidgetScheduler.refresh(context)
        }
    }

    companion object {
        const val ACTION_REFRESH = "com.signal200.app.widget.REFRESH"

        fun updateAll(context: Context, snapshot: MarketSnapshot?) {
            val manager = AppWidgetManager.getInstance(context)
            val component = ComponentName(context, SignalWidgetProvider::class.java)
            val ids = manager.getAppWidgetIds(component)
            if (ids.isEmpty()) return

            val ui = buildWidgetUi(context, snapshot)
            ids.forEach { id ->
                val views = RemoteViews(context.packageName, R.layout.signal_widget)
                views.setTextViewText(R.id.widget_action, ui.action)
                views.setTextViewText(R.id.widget_order, ui.order)
                views.setTextViewText(R.id.widget_market, ui.market)
                views.setTextViewText(R.id.widget_updated, ui.updated)
                views.setImageViewBitmap(R.id.widget_chart, buildSparkline(snapshot))
                views.setViewVisibility(R.id.widget_refresh, View.VISIBLE)
                views.setViewVisibility(R.id.widget_refresh_progress, View.GONE)

                val openIntent = Intent(context, MainActivity::class.java)
                val openPendingIntent = PendingIntent.getActivity(
                    context,
                    id,
                    openIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                val refreshIntent = Intent(context, SignalWidgetProvider::class.java)
                    .setAction(ACTION_REFRESH)
                val refreshPendingIntent = PendingIntent.getBroadcast(
                    context,
                    id,
                    refreshIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                views.setOnClickPendingIntent(R.id.widget_root, openPendingIntent)
                views.setOnClickPendingIntent(R.id.widget_refresh, refreshPendingIntent)
                manager.updateAppWidget(id, views)
            }
        }

        private fun showRefreshing(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val component = ComponentName(context, SignalWidgetProvider::class.java)
            manager.getAppWidgetIds(component).forEach { id ->
                val views = RemoteViews(context.packageName, R.layout.signal_widget)
                views.setViewVisibility(R.id.widget_refresh, View.GONE)
                views.setViewVisibility(R.id.widget_refresh_progress, View.VISIBLE)
                manager.partiallyUpdateAppWidget(id, views)
            }
        }

        private fun buildWidgetUi(context: Context, snapshot: MarketSnapshot?): WidgetUi {
            if (snapshot == null) {
                return WidgetUi(
                    action = "시장 데이터 대기",
                    order = "↻ 새로고침하거나 앱을 열어주세요.",
                    market = "TQQQ · SMA200 데이터 없음",
                    updated = "아직 갱신되지 않음"
                )
            }

            val store = PortfolioStore(context)
            val trades = store.loadTrades()
            val profile = store.loadCapitalProfile()
            val tqqqQuantity = quantity(trades, "TQQQ")
            val sgovQuantity = quantity(trades, "SGOV")
            val spymQuantity = quantity(trades, "SPYM")
            val tqqqPrice = snapshot.intraday?.price ?: snapshot.close
            val totalAssets = profile.totalAssetsKrw.replace(",", "").toDoubleOrNull()
            val cash = profile.availableCashKrw.replace(",", "").toDoubleOrNull()
                ?: if (trades.isEmpty()) totalAssets ?: 0.0 else 0.0
            val entryDay = when (snapshot.regime.state) {
                RegimeState.ENTRY_DAY_1 -> 1
                RegimeState.ENTRY_DAY_2 -> 2
                RegimeState.ENTRY_DAY_3 -> 3
                else -> null
            }
            val cycleKey = entryDay?.let { day ->
                snapshot.chart.getOrNull(snapshot.chart.lastIndex - (day - 1))?.date
            }
            val fixedEntryCapital = profile.entryCapitalKrw.toDoubleOrNull()
                ?.takeIf { profile.entryCycleKey == cycleKey && it > 0.0 }
            val effectiveEntryCapital = fixedEntryCapital
                ?: totalAssets?.takeIf { entryDay == 1 && tqqqQuantity <= 1e-8 }
            val spentEntry = trades.filter {
                it.ticker == "TQQQ" &&
                    it.type == TradeType.BUY &&
                    it.entryCycleKey == cycleKey &&
                    it.entryDay in 1..3
            }.sumOf { it.quantity * it.priceUsd + it.feeKrw }
            val allocationCapital = if (
                snapshot.regime.state == RegimeState.RISK_ON &&
                tqqqQuantity > 1e-8 &&
                totalAssets != null
            ) {
                max(0.0, totalAssets - tqqqQuantity * tqqqPrice)
            } else {
                totalAssets
            }
            val canPlanEntry = entryDay == null || effectiveEntryCapital != null
            val plan = if (
                allocationCapital != null &&
                allocationCapital > 0.0 &&
                canPlanEntry
            ) {
                StrategyEngine.capitalAllocationPlan(
                    state = snapshot.regime.state,
                    totalAssetsKrw = allocationCapital,
                    tqqqPriceUsd = tqqqPrice,
                    spymPriceUsd = snapshot.spymPrice,
                    sgovPriceUsd = snapshot.sgovPrice,
                    usdKrw = 1.0,
                    entryCapitalKrw = effectiveEntryCapital,
                    completedEntryDays = if (fixedEntryCapital != null) {
                        profile.completedEntryDays
                    } else {
                        emptySet()
                    },
                    spentEntryKrw = spentEntry,
                    currentTqqqQuantity = tqqqQuantity,
                    currentSgovQuantity = sgovQuantity,
                    currentSpymQuantity = spymQuantity,
                    availableCashKrw = cash,
                    feeRatePercent = store.loadFeeRatePercent()
                )
            } else null

            val action = when {
                snapshot.regime.state in setOf(RegimeState.SAFE, RegimeState.EXIT_PENDING) &&
                    (tqqqQuantity >= 1.0 || spymQuantity >= 1.0) ->
                    "TQQQ·SPYM 전량매도"
                snapshot.regime.state == RegimeState.RISK_ON && tqqqQuantity < 1.0 ->
                    "SGOV·SPYM 50:50"
                else -> snapshot.regime.label
            }
            val order = when {
                totalAssets == null -> "앱에서 총자산을 입력하세요."
                plan?.todayTqqqBuyQuantity?.let { it >= 1.0 } == true -> buildString {
                    plan.todaySgovSellQuantity?.takeIf { it >= 1.0 }?.let {
                        append("SGOV ${formatQuantity(it)}주 매도 · ")
                    }
                    append("TQQQ ${formatQuantity(plan.todayTqqqBuyQuantity)}주 매수")
                }
                plan != null && plan.entryDay == null -> {
                    plan.targets
                        .filter { abs(it.orderQuantity) >= 1.0 }
                        .joinToString(" · ") {
                            "${it.ticker} ${formatQuantity(abs(it.orderQuantity))}주 ${
                                if (it.orderQuantity > 0.0) "매수" else "매도"
                            }"
                        }
                        .ifBlank { "오늘 추가 주문 없음" }
                }
                entryDay != null -> "분할진입 기록을 앱에서 확인하세요."
                else -> "현재 주문 계산 대기"
            }

            return WidgetUi(
                action = "α $action\nβ ${snapshot.beta?.regime?.label ?: "데이터 대기"}",
                order = order,
                market = buildString {
                    append("TQQQ $${formatPrice(tqqqPrice)} · SMA $${formatPrice(snapshot.sma200)}")
                    snapshot.beta?.let { append(" · QQQ $${formatPrice(it.qqqClose)}") }
                },
                updated = "업데이트 ${formatTime(snapshot.fetchedAt)} · 위젯을 누르면 앱 열기"
            )
        }

        private fun quantity(trades: List<PortfolioTrade>, ticker: String): Double =
            trades.filter { it.ticker == ticker }.sumOf {
                if (it.type == TradeType.BUY) it.quantity else -it.quantity
            }.coerceAtLeast(0.0)

        private fun formatPrice(value: Double): String =
            String.format(Locale.US, "%.2f", value)

        private fun formatQuantity(value: Double): String =
            NumberFormat.getIntegerInstance(Locale.US).format(value)

        private fun formatTime(timestamp: Long): String =
            SimpleDateFormat("MM-dd HH:mm", Locale.KOREA).format(Date(timestamp))

        private fun buildSparkline(snapshot: MarketSnapshot?): Bitmap {
            val width = 224
            val height = 152
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val points = snapshot?.chart?.takeLast(60).orEmpty()
            if (points.size < 2) return bitmap

            val padding = 8f
            val values = points.flatMap { listOf(it.close, it.sma200) }
            val minimum = values.minOrNull() ?: return bitmap
            val maximum = values.maxOrNull() ?: return bitmap
            val range = (maximum - minimum).takeIf { it > 1e-9 } ?: 1.0
            val canvas = Canvas(bitmap)
            val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.argb(75, 143, 180, 255)
                strokeWidth = 1.5f
            }
            val closePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.rgb(143, 180, 255)
                strokeWidth = 4f
                style = Paint.Style.STROKE
            }
            val smaPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.rgb(255, 166, 92)
                strokeWidth = 3f
                style = Paint.Style.STROKE
            }
            canvas.drawLine(padding, height / 2f, width - padding, height / 2f, gridPaint)

            fun x(index: Int): Float =
                padding + index.toFloat() / points.lastIndex * (width - padding * 2f)

            fun y(value: Double): Float =
                padding + ((maximum - value) / range).toFloat() * (height - padding * 2f)

            for (index in 1..points.lastIndex) {
                canvas.drawLine(
                    x(index - 1),
                    y(points[index - 1].sma200),
                    x(index),
                    y(points[index].sma200),
                    smaPaint
                )
                canvas.drawLine(
                    x(index - 1),
                    y(points[index - 1].close),
                    x(index),
                    y(points[index].close),
                    closePaint
                )
            }
            val last = points.last()
            canvas.drawCircle(x(points.lastIndex), y(last.close), 5f, closePaint.apply {
                style = Paint.Style.FILL
            })
            return bitmap
        }
    }
}

private data class WidgetUi(
    val action: String,
    val order: String,
    val market: String,
    val updated: String
)

object SignalWidgetScheduler {
    private const val PERIODIC_WORK = "signal-widget-periodic"
    private const val IMMEDIATE_WORK = "signal-widget-immediate"

    fun start(context: Context) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()
        val periodic = PeriodicWorkRequestBuilder<SignalWidgetWorker>(
            15,
            TimeUnit.MINUTES
        ).setConstraints(constraints).build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            PERIODIC_WORK,
            ExistingPeriodicWorkPolicy.UPDATE,
            periodic
        )
        refresh(context)
    }

    fun refresh(context: Context) {
        val request = OneTimeWorkRequestBuilder<SignalWidgetWorker>()
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            IMMEDIATE_WORK,
            ExistingWorkPolicy.REPLACE,
            request
        )
    }

    fun stop(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(PERIODIC_WORK)
        WorkManager.getInstance(context).cancelUniqueWork(IMMEDIATE_WORK)
    }
}

class SignalWidgetWorker(
    appContext: Context,
    workerParameters: WorkerParameters
) : CoroutineWorker(appContext, workerParameters) {
    override suspend fun doWork(): Result {
        val repository = MarketRepository(applicationContext)
        val snapshot = runCatching { repository.fetchSnapshot() }
            .getOrElse { repository.cachedSnapshot() }
        SignalWidgetProvider.updateAll(applicationContext, snapshot)
        return if (snapshot != null) Result.success() else Result.retry()
    }
}
