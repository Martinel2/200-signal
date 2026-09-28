package com.signal200.app.data

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
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
import com.signal200.app.domain.MarketSnapshot
import com.signal200.app.domain.RegimeState
import com.signal200.app.domain.StrategyEngine
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

object SignalNotificationScheduler {
    private const val PREFERENCES = "signal_notifications"
    private const val ENABLED = "enabled"
    private const val INTRADAY = "intraday"
    private const val PERIODIC_WORK = "signal-market-alert-periodic"
    private const val IMMEDIATE_WORK = "signal-market-alert-immediate"

    fun isEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .getBoolean(ENABLED, false)

    fun isIntradayEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .getBoolean(INTRADAY, true)

    fun hasEnabledChannel(context: Context): Boolean {
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return false
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return true
        val manager = context.getSystemService(NotificationManager::class.java)
        return listOf(
            SignalNotifier.CHANNEL_CONFIRMED,
            SignalNotifier.CHANNEL_PROFIT,
            SignalNotifier.CHANNEL_INTRADAY
        ).any { channelId ->
            manager.getNotificationChannel(channelId)?.importance
                ?.let { it != NotificationManager.IMPORTANCE_NONE }
                ?: false
        }
    }

    fun setEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(ENABLED, enabled)
            .apply()
        if (enabled) schedule(context) else {
            WorkManager.getInstance(context).cancelUniqueWork(PERIODIC_WORK)
            WorkManager.getInstance(context).cancelUniqueWork(IMMEDIATE_WORK)
        }
    }

    fun setIntradayEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(INTRADAY, enabled)
            .apply()
    }

    fun ensureScheduled(context: Context) {
        createChannels(context)
        if (isEnabled(context)) schedule(context)
    }

    fun sendTest(context: Context) {
        createChannels(context)
        SignalNotifier.notify(
            context = context,
            channel = SignalNotifier.CHANNEL_CONFIRMED,
            id = 2004,
            title = "200 SIGNAL 알림 테스트",
            text = "신호 알림이 정상적으로 설정되었습니다."
        )
    }

    private fun schedule(context: Context) {
        createChannels(context)
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()
        val periodic = PeriodicWorkRequestBuilder<SignalNotificationWorker>(
            15,
            TimeUnit.MINUTES
        ).setConstraints(constraints).build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            PERIODIC_WORK,
            ExistingPeriodicWorkPolicy.UPDATE,
            periodic
        )
        val immediate = OneTimeWorkRequestBuilder<SignalNotificationWorker>()
            .setConstraints(constraints)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            IMMEDIATE_WORK,
            ExistingWorkPolicy.REPLACE,
            immediate
        )
    }

    private fun createChannels(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannels(
            listOf(
                NotificationChannel(
                    SignalNotifier.CHANNEL_CONFIRMED,
                    "확정 종가 신호",
                    NotificationManager.IMPORTANCE_HIGH
                ).apply {
                    description = "알파 TQQQ SMA200와 베타 QQQ 밴드 확정 신호 변경"
                },
                NotificationChannel(
                    SignalNotifier.CHANNEL_INTRADAY,
                    "장중 예비 경고",
                    NotificationManager.IMPORTANCE_DEFAULT
                ).apply {
                    description = "장중 가격이 확정 SMA200의 반대편으로 움직일 때 보내는 참고 경고"
                },
                NotificationChannel(
                    SignalNotifier.CHANNEL_PROFIT,
                    "부분익절 단계",
                    NotificationManager.IMPORTANCE_HIGH
                ).apply {
                    description = "내 거래 원장의 수익률이 부분익절 단계에 처음 도달할 때 보내는 알림"
                }
            )
        )
    }
}

class SignalNotificationWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        if (!SignalNotificationScheduler.isEnabled(applicationContext)) return Result.success()
        return runCatching {
            val snapshot = MarketRepository(applicationContext).fetchSnapshot()
            SignalNotifier.evaluateAndNotify(applicationContext, snapshot)
            Result.success()
        }.getOrElse {
            if (runAttemptCount < 3) Result.retry() else Result.success()
        }
    }
}

private object SignalNotifier {
    const val CHANNEL_CONFIRMED = "confirmed_signal"
    const val CHANNEL_INTRADAY = "intraday_warning"
    const val CHANNEL_PROFIT = "profit_milestone"
    private const val PREFERENCES = "signal_notifications"

    fun evaluateAndNotify(context: Context, snapshot: MarketSnapshot) {
        val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
        val confirmedKey = snapshot.regime.state.name
        val previousConfirmed = preferences.getString("last_confirmed_key", null)

        if (previousConfirmed == null) {
            preferences.edit().putString("last_confirmed_key", confirmedKey).apply()
        } else if (previousConfirmed != confirmedKey) {
            preferences.edit().putString("last_confirmed_key", confirmedKey).apply()
            notify(
                context,
                CHANNEL_CONFIRMED,
                2001,
                "TQQQ 확정 신호 · ${snapshot.regime.label}",
                confirmedMessage(context, snapshot)
            )
        }

        evaluateProfitMilestones(context, snapshot, preferences)
        evaluateBeta(context, snapshot, preferences)

        if (!SignalNotificationScheduler.isIntradayEnabled(context)) return
        val quote = snapshot.intraday ?: return
        val confirmedAbove = snapshot.close > snapshot.sma200
        val intradayAbove = quote.price > snapshot.sma200
        if (confirmedAbove == intradayAbove) {
            preferences.edit().remove("last_intraday_warning").apply()
            return
        }

        val warningKey = "${snapshot.latestDate}:${if (intradayAbove) "UP" else "DOWN"}"
        if (preferences.getString("last_intraday_warning", null) == warningKey) return
        preferences.edit().putString("last_intraday_warning", warningKey).apply()
        val title = if (intradayAbove) "장중 상향 돌파 예비 관찰" else "장중 하향 이탈 예비 경고"
        notify(
            context,
            CHANNEL_INTRADAY,
            2002,
            title,
            "TQQQ $${"%.2f".format(quote.price)} · SMA200 $${"%.2f".format(snapshot.sma200)} · 종가 확정 전 참고"
        )
    }

    private fun evaluateBeta(
        context: Context,
        snapshot: MarketSnapshot,
        preferences: android.content.SharedPreferences
    ) {
        val beta = snapshot.beta ?: return
        val key = "${beta.regime.state}:${beta.trailingStopDate}:${beta.triggeredMilestones}"
        val previous = preferences.getString("last_beta_key", null)
        if (previous == null) {
            preferences.edit().putString("last_beta_key", key).apply()
            return
        }
        if (previous == key) return
        preferences.edit().putString("last_beta_key", key).apply()
        notify(
            context,
            if (beta.intradayTrailingStop) CHANNEL_INTRADAY else CHANNEL_CONFIRMED,
            2005,
            "베타 200큐큐단 · ${beta.regime.label}",
            "QQQ $${"%.2f".format(beta.qqqClose)} · 상단 $${"%.2f".format(beta.upperBand)} · 하단 $${"%.2f".format(beta.lowerBand)} · ${beta.regime.action}"
        )
    }

    private fun evaluateProfitMilestones(
        context: Context,
        snapshot: MarketSnapshot,
        alertPreferences: android.content.SharedPreferences
    ) {
        val trades = PortfolioStore(context).loadTrades()
        if (trades.isEmpty()) return
        val taxYear = SimpleDateFormat("yyyy", Locale.US).format(Date()).toInt()
        val currentPrice = snapshot.intraday?.price ?: snapshot.close
        val summary = StrategyEngine.portfolioSummary(
            trades = trades.filter { it.ticker == "TQQQ" },
            currentPriceUsd = currentPrice,
            currentFx = 1.0,
            taxYear = taxYear
        )
        val profitRate = summary.positionReturn ?: return
        val cycleKey = summary.cycleStartTradeId ?: return
        if (summary.error != null) return

        val accountPreferences = context.getSharedPreferences("account", Context.MODE_PRIVATE)
        val completed = accountPreferences.getStringSet("completed", emptySet())
            ?.mapNotNull { it.toDoubleOrNull() }
            ?.toSet() ?: emptySet()
        val fractional = accountPreferences.getBoolean("fractional", false)
        val plan = StrategyEngine.takeProfitPlan(
            quantity = summary.remainingQuantity,
            profitRate = profitRate,
            completed = completed,
            fractional = fractional
        )
        if (plan.orders.isEmpty()) return

        val alerted = alertPreferences.getStringSet("alerted_profit_keys", emptySet())
            ?.toMutableSet() ?: mutableSetOf()
        val newOrders = plan.orders.filter { order ->
            "$cycleKey:${order.threshold}" !in alerted
        }
        if (newOrders.isEmpty()) return
        newOrders.forEach { alerted += "$cycleKey:${it.threshold}" }
        alertPreferences.edit().putStringSet("alerted_profit_keys", alerted).apply()

        val stages = newOrders.joinToString(" · ") { "+${(it.threshold * 100).toInt()}%" }
        val quantity = newOrders.sumOf { it.quantity }
        notify(
            context,
            CHANNEL_PROFIT,
            2003,
            "TQQQ 부분익절 단계 도달 · $stages",
            "달러 수수료 반영 ${signedPercent(profitRate)} · ${formatQuantity(quantity)}주를 SPYM으로 전환할지 확인하세요."
        )
    }

    fun notify(context: Context, channel: String, id: Int, title: String, text: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) return

        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            id,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setPriority(
                if (channel == CHANNEL_CONFIRMED) {
                    NotificationCompat.PRIORITY_HIGH
                } else {
                    NotificationCompat.PRIORITY_DEFAULT
                }
            )
            .build()
        NotificationManagerCompat.from(context).notify(id, notification)
    }

    private fun confirmedMessage(context: Context, snapshot: MarketSnapshot): String {
        val store = PortfolioStore(context)
        val trades = store.loadTrades()
        val profile = store.loadCapitalProfile()
        val currentPrice = snapshot.intraday?.price ?: snapshot.close
        val taxYear = SimpleDateFormat("yyyy", Locale.US).format(Date()).toInt()
        val summary = StrategyEngine.portfolioSummary(
            trades.filter { it.ticker == "TQQQ" },
            currentPrice,
            1.0,
            taxYear
        )
        val hasPosition = summary.error == null && summary.remainingQuantity > 1e-8
        val day = when (snapshot.regime.state) {
            RegimeState.ENTRY_DAY_1 -> 1
            RegimeState.ENTRY_DAY_2 -> 2
            RegimeState.ENTRY_DAY_3 -> 3
            else -> null
        }
        val cycleKey = day?.let {
            snapshot.chart.getOrNull(snapshot.chart.lastIndex - (it - 1))?.date
        }
        val entryCapital = profile.entryCapitalKrw.toDoubleOrNull()
            ?.takeIf { profile.entryCycleKey == cycleKey && it > 0.0 }
        val spent = trades
            .filter {
                it.type == com.signal200.app.domain.TradeType.BUY &&
                    it.ticker == "TQQQ" &&
                    it.entryCycleKey == cycleKey &&
                    it.entryDay in 1..3
            }
            .sumOf { it.quantity * it.priceUsd + it.feeKrw }

        return when (snapshot.regime.state) {
            RegimeState.ENTRY_DAY_1 ->
                "확정 종가가 SMA200 위로 돌파했습니다. ${
                    if (hasPosition) "기존 TQQQ 원장과 1차 진입 여부를 확인하세요."
                    else "TQQQ 미보유 계좌는 1차 진입 조건과 가용자금을 확인하세요."
                }"
            RegimeState.ENTRY_DAY_2,
            RegimeState.ENTRY_DAY_3 -> {
                val previousCompleted = day != null && (1 until day).all { it in profile.completedEntryDays }
                if (entryCapital != null && previousCompleted) {
                    val remaining = kotlin.math.max(0.0, entryCapital - spent)
                    val budget = if (day == 2) remaining / 2.0 else remaining
                    "${day}차 진입 조건입니다. 실제 남은 진입자금 기준 약 ${formatUsd(budget)}를 확인하세요."
                } else {
                    "${day}일 차입니다. 이전 차수 기록이 없어 자동 주문을 제안하지 않습니다. 실제 체결 내역을 확인하세요."
                }
            }
        RegimeState.EXIT_PENDING -> "확정 종가가 SMA200 아래로 이탈했습니다. 대피 원칙을 확인하세요."
        RegimeState.SAFE -> "확정 종가가 SMA200 아래입니다. SGOV 대기 구간입니다."
            RegimeState.RISK_ON -> if (hasPosition) {
                "확정 종가가 SMA200 위입니다. TQQQ 보유 구간입니다."
            } else {
                "TQQQ 0주·SMA200 위입니다. SGOV 50%·SPYM 50% 목표를 확인하세요."
            }
        RegimeState.WAIT -> "종가와 SMA200이 경계에 있습니다. 방향 확정을 기다리세요."
        RegimeState.DATA_ERROR -> "확정 신호 계산에 필요한 데이터가 부족합니다."
        }
    }

    private fun formatUsd(value: Double): String =
        "$" + java.text.NumberFormat.getNumberInstance(Locale.US).apply {
            minimumFractionDigits = 2
            maximumFractionDigits = 2
        }.format(value)

    private fun signedPercent(value: Double): String =
        String.format(Locale.US, "%+.2f%%", value * 100.0)

    private fun formatQuantity(value: Double): String =
        if (value % 1.0 == 0.0) value.toInt().toString()
        else "%.6f".format(Locale.US, value).trimEnd('0').trimEnd('.')
}
