package com.signal200.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StrategyEngineTest {
    private fun rows(closes: List<Double>) = closes.mapIndexed { index, close ->
        PriceRow("2026-${index.toString().padStart(3, '0')}", close)
    }

    @Test
    fun `SMA는 지정 기간의 단순평균이다`() {
        assertEquals(
            listOf(null, null, 2.0, 3.0),
            StrategyEngine.simpleMovingAverage(listOf(1.0, 2.0, 3.0, 4.0), 3)
        )
    }

    @Test
    fun `상향 돌파는 1차 진입이다`() {
        val closes = List(200) { 100.0 } + listOf(99.0, 101.0)
        assertEquals(RegimeState.ENTRY_DAY_1, StrategyEngine.deriveRegime(rows(closes)).state)
    }

    @Test
    fun `하향 이탈은 대피 신호다`() {
        val closes = List(200) { 100.0 } + listOf(101.0, 99.0)
        assertEquals(RegimeState.EXIT_PENDING, StrategyEngine.deriveRegime(rows(closes)).state)
    }

    @Test
    fun `상향 돌파 다음 거래일은 2차 진입이다`() {
        val closes = List(200) { 100.0 } + listOf(99.0, 101.0, 102.0)
        assertEquals(RegimeState.ENTRY_DAY_2, StrategyEngine.deriveRegime(rows(closes)).state)
    }

    @Test
    fun `SMA와 같은 종가는 원문 미정으로 주문을 보류한다`() {
        val closes = List(201) { 100.0 }
        assertEquals(RegimeState.WAIT, StrategyEngine.deriveRegime(rows(closes)).state)
    }

    @Test
    fun `상향 돌파 다음 날 SMA와 같으면 신규 주문을 보류한다`() {
        val closes = List(200) { 100.0 } + listOf(99.0, 101.0, 100.0)
        assertEquals(RegimeState.WAIT, StrategyEngine.deriveRegime(rows(closes)).state)
    }

    @Test
    fun `베타는 QQQ 상단 2퍼센트 돌파를 별도 진입 신호로 계산한다`() {
        val qqq = rows(List(200) { 100.0 } + 103.0)
        val tqqq = rows(List(201) { 50.0 })

        val beta = StrategyEngine.deriveBetaSnapshot(qqq, tqqq)

        assertEquals(BetaState.ENTRY_DAY_1, beta.regime.state)
        assertTrue(beta.upperBand < beta.qqqClose)
    }

    @Test
    fun `베타는 진입 후 밴드 안에서 보유하고 하단 2퍼센트 이탈 시 대피한다`() {
        val holdQqq = rows(List(200) { 100.0 } + listOf(103.0, 100.0))
        val exitQqq = rows(List(200) { 100.0 } + listOf(103.0, 100.0, 97.0))

        assertEquals(
            BetaState.ENTRY_DAY_2,
            StrategyEngine.deriveBetaSnapshot(holdQqq, rows(List(202) { 50.0 })).regime.state
        )
        assertEquals(
            BetaState.EXIT_PENDING,
            StrategyEngine.deriveBetaSnapshot(exitQqq, rows(List(203) { 50.0 })).regime.state
        )
    }

    @Test
    fun `베타 TS는 사이클 TQQQ 고점 대비 25퍼센트 하락에 최초 발동한다`() {
        val qqq = rows(List(200) { 100.0 } + List(4) { 103.0 })
        val tqqq = rows(List(200) { 50.0 } + listOf(100.0, 110.0, 120.0, 90.0))

        val beta = StrategyEngine.deriveBetaSnapshot(qqq, tqqq)

        assertEquals(BetaState.TRAILING_STOP, beta.regime.state)
        assertEquals(-0.25, beta.tqqqDrawdown!!, 1e-9)
        assertEquals(qqq.last().date, beta.trailingStopDate)
    }

    @Test
    fun `베타 대익절은 100 200 400 퍼센트 등비 단계다`() {
        val qqq = rows(List(200) { 100.0 } + List(4) { 103.0 })
        val tqqq = rows(List(200) { 50.0 } + listOf(50.0, 50.0, 50.0, 250.0))

        val beta = StrategyEngine.deriveBetaSnapshot(qqq, tqqq)

        assertEquals(BetaState.TAKE_PROFIT, beta.regime.state)
        assertEquals(listOf(0.10, 0.25, 0.50, 1.0, 2.0, 4.0), beta.triggeredMilestones)
    }

    @Test
    fun `TQQQ가 없으면 상향 돌파 첫날에만 정규 신규진입을 시작한다`() {
        assertEquals(
            TqqqEntryStatus.WAIT_FOR_CROSS,
            StrategyEngine.tqqqEntryStatus(RegimeState.SAFE, hasTqqqPosition = false)
        )
        assertEquals(
            TqqqEntryStatus.LATE_ENTRY_NO_TQQQ,
            StrategyEngine.tqqqEntryStatus(RegimeState.RISK_ON, hasTqqqPosition = false)
        )
        assertEquals(
            TqqqEntryStatus.START_ON_CROSS,
            StrategyEngine.tqqqEntryStatus(RegimeState.ENTRY_DAY_1, hasTqqqPosition = false)
        )
        assertEquals(
            TqqqEntryStatus.VERIFY_EXISTING_ENTRY,
            StrategyEngine.tqqqEntryStatus(RegimeState.ENTRY_DAY_2, hasTqqqPosition = false)
        )
    }

    @Test
    fun `SMA 위라도 TQQQ가 0주면 현재 행동은 반반 매수다`() {
        assertTrue(
            StrategyEngine.action(RegimeState.RISK_ON, hasTqqqPosition = false)
                .contains("SGOV 50%·SPYM 50%")
        )
        assertTrue(
            StrategyEngine.action(RegimeState.RISK_ON, hasTqqqPosition = true)
                .contains("TQQQ를 보유")
        )
    }

    @Test
    fun `SMA 아래 신규 자금은 SGOV 전액이다`() {
        val plan = StrategyEngine.capitalAllocationPlan(
            state = RegimeState.SAFE,
            totalAssetsKrw = 30_000_000.0,
            tqqqPriceUsd = 50.0,
            spymPriceUsd = 70.0,
            sgovPriceUsd = 100.0,
            usdKrw = 1_500.0
        )!!

        assertEquals(listOf("TQQQ", "SGOV", "SPYM"), plan.targets.map { it.ticker })
        assertEquals(30_000_000.0, plan.targets[1].amountKrw, 0.0)
        assertEquals(200.0, plan.targets[1].quantity, 1e-9)
        assertEquals(0.0, plan.targets.last().quantity, 0.0)
    }

    @Test
    fun `이미 SMA 위인 신규 진입은 SGOV와 SPYM 반반이다`() {
        val plan = StrategyEngine.capitalAllocationPlan(
            state = RegimeState.RISK_ON,
            totalAssetsKrw = 30_000_000.0,
            tqqqPriceUsd = 50.0,
            spymPriceUsd = 100.0,
            sgovPriceUsd = 100.0,
            usdKrw = 1_500.0
        )!!

        assertEquals(listOf("SGOV", "SPYM"), plan.targets.map { it.ticker })
        assertEquals(listOf(15_000_000.0, 15_000_000.0), plan.targets.map { it.amountKrw })
    }

    @Test
    fun `SMA 아래 통합 주문은 TQQQ와 SPYM을 전량 매도로 계산한다`() {
        val plan = StrategyEngine.capitalAllocationPlan(
            state = RegimeState.SAFE,
            totalAssetsKrw = 10_000.0,
            tqqqPriceUsd = 50.0,
            spymPriceUsd = 80.0,
            sgovPriceUsd = 100.0,
            usdKrw = 1.0,
            currentTqqqQuantity = 12.0,
            currentSgovQuantity = 50.0,
            currentSpymQuantity = 10.0
        )!!

        assertEquals(-12.0, plan.targets.first { it.ticker == "TQQQ" }.orderQuantity, 0.0)
        assertEquals(-10.0, plan.targets.first { it.ticker == "SPYM" }.orderQuantity, 0.0)
        assertEquals(50.0, plan.targets.first { it.ticker == "SGOV" }.orderQuantity, 0.0)
    }

    @Test
    fun `자동 배분과 TQQQ 진입수량은 정수 주식으로 내림한다`() {
        val allocation = StrategyEngine.capitalAllocationPlan(
            state = RegimeState.RISK_ON,
            totalAssetsKrw = 1_000.0,
            tqqqPriceUsd = 70.0,
            spymPriceUsd = 300.0,
            sgovPriceUsd = 90.0,
            usdKrw = 1.0
        )!!
        val entry = StrategyEngine.capitalAllocationPlan(
            state = RegimeState.ENTRY_DAY_3,
            totalAssetsKrw = 1_000.0,
            tqqqPriceUsd = 70.0,
            spymPriceUsd = 300.0,
            sgovPriceUsd = 90.0,
            usdKrw = 1.0,
            availableCashKrw = 1_000.0
        )!!

        assertEquals(5.0, allocation.targets.first { it.ticker == "SGOV" }.quantity, 0.0)
        assertEquals(1.0, allocation.targets.first { it.ticker == "SPYM" }.quantity, 0.0)
        assertEquals(4.0, entry.todayTqqqBuyQuantity!!, 0.0)
    }

    @Test
    fun `정수 주문은 수수료까지 포함해 가용예산을 넘지 않는다`() {
        val plan = StrategyEngine.capitalAllocationPlan(
            state = RegimeState.ENTRY_DAY_1,
            totalAssetsKrw = 3_000.0,
            tqqqPriceUsd = 100.0,
            spymPriceUsd = 300.0,
            sgovPriceUsd = 100.0,
            usdKrw = 1.0,
            availableCashKrw = 3_000.0,
            feeRatePercent = 1.0
        )!!

        assertEquals(9.0, plan.todayTqqqBuyQuantity!!, 0.0)
        assertEquals(909.0, plan.todayCashUseKrw!!, 1e-9)
    }

    @Test
    fun `신규 주문을 막은 시세 종목을 정확히 알려준다`() {
        val error = StrategyEngine.capitalAllocationError(
            state = RegimeState.RISK_ON,
            totalAssetsKrw = 10_000.0,
            tqqqPriceUsd = 50.0,
            spymPriceUsd = 0.0,
            sgovPriceUsd = 100.0,
            usdKrw = 1.0,
            spentEntryKrw = 0.0,
            currentTqqqQuantity = 0.0,
            currentSgovQuantity = 0.0,
            currentSpymQuantity = 0.0,
            availableCashKrw = 10_000.0,
            feeRatePercent = 0.0
        )

        assertTrue(error!!.startsWith("SPYM 현재 시세"))
    }

    @Test
    fun `상향 돌파는 삼일 동안 TQQQ 목표 비중을 늘린다`() {
        val dayTwo = StrategyEngine.capitalAllocationPlan(
            state = RegimeState.ENTRY_DAY_2,
            totalAssetsKrw = 33_000_000.0,
            tqqqPriceUsd = 50.0,
            spymPriceUsd = 100.0,
            sgovPriceUsd = 100.0,
            usdKrw = 1_500.0,
            entryCapitalKrw = 30_000_000.0,
            completedEntryDays = setOf(1),
            spentEntryKrw = 10_000_000.0
        )!!

        assertEquals(10_000_000.0, dayTwo.todayTqqqBuyKrw!!, 0.01)
        assertEquals(20_000_000.0, dayTwo.targets.first { it.ticker == "TQQQ" }.amountKrw, 0.01)
        assertEquals(10_000_000.0, dayTwo.targets.first { it.ticker == "SGOV" }.amountKrw, 0.01)
    }

    @Test
    fun `2차와 3차 예산은 실제 남은 진입자금으로 계산한다`() {
        val dayTwo = StrategyEngine.capitalAllocationPlan(
            state = RegimeState.ENTRY_DAY_2,
            totalAssetsKrw = 30_000_000.0,
            tqqqPriceUsd = 50.0,
            spymPriceUsd = 100.0,
            sgovPriceUsd = 100.0,
            usdKrw = 1_500.0,
            entryCapitalKrw = 30_000_000.0,
            completedEntryDays = setOf(1),
            spentEntryKrw = 10_100_000.0
        )!!
        val dayThree = StrategyEngine.capitalAllocationPlan(
            state = RegimeState.ENTRY_DAY_3,
            totalAssetsKrw = 30_000_000.0,
            tqqqPriceUsd = 50.0,
            spymPriceUsd = 100.0,
            sgovPriceUsd = 100.0,
            usdKrw = 1_500.0,
            entryCapitalKrw = 30_000_000.0,
            completedEntryDays = setOf(1, 2),
            spentEntryKrw = 20_050_000.0
        )!!

        assertEquals(9_950_000.0, dayTwo.todayTqqqBuyKrw!!, 0.01)
        assertEquals(9_950_000.0, dayThree.todayTqqqBuyKrw!!, 0.01)
    }

    @Test
    fun `3일차에는 누락한 2차부터 이어서 등록한다`() {
        val plan = StrategyEngine.capitalAllocationPlan(
            state = RegimeState.ENTRY_DAY_3,
            totalAssetsKrw = 3_000.0,
            tqqqPriceUsd = 100.0,
            spymPriceUsd = 100.0,
            sgovPriceUsd = 100.0,
            usdKrw = 1.0,
            entryCapitalKrw = 3_000.0,
            completedEntryDays = setOf(1),
            spentEntryKrw = 1_000.0,
            availableCashKrw = 2_000.0
        )!!

        assertEquals(2, plan.nextEntryDay)
        assertEquals(1_000.0, plan.todayTqqqBuyKrw!!, 0.01)
    }

    @Test
    fun `체결 완료한 TQQQ 보유분을 다시 매수 주문으로 계산하지 않는다`() {
        val plan = StrategyEngine.capitalAllocationPlan(
            state = RegimeState.ENTRY_DAY_1,
            totalAssetsKrw = 3_000.0,
            tqqqPriceUsd = 100.0,
            spymPriceUsd = 100.0,
            sgovPriceUsd = 100.0,
            usdKrw = 1.0,
            entryCapitalKrw = 3_000.0,
            completedEntryDays = setOf(1, 2, 3),
            spentEntryKrw = 3_000.0,
            currentTqqqQuantity = 30.0
        )!!

        assertEquals(null, plan.todayTqqqBuyQuantity)
        assertEquals(0.0, plan.targets.first { it.ticker == "TQQQ" }.orderQuantity, 0.0)
        assertEquals("TQQQ 분할매수 완료", plan.title)
    }

    @Test
    fun `TQQQ 매수재원은 현금을 먼저 쓰고 부족분만 SGOV 매도로 표시한다`() {
        val plan = StrategyEngine.capitalAllocationPlan(
            state = RegimeState.ENTRY_DAY_1,
            totalAssetsKrw = 30_000_000.0,
            tqqqPriceUsd = 50.0,
            spymPriceUsd = 100.0,
            sgovPriceUsd = 100.0,
            usdKrw = 1_500.0,
            entryCapitalKrw = 30_000_000.0,
            currentSgovQuantity = 100.0,
            availableCashKrw = 4_000_000.0
        )!!

        assertEquals(4_000_000.0, plan.todayCashUseKrw!!, 0.01)
        assertEquals(40.0, plan.todaySgovSellQuantity!!, 1e-9)
        assertEquals(0.0, plan.fundingShortfallKrw, 0.0)
    }

    @Test
    fun `입력된 현금과 SGOV가 부족하면 진입자금 부족액을 표시한다`() {
        val plan = StrategyEngine.capitalAllocationPlan(
            state = RegimeState.ENTRY_DAY_1,
            totalAssetsKrw = 30_000_000.0,
            tqqqPriceUsd = 50.0,
            spymPriceUsd = 100.0,
            sgovPriceUsd = 100.0,
            usdKrw = 1_500.0,
            entryCapitalKrw = 30_000_000.0,
            currentSgovQuantity = 20.0,
            availableCashKrw = 4_000_000.0
        )!!

        assertEquals(2_975_000.0, plan.fundingShortfallKrw, 0.01)
    }

    @Test
    fun `SGOV와 SPYM은 현재 보유수량을 뺀 실제 조정수량을 계산한다`() {
        val plan = StrategyEngine.capitalAllocationPlan(
            state = RegimeState.RISK_ON,
            totalAssetsKrw = 30_000_000.0,
            tqqqPriceUsd = 50.0,
            spymPriceUsd = 100.0,
            sgovPriceUsd = 100.0,
            usdKrw = 1_500.0,
            currentSgovQuantity = 80.0,
            currentSpymQuantity = 120.0
        )!!

        assertEquals(20.0, plan.targets.first { it.ticker == "SGOV" }.orderQuantity, 1e-9)
        assertEquals(-20.0, plan.targets.first { it.ticker == "SPYM" }.orderQuantity, 1e-9)
    }

    @Test
    fun `이전 차수 완료 기록이 없으면 누락 차수 주문부터 만든다`() {
        val dayTwo = StrategyEngine.capitalAllocationPlan(
            state = RegimeState.ENTRY_DAY_2,
            totalAssetsKrw = 30_000_000.0,
            tqqqPriceUsd = 50.0,
            spymPriceUsd = 100.0,
            sgovPriceUsd = 100.0,
            usdKrw = 1_500.0,
            entryCapitalKrw = 30_000_000.0,
            completedEntryDays = emptySet()
        )!!

        assertEquals(10_000_000.0, dayTwo.todayTqqqBuyKrw!!, 0.01)
        assertEquals(1, dayTwo.nextEntryDay)
        assertEquals(listOf("TQQQ", "SGOV"), dayTwo.targets.map { it.ticker })
    }

    @Test
    fun `환율을 반영한 수익률을 계산한다`() {
        val result = StrategyEngine.profitRate(50.0, 1300.0, 60.0, 1400.0)!!
        assertTrue(kotlin.math.abs(result - 0.2923076923) < 1e-9)
    }

    @Test
    fun `소익절은 남은 수량 기준으로 순차 계산한다`() {
        val plan = StrategyEngine.takeProfitPlan(100.0, 0.30, emptySet(), fractional = false)
        assertEquals(listOf(10.0, 9.0), plan.orders.map { it.quantity })
        assertEquals(81.0, plan.remaining, 0.0)
    }

    @Test
    fun `완료한 익절 단계는 반복하지 않는다`() {
        val plan = StrategyEngine.takeProfitPlan(90.0, 0.30, setOf(0.10), fractional = false)
        assertEquals(listOf(0.25), plan.orders.map { it.threshold })
    }

    @Test
    fun `대익절은 남은 수량 절반을 반올림한다`() {
        val plan = StrategyEngine.takeProfitPlan(
            73.0,
            1.10,
            setOf(0.10, 0.25, 0.50),
            fractional = false
        )
        assertEquals(37.0, plan.orders.first().quantity, 0.0)
        assertEquals(36.0, plan.remaining, 0.0)
    }

    @Test
    fun `장중 참고가는 전일 종가 대비 등락률을 계산한다`() {
        val quote = IntradayQuote(
            price = 102.0,
            timestamp = 1_000L,
            previousClose = 100.0,
            session = "정규장",
            delayedBySeconds = 0
        )
        assertEquals(0.02, quote.changeRate, 1e-12)
    }

    @Test
    fun `거래원장은 달러 체결가와 수수료 및 기존 익절을 반영한다`() {
        val trades = listOf(
            PortfolioTrade(1, "2026-01-02", TradeType.BUY, 100.0, 50.0, 1.0, 10.0),
            PortfolioTrade(2, "2026-06-02", TradeType.SELL, 10.0, 75.0, 1.0, 5.0)
        )
        val result = StrategyEngine.portfolioSummary(
            trades = trades,
            currentPriceUsd = 70.0,
            currentFx = 1_350.0,
            taxYear = 2026
        )

        assertEquals(90.0, result.remainingQuantity, 1e-9)
        assertEquals(244.0, result.realizedPnlKrw, 1e-6)
        assertEquals(244.0, result.realizedPnlForTaxYearKrw, 1e-6)
        assertEquals(1_791.0, result.unrealizedPnlKrw, 1e-6)
        assertEquals(2_035.0, result.totalPnlKrw, 1e-6)
        assertEquals(15.0, result.totalFeesKrw, 1e-6)
    }

    @Test
    fun `예상세액은 손익통산과 기본공제 후 22퍼센트다`() {
        val estimate = StrategyEngine.taxEstimate(
            realizedForeignStockGainKrw = 10_000_000.0,
            otherForeignStockGainKrw = -2_000_000.0
        )
        assertEquals(8_000_000.0, estimate.combinedGainKrw, 0.0)
        assertEquals(5_500_000.0, estimate.taxableIncomeKrw, 0.0)
        assertEquals(1_100_000.0, estimate.nationalTaxKrw, 0.0)
        assertEquals(110_000.0, estimate.localTaxKrw, 0.0)
        assertEquals(1_210_000.0, estimate.totalTaxKrw, 0.0)
    }

    @Test
    fun `보유량보다 많이 매도하면 오류를 반환한다`() {
        val result = StrategyEngine.portfolioSummary(
            trades = listOf(
                PortfolioTrade(1, "2026-01-02", TradeType.BUY, 1.0, 50.0, 1_300.0, 0.0),
                PortfolioTrade(2, "2026-01-03", TradeType.SELL, 2.0, 60.0, 1_300.0, 0.0)
            ),
            currentPriceUsd = 60.0,
            currentFx = 1_300.0,
            taxYear = 2026
        )
        assertTrue(result.error?.contains("보유 수량") == true)
    }

    @Test
    fun `현재 거래일 일봉은 장 마감 확인 전 제외한다`() {
        val start = 1_000L
        val end = 2_000L
        assertTrue(!MarketSessionFilter.isCompletedDailyBar(1_000L, start, end, 1_500L))
        assertTrue(!MarketSessionFilter.isCompletedDailyBar(1_000L, start, end, 2_299L))
        assertTrue(MarketSessionFilter.isCompletedDailyBar(1_000L, start, end, 2_300L))
        assertTrue(MarketSessionFilter.isCompletedDailyBar(900L, start, end, 1_500L))
    }

    @Test
    fun `부분익절 후 익절 기준은 남은 보유분 수익률이다`() {
        val result = StrategyEngine.portfolioSummary(
            trades = listOf(
                PortfolioTrade(1, "2026-01-02", TradeType.BUY, 100.0, 100.0, 1.0, 0.0),
                PortfolioTrade(2, "2026-02-02", TradeType.SELL, 10.0, 110.0, 1.0, 0.0)
            ),
            currentPriceUsd = 125.0,
            currentFx = 1.0,
            taxYear = 2026
        )
        assertEquals(0.25, result.positionReturn!!, 1e-12)
        assertEquals(0.235, result.cycleReturn!!, 1e-12)
    }

    @Test
    fun `평단과 보유 수익률은 매수 수수료를 제외한 체결가 기준이다`() {
        val result = StrategyEngine.portfolioSummary(
            trades = listOf(
                PortfolioTrade(1, "2026-01-02", TradeType.BUY, 10.0, 100.0, 1.0, 10.0),
                PortfolioTrade(2, "2026-01-03", TradeType.BUY, 10.0, 120.0, 1.0, 12.0)
            ),
            currentPriceUsd = 121.0,
            currentFx = 1.0,
            taxYear = 2026
        )

        assertEquals(110.0, result.averagePriceUsd!!, 1e-12)
        assertEquals(0.10, result.positionReturn!!, 1e-12)
    }

    @Test
    fun `전량 매도 후 재진입하면 새 사이클 키를 사용한다`() {
        val result = StrategyEngine.portfolioSummary(
            trades = listOf(
                PortfolioTrade(1, "2026-01-02", TradeType.BUY, 10.0, 100.0, 1.0, 0.0),
                PortfolioTrade(2, "2026-02-02", TradeType.SELL, 10.0, 110.0, 1.0, 0.0),
                PortfolioTrade(3, "2026-03-02", TradeType.BUY, 5.0, 120.0, 1.0, 0.0)
            ),
            currentPriceUsd = 125.0,
            currentFx = 1.0,
            taxYear = 2026
        )
        assertEquals(3L, result.cycleStartTradeId)
        assertEquals(5.0 / 120.0, result.positionReturn!!, 1e-12)
    }

    @Test
    fun `정수 주문에서 계산 수량이 0주면 억지로 전량 매도하지 않는다`() {
        val plan = StrategyEngine.takeProfitPlan(
            quantity = 1.0,
            profitRate = 0.10,
            completed = emptySet(),
            fractional = false
        )
        assertTrue(plan.orders.isEmpty())
        assertEquals(1.0, plan.remaining, 0.0)
    }

    @Test
    fun `수수료율은 달러 거래대금의 퍼센트로 계산한다`() {
        val fee = StrategyEngine.feeFromRate(
            quantity = 10.0,
            priceUsd = 75.0,
            fxKrwPerUsd = 1_400.0,
            feeRatePercent = 0.07
        )
        assertEquals(0.525, fee, 1e-9)
    }

    @Test
    fun `전량 매도 후에도 순입금과 실현손익 기록을 보존한다`() {
        val trades = listOf(
            PortfolioTrade(1, "2026-01-02", TradeType.BUY, 10.0, 50.0, 1.0, 1.0),
            PortfolioTrade(2, "2026-06-02", TradeType.SELL, 10.0, 75.0, 1.0, 0.525, 0.07)
        )
        val result = StrategyEngine.portfolioSummary(trades, 0.0, 1_400.0, 2026)
        val sale = result.saleSettlements.single()

        assertEquals(0.0, result.remainingQuantity, 0.0)
        assertEquals(749.475, sale.netProceedsKrw, 1e-9)
        assertEquals(248.475, sale.realizedPnlKrw, 1e-9)
        assertEquals(248.475, result.realizedPnlKrw, 1e-9)
        assertEquals(248.475, result.totalPnlKrw, 1e-9)
    }

    @Test
    fun `손실 매도는 음수 실현손익으로 명시한다`() {
        val result = StrategyEngine.portfolioSummary(
            trades = listOf(
                PortfolioTrade(1, "2026-01-02", TradeType.BUY, 10.0, 100.0, 1.0, 0.0),
                PortfolioTrade(2, "2026-02-02", TradeType.SELL, 10.0, 90.0, 1.0, 0.0)
            ),
            currentPriceUsd = 0.0,
            currentFx = 1.0,
            taxYear = 2026
        )
        assertEquals(-100.0, result.saleSettlements.single().realizedPnlKrw, 0.0)
        assertEquals(-100.0, result.realizedPnlKrw, 0.0)
    }

    @Test
    fun `수동 지정한 분할매수 차수만 같은 사이클 완료로 계산한다`() {
        val trades = listOf(
            PortfolioTrade(1, "2026-08-01", TradeType.BUY, 1.0, 100.0, 1.0, 0.0, entryCycleKey = "cycle", entryDay = 1),
            PortfolioTrade(2, "2026-08-04", TradeType.BUY, 1.0, 101.0, 1.0, 0.0, entryCycleKey = "cycle", entryDay = 2),
            PortfolioTrade(3, "2026-08-05", TradeType.BUY, 1.0, 102.0, 1.0, 0.0)
        )

        assertEquals(setOf(1, 2), StrategyEngine.completedEntryDays(trades, "cycle"))
    }

}
