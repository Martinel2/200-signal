package com.signal200.app.domain

import kotlin.math.floor
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.round

data class PriceRow(
    val date: String,
    val close: Double
)

enum class RegimeState {
    SAFE,
    ENTRY_DAY_1,
    ENTRY_DAY_2,
    ENTRY_DAY_3,
    RISK_ON,
    EXIT_PENDING,
    WAIT,
    DATA_ERROR
}

enum class BetaState {
    SAFE,
    ENTRY_DAY_1,
    ENTRY_DAY_2,
    ENTRY_DAY_3,
    RISK_ON,
    TAKE_PROFIT,
    TRAILING_STOP,
    EXIT_PENDING
}

data class BetaRegime(
    val state: BetaState,
    val label: String,
    val action: String,
    val reason: String
)

data class BetaSnapshot(
    val regime: BetaRegime,
    val latestDate: String,
    val qqqClose: Double,
    val sma200: Double,
    val upperBand: Double,
    val lowerBand: Double,
    val distance: Double,
    val cycleStartDate: String? = null,
    val tqqqPeak: Double? = null,
    val tqqqDrawdown: Double? = null,
    val averageEntryPrice: Double? = null,
    val profitRate: Double? = null,
    val completedMilestones: List<Double> = emptyList(),
    val triggeredMilestones: List<Double> = emptyList(),
    val trailingStopDate: String? = null,
    val intradayTrailingStop: Boolean = false
)

data class Regime(
    val state: RegimeState,
    val label: String,
    val reason: String
)

data class ChartPoint(
    val date: String,
    val close: Double,
    val sma200: Double
)

data class IntradayQuote(
    val price: Double,
    val timestamp: Long,
    val previousClose: Double,
    val session: String,
    val delayedBySeconds: Int
) {
    val changeRate: Double
        get() = if (previousClose > 0) price / previousClose - 1.0 else 0.0
}

data class MarketSnapshot(
    val regime: Regime,
    val latestDate: String,
    val close: Double,
    val sma200: Double,
    val distance: Double,
    val spymPrice: Double,
    val sgovPrice: Double,
    val usdKrw: Double,
    val chart: List<ChartPoint>,
    val beta: BetaSnapshot? = null,
    val intraday: IntradayQuote? = null,
    val fetchedAt: Long,
    val fromCache: Boolean = false,
    val source: String = "Yahoo Finance 비공식 Chart API"
)

data class TakeProfitOrder(
    val threshold: Double,
    val fraction: Double,
    val quantity: Double
)

data class TakeProfitPlan(
    val orders: List<TakeProfitOrder>,
    val totalToSell: Double,
    val remaining: Double
)

enum class TradeType {
    BUY,
    SELL
}

enum class TqqqEntryStatus {
    START_ON_CROSS,
    VERIFY_EXISTING_ENTRY,
    LATE_ENTRY_NO_TQQQ,
    WAIT_FOR_CROSS,
    POSITION_EXISTS
}

data class PortfolioTrade(
    val id: Long,
    val date: String,
    val type: TradeType,
    val quantity: Double,
    val priceUsd: Double,
    val fxKrwPerUsd: Double,
    val feeKrw: Double,
    val feeRatePercent: Double? = null,
    val entryCycleKey: String? = null,
    val entryDay: Int? = null,
    val ticker: String = "TQQQ"
)

data class SaleSettlement(
    val tradeId: Long,
    val grossProceedsKrw: Double,
    val feeKrw: Double,
    val netProceedsKrw: Double,
    val allocatedCostKrw: Double,
    val realizedPnlKrw: Double
)

data class PortfolioSummary(
    val remainingQuantity: Double,
    val remainingCostKrw: Double,
    val averagePriceUsd: Double?,
    val currentValueKrw: Double,
    val unrealizedPnlKrw: Double,
    val realizedPnlKrw: Double,
    val realizedPnlForTaxYearKrw: Double,
    val totalPnlKrw: Double,
    val positionReturn: Double?,
    val cycleReturn: Double?,
    val cycleStartTradeId: Long?,
    val totalFeesKrw: Double,
    val saleSettlements: List<SaleSettlement>,
    val error: String? = null
)

data class TaxEstimate(
    val combinedGainKrw: Double,
    val taxableIncomeKrw: Double,
    val nationalTaxKrw: Double,
    val localTaxKrw: Double,
    val totalTaxKrw: Double
)

data class AllocationTarget(
    val ticker: String,
    val fraction: Double,
    val amountKrw: Double,
    val referencePriceUsd: Double,
    val quantity: Double,
    val currentQuantity: Double = 0.0
) {
    val orderQuantity: Double
        get() = quantity - currentQuantity
}

data class CapitalAllocationPlan(
    val title: String,
    val description: String,
    val targets: List<AllocationTarget>,
    val todayTqqqBuyKrw: Double? = null,
    val todayTqqqBuyQuantity: Double? = null,
    val todayCashUseKrw: Double? = null,
    val todaySgovSellQuantity: Double? = null,
    val fundingShortfallKrw: Double = 0.0,
    val entryDay: Int? = null,
    val entryCapitalKrw: Double? = null,
    val completedEntryDays: Set<Int> = emptySet(),
    val nextEntryDay: Int? = null
)

object StrategyEngine {
    val smallMilestones = listOf(0.10, 0.25, 0.50)

    fun simpleMovingAverage(values: List<Double>, period: Int): List<Double?> {
        if (period <= 0 || values.size < period) return List(values.size) { null }
        val output = MutableList<Double?>(values.size) { null }
        var sum = 0.0

        values.forEachIndexed { index, value ->
            sum += value
            if (index >= period) sum -= values[index - period]
            if (index >= period - 1) output[index] = sum / period
        }
        return output
    }

    fun deriveRegime(rows: List<PriceRow>): Regime {
        if (rows.size < 201) {
            return Regime(
                RegimeState.DATA_ERROR,
                "데이터 부족",
                "SMA200 판정에는 최소 201거래일이 필요합니다."
            )
        }

        val averages = simpleMovingAverage(rows.map { it.close }, 200)
        val latestIndex = rows.lastIndex
        val latest = rows[latestIndex]
        val previous = rows[latestIndex - 1]
        val latestSma = averages[latestIndex] ?: return dataError()
        val previousSma = averages[latestIndex - 1] ?: return dataError()

        val crossUp = previous.close <= previousSma && latest.close > latestSma
        val crossDown = previous.close >= previousSma && latest.close < latestSma

        if (crossDown) {
            return Regime(
                RegimeState.EXIT_PENDING,
                "대피 신호",
                "확정 종가가 SMA200을 아래로 이탈했습니다."
            )
        }
        if (crossUp) {
            return Regime(
                RegimeState.ENTRY_DAY_1,
                "1차 진입",
                "확정 종가가 SMA200을 위로 돌파했습니다."
            )
        }
        if (latest.close == latestSma) {
            return Regime(
                RegimeState.WAIT,
                "경계 대기",
                "종가와 SMA200이 같습니다. 원문에 동일값 규칙이 없어 신규 주문을 보류합니다."
            )
        }

        if (latest.close > latestSma) {
            var recentUp = -1
            var recentDown = -1
            for (index in 200..latestIndex) {
                val priorSma = averages[index - 1] ?: continue
                val currentSma = averages[index] ?: continue
                val priorClose = rows[index - 1].close
                val currentClose = rows[index].close
                if (priorClose <= priorSma && currentClose > currentSma) recentUp = index
                if (priorClose >= priorSma && currentClose < currentSma) recentDown = index
            }

            val sessions = latestIndex - recentUp
            if (recentUp > recentDown && sessions == 1) {
                return Regime(
                    RegimeState.ENTRY_DAY_2,
                    "2차 진입",
                    "상향 돌파 후 두 번째 거래일이며 종가가 SMA200 위입니다."
                )
            }
            if (recentUp > recentDown && sessions == 2) {
                return Regime(
                    RegimeState.ENTRY_DAY_3,
                    "3차 진입",
                    "상향 돌파 후 세 번째 거래일이며 종가가 SMA200 위입니다."
                )
            }
            return Regime(
                RegimeState.RISK_ON,
                "TQQQ 보유",
                "확정 종가가 SMA200 위에 있습니다."
            )
        }

        return Regime(
            RegimeState.SAFE,
            "SGOV 대기",
            "확정 종가가 SMA200 아래에 있습니다."
        )
    }

    fun buildSnapshot(
        rows: List<PriceRow>,
        spymPrice: Double,
        sgovPrice: Double,
        usdKrw: Double,
        qqqRows: List<PriceRow>? = null,
        intraday: IntradayQuote? = null,
        fetchedAt: Long = System.currentTimeMillis()
    ): MarketSnapshot {
        require(rows.size >= 201) { "SMA200 계산에 필요한 데이터가 부족합니다." }
        val averages = simpleMovingAverage(rows.map { it.close }, 200)
        val latestIndex = rows.lastIndex
        val latestSma = requireNotNull(averages[latestIndex])
        val chartStart = max(0, rows.size - 260)
        val chart = (chartStart..latestIndex).mapNotNull { index ->
            averages[index]?.let { average ->
                ChartPoint(rows[index].date, rows[index].close, average)
            }
        }

        return MarketSnapshot(
            regime = deriveRegime(rows),
            latestDate = rows[latestIndex].date,
            close = rows[latestIndex].close,
            sma200 = latestSma,
            distance = rows[latestIndex].close / latestSma - 1.0,
            spymPrice = spymPrice,
            sgovPrice = sgovPrice,
            usdKrw = usdKrw,
            chart = chart,
            beta = qqqRows?.let { runCatching { deriveBetaSnapshot(it, rows, intraday) }.getOrNull() },
            intraday = intraday,
            fetchedAt = fetchedAt
        )
    }

    fun deriveBetaSnapshot(
        qqqRows: List<PriceRow>,
        tqqqRows: List<PriceRow>,
        intraday: IntradayQuote? = null
    ): BetaSnapshot {
        require(qqqRows.size >= 201) { "QQQ SMA200 계산에 필요한 데이터가 부족합니다." }
        val averages = simpleMovingAverage(qqqRows.map { it.close }, 200)
        val tqqqByDate = tqqqRows.associateBy { it.date }
        require(tqqqByDate[qqqRows.last().date] != null) { "QQQ와 TQQQ 최신 거래일이 다릅니다." }

        fun upper(index: Int) = requireNotNull(averages[index]) * 1.02
        fun lower(index: Int) = requireNotNull(averages[index]) * 0.98

        var active = qqqRows[199].close > upper(199)
        var cycleStart = if (active) 199 else -1
        var exitedToday = false
        for (index in 200..qqqRows.lastIndex) {
            val crossedUpper = qqqRows[index - 1].close <= upper(index - 1) &&
                qqqRows[index].close > upper(index)
            if (!active && crossedUpper) {
                active = true
                cycleStart = index
            } else if (active && qqqRows[index].close < lower(index)) {
                exitedToday = index == qqqRows.lastIndex
                active = false
                cycleStart = -1
            }
        }

        val latestIndex = qqqRows.lastIndex
        val latest = qqqRows[latestIndex]
        val latestSma = requireNotNull(averages[latestIndex])
        if (!active) {
            val regime = if (exitedToday) {
                BetaRegime(
                    BetaState.EXIT_PENDING,
                    "전량 대피",
                    "TQQQ와 SPYM을 전량 매도하고 SGOV로 전환하세요.",
                    "QQQ 확정 종가가 SMA200 하단 −2% 밴드 아래로 이탈했습니다."
                )
            } else {
                BetaRegime(
                    BetaState.SAFE,
                    "SGOV 대기",
                    "SGOV를 유지하고 QQQ 상단 +2% 돌파를 기다리세요.",
                    "베타 상승 사이클 밖입니다."
                )
            }
            return BetaSnapshot(
                regime = regime,
                latestDate = latest.date,
                qqqClose = latest.close,
                sma200 = latestSma,
                upperBand = latestSma * 1.02,
                lowerBand = latestSma * 0.98,
                distance = latest.close / latestSma - 1.0
            )
        }

        val cycleDates = qqqRows.subList(cycleStart, latestIndex + 1).map { it.date }
        val cyclePrices = cycleDates.mapNotNull { date ->
            tqqqByDate[date]?.let { date to it.close }
        }
        require(cyclePrices.isNotEmpty()) { "베타 사이클의 TQQQ 데이터가 없습니다." }

        var peak = 0.0
        var trailingStopDate: String? = null
        cyclePrices.forEach { (date, price) ->
            peak = max(peak, price)
            if (trailingStopDate == null && price / peak - 1.0 <= -0.25) {
                trailingStopDate = date
            }
        }
        val latestTqqq = cyclePrices.last().second
        val drawdown = latestTqqq / peak - 1.0
        val intradayTrailingStop = trailingStopDate == null &&
            intraday?.price?.let { it / peak - 1.0 <= -0.25 } == true

        val entryPrices = cycleDates.take(3).mapNotNull { tqqqByDate[it]?.close }
        val averageEntryPrice = if (entryPrices.size == 3) {
            3.0 / entryPrices.sumOf { 1.0 / it }
        } else null
        val profitRate = averageEntryPrice?.let { latestTqqq / it - 1.0 }
        val profitHistory = averageEntryPrice?.let { average ->
            cyclePrices.drop(2).map { (_, price) -> price / average - 1.0 }
        }.orEmpty()
        val previousMaximum = profitHistory.dropLast(1).maxOrNull() ?: Double.NEGATIVE_INFINITY
        val maximumProfit = profitHistory.maxOrNull() ?: Double.NEGATIVE_INFINITY
        val completedMilestones = betaMilestones(maximumProfit).filter { maximumProfit >= it }
        val triggeredMilestones = completedMilestones.filter { previousMaximum < it }

        val entryDay = latestIndex - cycleStart + 1
        val regime = when {
            entryDay in 1..3 -> BetaRegime(
                BetaState.valueOf("ENTRY_DAY_$entryDay"),
                "${entryDay}차 진입",
                when (entryDay) {
                    1 -> "가용 진입자금의 1/3로 TQQQ 1차 매수를 확인하세요."
                    2 -> "남은 진입자금의 1/2로 TQQQ 2차 매수를 확인하세요."
                    else -> "남은 진입자금으로 TQQQ 3차 매수를 확인하세요."
                },
                "QQQ가 SMA200 상단 +2% 밴드를 돌파한 뒤 ${entryDay}번째 거래일입니다."
            )
            trailingStopDate == latest.date || intradayTrailingStop -> BetaRegime(
                BetaState.TRAILING_STOP,
                if (intradayTrailingStop) "TS 예비 경고" else "TS −25%",
                "TQQQ 잔고의 50%를 SPYM으로 전환할지 확인하세요.",
                if (intradayTrailingStop) {
                    "장중 TQQQ가 사이클 고점 대비 −25%에 닿았습니다. 확정 전 참고 신호입니다."
                } else {
                    "TQQQ 확정 종가가 사이클 고점 대비 −25%에 처음 도달했습니다."
                }
            )
            triggeredMilestones.isNotEmpty() -> BetaRegime(
                BetaState.TAKE_PROFIT,
                "부분익절 ${triggeredMilestones.joinToString("·") { "+${(it * 100).toInt()}%" }}",
                "해당 단계의 TQQQ를 SPYM으로 전환할지 확인하세요.",
                "3분할 확정 종가 참고 평단 대비 새 익절 단계에 도달했습니다."
            )
            else -> BetaRegime(
                BetaState.RISK_ON,
                "TQQQ 보유",
                "TQQQ를 보유하고 익절·TS·QQQ 하단 이탈을 감시하세요.",
                "QQQ가 하단 −2% 밴드를 이탈하지 않아 베타 상승 사이클이 유지됩니다."
            )
        }

        return BetaSnapshot(
            regime = regime,
            latestDate = latest.date,
            qqqClose = latest.close,
            sma200 = latestSma,
            upperBand = latestSma * 1.02,
            lowerBand = latestSma * 0.98,
            distance = latest.close / latestSma - 1.0,
            cycleStartDate = qqqRows[cycleStart].date,
            tqqqPeak = peak,
            tqqqDrawdown = drawdown,
            averageEntryPrice = averageEntryPrice,
            profitRate = profitRate,
            completedMilestones = completedMilestones,
            triggeredMilestones = triggeredMilestones,
            trailingStopDate = trailingStopDate,
            intradayTrailingStop = intradayTrailingStop
        )
    }

    private fun betaMilestones(maximumProfit: Double): List<Double> {
        val output = smallMilestones.toMutableList()
        var threshold = 1.0
        while (threshold <= maximumProfit) {
            output += threshold
            threshold *= 2.0
        }
        return output
    }

    fun profitRate(
        averagePriceUsd: Double,
        buyFx: Double,
        currentPriceUsd: Double,
        currentFx: Double
    ): Double? {
        if (listOf(averagePriceUsd, buyFx, currentPriceUsd, currentFx).any { !it.isFinite() || it <= 0 }) {
            return null
        }
        return (currentPriceUsd * currentFx) / (averagePriceUsd * buyFx) - 1.0
    }

    fun tqqqEntryStatus(state: RegimeState, hasTqqqPosition: Boolean): TqqqEntryStatus {
        if (hasTqqqPosition) return TqqqEntryStatus.POSITION_EXISTS
        return when (state) {
            RegimeState.ENTRY_DAY_1 -> TqqqEntryStatus.START_ON_CROSS
            RegimeState.ENTRY_DAY_2,
            RegimeState.ENTRY_DAY_3 -> TqqqEntryStatus.VERIFY_EXISTING_ENTRY
            RegimeState.RISK_ON -> TqqqEntryStatus.LATE_ENTRY_NO_TQQQ
            else -> TqqqEntryStatus.WAIT_FOR_CROSS
        }
    }

    fun capitalAllocationPlan(
        state: RegimeState,
        totalAssetsKrw: Double,
        tqqqPriceUsd: Double,
        spymPriceUsd: Double,
        sgovPriceUsd: Double,
        usdKrw: Double,
        entryCapitalKrw: Double? = null,
        completedEntryDays: Set<Int> = emptySet(),
        spentEntryKrw: Double = 0.0,
        currentTqqqQuantity: Double = 0.0,
        currentSgovQuantity: Double = 0.0,
        currentSpymQuantity: Double = 0.0,
        availableCashKrw: Double = 0.0,
        feeRatePercent: Double = 0.0
    ): CapitalAllocationPlan? {
        if (capitalAllocationError(
                state,
                totalAssetsKrw,
                tqqqPriceUsd,
                spymPriceUsd,
                sgovPriceUsd,
                usdKrw,
                spentEntryKrw,
                currentTqqqQuantity,
                currentSgovQuantity,
                currentSpymQuantity,
                availableCashKrw,
                feeRatePercent
            ) != null
        ) return null

        fun target(
            ticker: String,
            fraction: Double,
            priceUsd: Double,
            capitalKrw: Double = totalAssetsKrw,
            currentQuantity: Double = 0.0
        ): AllocationTarget {
            val amountKrw = capitalKrw * fraction
            val feeMultiplier = 1.0 + feeRatePercent / 100.0
            return AllocationTarget(
                ticker = ticker,
                fraction = fraction,
                amountKrw = amountKrw,
                referencePriceUsd = priceUsd,
                quantity = floor(amountKrw / (priceUsd * usdKrw * feeMultiplier)),
                currentQuantity = currentQuantity
            )
        }

        return when (state) {
            RegimeState.SAFE,
            RegimeState.EXIT_PENDING -> CapitalAllocationPlan(
                title = "SGOV 100%",
                description = "SMA200 아래 대피 구간이므로 TQQQ·SPYM은 0주, SGOV는 총자산 100%가 목표입니다.",
                targets = listOf(
                    target("TQQQ", 0.0, tqqqPriceUsd, currentQuantity = currentTqqqQuantity),
                    target("SGOV", 1.0, sgovPriceUsd, currentQuantity = currentSgovQuantity),
                    target("SPYM", 0.0, spymPriceUsd, currentQuantity = currentSpymQuantity)
                )
            )

            RegimeState.RISK_ON -> CapitalAllocationPlan(
                title = "SGOV 50% · SPYM 50%",
                description = "이미 SMA200 위인 신규 진입자는 TQQQ를 추격매수하지 않고 SGOV와 SPYM에 반씩 배분합니다.",
                targets = listOf(
                    target("SGOV", 0.5, sgovPriceUsd, currentQuantity = currentSgovQuantity),
                    target("SPYM", 0.5, spymPriceUsd, currentQuantity = currentSpymQuantity)
                )
            )

            RegimeState.ENTRY_DAY_1,
            RegimeState.ENTRY_DAY_2,
            RegimeState.ENTRY_DAY_3 -> {
                val day = when (state) {
                    RegimeState.ENTRY_DAY_1 -> 1
                    RegimeState.ENTRY_DAY_2 -> 2
                    else -> 3
                }
                val fixedCapital = entryCapitalKrw
                    ?.takeIf { it.isFinite() && it > 0.0 }
                    ?: totalAssetsKrw
                var completedCount = 0
                for (candidate in 1..3) {
                    if (candidate in completedEntryDays) completedCount = candidate else break
                }
                val normalizedCompleted = (1..completedCount).toSet()
                val nextDay = (completedCount + 1).takeIf { it <= 3 }
                val executionDay = nextDay?.takeIf { it <= day }
                val canExecuteToday = executionDay != null
                val spent = min(fixedCapital, max(0.0, spentEntryKrw))
                val remainingCash = max(0.0, fixedCapital - spent)
                val todayAmount = when {
                    !canExecuteToday -> 0.0
                    executionDay == 1 -> fixedCapital / 3.0
                    executionDay == 2 -> remainingCash / 2.0
                    else -> remainingCash
                }
                val projectedSpent = min(fixedCapital, spent + todayAmount)
                val cumulativeTqqqFraction = projectedSpent / fixedCapital
                val safeFraction = max(0.0, 1.0 - cumulativeTqqqFraction)
                val feeFraction = feeRatePercent / 100.0
                val todayQuantity = floor(
                    todayAmount / (tqqqPriceUsd * usdKrw * (1.0 + feeFraction))
                )
                val todayOrderCost =
                    todayQuantity * tqqqPriceUsd * usdKrw * (1.0 + feeFraction)
                val cashUse = min(availableCashKrw, todayOrderCost)
                val sgovFundingKrw = max(0.0, todayOrderCost - cashUse)
                val availableSgovValueKrw =
                    currentSgovQuantity * sgovPriceUsd * usdKrw * (1.0 - feeFraction)
                val fundingShortfall = max(0.0, sgovFundingKrw - availableSgovValueKrw)
                CapitalAllocationPlan(
                    title = when {
                        nextDay == null -> "TQQQ 분할매수 완료"
                        executionDay != null && executionDay < day -> "TQQQ ${executionDay}차 누락분 분할매수"
                        else -> "TQQQ ${executionDay ?: day}차 분할매수"
                    },
                    description = when {
                        nextDay == null ->
                            "1·2·3차 체결이 모두 등록됐습니다. 추가 TQQQ 매수 주문은 없습니다."
                        executionDay != null && executionDay < day ->
                            "${executionDay}차 등록을 놓쳤습니다. 누락분을 먼저 등록하면 다음 차수를 이어서 진행할 수 있습니다."
                        canExecuteToday && executionDay == 1 ->
                            "상향 돌파 1일 차입니다. 전체 가용자금의 1/3을 TQQQ로 옮깁니다."
                        canExecuteToday && executionDay == 2 ->
                            "상향 돌파 2일 차입니다. 실제 남은 진입자금의 1/2을 TQQQ로 옮깁니다."
                        canExecuteToday ->
                            "상향 돌파 3일 차입니다. 실제 남은 진입자금 전액을 TQQQ로 옮깁니다."
                        else ->
                            "${day}차 체결 완료가 기록되어 추가 주문이 없습니다."
                    },
                    targets = buildList {
                        if (cumulativeTqqqFraction > 0.0) {
                            add(
                                target(
                                    "TQQQ",
                                    cumulativeTqqqFraction,
                                    tqqqPriceUsd,
                                    fixedCapital,
                                    currentTqqqQuantity
                                )
                            )
                        }
                        if (safeFraction > 0.0) {
                            add(
                                target(
                                    "SGOV",
                                    safeFraction,
                                    sgovPriceUsd,
                                    fixedCapital,
                                    currentSgovQuantity
                                )
                            )
                        }
                    },
                    todayTqqqBuyKrw = todayAmount.takeIf { canExecuteToday && it > 0.0 },
                    todayTqqqBuyQuantity = if (canExecuteToday) {
                        todayQuantity
                    } else null,
                    todayCashUseKrw = cashUse.takeIf { canExecuteToday && it > 0.0 },
                    todaySgovSellQuantity = if (canExecuteToday && sgovFundingKrw > 0.0) {
                        min(
                            currentSgovQuantity,
                            ceil(
                                sgovFundingKrw /
                                    (sgovPriceUsd * usdKrw * (1.0 - feeFraction))
                            )
                        )
                    } else null,
                    fundingShortfallKrw = fundingShortfall.takeIf { canExecuteToday } ?: 0.0,
                    entryDay = day,
                    entryCapitalKrw = fixedCapital,
                    completedEntryDays = normalizedCompleted,
                    nextEntryDay = nextDay
                )
            }

            RegimeState.WAIT,
            RegimeState.DATA_ERROR -> null
        }
    }

    fun capitalAllocationError(
        state: RegimeState?,
        totalAssetsKrw: Double?,
        tqqqPriceUsd: Double?,
        spymPriceUsd: Double?,
        sgovPriceUsd: Double?,
        usdKrw: Double?,
        spentEntryKrw: Double?,
        currentTqqqQuantity: Double?,
        currentSgovQuantity: Double?,
        currentSpymQuantity: Double?,
        availableCashKrw: Double?,
        feeRatePercent: Double?
    ): String? = when {
        totalAssetsKrw == null || !totalAssetsKrw.isFinite() || totalAssetsKrw <= 0.0 ->
            "총자산이 없거나 0 이하라 신규 주문을 계산할 수 없습니다."
        tqqqPriceUsd == null || !tqqqPriceUsd.isFinite() || tqqqPriceUsd <= 0.0 ->
            "TQQQ 현재 시세를 받지 못해 신규 주문을 계산하지 않습니다. 새로고침 후 다시 확인하세요."
        spymPriceUsd == null || !spymPriceUsd.isFinite() || spymPriceUsd <= 0.0 ->
            "SPYM 현재 시세를 받지 못해 신규 주문을 계산하지 않습니다. 새로고침 후 다시 확인하세요."
        sgovPriceUsd == null || !sgovPriceUsd.isFinite() || sgovPriceUsd <= 0.0 ->
            "SGOV 현재 시세를 받지 못해 신규 주문을 계산하지 않습니다. 새로고침 후 다시 확인하세요."
        usdKrw == null || !usdKrw.isFinite() || usdKrw <= 0.0 ->
            "환율을 받지 못해 신규 주문을 계산하지 않습니다. 새로고침 후 다시 확인하세요."
        spentEntryKrw == null || !spentEntryKrw.isFinite() || spentEntryKrw < 0.0 ->
            "분할매수 사용금액 기록이 올바르지 않습니다. 거래내역을 확인하세요."
        currentTqqqQuantity == null || !currentTqqqQuantity.isFinite() || currentTqqqQuantity < 0.0 ->
            "TQQQ 보유수량 기록이 올바르지 않습니다. 거래내역을 확인하세요."
        currentSgovQuantity == null || !currentSgovQuantity.isFinite() || currentSgovQuantity < 0.0 ->
            "SGOV 보유수량 기록이 올바르지 않습니다. 거래내역을 확인하세요."
        currentSpymQuantity == null || !currentSpymQuantity.isFinite() || currentSpymQuantity < 0.0 ->
            "SPYM 보유수량 기록이 올바르지 않습니다. 거래내역을 확인하세요."
        availableCashKrw == null || !availableCashKrw.isFinite() || availableCashKrw < 0.0 ->
            "사용 가능 현금 기록이 올바르지 않습니다. 계좌 입력값을 확인하세요."
        feeRatePercent == null || !feeRatePercent.isFinite() || feeRatePercent < 0.0 || feeRatePercent >= 100.0 ->
            "수수료율은 0% 이상 100% 미만으로 입력하세요."
        state == RegimeState.WAIT ->
            "확정 종가가 SMA200 경계에 있어 방향이 정해질 때까지 신규 주문을 보류합니다."
        state == RegimeState.DATA_ERROR || state == null ->
            "확정 시장 상태를 계산하지 못해 신규 주문을 보류합니다. 데이터를 새로고침하세요."
        else -> null
    }

    fun milestones(profitRate: Double): List<Double> {
        val largeCount = max(3, floor(max(profitRate, 3.0)).toInt())
        return smallMilestones + (1..largeCount).map { it.toDouble() }
    }

    fun completedEntryDays(trades: List<PortfolioTrade>, cycleKey: String?): Set<Int> =
        trades.filter {
            it.ticker == "TQQQ" && it.type == TradeType.BUY && it.entryCycleKey == cycleKey
        }.mapNotNull { it.entryDay }.toSet()

    fun takeProfitPlan(
        quantity: Double,
        profitRate: Double,
        completed: Set<Double>,
        fractional: Boolean
    ): TakeProfitPlan {
        if (!quantity.isFinite() || quantity <= 0 || !profitRate.isFinite()) {
            return TakeProfitPlan(emptyList(), 0.0, max(0.0, quantity.takeIf { it.isFinite() } ?: 0.0))
        }

        var remaining = quantity
        val orders = milestones(profitRate)
            .filter { profitRate >= it && it !in completed }
            .sorted()
            .mapNotNull { threshold ->
                val fraction = if (threshold < 1.0) 0.10 else 0.50
                val raw = remaining * fraction
                val sell = if (fractional) {
                    round(raw * 1_000_000.0) / 1_000_000.0
                } else {
                    floor(raw + 0.5)
                }
                if (sell <= 0) return@mapNotNull null
                val bounded = min(remaining, sell)
                if (bounded <= 0) return@mapNotNull null
                remaining = round((remaining - bounded) * 1_000_000.0) / 1_000_000.0
                TakeProfitOrder(threshold, fraction, bounded)
            }

        return TakeProfitPlan(
            orders = orders,
            totalToSell = round((quantity - remaining) * 1_000_000.0) / 1_000_000.0,
            remaining = remaining
        )
    }

    /** 달러 이동평균 원가 방식의 TQQQ 계좌 추적 계산입니다. */
    fun portfolioSummary(
        trades: List<PortfolioTrade>,
        currentPriceUsd: Double,
        currentFx: Double,
        taxYear: Int
    ): PortfolioSummary {
        if (!currentPriceUsd.isFinite() || currentPriceUsd < 0) {
            return emptyPortfolio("현재 달러 시세가 올바르지 않습니다.")
        }

        var quantity = 0.0
        var remainingCost = 0.0
        var remainingPurchaseAmount = 0.0
        var realized = 0.0
        var realizedForYear = 0.0
        var totalBuyCost = 0.0
        var totalSellProceeds = 0.0
        var cycleBuyCost = 0.0
        var cycleSellProceeds = 0.0
        var cycleStartTradeId: Long? = null
        var fees = 0.0
        val saleSettlements = mutableListOf<SaleSettlement>()

        trades.sortedWith(compareBy<PortfolioTrade> { it.date }.thenBy { it.id }).forEach { trade ->
            if (trade.date.length < 4 ||
                listOfNotNull(
                    trade.quantity,
                    trade.priceUsd,
                    trade.fxKrwPerUsd,
                    trade.feeKrw,
                    trade.feeRatePercent
                )
                    .any { !it.isFinite() } ||
                trade.quantity <= 0 || trade.priceUsd <= 0 || trade.fxKrwPerUsd <= 0 ||
                trade.feeKrw < 0 || (trade.feeRatePercent != null && trade.feeRatePercent < 0)
            ) {
                return emptyPortfolio("거래내역에 올바르지 않은 값이 있습니다.")
            }

            fees += trade.feeKrw
            when (trade.type) {
                TradeType.BUY -> {
                    val cost = trade.quantity * trade.priceUsd + trade.feeKrw
                    if (quantity < 1e-8) cycleStartTradeId = trade.id
                    quantity += trade.quantity
                    remainingCost += cost
                    remainingPurchaseAmount += trade.quantity * trade.priceUsd
                    totalBuyCost += cost
                    cycleBuyCost += cost
                }

                TradeType.SELL -> {
                    if (trade.quantity > quantity + 1e-8) {
                        return emptyPortfolio("${trade.date} 매도 수량이 당시 보유 수량보다 많습니다.")
                    }
                    val averageCost = if (quantity > 0) remainingCost / quantity else 0.0
                    val averagePurchasePrice = if (quantity > 0) remainingPurchaseAmount / quantity else 0.0
                    val allocatedCost = averageCost * trade.quantity
                    val grossProceeds = trade.quantity * trade.priceUsd
                    val proceeds = grossProceeds - trade.feeKrw
                    val pnl = proceeds - allocatedCost
                    saleSettlements += SaleSettlement(
                        tradeId = trade.id,
                        grossProceedsKrw = grossProceeds,
                        feeKrw = trade.feeKrw,
                        netProceedsKrw = proceeds,
                        allocatedCostKrw = allocatedCost,
                        realizedPnlKrw = pnl
                    )
                    quantity -= trade.quantity
                    remainingCost -= allocatedCost
                    remainingPurchaseAmount -= averagePurchasePrice * trade.quantity
                    realized += pnl
                    totalSellProceeds += proceeds
                    cycleSellProceeds += proceeds
                    if (trade.date.take(4).toIntOrNull() == taxYear) realizedForYear += pnl
                    if (quantity < 1e-8) {
                        quantity = 0.0
                        remainingCost = 0.0
                        remainingPurchaseAmount = 0.0
                        cycleBuyCost = 0.0
                        cycleSellProceeds = 0.0
                        cycleStartTradeId = null
                    }
                }
            }
        }

        val currentValue = quantity * currentPriceUsd
        val unrealized = currentValue - remainingCost
        val totalPnl = totalSellProceeds + currentValue - totalBuyCost
        return PortfolioSummary(
            remainingQuantity = quantity,
            remainingCostKrw = remainingCost,
            averagePriceUsd = if (quantity > 0.0) remainingPurchaseAmount / quantity else null,
            currentValueKrw = currentValue,
            unrealizedPnlKrw = unrealized,
            realizedPnlKrw = realized,
            realizedPnlForTaxYearKrw = realizedForYear,
            totalPnlKrw = totalPnl,
            positionReturn = if (remainingPurchaseAmount > 0) {
                currentValue / remainingPurchaseAmount - 1.0
            } else null,
            cycleReturn = if (cycleBuyCost > 0) {
                (cycleSellProceeds + currentValue - cycleBuyCost) / cycleBuyCost
            } else null,
            cycleStartTradeId = cycleStartTradeId,
            totalFeesKrw = fees,
            saleSettlements = saleSettlements
        )
    }

    fun feeFromRate(
        quantity: Double,
        priceUsd: Double,
        fxKrwPerUsd: Double,
        feeRatePercent: Double
    ): Double {
        if (listOf(quantity, priceUsd, fxKrwPerUsd, feeRatePercent).any { !it.isFinite() } ||
            quantity <= 0 || priceUsd <= 0 || fxKrwPerUsd <= 0 || feeRatePercent < 0
        ) return Double.NaN
        return quantity * priceUsd * feeRatePercent / 100.0
    }

    fun taxEstimate(
        realizedForeignStockGainKrw: Double,
        otherForeignStockGainKrw: Double,
        basicDeductionKrw: Double = 2_500_000.0
    ): TaxEstimate {
        val combined = realizedForeignStockGainKrw + otherForeignStockGainKrw
        val taxable = max(0.0, combined - max(0.0, basicDeductionKrw))
        val national = taxable * 0.20
        val local = taxable * 0.02
        return TaxEstimate(
            combinedGainKrw = combined,
            taxableIncomeKrw = taxable,
            nationalTaxKrw = national,
            localTaxKrw = local,
            totalTaxKrw = national + local
        )
    }

    private fun emptyPortfolio(error: String) = PortfolioSummary(
        remainingQuantity = 0.0,
        remainingCostKrw = 0.0,
        averagePriceUsd = null,
        currentValueKrw = 0.0,
        unrealizedPnlKrw = 0.0,
        realizedPnlKrw = 0.0,
        realizedPnlForTaxYearKrw = 0.0,
        totalPnlKrw = 0.0,
        positionReturn = null,
        cycleReturn = null,
        cycleStartTradeId = null,
        totalFeesKrw = 0.0,
        saleSettlements = emptyList(),
        error = error
    )

    fun action(state: RegimeState, hasTqqqPosition: Boolean? = null): String {
        if (state == RegimeState.RISK_ON && hasTqqqPosition == false) {
            return "TQQQ가 0주이므로 총자산을 SGOV 50%·SPYM 50%로 나눈 매수수량을 확인하세요."
        }
        return when (state) {
        RegimeState.SAFE -> "SGOV를 유지하고 다음 상향 돌파를 기다리세요."
        RegimeState.ENTRY_DAY_1 -> "가용 진입자금의 1/3만 TQQQ 매수를 검토하세요."
        RegimeState.ENTRY_DAY_2 -> "남은 진입 현금의 1/2로 2차 매수를 검토하세요."
        RegimeState.ENTRY_DAY_3 -> "남은 진입 현금으로 3차 매수를 검토하세요."
        RegimeState.RISK_ON -> "TQQQ를 보유하고 다음 익절 단계와 하향 이탈을 감시하세요."
        RegimeState.EXIT_PENDING -> "TQQQ와 SPYM 전량 매도 후 SGOV 전환을 검토하세요."
        RegimeState.WAIT -> "방향이 확정될 때까지 새로운 주문을 보류하세요."
        RegimeState.DATA_ERROR -> "데이터를 확인하기 전에는 행동하지 마세요."
        }
    }

    private fun dataError() = Regime(
        RegimeState.DATA_ERROR,
        "데이터 오류",
        "종가 또는 SMA200 값이 올바르지 않습니다."
    )
}

object MarketSessionFilter {
    private const val CLOSE_CONFIRMATION_DELAY_SECONDS = 5 * 60L

    fun isCompletedDailyBar(
        barTimestampSeconds: Long,
        regularSessionStartSeconds: Long,
        regularSessionEndSeconds: Long,
        nowSeconds: Long
    ): Boolean {
        if (regularSessionStartSeconds <= 0 || regularSessionEndSeconds <= regularSessionStartSeconds) {
            return true
        }
        val isCurrentSessionBar = barTimestampSeconds >= regularSessionStartSeconds
        return !isCurrentSessionBar ||
            nowSeconds >= regularSessionEndSeconds + CLOSE_CONFIRMATION_DELAY_SECONDS
    }
}
