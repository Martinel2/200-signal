package com.signal200.app.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.automirrored.outlined.ShowChart
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AccountBalanceWallet
import androidx.compose.material.icons.outlined.AutoStories
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.viewmodel.compose.viewModel
import com.signal200.app.domain.ChartPoint
import com.signal200.app.domain.BetaSnapshot
import com.signal200.app.domain.BetaState
import com.signal200.app.domain.MarketSnapshot
import com.signal200.app.domain.PortfolioSummary
import com.signal200.app.domain.PortfolioTrade
import com.signal200.app.domain.RegimeState
import com.signal200.app.domain.SaleSettlement
import com.signal200.app.domain.StrategyEngine
import com.signal200.app.domain.TqqqEntryStatus
import com.signal200.app.domain.TradeType
import com.signal200.app.data.CapitalProfile
import com.signal200.app.data.CashDeposit
import com.signal200.app.data.PortfolioStore
import com.signal200.app.data.SignalNotificationScheduler
import com.signal200.app.widget.SignalWidgetProvider
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val Ink = Color(0xFF172033)
private val Paper = Color(0xFFF3F5F8)
private val PaperStrong = Color(0xFFFFFFFF)
private val Lime = Color(0xFFDCE8FF)
private val LimeDark = Color(0xFF2563EB)
private val Orange = Color(0xFFD94A3D)
private val Muted = Color(0xFF64748B)
private val HoldTeal = Color(0xFF0F8A78)
private val HoldPale = Color(0xFFDDF4EF)
private val SafePurple = Color(0xFF6D5BD0)
private val ChartOrange = Color(0xFFF59E0B)

private data class AccountActionUi(
    val eyebrow: String,
    val title: String,
    val detail: String,
    val accent: Color,
    val background: Color
)

private data class EntryRecordRequest(
    val day: Int,
    val cycleKey: String,
    val suggestedQuantity: Double
)

@Composable
fun SignalTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = lightColorScheme(
            primary = LimeDark,
            onPrimary = Color.White,
            background = Paper,
            onBackground = Ink,
            surface = PaperStrong,
            onSurface = Ink
        ),
        typography = MaterialTheme.typography.copy(
            bodyLarge = MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.SansSerif),
            titleLarge = MaterialTheme.typography.titleLarge.copy(fontFamily = FontFamily.SansSerif)
        ),
        content = content
    )
}

private enum class AppTab(val label: String) {
    SIGNAL("신호"),
    ACCOUNT("내 계좌"),
    GUIDE("가이드")
}

@Composable
fun Signal200App(viewModel: MainViewModel = viewModel()) {
    val context = LocalContext.current
    val onboardingPreferences = remember {
        context.getSharedPreferences("onboarding", Context.MODE_PRIVATE)
    }
    val state by viewModel.state.collectAsState()
    var tabIndex by remember { mutableIntStateOf(0) }
    var showOnboarding by remember {
        mutableStateOf(!onboardingPreferences.getBoolean("completed_v1", false))
    }

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        viewModel.refresh(force = false)
    }

    if (showOnboarding) {
        OnboardingScreen(
            onFinish = {
                onboardingPreferences.edit().putBoolean("completed_v1", true).apply()
                showOnboarding = false
            }
        )
        return
    }

    Scaffold(
        containerColor = Paper,
        bottomBar = {
            NavigationBar(
                containerColor = Ink,
                contentColor = PaperStrong,
                modifier = Modifier.navigationBarsPadding()
            ) {
                AppTab.entries.forEachIndexed { index, tab ->
                    NavigationBarItem(
                        selected = tabIndex == index,
                        onClick = { tabIndex = index },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = Ink,
                            selectedTextColor = PaperStrong,
                            indicatorColor = Lime,
                            unselectedIconColor = Color(0xFFAAB4C8),
                            unselectedTextColor = Color(0xFFAAB4C8)
                        ),
                        icon = {
                            Icon(
                                when (tab) {
                                    AppTab.SIGNAL -> Icons.AutoMirrored.Outlined.ShowChart
                                    AppTab.ACCOUNT -> Icons.Outlined.AccountBalanceWallet
                                    AppTab.GUIDE -> Icons.Outlined.AutoStories
                                },
                                contentDescription = tab.label
                            )
                        },
                        label = { Text(tab.label) }
                    )
                }
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .statusBarsPadding()
        ) {
            AppHeader(
                loading = state.loading,
                onRefresh = { viewModel.refresh(force = true) }
            )
            state.message?.let { StatusMessage(it, state.connected) }
            when (AppTab.entries[tabIndex]) {
                AppTab.SIGNAL -> SignalScreen(state)
                AppTab.ACCOUNT -> AccountScreen(state.snapshot)
                AppTab.GUIDE -> GuideScreen(onShowOnboarding = { showOnboarding = true })
            }
        }
    }
}

private data class OnboardingPage(
    val kicker: String,
    val title: String,
    val description: String,
    val steps: List<String>
)

@Composable
private fun OnboardingScreen(onFinish: () -> Unit) {
    val pages = listOf(
        OnboardingPage(
            "WELCOME · 01",
            "이 앱은 주문을 대신하지 않습니다",
            "TQQQ 확정 종가와 SMA200을 계산해 지금 확인할 행동을 보여주고, 내 거래내역으로 익절 단계와 예상 손익을 계산합니다.",
            listOf(
                "앱은 증권사 계좌와 연결되지 않습니다.",
                "매수·매도 주문은 반드시 증권사 앱에서 직접 합니다.",
                "장중 참고가와 확정 종가 신호를 구분해서 봅니다."
            )
        ),
        OnboardingPage(
            "SIGNAL · 02",
            "먼저 ‘신호’ 화면을 봅니다",
            "상단 새로고침은 시세를 다시 받고, 장중 참고 카드는 현재 움직임을, 검은색 신호 카드는 확정 종가 기준 행동을 보여줍니다.",
            listOf(
                "↻ 새로고침: 종목 시세를 즉시 다시 조회",
                "차트 길게 누르기: 기준선을 좌우로 움직여 날짜별 종가·SMA200 확인",
                "알림 켜기: 약 15분마다 신호·익절 단계를 확인",
                "테스트 알림: 휴대폰 알림 권한이 정상인지 확인",
                "검은 신호 카드: 실제 전략 판단에 사용하는 확정 신호"
            )
        ),
        OnboardingPage(
            "LEDGER · 03",
            "‘내 계좌’에 거래를 넣습니다",
            "총자산만 입력하면 현재 신호별 매수금액과 수량을 자동 계산합니다. TQQQ 보유분은 거래 원장에 입력합니다.",
            listOf(
                "현재 총자산을 입력하고, 기존 TQQQ·SGOV·SPYM 체결은 공통 거래 원장에 등록합니다.",
                "SMA 아래는 SGOV 100%, 기존 상승 구간 신규 진입은 SGOV·SPYM 반반으로 표시됩니다.",
                "상향 돌파 1차는 전체 자금 1/3, 2차는 실제 잔액 1/2, 3차는 실제 잔액 전액을 계산합니다.",
                "‘+ 매수·매도 거래 추가’를 누릅니다.",
                "매수/매도, 체결일, 수량, 달러 체결가, 수수료율(%)을 입력합니다.",
                "도달한 익절 단계는 증권사 매도 후 ‘+완료하기’를 누르면 전략 수량과 현재 참고가로 원장에 자동 기록됩니다.",
                "앱이 제안한 거래를 실제로 했다면 거래 원장에도 반드시 기록합니다."
            )
        ),
        OnboardingPage(
            "ROUTINE · 04",
            "이 순서만 기억하세요",
            "처음 한 번 계좌를 등록하고 알림을 켠 뒤에는, 알림이 왔을 때 확정 신호와 내 계좌의 NEXT ACTION을 확인하면 됩니다.",
            listOf(
                "① 신호 화면에서 확정 종가 상태 확인",
                "② 내 계좌에서 예상 수량과 손익 확인",
                "③ 증권사 앱에서 직접 주문",
                "④ 체결된 거래를 다시 내 계좌 원장에 입력",
                "가이드 탭에서 언제든 전체 설명을 다시 볼 수 있습니다."
            )
        )
    )
    var pageIndex by remember { mutableIntStateOf(0) }
    val page = pages[pageIndex]

    Surface(color = Paper, modifier = Modifier.fillMaxSize()) {
        Column(
            Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(20.dp)
        ) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                pages.indices.forEach { index ->
                    Box(
                        Modifier
                            .weight(1f)
                            .height(5.dp)
                            .background(if (index <= pageIndex) LimeDark else Color(0xFFD6D9D1))
                    )
                }
            }
            Spacer(Modifier.height(34.dp))
            LazyColumn(Modifier.weight(1f)) {
                item {
                    Text(page.kicker, color = LimeDark, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                    Spacer(Modifier.height(10.dp))
                    Text(
                        page.title,
                        color = Ink,
                        fontWeight = FontWeight.Black,
                        fontSize = 36.sp,
                        lineHeight = 43.sp
                    )
                    Spacer(Modifier.height(18.dp))
                    Text(page.description, color = Muted, fontSize = 15.sp, lineHeight = 23.sp)
                    Spacer(Modifier.height(28.dp))
                }
                items(page.steps.size) { index ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .background(PaperStrong)
                            .padding(16.dp)
                    ) {
                        Text(
                            "${index + 1}",
                            color = LimeDark,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(Modifier.width(12.dp))
                        Text(page.steps[index], color = Ink, fontSize = 13.sp, lineHeight = 20.sp)
                    }
                    Spacer(Modifier.height(8.dp))
                }
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                if (pageIndex > 0) {
                    TextButton(onClick = { pageIndex -= 1 }) { Text("이전", color = Ink) }
                }
                Spacer(Modifier.weight(1f))
                Button(
                    onClick = {
                        if (pageIndex == pages.lastIndex) onFinish() else pageIndex += 1
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Ink, contentColor = Lime),
                    shape = RoundedCornerShape(0.dp),
                    modifier = Modifier.height(52.dp)
                ) {
                    Text(if (pageIndex == pages.lastIndex) "앱 시작하기" else "다음", fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
private fun AppHeader(loading: Boolean, onRefresh: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 18.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .background(Lime)
                .padding(horizontal = 8.dp, vertical = 6.dp)
        ) {
            Text("200", color = Ink, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.width(9.dp))
        Text("SIGNAL", color = Ink, fontWeight = FontWeight.Black, letterSpacing = 2.sp)
        Spacer(Modifier.weight(1f))
        if (loading) {
            CircularProgressIndicator(
                modifier = Modifier.size(25.dp),
                color = LimeDark,
                strokeWidth = 2.dp
            )
        } else {
            Icon(
                Icons.Outlined.Refresh,
                contentDescription = "새로고침",
                tint = Ink,
                modifier = Modifier
                    .size(28.dp)
                    .clickable(onClick = onRefresh)
            )
        }
    }
}

@Composable
private fun StatusMessage(message: String, connected: Boolean) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(if (connected) Color(0xFFFFE8D8) else Color(0xFFE4E8F3))
            .padding(horizontal = 18.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier
                .size(7.dp)
                .background(if (connected) Orange else Color(0xFF6781C2), CircleShape)
        )
        Spacer(Modifier.width(8.dp))
        Text(message, color = Ink, fontSize = 12.sp)
    }
}

@Composable
private fun SignalScreen(state: MarketUiState) {
    val context = LocalContext.current
    val portfolioStore = remember { PortfolioStore(context) }
    val hasTqqqPosition = portfolioStore.loadTrades().filter { it.ticker == "TQQQ" }.sumOf {
        if (it.type == TradeType.BUY) it.quantity else -it.quantity
    } > 1e-8
    val snapshot = state.snapshot
    if (snapshot == null) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(18.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item {
                Column(
                    Modifier.fillMaxWidth().padding(vertical = 36.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    if (state.loading) CircularProgressIndicator(color = LimeDark)
                    Spacer(Modifier.height(16.dp))
                    Text(
                        if (state.connected) "시장 데이터를 불러오고 있습니다." else "인터넷 연결을 기다리고 있습니다.",
                        color = Muted
                    )
                    Text("데이터가 없으면 신호를 만들지 않습니다.", color = Muted, fontSize = 12.sp)
                }
            }
            item { NotificationSettingsCard() }
        }
        return
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(
            start = 18.dp,
            end = 18.dp,
            top = 26.dp,
            bottom = 40.dp
        ),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            Text("MARKET REGIME", color = LimeDark, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
            Text(
                "오늘의 신호",
                color = Ink,
                fontSize = 38.sp,
                lineHeight = 42.sp,
                fontWeight = FontWeight.Black
            )
        }
        item { PriceChart(snapshot.chart) }
        item { Metrics(snapshot) }
        item { SignalCard(snapshot, hasTqqqPosition) }
        item { BetaSignalCard(snapshot.beta) }
        item { IntradayCard(snapshot) }
        item { NotificationSettingsCard() }
        item {
            Text(
                if (snapshot.fromCache) "OFFLINE CACHE · ${formatTime(snapshot.fetchedAt)}" else
                    "UPDATED · ${formatTime(snapshot.fetchedAt)}",
                color = Muted,
                fontFamily = FontFamily.Monospace,
                fontSize = 10.sp
            )
            Text(
                "비공식 시세 데이터입니다. 실제 주문 전 증권사 확정 종가를 확인하세요.",
                color = Muted,
                fontSize = 11.sp
            )
        }
    }
}

@Composable
private fun NotificationSettingsCard() {
    val context = LocalContext.current
    var refreshKey by remember { mutableIntStateOf(0) }
    val enabled = SignalNotificationScheduler.isEnabled(context)
    val intradayEnabled = SignalNotificationScheduler.isIntradayEnabled(context)
    val systemAllowed = SignalNotificationScheduler.hasEnabledChannel(context)
    refreshKey

    fun enableNotifications() {
        SignalNotificationScheduler.setEnabled(context, true)
        refreshKey += 1
    }

    fun openNotificationSettings() {
        val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
            }
        } else {
            Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:${context.packageName}")
            )
        }
        context.startActivity(intent)
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) enableNotifications() else refreshKey += 1
    }

    Column(
        Modifier
            .fillMaxWidth()
            .background(if (enabled && systemAllowed) HoldPale else PaperStrong)
            .padding(18.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(9.dp)
                    .background(if (enabled && systemAllowed) HoldTeal else Orange, CircleShape)
            )
            Spacer(Modifier.width(9.dp))
            Column {
                Text("BACKGROUND ALERTS", color = LimeDark, fontFamily = FontFamily.Monospace, fontSize = 10.sp)
                Text(
                    when {
                        enabled && systemAllowed -> "신호 알림 켜짐"
                        enabled -> "시스템 알림 권한 필요"
                        else -> "신호 알림 꺼짐"
                    },
                    color = Ink,
                    fontWeight = FontWeight.Black,
                    fontSize = 18.sp
                )
            }
        }
        Spacer(Modifier.height(10.dp))
        Text(
            "인터넷 연결 시 약 15분 주기로 알파·베타 확정 신호, 부분익절 단계, 장중 예비 경고를 확인합니다.",
            color = Muted,
            fontSize = 11.sp,
            lineHeight = 17.sp
        )
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = {
                    if (enabled && !systemAllowed) {
                        openNotificationSettings()
                    } else if (enabled) {
                        SignalNotificationScheduler.setEnabled(context, false)
                        refreshKey += 1
                    } else if (
                        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                        context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
                        PackageManager.PERMISSION_GRANTED
                    ) {
                        permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    } else {
                        enableNotifications()
                    }
                },
                colors = ButtonDefaults.buttonColors(
                    containerColor = Ink,
                    contentColor = if (enabled) Orange else Lime
                ),
                shape = RoundedCornerShape(0.dp)
            ) {
                Text(
                    when {
                        enabled && !systemAllowed -> "시스템 설정"
                        enabled -> "알림 끄기"
                        else -> "알림 켜기"
                    }
                )
            }

            if (enabled && systemAllowed) {
                TextButton(onClick = { SignalNotificationScheduler.sendTest(context) }) {
                    Text("테스트 알림", color = Ink)
                }
            }
        }
        if (enabled) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (intradayEnabled) "장중 예비 경고 사용 중" else "확정 종가 신호만 사용",
                    color = Ink,
                    fontSize = 11.sp
                )
                Spacer(Modifier.weight(1f))
                TextButton(onClick = {
                    SignalNotificationScheduler.setIntradayEnabled(context, !intradayEnabled)
                    refreshKey += 1
                }) {
                    Text(if (intradayEnabled) "예비 경고 끄기" else "예비 경고 켜기")
                }
            }
        }
    }
}

@Composable
private fun IntradayCard(snapshot: MarketSnapshot) {
    val quote = snapshot.intraday
    if (quote == null) {
        Row(
            Modifier
                .fillMaxWidth()
                .background(Color(0xFFE7E8E2))
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("장중 참고 시세를 받지 못했습니다.", color = Muted, fontSize = 12.sp)
            Spacer(Modifier.weight(1f))
            Text("확정 종가 신호는 정상", color = Ink, fontFamily = FontFamily.Monospace, fontSize = 10.sp)
        }
        return
    }

    val intradayDistance = quote.price / snapshot.sma200 - 1.0
    val confirmedAbove = snapshot.close > snapshot.sma200
    val intradayAbove = quote.price > snapshot.sma200
    val warning = when {
        confirmedAbove && !intradayAbove -> "하향 이탈 예비 경고"
        !confirmedAbove && intradayAbove -> "상향 돌파 예비 관찰"
        intradayAbove -> "장중 SMA200 위"
        else -> "장중 SMA200 아래"
    }
    val warningColor = when {
        confirmedAbove != intradayAbove -> Orange
        intradayAbove -> HoldTeal
        else -> SafePurple
    }
    val delay = when {
        quote.delayedBySeconds <= 0 -> "공급자 표시 지연 0초"
        quote.delayedBySeconds < 60 -> "${quote.delayedBySeconds}초 지연"
        else -> "${quote.delayedBySeconds / 60}분 지연"
    }

    Column(
        Modifier
            .fillMaxWidth()
            .background(PaperStrong)
            .padding(20.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column {
                Text("INTRADAY REFERENCE", color = SafePurple, fontFamily = FontFamily.Monospace, fontSize = 10.sp)
                Text("장중 참고 시세", color = Ink, fontWeight = FontWeight.Black, fontSize = 21.sp)
            }
            Spacer(Modifier.weight(1f))
            Text(
                quote.session,
                color = PaperStrong,
                modifier = Modifier
                    .background(Ink)
                    .padding(horizontal = 9.dp, vertical = 6.dp),
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace
            )
        }
        Spacer(Modifier.height(20.dp))
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                "$${"%.2f".format(quote.price)}",
                color = Ink,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Black,
                fontSize = 34.sp
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.padding(bottom = 2.dp)) {
                Text(
                    "전일 확정 종가 $${"%.2f".format(Locale.US, quote.previousClose)} 대비",
                    color = Muted,
                    fontSize = 9.sp
                )
                Text(
                    signedPercent(quote.changeRate),
                    color = if (quote.changeRate >= 0) LimeDark else Orange,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold
                )
            }
        }
        Spacer(Modifier.height(15.dp))
        Row(
            Modifier
                .fillMaxWidth()
                .background(warningColor.copy(alpha = 0.12f))
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(Modifier.size(8.dp).background(warningColor, CircleShape))
            Spacer(Modifier.width(9.dp))
            Column {
                Text(warning, color = warningColor, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                Text(
                    "SMA200 대비 ${signedPercent(intradayDistance)} · 종가 확정 전에는 매매 신호가 아닙니다.",
                    color = Muted,
                    fontSize = 10.sp
                )
            }
        }
        Spacer(Modifier.height(10.dp))
        Text(
            "${formatTime(quote.timestamp)} · $delay · Yahoo 비공식 시세",
            color = Muted,
            fontFamily = FontFamily.Monospace,
            fontSize = 9.sp
        )
    }
}

@Composable
private fun SignalCard(snapshot: MarketSnapshot, hasTqqqPosition: Boolean) {
    val accent = when (snapshot.regime.state) {
        RegimeState.EXIT_PENDING -> Orange
        RegimeState.SAFE -> Color(0xFFB8AFFF)
        RegimeState.ENTRY_DAY_1,
        RegimeState.ENTRY_DAY_2,
        RegimeState.ENTRY_DAY_3 -> Color(0xFF75A7FF)
        RegimeState.RISK_ON -> Color(0xFF55D6BC)
        RegimeState.WAIT -> Color(0xFFFFC65C)
        RegimeState.DATA_ERROR -> Color(0xFFFF8A7C)
    }
    Card(
        colors = CardDefaults.cardColors(containerColor = Ink),
        shape = RoundedCornerShape(10.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(24.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(snapshot.latestDate, color = Color(0xFFADB4AF), fontSize = 12.sp)
                Spacer(Modifier.weight(1f))
                Text(
                    if (snapshot.fromCache) "CACHE · 종가" else "LIVE · 종가",
                    color = accent,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace
                )
            }
            Spacer(Modifier.height(54.dp))
            Text("ALPHA · 200티큐단", color = accent, fontFamily = FontFamily.Monospace, fontSize = 10.sp)
            Text(
                if (snapshot.regime.state == RegimeState.RISK_ON && !hasTqqqPosition) {
                    "SGOV·SPYM\n50:50"
                } else {
                    snapshot.regime.label
                },
                color = PaperStrong,
                fontWeight = FontWeight.Black,
                fontSize = 43.sp,
                lineHeight = 48.sp
            )
            Spacer(Modifier.height(36.dp))
            Text(
                StrategyEngine.action(snapshot.regime.state, hasTqqqPosition),
                color = PaperStrong,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(7.dp))
            Text(snapshot.regime.reason, color = Color(0xFFADB4AF), fontSize = 12.sp)
        }
    }
}

@Composable
private fun BetaSignalCard(beta: BetaSnapshot?) {
    if (beta == null) {
        Column(Modifier.fillMaxWidth().background(PaperStrong).padding(20.dp)) {
            Text("BETA · 200큐큐단", color = SafePurple, fontFamily = FontFamily.Monospace, fontSize = 10.sp)
            Text("QQQ 데이터 대기", color = Ink, fontWeight = FontWeight.Black, fontSize = 21.sp)
            Text("알파 신호와 별도로 QQQ SMA200 ±2% 데이터를 받고 있습니다.", color = Muted, fontSize = 11.sp)
        }
        return
    }
    val accent = when (beta.regime.state) {
        BetaState.EXIT_PENDING,
        BetaState.TRAILING_STOP -> Orange
        BetaState.SAFE -> Color(0xFFB8AFFF)
        BetaState.ENTRY_DAY_1,
        BetaState.ENTRY_DAY_2,
        BetaState.ENTRY_DAY_3 -> Color(0xFF75A7FF)
        BetaState.TAKE_PROFIT -> Lime
        BetaState.RISK_ON -> Color(0xFF55D6BC)
    }
    Card(
        colors = CardDefaults.cardColors(containerColor = Ink),
        shape = RoundedCornerShape(10.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(24.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("BETA · 200큐큐단", color = accent, fontFamily = FontFamily.Monospace, fontSize = 10.sp)
                Spacer(Modifier.weight(1f))
                Text(beta.latestDate, color = Color(0xFFADB4AF), fontSize = 11.sp)
            }
            Spacer(Modifier.height(30.dp))
            Text(
                beta.regime.label,
                color = PaperStrong,
                fontWeight = FontWeight.Black,
                fontSize = 34.sp,
                lineHeight = 39.sp
            )
            Spacer(Modifier.height(18.dp))
            Text(beta.regime.action, color = PaperStrong, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(7.dp))
            Text(beta.regime.reason, color = Color(0xFFADB4AF), fontSize = 12.sp)
            Spacer(Modifier.height(20.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                BetaMetric("QQQ", "$${"%.2f".format(Locale.US, beta.qqqClose)}")
                BetaMetric("상단 +2%", "$${"%.2f".format(Locale.US, beta.upperBand)}")
                BetaMetric("하단 −2%", "$${"%.2f".format(Locale.US, beta.lowerBand)}")
            }
            if (beta.cycleStartDate != null) {
                Spacer(Modifier.height(18.dp))
                Text(
                    buildString {
                        append("사이클 ${beta.cycleStartDate}")
                        beta.tqqqDrawdown?.let { append(" · TQQQ 고점 대비 ${signedPercent(it)}") }
                        beta.profitRate?.let { append(" · 참고 수익률 ${signedPercent(it)}") }
                    },
                    color = Color(0xFFADB4AF),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 10.sp
                )
                beta.trailingStopDate?.let {
                    Text("TS 최초 감지 $it", color = Orange, fontSize = 10.sp)
                }
            }
        }
    }
}

@Composable
private fun BetaMetric(label: String, value: String) {
    Column {
        Text(label, color = Color(0xFFADB4AF), fontSize = 9.sp)
        Text(value, color = PaperStrong, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 13.sp)
    }
}

@Composable
private fun Metrics(snapshot: MarketSnapshot) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(PaperStrong)
            .padding(vertical = 18.dp),
        horizontalArrangement = Arrangement.SpaceEvenly
    ) {
        Metric("TQQQ 종가", "$${"%.2f".format(snapshot.close)}")
        Metric("SMA 200", "$${"%.2f".format(snapshot.sma200)}")
        Metric(
            "이격률",
            signedPercent(snapshot.distance),
            if (snapshot.distance >= 0) HoldTeal else Orange
        )
    }
}

@Composable
private fun Metric(label: String, value: String, valueColor: Color = Ink) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, color = Muted, fontSize = 10.sp)
        Spacer(Modifier.height(7.dp))
        Text(
            value,
            color = valueColor,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            fontSize = 18.sp
        )
    }
}

@Composable
private fun PriceChart(points: List<ChartPoint>) {
    var selectedIndex by remember(points) { mutableStateOf<Int?>(null) }
    val selectedPoint = selectedIndex?.let(points::getOrNull)
    Column(
        Modifier
            .fillMaxWidth()
            .background(PaperStrong)
            .padding(18.dp)
    ) {
        Text("PRICE FIRST", color = LimeDark, fontFamily = FontFamily.Monospace, fontSize = 10.sp)
        Text("확정 종가 차트", color = Ink, fontWeight = FontWeight.Black, fontSize = 21.sp)
        Text("최근 약 1년 · 장중 미완성 캔들 제외", color = Muted, fontSize = 10.sp)
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(8.dp).background(LimeDark, CircleShape))
                Spacer(Modifier.width(6.dp))
                Text("TQQQ 종가", color = Ink, fontSize = 10.sp)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(8.dp).background(ChartOrange, CircleShape))
                Spacer(Modifier.width(6.dp))
                Text("SMA200", color = Ink, fontSize = 10.sp)
            }
        }
        Spacer(Modifier.height(12.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .height(40.dp)
                .background(if (selectedPoint != null) Lime else Paper)
                .padding(horizontal = 10.dp),
            contentAlignment = Alignment.CenterStart
        ) {
            Text(
                if (selectedPoint != null) {
                    "${selectedPoint.date}  ·  종가 $${"%.2f".format(Locale.US, selectedPoint.close)}  ·  SMA200 $${"%.2f".format(Locale.US, selectedPoint.sma200)}"
                } else {
                    "차트를 길게 누른 뒤 좌우로 움직여 날짜별 종가 확인"
                },
                color = if (selectedPoint != null) Ink else Muted,
                fontFamily = FontFamily.Monospace,
                fontWeight = if (selectedPoint != null) FontWeight.Bold else FontWeight.Normal,
                fontSize = 10.sp
            )
        }
        Spacer(Modifier.height(10.dp))
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(220.dp)
                .pointerInput(points) {
                    if (points.size < 2) return@pointerInput
                    fun indexAt(x: Float): Int {
                        val fraction = (x / size.width.coerceAtLeast(1)).coerceIn(0f, 1f)
                        return kotlin.math.round(fraction * points.lastIndex).toInt()
                    }
                    detectDragGesturesAfterLongPress(
                        onDragStart = { offset -> selectedIndex = indexAt(offset.x) },
                        onDrag = { change, _ ->
                            change.consume()
                            selectedIndex = indexAt(change.position.x)
                        },
                        onDragEnd = { selectedIndex = null },
                        onDragCancel = { selectedIndex = null }
                    )
                }
        ) {
            if (points.size < 2) return@Canvas
            val values = points.flatMap { listOf(it.close, it.sma200) }
            val minValue = values.min() * 0.95f
            val maxValue = values.max() * 1.05f
            val spread = (maxValue - minValue).coerceAtLeast(1.0)

            fun x(index: Int) = index.toFloat() / points.lastIndex * size.width
            fun y(value: Double) = ((maxValue - value) / spread * size.height).toFloat()

            repeat(4) { index ->
                val gridY = size.height * index / 3f
                drawLine(
                    color = Ink.copy(alpha = 0.10f),
                    start = Offset(0f, gridY),
                    end = Offset(size.width, gridY),
                    strokeWidth = 1f
                )
            }

            val smaPath = Path()
            val pricePath = Path()
            points.forEachIndexed { index, point ->
                if (index == 0) {
                    smaPath.moveTo(x(index), y(point.sma200))
                    pricePath.moveTo(x(index), y(point.close))
                } else {
                    smaPath.lineTo(x(index), y(point.sma200))
                    pricePath.lineTo(x(index), y(point.close))
                }
            }
            drawPath(smaPath, ChartOrange, style = Stroke(width = 5f, cap = StrokeCap.Round))
            drawPath(pricePath, LimeDark, style = Stroke(width = 6f, cap = StrokeCap.Round))
            drawCircle(Color.White, radius = 10f, center = Offset(x(points.lastIndex), y(points.last().close)))
            drawCircle(LimeDark, radius = 6f, center = Offset(x(points.lastIndex), y(points.last().close)))
            selectedIndex?.coerceIn(0, points.lastIndex)?.let { index ->
                val point = points[index]
                val selectedX = x(index)
                val closeY = y(point.close)
                val smaY = y(point.sma200)
                drawLine(
                    color = Ink.copy(alpha = 0.65f),
                    start = Offset(selectedX, 0f),
                    end = Offset(selectedX, size.height),
                    strokeWidth = 2f
                )
                drawLine(
                    color = LimeDark.copy(alpha = 0.35f),
                    start = Offset(0f, closeY),
                    end = Offset(size.width, closeY),
                    strokeWidth = 2f
                )
                drawCircle(Color.White, radius = 10f, center = Offset(selectedX, closeY))
                drawCircle(LimeDark, radius = 6f, center = Offset(selectedX, closeY))
                drawCircle(Color.White, radius = 9f, center = Offset(selectedX, smaY))
                drawCircle(ChartOrange, radius = 5f, center = Offset(selectedX, smaY))
            }
        }
    }
}

@Composable
private fun AccountScreen(snapshot: MarketSnapshot?) {
    val context = LocalContext.current
    val store = remember { PortfolioStore(context) }
    val preferences = remember { context.getSharedPreferences("account", Context.MODE_PRIVATE) }
    var trades by remember { mutableStateOf(store.loadTrades()) }
    var cashDeposits by remember { mutableStateOf(store.loadCashDeposits()) }
    var capitalProfile by remember { mutableStateOf(store.loadCapitalProfile()) }
    var feeRatePercent by remember { mutableDoubleStateOf(store.loadFeeRatePercent()) }
    val fractional = false
    var completed by remember {
        mutableStateOf(
            preferences.getStringSet("completed", emptySet())
                ?.mapNotNull { it.toDoubleOrNull() }
                ?.toSet() ?: emptySet()
        )
    }
    var completedCycleKey by remember {
        mutableStateOf(
            if (preferences.contains("completedCycleKey")) {
                preferences.getLong("completedCycleKey", 0L)
            } else null
        )
    }
    val taxYear = SimpleDateFormat("yyyy", Locale.US).format(Date()).toInt()
    var showTradeDialog by remember { mutableStateOf(false) }
    var showCashDepositDialog by remember { mutableStateOf(false) }
    var showLedgerDialog by remember { mutableStateOf(false) }
    var ledgerEditMode by remember { mutableStateOf(false) }
    var editCashDepositTarget by remember { mutableStateOf<CashDeposit?>(null) }
    var entryRecordRequest by remember { mutableStateOf<EntryRecordRequest?>(null) }
    var editTarget by remember { mutableStateOf<PortfolioTrade?>(null) }
    var deleteTarget by remember { mutableStateOf<PortfolioTrade?>(null) }
    var pendingMilestone by remember { mutableStateOf<Double?>(null) }
    val currentPrice = snapshot?.intraday?.price ?: snapshot?.close ?: 0.0
    val summary = StrategyEngine.portfolioSummary(
        trades.filter { it.ticker == "TQQQ" },
        currentPrice,
        1.0,
        taxYear
    )
    val plan = if (snapshot != null && summary.positionReturn != null && summary.error == null) {
        StrategyEngine.takeProfitPlan(
            summary.remainingQuantity,
            summary.positionReturn,
            completed,
            fractional
        )
    } else null

    LaunchedEffect(trades, capitalProfile, snapshot?.fetchedAt) {
        SignalWidgetProvider.updateAll(context, snapshot)
    }

    LaunchedEffect(summary.cycleStartTradeId) {
        val activeCycle = summary.cycleStartTradeId
        when {
            activeCycle == null -> {
                completed = emptySet()
                completedCycleKey = null
                preferences.edit()
                    .remove("completed")
                    .remove("completedCycleKey")
                    .apply()
            }
            completedCycleKey == null -> {
                // v0.4 이하의 활성 포지션은 기존 완료 체크를 보존하며 새 키만 붙인다.
                completedCycleKey = activeCycle
                preferences.edit().putLong("completedCycleKey", activeCycle).apply()
            }
            completedCycleKey != activeCycle -> {
                completed = emptySet()
                completedCycleKey = activeCycle
                preferences.edit()
                    .remove("completed")
                    .putLong("completedCycleKey", activeCycle)
                    .apply()
            }
        }
    }

    if (showTradeDialog) {
        TradeDialog(
            snapshot = snapshot,
            summary = summary,
            defaultFeeRatePercent = feeRatePercent,
            initialType = TradeType.BUY,
            initialQuantity = entryRecordRequest?.suggestedQuantity,
            initialEntryCycleKey = entryRecordRequest?.cycleKey,
            initialEntryDay = entryRecordRequest?.day,
            initialTrade = editTarget,
            currentQuantityByTicker = listOf("TQQQ", "SGOV", "SPYM").associateWith { ticker ->
                trades.filter { it.ticker == ticker }.sumOf {
                    if (it.type == TradeType.BUY) it.quantity else -it.quantity
                }.coerceAtLeast(0.0)
            },
            onDismiss = {
                showTradeDialog = false
                entryRecordRequest = null
                editTarget = null
            },
            onSave = { trade ->
                val previousTrade = editTarget
                val linkedTrade = if (trade.ticker == "TQQQ" && trade.type == TradeType.BUY) {
                    if (trade.entryDay == null) {
                        trade.copy(entryCycleKey = null)
                    } else {
                        trade.copy(
                            entryCycleKey = trade.entryCycleKey
                                ?: capitalProfile.entryCycleKey.takeIf { it.isNotBlank() }
                                ?: snapshot?.let(::entryCycleId)
                                ?: "manual-${trade.date}"
                        )
                    }
                } else trade.copy(entryCycleKey = null, entryDay = null)
                trades = if (previousTrade == null) {
                    trades + linkedTrade
                } else {
                    trades.map { if (it.id == previousTrade.id) linkedTrade else it }
                }.sortedWith(compareBy<PortfolioTrade> { it.date }.thenBy { it.id })
                store.saveTrades(trades)
                linkedTrade.feeRatePercent?.let {
                    feeRatePercent = it
                    store.saveFeeRatePercent(it)
                }
                val total = capitalProfile.totalAssetsKrw.replace(",", "").toDoubleOrNull() ?: 0.0
                val cash = capitalProfile.availableCashKrw
                    .replace(",", "")
                    .toDoubleOrNull() ?: total
                fun cashEffect(value: PortfolioTrade): Double {
                    val gross = value.quantity * value.priceUsd
                    return if (value.type == TradeType.BUY) {
                        -gross - value.feeKrw
                    } else {
                        gross - value.feeKrw
                    }
                }
                val cashAfter = cash - (previousTrade?.let(::cashEffect) ?: 0.0) + cashEffect(linkedTrade)
                val activeEntryCycle = linkedTrade.entryCycleKey
                    ?: capitalProfile.entryCycleKey.takeIf { it.isNotBlank() }
                val completedEntryDays = StrategyEngine.completedEntryDays(trades, activeEntryCycle)
                capitalProfile = capitalProfile.copy(
                    entryCycleKey = activeEntryCycle.takeIf { completedEntryDays.isNotEmpty() }.orEmpty(),
                    entryCapitalKrw = if (completedEntryDays.isEmpty()) {
                        ""
                    } else if (
                        linkedTrade.entryCycleKey != null &&
                        capitalProfile.entryCycleKey != linkedTrade.entryCycleKey
                    ) formatEditableNumber(total) else capitalProfile.entryCapitalKrw,
                    completedEntryDays = completedEntryDays,
                    availableCashKrw = formatEditableNumber(kotlin.math.max(0.0, cashAfter))
                )
                store.saveCapitalProfile(capitalProfile)
                showTradeDialog = false
                entryRecordRequest = null
                editTarget = null
            }
        )
    }
    if (showCashDepositDialog) {
        CashDepositDialog(
            currentFxKrwPerUsd = snapshot?.usdKrw,
            initialDeposit = editCashDepositTarget,
            onDismiss = {
                showCashDepositDialog = false
                editCashDepositTarget = null
            },
            onSave = { deposit ->
                val previousDeposit = editCashDepositTarget
                cashDeposits = if (previousDeposit == null) {
                    cashDeposits + deposit
                } else {
                    cashDeposits.map { if (it.id == previousDeposit.id) deposit else it }
                }.sortedByDescending { it.id }
                store.saveCashDeposits(cashDeposits)
                val oldTotal = capitalProfile.totalAssetsKrw.replace(",", "").toDoubleOrNull() ?: 0.0
                val explicitCash =
                    capitalProfile.availableCashKrw.replace(",", "").toDoubleOrNull()
                val oldCash = explicitCash ?: if (trades.isEmpty()) {
                    oldTotal
                } else {
                    0.0
                }
                val depositDelta = deposit.amountUsd - (previousDeposit?.amountUsd ?: 0.0)
                capitalProfile = capitalProfile.copy(
                    totalAssetsKrw = formatEditableNumber(kotlin.math.max(0.0, oldTotal + depositDelta)),
                    availableCashKrw = formatEditableNumber(kotlin.math.max(0.0, oldCash + depositDelta))
                )
                store.saveCapitalProfile(capitalProfile)
                showCashDepositDialog = false
                editCashDepositTarget = null
            }
        )
    }
    if (showLedgerDialog) {
        val settlements = summary.saleSettlements.associateBy { it.tradeId }
        AlertDialog(
            onDismissRequest = {
                showLedgerDialog = false
                ledgerEditMode = false
            },
            title = { Text("거래내역 · ${trades.size}건") },
            text = {
                Column {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            if (ledgerEditMode) "수정할 거래를 선택하세요." else "최근 거래부터 표시합니다.",
                            color = Muted,
                            fontSize = 11.sp,
                            modifier = Modifier.weight(1f)
                        )
                        TextButton(onClick = { ledgerEditMode = !ledgerEditMode }) {
                            Text(if (ledgerEditMode) "취소" else "수정", color = LimeDark)
                        }
                    }
                    if (trades.isEmpty()) {
                        Text("아직 등록된 거래가 없습니다.", color = Muted)
                    } else {
                        LazyColumn(
                            Modifier.fillMaxWidth().heightIn(max = 500.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            items(trades.asReversed(), key = { it.id }) { trade ->
                                TradeRow(
                                    trade = trade,
                                    settlement = settlements[trade.id],
                                    onSelect = if (ledgerEditMode) {
                                        {
                                            showLedgerDialog = false
                                            ledgerEditMode = false
                                            entryRecordRequest = null
                                            editTarget = trade
                                            showTradeDialog = true
                                        }
                                    } else null,
                                    onDelete = if (ledgerEditMode) null else {
                                        {
                                            showLedgerDialog = false
                                            deleteTarget = trade
                                        }
                                    }
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    showLedgerDialog = false
                    ledgerEditMode = false
                }) { Text("닫기", color = LimeDark) }
            }
        )
    }
    deleteTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("거래내역 삭제") },
            text = { Text("${target.date} ${if (target.type == TradeType.BUY) "매수" else "매도"} 기록을 삭제할까요?") },
            confirmButton = {
                TextButton(onClick = {
                    trades = trades.filterNot { it.id == target.id }
                    store.saveTrades(trades)
                    val cash = capitalProfile.availableCashKrw
                        .replace(",", "")
                        .toDoubleOrNull() ?: 0.0
                    val gross = target.quantity * target.priceUsd
                    val restoredCash = if (target.type == TradeType.BUY) {
                        cash + gross + target.feeKrw
                    } else {
                        cash - gross + target.feeKrw
                    }
                    capitalProfile = capitalProfile.copy(
                        completedEntryDays = target.entryDay?.let { day ->
                            capitalProfile.completedEntryDays.filter { it < day }.toSet()
                        } ?: capitalProfile.completedEntryDays,
                        availableCashKrw = formatEditableNumber(
                            kotlin.math.max(0.0, restoredCash)
                        )
                    )
                    store.saveCapitalProfile(capitalProfile)
                    deleteTarget = null
                }) { Text("삭제", color = Orange) }
            },
            dismissButton = { TextButton(onClick = { deleteTarget = null }) { Text("취소") } }
        )
    }
    pendingMilestone?.let { threshold ->
        val isCompleted = threshold in completed
        val order = plan?.orders?.firstOrNull { it.threshold == threshold }
        val quotePrice = snapshot?.intraday?.price ?: snapshot?.close
        val fee = if (order != null && quotePrice != null) {
            StrategyEngine.feeFromRate(order.quantity, quotePrice, 1.0, feeRatePercent)
        } else Double.NaN
        val gross = if (order != null && quotePrice != null) {
            order.quantity * quotePrice
        } else Double.NaN
        val allocatedCost = if (order != null && summary.remainingQuantity > 0) {
            summary.remainingCostKrw / summary.remainingQuantity * order.quantity
        } else Double.NaN
        val expectedPnl = gross - fee - allocatedCost
        AlertDialog(
            onDismissRequest = { pendingMilestone = null },
            title = {
                Text(
                    if (isCompleted) {
                        "+${(threshold * 100).toInt()}% 완료 표시 취소"
                    } else {
                        "+${(threshold * 100).toInt()}% 익절 완료로 표시"
                    }
                )
            },
            text = {
                Column {
                    Text(
                        if (isCompleted) {
                            "이 단계를 다시 미완료로 바꾸면 앱이 조건 충족 시 다시 매도 수량을 제안할 수 있습니다. 연결된 매도 거래 기록은 삭제되지 않습니다."
                        } else {
                            "전략 수량과 현재 달러 참고가로 TQQQ 매도와 SPYM 매수를 공통 원장에 함께 등록합니다."
                        }
                    )
                    if (!isCompleted && order != null && gross.isFinite() && fee.isFinite()) {
                        Spacer(Modifier.height(12.dp))
                        Column(Modifier.fillMaxWidth().background(HoldPale).padding(12.dp)) {
                            ResultLine("자동 매도 수량", "${formatQuantity(order.quantity)}주")
                            ResultLine("참고 가격", "$${"%.2f".format(Locale.US, quotePrice)}")
                            ResultLine("수수료율", "${formatEditableNumber(feeRatePercent)}%")
                            ResultLine("예상 순입금", formatUsd(gross - fee))
                            ResultLine("예상 실현손익", signedUsd(expectedPnl))
                        }
                        Spacer(Modifier.height(8.dp))
                        TextButton(
                            onClick = {
                                completed = completed + threshold
                                preferences.edit()
                                    .putStringSet("completed", completed.map { it.toString() }.toSet())
                                    .apply()
                                pendingMilestone = null
                            }
                        ) {
                            Text("이미 원장에 매도를 입력함 · 완료만 표시", color = Muted)
                        }
                    } else if (!isCompleted) {
                        Spacer(Modifier.height(12.dp))
                        Text("현재 시세 또는 익절 수량을 계산할 수 없어 자동 기록할 수 없습니다.", color = Orange)
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    if (isCompleted) {
                        completed = completed - threshold
                        preferences.edit()
                            .putStringSet("completed", completed.map { it.toString() }.toSet())
                            .apply()
                        pendingMilestone = null
                    } else {
                        if (order == null || quotePrice == null ||
                            !fee.isFinite() || !gross.isFinite() ||
                            snapshot == null || snapshot.spymPrice <= 0.0
                        ) return@TextButton
                        val now = System.currentTimeMillis()
                        val date = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
                        val sale = PortfolioTrade(
                            id = now,
                            date = date,
                            type = TradeType.SELL,
                            quantity = order.quantity,
                            priceUsd = quotePrice,
                            fxKrwPerUsd = 1.0,
                            feeKrw = fee,
                            feeRatePercent = feeRatePercent,
                            ticker = "TQQQ"
                        )
                        val netProceeds = gross - fee
                        val feeFraction = feeRatePercent / 100.0
                        val spymQuantity = kotlin.math.floor(
                            netProceeds / (1.0 + feeFraction) / snapshot.spymPrice
                        )
                        val spymGross = spymQuantity * snapshot.spymPrice
                        val spymFee = spymGross * feeFraction
                        val generatedTrades = buildList {
                            add(sale)
                            if (spymQuantity >= 1.0) {
                                add(
                                    PortfolioTrade(
                                        id = now + 1,
                                        date = date,
                                        type = TradeType.BUY,
                                        quantity = spymQuantity,
                                        priceUsd = snapshot.spymPrice,
                                        fxKrwPerUsd = 1.0,
                                        feeKrw = spymFee,
                                        feeRatePercent = feeRatePercent,
                                        ticker = "SPYM"
                                    )
                                )
                            }
                        }
                        trades = (trades + generatedTrades)
                            .sortedWith(compareBy<PortfolioTrade> { it.date }.thenBy { it.id })
                        store.saveTrades(trades)
                        val cash = capitalProfile.availableCashKrw
                            .replace(",", "")
                            .toDoubleOrNull() ?: 0.0
                        capitalProfile = capitalProfile.copy(
                            availableCashKrw = formatEditableNumber(
                                cash + netProceeds - spymGross - spymFee
                            )
                        )
                        store.saveCapitalProfile(capitalProfile)
                        completed = completed + threshold
                        preferences.edit()
                            .putStringSet("completed", completed.map { it.toString() }.toSet())
                            .apply()
                        pendingMilestone = null
                    }
                }) {
                    Text(
                        if (isCompleted) "미완료로 변경" else "자동 기록 완료",
                        color = if (isCompleted) Orange else HoldTeal
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingMilestone = null }) { Text("돌아가기") }
            }
        )
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(18.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            Text("MY POSITION", color = LimeDark, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
            Text("거래 원장", color = Ink, fontWeight = FontWeight.Black, fontSize = 36.sp)
            Text("계좌와 자동주문을 한눈에 관리합니다.", color = Muted, fontSize = 12.sp)
        }
        item {
            CapitalAllocationCard(
                snapshot = snapshot,
                profile = capitalProfile,
                hasTqqqPosition = summary.error == null && summary.remainingQuantity > 1e-8,
                tqqqSummary = summary,
                trades = trades,
                onProfileChange = {
                    capitalProfile = it
                    store.saveCapitalProfile(it)
                },
                defaultFeeRatePercent = feeRatePercent,
                cashDeposits = cashDeposits,
                onAddCash = {
                    editCashDepositTarget = null
                    showCashDepositDialog = true
                },
                onEditCashDeposit = { deposit ->
                    editCashDepositTarget = deposit
                    showCashDepositDialog = true
                },
                onAddTrade = {
                    entryRecordRequest = null
                    editTarget = null
                    showTradeDialog = true
                },
                onShowLedger = { showLedgerDialog = true },
                onRegisterCalculatedTrades = { calculatedTrades ->
                    trades = (trades + calculatedTrades)
                        .sortedWith(compareBy<PortfolioTrade> { it.date }.thenBy { it.id })
                    store.saveTrades(trades)
                    val total = capitalProfile.totalAssetsKrw
                        .replace(",", "")
                        .toDoubleOrNull() ?: 0.0
                    val cash = capitalProfile.availableCashKrw
                        .replace(",", "")
                        .toDoubleOrNull() ?: total
                    val cashDelta = calculatedTrades.sumOf { trade ->
                        val gross = trade.quantity * trade.priceUsd
                        if (trade.type == TradeType.BUY) {
                            -gross - trade.feeKrw
                        } else {
                            gross - trade.feeKrw
                        }
                    }
                    capitalProfile = capitalProfile.copy(
                        availableCashKrw = formatEditableNumber(
                            kotlin.math.max(0.0, cash + cashDelta)
                        )
                    )
                    store.saveCapitalProfile(capitalProfile)
                },
                onRecordEntryTrade = { day, cycleKey, suggestedQuantity, sgovSellQuantity ->
                    val price = snapshot?.let { it.intraday?.price ?: it.close }
                    if (price != null && price > 0.0 && suggestedQuantity > 0.0) {
                        val now = System.currentTimeMillis()
                        val date = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
                        val buyFee = StrategyEngine.feeFromRate(
                            suggestedQuantity,
                            price,
                            1.0,
                            feeRatePercent
                        )
                        val tqqqBuy = PortfolioTrade(
                            id = now,
                            date = date,
                            type = TradeType.BUY,
                            quantity = suggestedQuantity,
                            priceUsd = price,
                            fxKrwPerUsd = 1.0,
                            feeKrw = buyFee,
                            feeRatePercent = feeRatePercent,
                            entryCycleKey = cycleKey,
                            entryDay = day,
                            ticker = "TQQQ"
                        )
                        val generatedTrades = buildList {
                            if (
                                sgovSellQuantity >= 1.0 &&
                                snapshot.sgovPrice > 0.0
                            ) {
                                val sellFee = StrategyEngine.feeFromRate(
                                    sgovSellQuantity,
                                    snapshot.sgovPrice,
                                    1.0,
                                    feeRatePercent
                                )
                                add(
                                    PortfolioTrade(
                                        id = now - 1,
                                        date = date,
                                        type = TradeType.SELL,
                                        quantity = sgovSellQuantity,
                                        priceUsd = snapshot.sgovPrice,
                                        fxKrwPerUsd = 1.0,
                                        feeKrw = sellFee,
                                        feeRatePercent = feeRatePercent,
                                        ticker = "SGOV"
                                    )
                                )
                            }
                            add(tqqqBuy)
                        }
                        trades = (trades + generatedTrades)
                            .sortedWith(compareBy<PortfolioTrade> { it.date }.thenBy { it.id })
                        store.saveTrades(trades)
                        val total = capitalProfile.totalAssetsKrw
                            .replace(",", "")
                            .toDoubleOrNull() ?: 0.0
                        val cash = capitalProfile.availableCashKrw
                            .replace(",", "")
                            .toDoubleOrNull() ?: total
                        val cashDelta = generatedTrades.sumOf { trade ->
                            val gross = trade.quantity * trade.priceUsd
                            if (trade.type == TradeType.BUY) {
                                -gross - trade.feeKrw
                            } else {
                                gross - trade.feeKrw
                            }
                        }
                        capitalProfile = capitalProfile.copy(
                            entryCycleKey = cycleKey,
                            entryCapitalKrw = capitalProfile.entryCapitalKrw
                                .ifBlank { formatEditableNumber(total) },
                            completedEntryDays = capitalProfile.completedEntryDays + day,
                            availableCashKrw = formatEditableNumber(
                                kotlin.math.max(0.0, cash + cashDelta)
                            )
                        )
                        store.saveCapitalProfile(capitalProfile)
                    }
                }
            )
        }
        item {
            val enabled = summary.cycleStartTradeId != null && summary.error == null
            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0xFFFFF7E6)),
                shape = RoundedCornerShape(16.dp),
                elevation = CardDefaults.cardElevation(defaultElevation = 3.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
            Column(Modifier.padding(18.dp)) {
                Text("MANUAL COMPLETION", color = ChartOrange, fontFamily = FontFamily.Monospace, fontSize = 10.sp)
                Text("실제로 완료한 부분익절", color = Ink, fontWeight = FontWeight.Black, fontSize = 19.sp)
                Spacer(Modifier.height(6.dp))
                Text(
                    if (enabled) {
                        "도달한 다음 단계만 누를 수 있습니다. 전략 수량·현재 달러 참고가·저장된 수수료율로 정산을 확인한 뒤 한 번에 기록합니다."
                    } else {
                        "현재 보유 중인 TQQQ 사이클이 없습니다. 매수 거래를 먼저 등록하면 사용할 수 있습니다."
                    },
                    color = Muted,
                    fontSize = 11.sp,
                    lineHeight = 17.sp
                )
                Spacer(Modifier.height(12.dp))
                listOf(0.10, 0.25, 0.50, 1.0, 2.0, 3.0).chunked(2).forEach { row ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        row.forEach { threshold ->
                            val selected = threshold in completed
                            val isNextReached = plan?.orders?.firstOrNull()?.threshold == threshold
                            MilestoneChip(
                                threshold = threshold,
                                selected = selected,
                                enabled = enabled && (selected || isNextReached),
                                onClick = { pendingMilestone = threshold },
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                }
            }
            }
        }
    }
}

@Composable
private fun CapitalAllocationCard(
    snapshot: MarketSnapshot?,
    profile: CapitalProfile,
    hasTqqqPosition: Boolean,
    tqqqSummary: PortfolioSummary?,
    trades: List<PortfolioTrade>,
    onProfileChange: (CapitalProfile) -> Unit,
    defaultFeeRatePercent: Double,
    cashDeposits: List<CashDeposit>,
    onAddCash: () -> Unit,
    onEditCashDeposit: (CashDeposit) -> Unit,
    onAddTrade: () -> Unit,
    onShowLedger: () -> Unit,
    onRegisterCalculatedTrades: (List<PortfolioTrade>) -> Unit,
    onRecordEntryTrade: (Int, String, Double, Double) -> Unit
) {
    val totalAssets = profile.totalAssetsKrw.replace(",", "").toDoubleOrNull()
    val tqqqPrice = snapshot?.intraday?.price ?: snapshot?.close
    val recoveringMissedEntry =
        snapshot?.regime?.state == RegimeState.RISK_ON && profile.hasIncompleteEntryCycle()
    val entryDay = snapshot?.regime?.state?.entryDayNumber() ?: if (recoveringMissedEntry) 3 else null
    val entryCycleId = snapshot?.let(::entryCycleId) ?: profile.entryCycleKey.takeIf { recoveringMissedEntry }
    val allocationState = if (recoveringMissedEntry) {
        RegimeState.ENTRY_DAY_3
    } else snapshot?.regime?.state
    val fixedEntryCapital = profile.entryCapitalKrw.toDoubleOrNull()
        ?.takeIf {
            profile.entryCycleKey == entryCycleId &&
                it.isFinite() &&
                it > 0.0
        }
    val automaticEntryCapital = totalAssets
        ?.takeIf { entryDay == 1 && fixedEntryCapital == null && !hasTqqqPosition && it > 0.0 }
    val effectiveEntryCapital = fixedEntryCapital ?: automaticEntryCapital
    val untrackedExistingEntry =
        entryDay != null && effectiveEntryCapital == null && hasTqqqPosition
    val untrackedLaterEntry = entryDay != null && entryDay > 1 && effectiveEntryCapital == null
    val currentSgovQuantity = trades.filter { it.ticker == "SGOV" }.sumOf {
        if (it.type == TradeType.BUY) it.quantity else -it.quantity
    }.coerceAtLeast(0.0)
    val currentSpymQuantity = trades.filter { it.ticker == "SPYM" }.sumOf {
        if (it.type == TradeType.BUY) it.quantity else -it.quantity
    }.coerceAtLeast(0.0)
    val usesAutomaticNewCash =
        currentSgovQuantity <= 1e-8 &&
            currentSpymQuantity <= 1e-8 &&
            profile.availableCashKrw.isBlank()
    val availableCashKrw =
        profile.availableCashKrw.replace(",", "").toDoubleOrNull()
            ?: if (usesAutomaticNewCash) totalAssets ?: 0.0 else 0.0
    val holdingsInputInvalid =
        profile.availableCashKrw.isNotBlank() &&
            (profile.availableCashKrw.replace(",", "").toDoubleOrNull()
                ?.let { it < 0.0 } != false)
    val currentTqqqQuantity = kotlin.math.max(
        0.0,
        trades.filter { it.ticker == "TQQQ" }.sumOf {
            if (it.type == TradeType.BUY) it.quantity else -it.quantity
        }
    )
    val calculatedStrategyAssetsKrw = snapshot?.let {
        currentTqqqQuantity * tqqqPrice.orZero() +
            currentSgovQuantity * it.sgovPrice +
            currentSpymQuantity * it.spymPrice +
            availableCashKrw
    }
    val allocationCapital = if (
        allocationState == RegimeState.RISK_ON &&
        hasTqqqPosition &&
        totalAssets != null
    ) {
        kotlin.math.max(0.0, totalAssets - currentTqqqQuantity * tqqqPrice.orZero())
    } else {
        totalAssets
    }
    val spentEntryKrw = trades
        .filter {
            it.type == TradeType.BUY &&
                it.ticker == "TQQQ" &&
                it.entryCycleKey == entryCycleId &&
                it.entryDay in 1..3
        }
        .sumOf { it.quantity * it.priceUsd + it.feeKrw }
    val planError = StrategyEngine.capitalAllocationError(
        state = allocationState,
        totalAssetsKrw = allocationCapital,
        tqqqPriceUsd = tqqqPrice,
        spymPriceUsd = snapshot?.spymPrice,
        sgovPriceUsd = snapshot?.sgovPrice,
        usdKrw = 1.0,
        spentEntryKrw = spentEntryKrw,
        currentTqqqQuantity = currentTqqqQuantity,
        currentSgovQuantity = currentSgovQuantity,
        currentSpymQuantity = currentSpymQuantity,
        availableCashKrw = availableCashKrw,
        feeRatePercent = defaultFeeRatePercent
    )
    val plan = if (
        snapshot != null &&
        allocationCapital != null &&
        allocationCapital > 0.0 &&
        tqqqPrice != null &&
        !untrackedExistingEntry &&
        !untrackedLaterEntry &&
        !holdingsInputInvalid &&
        allocationState != null
    ) {
        StrategyEngine.capitalAllocationPlan(
            state = allocationState,
            totalAssetsKrw = allocationCapital,
            tqqqPriceUsd = tqqqPrice,
            spymPriceUsd = snapshot.spymPrice,
            sgovPriceUsd = snapshot.sgovPrice,
            usdKrw = 1.0,
            entryCapitalKrw = effectiveEntryCapital,
            completedEntryDays = if (fixedEntryCapital != null) {
                profile.completedEntryDays
            } else emptySet(),
            spentEntryKrw = spentEntryKrw,
            currentTqqqQuantity = currentTqqqQuantity,
            currentSgovQuantity = currentSgovQuantity,
            currentSpymQuantity = currentSpymQuantity,
            availableCashKrw = availableCashKrw,
            feeRatePercent = defaultFeeRatePercent
        )
    } else null
    var showResetConfirmation by remember { mutableStateOf(false) }
    var showAllocationRegistration by remember { mutableStateOf(false) }
    var editingTotalAssets by remember { mutableStateOf(profile.totalAssetsKrw.isBlank()) }
    var totalAssetsDraft by remember { mutableStateOf(profile.totalAssetsKrw) }
    var totalAssetsEditError by remember { mutableStateOf<String?>(null) }
    var showCashHistory by remember { mutableStateOf(false) }

    LaunchedEffect(profile.totalAssetsKrw, editingTotalAssets) {
        if (!editingTotalAssets) totalAssetsDraft = profile.totalAssetsKrw
    }

    if (showResetConfirmation) {
        AlertDialog(
            onDismissRequest = { showResetConfirmation = false },
            title = { Text("분할매수 기록 초기화") },
            text = {
                Text("3일 매수 기준금액과 1·2·3차 체결 연결을 지울까요? 총자산과 SGOV·SPYM 입력값은 유지됩니다.")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onProfileChange(
                            profile.copy(
                                entryCycleKey = "",
                                entryCapitalKrw = "",
                                completedEntryDays = emptySet()
                            )
                        )
                        showResetConfirmation = false
                    }
                ) {
                    Text("초기화", color = Orange)
                }
            },
            dismissButton = {
                TextButton(onClick = { showResetConfirmation = false }) {
                    Text("취소")
                }
            }
        )
    }

    if (showAllocationRegistration && plan != null) {
        AlertDialog(
            onDismissRequest = { showAllocationRegistration = false },
            title = { Text("계산된 조정을 계좌에 등록") },
            text = {
                Text(
                    "표시된 TQQQ·SGOV·SPYM 매수·매도를 현재 참고가로 공통 거래 원장에 바로 등록합니다."
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val now = System.currentTimeMillis()
                        val date = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
                        onRegisterCalculatedTrades(
                            plan.targets
                                .filter { kotlin.math.abs(it.orderQuantity) >= 1.0 }
                                .mapIndexed { index, target ->
                                    val quantity = kotlin.math.floor(
                                        kotlin.math.abs(target.orderQuantity)
                                    )
                                    PortfolioTrade(
                                        id = now + index,
                                        date = date,
                                        type = if (target.orderQuantity > 0.0) {
                                            TradeType.BUY
                                        } else {
                                            TradeType.SELL
                                        },
                                        quantity = quantity,
                                        priceUsd = target.referencePriceUsd,
                                        fxKrwPerUsd = 1.0,
                                        feeKrw = StrategyEngine.feeFromRate(
                                            quantity,
                                            target.referencePriceUsd,
                                            1.0,
                                            defaultFeeRatePercent
                                        ),
                                        feeRatePercent = defaultFeeRatePercent,
                                        ticker = target.ticker
                                    )
                                }
                            )
                        showAllocationRegistration = false
                    }
                ) {
                    Text("조정 완료로 등록", color = LimeDark, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showAllocationRegistration = false }) {
                    Text("취소")
                }
            }
        )
    }

    val currentFx = snapshot?.usdKrw?.takeIf { it > 0.0 }
    val totalFxPnl = currentFx?.let { rate ->
        cashDeposits.sumOf { it.amountUsd * (rate - it.fxKrwPerUsd) }
    }
    val totalFxCost = cashDeposits.sumOf { it.amountUsd * it.fxKrwPerUsd }
    val totalFxReturn = totalFxPnl?.takeIf { totalFxCost > 0.0 }?.div(totalFxCost)

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Card(
            colors = CardDefaults.cardColors(containerColor = PaperStrong),
            shape = RoundedCornerShape(16.dp),
            elevation = CardDefaults.cardElevation(defaultElevation = 3.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
        Column(Modifier.padding(20.dp)) {
            if (editingTotalAssets) {
            Text("총자산", color = Ink, fontWeight = FontWeight.Black, fontSize = 17.sp)
            Spacer(Modifier.height(8.dp))
            LedgerField(
                value = totalAssetsDraft,
                onValueChange = {
                    totalAssetsDraft = it
                    totalAssetsEditError = null
                },
                label = "현재 전략계좌 총자산 (USD)",
                keyboardType = KeyboardType.Decimal
            )
            totalAssetsEditError?.let {
                Text(it, color = Orange, fontSize = 10.sp)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                if (profile.totalAssetsKrw.isNotBlank()) {
                    TextButton(
                        onClick = {
                            totalAssetsDraft = profile.totalAssetsKrw
                            totalAssetsEditError = null
                            editingTotalAssets = false
                        }
                    ) { Text("취소", color = Muted) }
                }
                Button(
                    onClick = {
                        val parsed = totalAssetsDraft.replace(",", "").toDoubleOrNull()
                        if (parsed == null || parsed <= 0.0) {
                            totalAssetsEditError = "총자산은 0보다 큰 달러 금액으로 입력하세요."
                        } else {
                            val saved = formatEditableNumber(parsed)
                            onProfileChange(profile.copy(totalAssetsKrw = saved))
                            totalAssetsDraft = saved
                            editingTotalAssets = false
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Ink, contentColor = Lime),
                    shape = RoundedCornerShape(0.dp)
                ) { Text("총자산 저장", fontWeight = FontWeight.Bold) }
            }
            } else {
                Row(
                Modifier.fillMaxWidth().padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                    Text("총자산", color = Ink, fontWeight = FontWeight.Black, fontSize = 17.sp)
                    Text(
                        totalAssets?.let(::formatUsd) ?: "입력 필요",
                        color = Ink,
                        fontSize = 38.sp,
                        fontWeight = FontWeight.Black
                    )
                    if (totalAssets != null) {
                        snapshot?.usdKrw?.takeIf { it > 0.0 }?.let { rate ->
                            Text(
                                formatKrw(totalAssets * rate),
                                color = Muted,
                                fontSize = 14.sp
                            )
                        }
                    }
                    }
                    TextButton(
                    onClick = {
                        totalAssetsDraft = profile.totalAssetsKrw
                        totalAssetsEditError = null
                        editingTotalAssets = true
                    }
                    ) { Text("수정", color = LimeDark, fontWeight = FontWeight.Bold) }
                }
            }
            Spacer(Modifier.height(12.dp))
            Box(Modifier.fillMaxWidth().height(1.dp).background(Color(0xFFE2E8F0)))
            Spacer(Modifier.height(10.dp))
            Row(Modifier.fillMaxWidth()) {
                Column(Modifier.weight(1f)) {
                    Text("보유 수익률", color = Muted, fontSize = 10.sp)
                    Text(
                        tqqqSummary?.positionReturn?.let(::signedPercent) ?: "—",
                        color = tqqqSummary?.positionReturn?.let { if (it >= 0.0) LimeDark else Orange } ?: Muted,
                        fontWeight = FontWeight.Black,
                        fontSize = 17.sp
                    )
                }
                Column(Modifier.weight(1f)) {
                    Text("환차손익", color = Muted, fontSize = 10.sp)
                    Text(
                        totalFxPnl?.let { pnl ->
                            "${signedKrw(pnl)}${totalFxReturn?.let { " (${signedPercent(it)})" }.orEmpty()}"
                        } ?: "—",
                        color = totalFxPnl?.let { if (it >= 0.0) LimeDark else Orange } ?: Muted,
                        fontWeight = FontWeight.Black,
                        fontSize = 17.sp
                    )
                }
            }
        }
        }
        Card(
            colors = CardDefaults.cardColors(containerColor = PaperStrong),
            shape = RoundedCornerShape(16.dp),
            elevation = CardDefaults.cardElevation(defaultElevation = 3.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
        Column(Modifier.padding(20.dp)) {
        Text("보유 자산", color = Ink, fontWeight = FontWeight.Black, fontSize = 21.sp)
        Spacer(Modifier.height(10.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("TQQQ", color = Ink, fontWeight = FontWeight.Black, fontSize = 17.sp)
                Text(
                    tqqqSummary?.averagePriceUsd?.let { "평균단가 ${formatUsdPrice(it)}" } ?: "평균단가 —",
                    color = Muted,
                    fontSize = 10.sp
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                Text("${formatQuantity(currentTqqqQuantity)}주", color = Ink, fontWeight = FontWeight.Black, fontSize = 17.sp)
                Text(
                    tqqqSummary?.positionReturn?.let { "수익률 ${signedPercent(it)}" } ?: "수익률 —",
                    color = tqqqSummary?.positionReturn?.let { if (it >= 0.0) LimeDark else Orange } ?: Muted,
                    fontSize = 10.sp
                )
            }
            Spacer(Modifier.width(18.dp))
            Text(
                tqqqPrice?.let { formatUsd(currentTqqqQuantity * it) } ?: "—",
                color = Ink,
                fontWeight = FontWeight.Black,
                fontSize = 17.sp
            )
        }
            Spacer(Modifier.height(12.dp))
            Box(Modifier.fillMaxWidth().height(1.dp).background(Color(0xFFE2E8F0)))
            Spacer(Modifier.height(10.dp))
            Surface(
                color = Paper,
                border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFE2E8F0)),
                shape = RoundedCornerShape(10.dp)
            ) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("사용 가능 현금", color = Ink, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                Spacer(Modifier.weight(1f))
                Text(formatUsd(availableCashKrw), color = Ink, fontWeight = FontWeight.Black, fontSize = 18.sp)
                TextButton(onClick = onAddCash) { Text("+ 입금", color = LimeDark, fontWeight = FontWeight.Bold) }
            }
            }
            ResultLine(
                "SGOV",
                "${formatQuantity(currentSgovQuantity)}주 · ${snapshot?.let { formatUsd(currentSgovQuantity * it.sgovPrice) } ?: "—"}"
            )
            ResultLine(
                "SPYM",
                "${formatQuantity(currentSpymQuantity)}주 · ${snapshot?.let { formatUsd(currentSpymQuantity * it.spymPrice) } ?: "—"}"
            )
            if (cashDeposits.isNotEmpty()) {
                TextButton(onClick = { showCashHistory = !showCashHistory }) {
                    Text(if (showCashHistory) "입금내역 접기" else "입금내역 ${cashDeposits.size}건", color = Muted, fontSize = 10.sp)
                }
            }
            if (showCashHistory) cashDeposits.take(3).forEach { deposit ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            "${deposit.date} · ${
                                if (deposit.inputCurrency == "KRW") {
                                    "+${NumberFormat.getIntegerInstance(Locale.KOREA).format(deposit.inputAmount)}원 → ${formatUsd(deposit.amountUsd)}"
                                } else {
                                    "+${formatUsd(deposit.inputAmount)}"
                                }
                            } · ${NumberFormat.getIntegerInstance(Locale.KOREA).format(deposit.fxKrwPerUsd)} KRW/USD",
                            color = Muted,
                            fontSize = 10.sp
                        )
                        currentFx?.let { rate ->
                            val fxPnl = deposit.amountUsd * (rate - deposit.fxKrwPerUsd)
                            val fxReturn = rate / deposit.fxKrwPerUsd - 1.0
                            Text(
                                "환차손익 ${signedKrw(fxPnl)} (${signedPercent(fxReturn)})",
                                color = if (fxPnl >= 0.0) LimeDark else Orange,
                                fontSize = 10.sp
                            )
                        }
                    }
                    TextButton(onClick = { onEditCashDeposit(deposit) }) {
                        Text("수정", color = LimeDark, fontSize = 10.sp)
                    }
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = onAddTrade,
                    colors = ButtonDefaults.buttonColors(containerColor = Ink, contentColor = Lime),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.weight(1f)
                ) { Text("거래 추가", fontWeight = FontWeight.Bold) }
                Button(
                    onClick = onShowLedger,
                    colors = ButtonDefaults.buttonColors(containerColor = Lime, contentColor = Ink),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.weight(1f)
                ) { Text("거래내역 ${trades.size}건", fontWeight = FontWeight.Bold) }
            }
        }
        }
        when {
            profile.totalAssetsKrw.isBlank() -> {
                Text("총자산을 입력하면 배분안이 표시됩니다.", color = Muted, fontSize = 11.sp)
            }

            totalAssets == null || totalAssets <= 0.0 -> {
                Text("총자산은 0보다 큰 숫자로 입력하세요.", color = Orange, fontSize = 11.sp)
            }

            holdingsInputInvalid -> {
                Text("SGOV·SPYM 수량과 현금은 0 이상의 숫자로 입력하세요.", color = Orange, fontSize = 11.sp)
            }

            snapshot == null -> {
                Text("시장 데이터를 받은 뒤 배분안을 계산합니다.", color = Muted, fontSize = 11.sp)
            }

            untrackedExistingEntry -> {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .background(Color(0xFFFFE7E2))
                        .padding(14.dp)
                ) {
                    Text("TQQQ 원장과 진입기록 불일치", color = Orange, fontWeight = FontWeight.Bold)
                    Text(
                        "TQQQ 보유수량은 있지만 이번 상향 돌파 사이클의 시작금액·차수 기록이 없습니다. 앱이 추가매수를 계산하지 않습니다. 거래내역과 실제 진입 시점을 확인하세요.",
                        color = Muted,
                        fontSize = 11.sp,
                        lineHeight = 17.sp
                    )
                }
            }

            untrackedLaterEntry -> {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .background(Color(0xFFFFF1D6))
                        .padding(14.dp)
                ) {
                    Text("원문에 처리 규칙 없음", color = ChartOrange, fontWeight = FontWeight.Bold)
                    Text(
                        "상향 돌파 ${entryDay}일 차에 처음 시작하는 경우의 TQQQ 매수법은 제공된 원문에 명시돼 있지 않습니다. 앱이 반반 또는 추격매수를 임의로 제안하지 않습니다. 1차를 실제로 매수했다면 거래 원장에 그 체결을 기록하세요.",
                        color = Muted,
                        fontSize = 11.sp,
                        lineHeight = 17.sp
                    )
                }
            }

            plan == null -> {
                Text(
                    planError ?: "신규 주문 계산 조건을 확인할 수 없습니다.",
                    color = Orange,
                    fontSize = 11.sp
                )
            }

            else -> {
                Card(
                    colors = CardDefaults.cardColors(containerColor = Ink),
                    shape = RoundedCornerShape(16.dp),
                    elevation = CardDefaults.cardElevation(defaultElevation = 3.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                Column(Modifier.padding(20.dp)) {
                    Text("현재 확정 신호", color = Lime, fontFamily = FontFamily.Monospace, fontSize = 10.sp)
                    Text(plan.title, color = PaperStrong, fontWeight = FontWeight.Black, fontSize = 22.sp)
                    Text(plan.description, color = Color(0xFFADB4AF), fontSize = 11.sp, lineHeight = 17.sp)

                    plan.entryCapitalKrw?.let {
                        Spacer(Modifier.height(12.dp))
                        EntryProgress(plan.completedEntryDays, plan.nextEntryDay)
                    }

                    plan.todayTqqqBuyKrw?.let { amount ->
                        Spacer(Modifier.height(14.dp))
                        Surface(color = Color(0xFF1D3355), shape = RoundedCornerShape(10.dp)) {
                        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                        AllocationLine(
                            label = "오늘 TQQQ 매수",
                            value = "${formatUsd(amount)} · 약 ${
                                formatQuantity(plan.todayTqqqBuyQuantity ?: 0.0)
                            }주",
                            highlight = true
                        )
                        plan.todayCashUseKrw?.let {
                            AllocationLine("사용 가능 현금 사용", formatUsd(it))
                        }
                        plan.todaySgovSellQuantity?.let {
                            AllocationLine("매수재원 SGOV 매도", "약 ${formatQuantity(it)}주")
                        }
                        if (plan.fundingShortfallKrw > 0.0) {
                            AllocationLine(
                                "입력된 매수재원 부족",
                                formatUsd(plan.fundingShortfallKrw),
                                highlight = true
                            )
                        }
                        }
                        }
                    }
                    if (plan.todayTqqqBuyKrw == null && plan.entryDay != null && plan.nextEntryDay == null) {
                        Spacer(Modifier.height(14.dp))
                        Surface(color = Color(0xFF1D3355), shape = RoundedCornerShape(10.dp)) {
                            Text(
                                "1·2·3차 체결 완료 · 추가 매수 없음",
                                color = Lime,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.fillMaxWidth().padding(12.dp)
                            )
                        }
                    }

                    if (plan.entryDay == null) {
                        Spacer(Modifier.height(12.dp))
                        Text("목표 보유", color = Color(0xFFADB4AF), fontSize = 10.sp)
                        plan.targets.forEach { target ->
                            AllocationLine(
                                label = "${target.ticker} ${(target.fraction * 100).toInt()}%",
                                value = "${formatUsd(target.amountKrw)} · 약 ${formatQuantity(target.quantity)}주"
                            )
                            if (target.ticker in setOf("SGOV", "SPYM") &&
                            kotlin.math.abs(target.orderQuantity) > 1e-8
                            ) {
                                AllocationLine(
                                    label = "${target.ticker} 실제 조정",
                                    value = "${formatQuantity(kotlin.math.abs(target.orderQuantity))}주 ${
                                        if (target.orderQuantity > 0) "매수" else "매도"
                                    }"
                                )
                            }
                        }
                    }

                    if (
                        plan.entryDay == null &&
                        plan.targets.isNotEmpty() &&
                        plan.targets.all {
                            it.ticker == "TQQQ" || it.ticker == "SGOV" || it.ticker == "SPYM"
                        } &&
                        plan.targets.any { kotlin.math.abs(it.orderQuantity) >= 1.0 }
                    ) {
                        Spacer(Modifier.height(14.dp))
                        Button(
                            onClick = { showAllocationRegistration = true },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Lime,
                                contentColor = Ink
                            ),
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("계산대로 조정 완료 등록", fontWeight = FontWeight.Bold)
                        }
                        Text(
                            "실제 주문은 증권사 앱에서 실행한 뒤 등록하세요.",
                            color = Color(0xFFADB4AF),
                            fontSize = 9.sp
                        )
                    }

                    val nextEntryDay = plan.nextEntryDay
                    val currentEntryDay = plan.entryDay
                    if (
                        nextEntryDay != null &&
                        currentEntryDay != null &&
                        nextEntryDay <= currentEntryDay
                    ) {
                        Spacer(Modifier.height(12.dp))
                        Button(
                            enabled = plan.fundingShortfallKrw <= 0.0,
                            onClick = {
                                if (fixedEntryCapital == null) {
                                    onProfileChange(
                                        profile.copy(
                                            entryCycleKey = entryCycleId ?: snapshot.latestDate,
                                            entryCapitalKrw = formatEditableNumber(totalAssets),
                                            availableCashKrw = if (usesAutomaticNewCash) {
                                                formatEditableNumber(totalAssets)
                                            } else {
                                                profile.availableCashKrw
                                            },
                                            completedEntryDays = emptySet()
                                        )
                                    )
                                }
                                onRecordEntryTrade(
                                    nextEntryDay,
                                    entryCycleId ?: snapshot.latestDate,
                                    plan.todayTqqqBuyQuantity ?: 0.0,
                                    plan.todaySgovSellQuantity ?: 0.0
                                )
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (nextEntryDay == currentEntryDay) Lime else ChartOrange,
                                contentColor = Ink
                            ),
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                if (nextEntryDay == currentEntryDay) {
                                    if (plan.fundingShortfallKrw > 0.0) {
                                        "SGOV 수량·현금을 먼저 확인"
                                    } else {
                                        "계산대로 TQQQ ${nextEntryDay}차 매수 등록"
                                    }
                                } else {
                                    "계산대로 TQQQ ${nextEntryDay}차 매수 등록"
                                },
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    "수량은 현재 달러 시세와 공통 거래 원장 기준 자동 계산값입니다. 등록 버튼을 누르면 해당 종목의 매수·매도 거래로 바로 추가됩니다.",
                    color = Muted,
                    fontSize = 10.sp,
                    lineHeight = 15.sp
                )
                if (plan.entryDay != null) {
                    TextButton(onClick = { showResetConfirmation = true }) {
                        Text("이번 3등분 기준 초기화", color = Orange, fontSize = 10.sp)
                    }
                }
            }
        }
    }
}

private fun Double?.orZero(): Double = this ?: 0.0

private fun CapitalProfile.hasIncompleteEntryCycle(): Boolean =
    entryCycleKey.isNotBlank() && entryCapitalKrw.toDoubleOrNull()?.let { it > 0.0 } == true &&
        (1..3).any { it !in completedEntryDays }

private fun RegimeState.entryDayNumber(): Int? = when (this) {
    RegimeState.ENTRY_DAY_1 -> 1
    RegimeState.ENTRY_DAY_2 -> 2
    RegimeState.ENTRY_DAY_3 -> 3
    else -> null
}

private fun entryCycleId(snapshot: MarketSnapshot): String? {
    val day = snapshot.regime.state.entryDayNumber() ?: return null
    val cycleStartIndex = snapshot.chart.lastIndex - (day - 1)
    return snapshot.chart.getOrNull(cycleStartIndex)?.date
}

@Composable
private fun PriceReturnLine(ticker: String, currentPrice: Double, averagePrice: Double?) {
    if (!currentPrice.isFinite() || currentPrice <= 0.0) return
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text("$ticker 현재가", color = Muted, fontSize = 10.sp)
        Spacer(Modifier.weight(1f))
        val returnText = averagePrice
            ?.takeIf { it.isFinite() && it > 0.0 }
            ?.let { " · 매수가 대비 ${signedPercent(currentPrice / it - 1.0)}" }
            .orEmpty()
        Text(
            "$${"%.2f".format(Locale.US, currentPrice)}$returnText",
            color = Ink,
            fontFamily = FontFamily.Monospace,
            fontSize = 10.sp
        )
    }
}

@Composable
private fun AllocationLine(label: String, value: String, highlight: Boolean = false) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(
            label,
            color = if (highlight) Lime else Color(0xFFADB4AF),
            fontWeight = if (highlight) FontWeight.Bold else FontWeight.Normal,
            fontSize = 11.sp
        )
        Spacer(Modifier.weight(1f))
        Text(
            value,
            color = if (highlight) Lime else PaperStrong,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            fontSize = 11.sp
        )
    }
}

@Composable
private fun EntryProgress(completedDays: Set<Int>, nextDay: Int?) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        (1..3).forEach { day ->
            val completed = day in completedDays
            val active = day == nextDay
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                Surface(
                    color = when {
                        completed -> LimeDark
                        active -> Color(0xFF3B82F6)
                        else -> Color(0xFF94A3B8)
                    },
                    shape = CircleShape,
                    modifier = Modifier.size(30.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(if (completed) "✓" else day.toString(), color = Color.White, fontWeight = FontWeight.Black)
                    }
                }
                Spacer(Modifier.height(5.dp))
                Text(
                    "${day}차 ${when { completed -> "완료"; active -> "진행"; else -> "대기" }}",
                    color = if (completed || active) Lime else Color(0xFFADB4AF),
                    fontSize = 10.sp,
                    fontWeight = if (completed || active) FontWeight.Bold else FontWeight.Normal
                )
            }
        }
    }
}

@Composable
private fun AccountActionBanner(
    snapshot: MarketSnapshot?,
    summary: PortfolioSummary,
    plan: com.signal200.app.domain.TakeProfitPlan?,
    capitalProfile: CapitalProfile,
    trades: List<PortfolioTrade>,
    onTakeProfit: (Double) -> Unit
) {
    val hasPosition = summary.error == null && summary.remainingQuantity > 1e-8
    val entryStatus = snapshot?.let {
        StrategyEngine.tqqqEntryStatus(it.regime.state, hasPosition)
    }
    val spymQuantity = trades.filter { it.ticker == "SPYM" }.sumOf {
        if (it.type == TradeType.BUY) it.quantity else -it.quantity
    }.coerceAtLeast(0.0)
    val hasSpymPosition = spymQuantity > 1e-8
    val nextProfitOrder = plan?.orders?.firstOrNull()
    val recoveringMissedEntry =
        snapshot?.regime?.state == RegimeState.RISK_ON && capitalProfile.hasIncompleteEntryCycle()
    val activeEntryDay = snapshot?.regime?.state?.entryDayNumber() ?: if (recoveringMissedEntry) 3 else null
    val activeCycleId = snapshot?.let(::entryCycleId) ?:
        capitalProfile.entryCycleKey.takeIf { recoveringMissedEntry }
    val activeEntryCapital = capitalProfile.entryCapitalKrw.toDoubleOrNull()
        ?.takeIf {
            capitalProfile.entryCycleKey == activeCycleId &&
                it.isFinite() &&
                it > 0.0
        }
    val activeEntrySpent = trades
        .filter {
            it.type == TradeType.BUY &&
                it.ticker == "TQQQ" &&
                it.entryCycleKey == activeCycleId &&
                it.entryDay in 1..3
        }
        .sumOf { it.quantity * it.priceUsd + it.feeKrw }
    val activeEntryPlan = if (
        snapshot != null &&
        activeEntryDay != null &&
        activeEntryCapital != null
    ) {
        StrategyEngine.capitalAllocationPlan(
            state = if (recoveringMissedEntry) RegimeState.ENTRY_DAY_3 else snapshot.regime.state,
            totalAssetsKrw = activeEntryCapital,
            tqqqPriceUsd = snapshot.intraday?.price ?: snapshot.close,
            spymPriceUsd = snapshot.spymPrice,
            sgovPriceUsd = snapshot.sgovPrice,
            usdKrw = 1.0,
            entryCapitalKrw = activeEntryCapital,
            completedEntryDays = capitalProfile.completedEntryDays,
            spentEntryKrw = activeEntrySpent,
            currentSgovQuantity = trades.filter { it.ticker == "SGOV" }.sumOf {
                if (it.type == TradeType.BUY) it.quantity else -it.quantity
            }.coerceAtLeast(0.0),
            currentSpymQuantity = spymQuantity,
            availableCashKrw =
                capitalProfile.availableCashKrw.replace(",", "").toDoubleOrNull() ?: 0.0
        )
    } else null
    val ui = when {
        summary.error != null -> AccountActionUi(
            eyebrow = "확인 필요",
            title = "거래 원장을 수정하세요",
            detail = summary.error,
            accent = Orange,
            background = Color(0xFFFFE7E2)
        )

        snapshot == null -> AccountActionUi(
            eyebrow = "시세 대기",
            title = "지금은 매도 판단 보류",
            detail = "확정 종가와 SMA200을 받아오면 여기에 매도 여부가 표시됩니다.",
            accent = Muted,
            background = Color(0xFFE9EDF3)
        )

        (hasPosition || hasSpymPosition) &&
            snapshot.regime.state in setOf(RegimeState.EXIT_PENDING, RegimeState.SAFE) ->
            AccountActionUi(
                eyebrow = "확정 종가 기준 · 전량매도",
                title = buildString {
                    if (hasPosition) {
                        append("TQQQ ${formatQuantity(summary.remainingQuantity)}주")
                    }
                    if (hasPosition && hasSpymPosition) append(" · ")
                    if (hasSpymPosition) append("SPYM ${formatQuantity(spymQuantity)}주")
                    append(" 전량매도 검토")
                },
                detail = "종가 $${"%.2f".format(Locale.US, snapshot.close)}가 SMA200 $${"%.2f".format(Locale.US, snapshot.sma200)} 아래입니다. TQQQ·SPYM을 정리하고 아래 카드의 SGOV 목표수량을 확인하세요.",
                accent = Orange,
                background = Color(0xFFFFE7E2)
            )

        hasPosition && activeEntryDay != null && activeEntryCapital == null ->
            AccountActionUi(
                eyebrow = "확인 필요 · 진입기록 불일치",
                title = "TQQQ 추가매수 자동 계산 보류",
                detail = "TQQQ 원장에는 보유수량이 있지만 이번 상향 돌파 사이클의 기준금액·차수 기록이 없습니다. 실제 거래내역을 먼저 확인하세요.",
                accent = Orange,
                background = Color(0xFFFFE7E2)
            )

        activeEntryPlan?.todayTqqqBuyKrw != null -> AccountActionUi(
            eyebrow = "상향 돌파 · ${activeEntryDay}차 분할매수",
            title = if (activeEntryPlan.fundingShortfallKrw > 0.0) {
                "SGOV 수량·현금 입력을 먼저 확인"
            } else {
                "오늘 TQQQ ${formatUsd(activeEntryPlan.todayTqqqBuyKrw)} 매수 확인"
            },
            detail = if (activeEntryPlan.fundingShortfallKrw > 0.0) {
                "입력된 매수재원이 ${formatUsd(activeEntryPlan.fundingShortfallKrw)} 부족해 주문 기록을 잠갔습니다."
            } else {
                "실제 남은 진입자금 기준입니다. 아래 카드에서 참고수량을 확인하고 증권사 체결 후 거래를 기록하세요."
            },
            accent = if (activeEntryPlan.fundingShortfallKrw > 0.0) Orange else LimeDark,
            background = if (activeEntryPlan.fundingShortfallKrw > 0.0) {
                Color(0xFFFFE7E2)
            } else Lime
        )

        hasPosition && nextProfitOrder != null -> AccountActionUi(
            eyebrow = "수익률 기준 · 부분익절",
            title = "지금 ${formatQuantity(nextProfitOrder.quantity)}주 부분매도",
            detail = "+${(nextProfitOrder.threshold * 100).toInt()}% 단계에 도달했습니다. 증권사에서 매도한 뒤 아래 버튼으로 원장과 완료 상태를 한 번에 기록하세요.",
            accent = ChartOrange,
            background = Color(0xFFFFF1D6)
        )

        hasPosition -> AccountActionUi(
            eyebrow = "현재 판단 · 보유",
            title = "지금은 팔지 않고 보유",
            detail = "확정 종가가 SMA200 위이고 새 부분익절 단계에 도달하지 않았습니다. SMA200 하향 이탈 전 선매도는 원칙 밖입니다.",
            accent = HoldTeal,
            background = HoldPale
        )

        entryStatus == TqqqEntryStatus.START_ON_CROSS -> AccountActionUi(
            eyebrow = "TQQQ 0주 · 정규 신규진입",
            title = "오늘이 TQQQ 1차 매수 시점",
            detail = "확정 종가가 SMA200 아래에서 위로 돌파했습니다. 아래 신규 자금 카드에서 총자산을 3등분하고 1차 참고금액을 확인하세요.",
            accent = LimeDark,
            background = Lime
        )

        entryStatus == TqqqEntryStatus.VERIFY_EXISTING_ENTRY -> AccountActionUi(
            eyebrow = "TQQQ 0주 · 진입시점 확인",
            title = "놓친 차수의 처리 규칙이 원문에 없음",
            detail = "현재는 상향 돌파 ${if (snapshot.regime.state == RegimeState.ENTRY_DAY_2) "2" else "3"}일 차입니다. 앱이 추격매수나 반반 배분을 임의로 정하지 않습니다. 1차를 실제로 매수했다면 먼저 거래 원장에 기록하세요.",
            accent = ChartOrange,
            background = Color(0xFFFFF1D6)
        )

        entryStatus == TqqqEntryStatus.LATE_ENTRY_NO_TQQQ -> AccountActionUi(
            eyebrow = "TQQQ 0주 · 후발진입",
            title = "SGOV·SPYM 반반 즉시 적용",
            detail = "TQQQ가 0주이고 확정 종가가 SMA200 위이므로 TQQQ를 추격매수하지 않고 아래 카드에서 SGOV 50%·SPYM 50% 실제 조정수량을 확인하세요.",
            accent = SafePurple,
            background = Color(0xFFEDE9FE)
        )

        else -> AccountActionUi(
            eyebrow = "현재 판단 · SGOV 대기",
            title = "지금은 매도할 TQQQ 없음",
            detail = "SMA200 아래 대피 구간입니다. 신규 자금은 SGOV에 두고 다음 확정 상향 돌파일에 TQQQ 1차 매수를 시작합니다.",
            accent = SafePurple,
            background = Color(0xFFEDE9FE)
        )
    }

    Column(
        Modifier
            .fillMaxWidth()
            .background(ui.background)
            .padding(20.dp)
    ) {
        Text("지금 할 일", color = ui.accent, fontWeight = FontWeight.Black, fontSize = 13.sp)
        Text(ui.eyebrow, color = ui.accent, fontFamily = FontFamily.Monospace, fontSize = 10.sp)
        Spacer(Modifier.height(6.dp))
        Text(ui.title, color = Ink, fontWeight = FontWeight.Black, fontSize = 24.sp, lineHeight = 30.sp)
        Spacer(Modifier.height(7.dp))
        Text(ui.detail, color = Muted, fontSize = 12.sp, lineHeight = 18.sp)
        if (hasPosition && nextProfitOrder != null &&
            snapshot?.regime?.state !in setOf(RegimeState.EXIT_PENDING, RegimeState.SAFE)
        ) {
            Spacer(Modifier.height(14.dp))
            Button(
                onClick = { onTakeProfit(nextProfitOrder.threshold) },
                colors = ButtonDefaults.buttonColors(
                    containerColor = ChartOrange,
                    contentColor = Ink
                ),
                shape = RoundedCornerShape(0.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    "증권사 매도 후 +${(nextProfitOrder.threshold * 100).toInt()}% 자동 기록",
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

@Composable
private fun AccountHoldingsCard(
    snapshot: MarketSnapshot?,
    profile: CapitalProfile,
    tqqqSummary: PortfolioSummary?,
    trades: List<PortfolioTrade>
) {
    val tqqqQuantity = tqqqSummary?.takeIf { it.error == null }?.remainingQuantity ?: 0.0
    val sgovQuantity = trades.filter { it.ticker == "SGOV" }.sumOf {
        if (it.type == TradeType.BUY) it.quantity else -it.quantity
    }.coerceAtLeast(0.0)
    val spymQuantity = trades.filter { it.ticker == "SPYM" }.sumOf {
        if (it.type == TradeType.BUY) it.quantity else -it.quantity
    }.coerceAtLeast(0.0)
    val cashUsd = profile.availableCashKrw.replace(",", "").toDoubleOrNull() ?: 0.0
    val tqqqValue = snapshot?.let { tqqqQuantity * (it.intraday?.price ?: it.close) }
    val sgovValue = snapshot?.let { sgovQuantity * it.sgovPrice }
    val spymValue = snapshot?.let { spymQuantity * it.spymPrice }
    val totalValue = if (snapshot != null) {
        (tqqqValue ?: 0.0) + (sgovValue ?: 0.0) + (spymValue ?: 0.0) + cashUsd
    } else {
        null
    }

    Column(
        Modifier
            .fillMaxWidth()
            .background(PaperStrong)
            .padding(20.dp)
    ) {
        Text("ALL ASSETS · USD", color = LimeDark, fontFamily = FontFamily.Monospace, fontSize = 10.sp)
        Text("전체 자산 현황", color = Ink, fontWeight = FontWeight.Black, fontSize = 21.sp)
        totalValue?.let {
            Text(formatUsd(it), color = Ink, fontWeight = FontWeight.Black, fontSize = 34.sp)
            snapshot?.usdKrw?.takeIf { rate -> rate > 0.0 }?.let { rate ->
                Text(
                    "현재 환율 기준 ${formatKrw(it * rate)}",
                    color = Muted,
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp
                )
            }
        } ?: Text("시세 대기", color = Muted, fontWeight = FontWeight.Bold, fontSize = 24.sp)
        Spacer(Modifier.height(12.dp))
        ResultLine(
            "TQQQ",
            "${formatQuantity(tqqqQuantity)}주 · ${tqqqValue?.let(::formatUsd) ?: "—"}"
        )
        ResultLine(
            "SGOV",
            "${formatQuantity(sgovQuantity)}주 · ${sgovValue?.let(::formatUsd) ?: "—"}"
        )
        ResultLine(
            "SPYM",
            "${formatQuantity(spymQuantity)}주 · ${spymValue?.let(::formatUsd) ?: "—"}"
        )
        ResultLine("사용 가능 현금", formatUsd(cashUsd))
    }
}

@Composable
private fun PortfolioResult(
    snapshot: MarketSnapshot?,
    summary: PortfolioSummary?,
    plan: com.signal200.app.domain.TakeProfitPlan?
) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(Lime)
            .padding(22.dp)
    ) {
        Text("TQQQ 달러 실현손익 + 평가손익 · 수수료 반영", color = Ink, fontFamily = FontFamily.Monospace, fontSize = 10.sp)
        val lastSale = summary?.saleSettlements?.lastOrNull()
        val isClosedPosition = summary != null &&
            summary.error == null &&
            summary.remainingQuantity < 1e-8 &&
            lastSale != null
        val headlineReturn = summary?.positionReturn ?: summary?.cycleReturn
        Text(
            if (isClosedPosition) {
                signedUsd(lastSale.realizedPnlKrw)
            } else if (snapshot == null && (summary?.remainingQuantity ?: 0.0) > 0.0) {
                "시세 대기"
            } else {
                headlineReturn?.let(::signedPercent) ?: "입력 대기"
            },
            color = when {
                isClosedPosition && lastSale.realizedPnlKrw < 0 -> Orange
                !isClosedPosition && headlineReturn != null && headlineReturn < 0 -> Orange
                else -> Ink
            },
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Black,
            fontSize = if (isClosedPosition) 34.sp else 48.sp
        )
        Text(
            if (isClosedPosition) {
                "마지막 매도 실현손익 · 순입금 ${formatUsd(lastSale.netProceedsKrw)}"
            } else if (snapshot != null) {
                val price = snapshot.intraday?.price ?: snapshot.close
                "합산 체결 평단 기준 보유 수익률 · TQQQ $${"%.2f".format(price)}"
            } else {
                "시장 데이터를 먼저 받아야 합니다."
            },
            color = Ink,
            fontSize = 11.sp
        )
        Spacer(Modifier.height(24.dp))
        summary?.takeIf { it.error == null }?.let {
            ResultLine("보유", "${formatQuantity(it.remainingQuantity)}주")
            it.averagePriceUsd?.let { average ->
                ResultLine("합산 체결 평단", formatUsdPrice(average))
            }
            if (snapshot != null) {
                it.positionReturn?.let { positionReturn ->
                    ResultLine("남은 보유분 수익률", signedPercent(positionReturn))
                }
                ResultLine("평가손익", signedUsd(it.unrealizedPnlKrw))
            }
            ResultLine("누적 실현손익", signedUsd(it.realizedPnlKrw))
            if (snapshot != null || it.remainingQuantity < 1e-8) {
                ResultLine("총손익", signedUsd(it.totalPnlKrw))
            }
            ResultLine("입력 수수료 합계", formatUsd(it.totalFeesKrw))
            it.saleSettlements.lastOrNull()?.let { sale ->
                ResultLine("마지막 매도 순입금", formatUsd(sale.netProceedsKrw))
                ResultLine("마지막 매도 실현손익", signedUsd(sale.realizedPnlKrw))
            }
            Spacer(Modifier.height(16.dp))
        }
        summary?.error?.let {
            Text(it, color = Orange, fontWeight = FontWeight.Bold, fontSize = 12.sp)
            Spacer(Modifier.height(14.dp))
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(Ink.copy(alpha = 0.25f)))
        Spacer(Modifier.height(24.dp))
        Text("NEXT ACTION", color = Ink, fontFamily = FontFamily.Monospace, fontSize = 10.sp)
        val action = when {
            snapshot == null -> "시장 데이터 대기"
            snapshot.regime.state == RegimeState.EXIT_PENDING ->
                "TQQQ·SPYM 전량 청산 후 SGOV 전환 검토"
            snapshot.regime.state == RegimeState.SAFE -> "SGOV에서 다음 상향 돌파 대기"
            plan != null && plan.orders.isNotEmpty() ->
                "TQQQ ${formatQuantity(plan.totalToSell)}주 익절 후 SPYM 전환 검토"
            else -> StrategyEngine.action(snapshot.regime.state)
        }
        Text(action, color = Ink, fontWeight = FontWeight.Black, fontSize = 23.sp, lineHeight = 29.sp)
        plan?.orders?.forEach { order ->
            Spacer(Modifier.height(10.dp))
            Row(Modifier.fillMaxWidth()) {
                Text("+${(order.threshold * 100).toInt()}% 단계", color = Ink, fontSize = 12.sp)
                Spacer(Modifier.weight(1f))
                Text("${formatQuantity(order.quantity)}주 → SPYM", color = Ink, fontFamily = FontFamily.Monospace)
            }
        }
    }
}

@Composable
private fun ResultLine(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Text(label, color = Ink.copy(alpha = 0.7f), fontSize = 11.sp)
        Spacer(Modifier.weight(1f))
        Text(value, color = Ink, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun TradeRow(
    trade: PortfolioTrade,
    settlement: SaleSettlement?,
    onSelect: (() -> Unit)?,
    onDelete: (() -> Unit)?
) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(if (onSelect != null) Lime.copy(alpha = 0.55f) else PaperStrong)
            .clickable(enabled = onSelect != null) { onSelect?.invoke() }
            .padding(start = 16.dp, top = 13.dp, bottom = 13.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier.background(if (trade.type == TradeType.BUY) Lime else Color(0xFFFFD7C8))
                .padding(horizontal = 8.dp, vertical = 6.dp)
        ) {
            Text(if (trade.type == TradeType.BUY) "매수" else "매도", color = Ink, fontWeight = FontWeight.Bold, fontSize = 10.sp)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                "${trade.ticker} · ${trade.date} · ${formatQuantity(trade.quantity)}주 × ${
                    formatUsdPrice(trade.priceUsd)
                } = ${formatUsd(trade.quantity * trade.priceUsd)}",
                color = Ink,
                fontWeight = FontWeight.Bold,
                fontSize = 12.sp
            )
            Text(
                buildString {
                    append("수수료 ${formatUsd(trade.feeKrw)}")
                    trade.feeRatePercent?.let { append(" (${"%.4f".format(it)}%)") }
                    trade.entryDay?.let { append(" · 분할매수 ${it}차") }
                },
                color = Muted,
                fontSize = 10.sp
            )
            settlement?.let {
                Text(
                    "순입금 ${formatUsd(it.netProceedsKrw)} · 실현손익 ${signedUsd(it.realizedPnlKrw)}",
                    color = if (it.realizedPnlKrw >= 0) HoldTeal else Orange,
                    fontWeight = FontWeight.Bold,
                    fontSize = 10.sp
                )
            }
            if (onSelect != null) {
                Text("눌러서 이 거래 수정", color = LimeDark, fontWeight = FontWeight.Bold, fontSize = 10.sp)
            }
        }
        if (onDelete != null) {
            TextButton(onClick = onDelete) { Text("삭제", color = Orange, fontSize = 11.sp) }
        }
    }
}

@Composable
private fun CashDepositDialog(
    currentFxKrwPerUsd: Double?,
    initialDeposit: CashDeposit? = null,
    onDismiss: () -> Unit,
    onSave: (CashDeposit) -> Unit
) {
    var amount by remember {
        mutableStateOf(initialDeposit?.inputAmount?.let(::formatEditableNumber) ?: "")
    }
    var currency by remember { mutableStateOf(initialDeposit?.inputCurrency ?: "USD") }
    var fxRate by remember {
        mutableStateOf(
            (initialDeposit?.fxKrwPerUsd ?: currentFxKrwPerUsd)
                ?.let(::formatEditableNumber) ?: ""
        )
    }
    var error by remember { mutableStateOf<String?>(null) }
    val date = initialDeposit?.date ?: SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initialDeposit == null) "추가 현금 등록" else "현금 입금 기록 수정") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "원화 또는 달러로 입금할 수 있습니다. 원화는 현재 환율로 USD 환산되며, 입력금액·환율·환산 USD가 함께 저장됩니다.",
                    color = Muted,
                    fontSize = 11.sp,
                    lineHeight = 17.sp
                )
                Row {
                    listOf("USD", "KRW").forEach { option ->
                        Button(
                            onClick = { currency = option },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (currency == option) Ink else Color.LightGray,
                                contentColor = if (currency == option) Lime else Ink
                            ),
                            shape = RoundedCornerShape(0.dp)
                        ) {
                            Text(if (option == "USD") "달러 입금" else "원화 입금")
                        }
                        Spacer(Modifier.width(8.dp))
                    }
                }
                LedgerField(
                    amount,
                    {
                        amount = it
                        error = null
                    },
                    if (currency == "USD") "입금액 (USD)" else "입금액 (KRW)",
                    KeyboardType.Decimal
                )
                LedgerField(
                    fxRate,
                    {
                        fxRate = it
                        error = null
                    },
                    "적용 환율 (KRW/USD)",
                    KeyboardType.Decimal
                )
                val previewAmount = amount.replace(",", "").toDoubleOrNull()
                val previewFx = fxRate.replace(",", "").toDoubleOrNull()
                if (currency == "KRW" && previewAmount != null && previewFx != null && previewFx > 0.0) {
                    ResultLine("환산 입금액", formatUsd(previewAmount / previewFx))
                }
                currentFxKrwPerUsd?.takeIf { it > 0.0 }?.let {
                    Text(
                        "현재 참고 환율 ${NumberFormat.getIntegerInstance(Locale.KOREA).format(it)} KRW/USD",
                        color = Muted,
                        fontSize = 10.sp
                    )
                }
                error?.let { Text(it, color = Orange, fontSize = 11.sp) }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val parsedAmount = amount.replace(",", "").toDoubleOrNull()
                    val fx = fxRate.replace(",", "").toDoubleOrNull()
                    if (parsedAmount == null || parsedAmount <= 0.0 || fx == null || fx <= 0.0) {
                        error = "입금액과 적용 환율을 확인하세요."
                    } else {
                        onSave(
                            CashDeposit(
                                id = initialDeposit?.id ?: System.currentTimeMillis(),
                                date = date,
                                amountUsd = if (currency == "KRW") parsedAmount / fx else parsedAmount,
                                fxKrwPerUsd = fx,
                                inputCurrency = currency,
                                inputAmount = parsedAmount
                            )
                        )
                    }
                }
            ) {
                Text("입금 기록 저장", color = LimeDark, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("취소") } }
    )
}

@Composable
private fun TradeDialog(
    snapshot: MarketSnapshot?,
    summary: PortfolioSummary?,
    defaultFeeRatePercent: Double,
    initialType: TradeType,
    initialQuantity: Double?,
    initialEntryCycleKey: String? = null,
    initialEntryDay: Int? = null,
    initialTrade: PortfolioTrade? = null,
    currentQuantityByTicker: Map<String, Double>,
    onDismiss: () -> Unit,
    onSave: (PortfolioTrade) -> Unit
) {
    val lockedEntryOrder = initialEntryDay != null && initialTrade == null
    var selectedEntryDay by remember { mutableStateOf(initialTrade?.entryDay ?: initialEntryDay) }
    var ticker by remember { mutableStateOf(initialTrade?.ticker ?: "TQQQ") }
    var type by remember { mutableStateOf(initialTrade?.type ?: initialType) }
    var date by remember {
        mutableStateOf(
            initialTrade?.date ?: SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
        )
    }
    var quantity by remember {
        mutableStateOf(
            initialTrade?.quantity?.let(::formatEditableNumber)
                ?: initialQuantity?.let(::formatEditableNumber)
                ?: ""
        )
    }
    var price by remember {
        mutableStateOf(
            initialTrade?.priceUsd?.let(::formatEditableNumber)
                ?: snapshot?.let {
                    "%.2f".format(Locale.US, it.intraday?.price ?: it.close)
                }
                ?: ""
        )
    }
    fun referencePrice(selectedTicker: String): String = snapshot?.let {
        val value = when (selectedTicker) {
            "SGOV" -> it.sgovPrice
            "SPYM" -> it.spymPrice
            else -> it.intraday?.price ?: it.close
        }
        "%.2f".format(Locale.US, value)
    } ?: ""
    var feeRate by remember {
        mutableStateOf(
            formatEditableNumber(initialTrade?.feeRatePercent ?: defaultFeeRatePercent)
        )
    }
    var error by remember { mutableStateOf<String?>(null) }
    val parsedQuantity = quantity.toDoubleOrNull()
    val parsedPrice = price.toDoubleOrNull()
    val parsedFeeRate = feeRate.toDoubleOrNull()
    val estimatedFee = if (
        parsedQuantity != null &&
        parsedPrice != null &&
        parsedFeeRate != null
    ) {
        StrategyEngine.feeFromRate(parsedQuantity, parsedPrice, 1.0, parsedFeeRate)
    } else Double.NaN
    val grossAmount = if (
        parsedQuantity != null &&
        parsedPrice != null
    ) {
        parsedQuantity * parsedPrice
    } else Double.NaN
    val averageCostPerShare = summary
        ?.takeIf { ticker == "TQQQ" }
        ?.takeIf { it.error == null && it.remainingQuantity > 0 }
        ?.let { it.remainingCostKrw / it.remainingQuantity }
    val estimatedRealizedPnl = if (
        type == TradeType.SELL &&
        grossAmount.isFinite() &&
        estimatedFee.isFinite() &&
        parsedQuantity != null &&
        averageCostPerShare != null
    ) {
        grossAmount - estimatedFee - averageCostPerShare * parsedQuantity
    } else null

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                when {
                    initialTrade != null -> "${initialTrade.ticker} 거래 수정"
                    lockedEntryOrder -> "TQQQ ${selectedEntryDay}차 체결 기록"
                    else -> "거래 추가"
                }
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (lockedEntryOrder) {
                    Text(
                        "증권사에서 실제 매수를 마친 뒤 체결수량과 달러 체결가를 입력하세요. SGOV를 매도해 자금을 마련했다면 저장 후 SGOV 보유수량도 실제 잔량으로 수정하세요.",
                        color = ChartOrange,
                        fontSize = 11.sp,
                        lineHeight = 17.sp
                    )
                }
                if (lockedEntryOrder) {
                    Text("거래유형 · TQQQ 매수", color = Ink, fontWeight = FontWeight.Bold)
                } else {
                    Row {
                        listOf("TQQQ", "SGOV", "SPYM").forEach { option ->
                            TextButton(
                                onClick = {
                                    ticker = option
                                    price = referencePrice(option)
                                    if (type == TradeType.SELL) {
                                        quantity = formatEditableNumber(
                                            currentQuantityByTicker[option] ?: 0.0
                                        )
                                    }
                                }
                            ) {
                                Text(
                                    option,
                                    color = if (ticker == option) LimeDark else Muted,
                                    fontWeight = if (ticker == option) FontWeight.Black else FontWeight.Normal
                                )
                            }
                        }
                    }
                    Row {
                        Button(
                            onClick = {
                                type = TradeType.BUY
                                quantity = ""
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (type == TradeType.BUY) Ink else Color.LightGray,
                                contentColor = if (type == TradeType.BUY) Lime else Ink
                            ),
                            shape = RoundedCornerShape(0.dp)
                        ) { Text("매수") }
                        Spacer(Modifier.width(8.dp))
                        Button(
                            onClick = {
                                type = TradeType.SELL
                                quantity = formatEditableNumber(
                                    currentQuantityByTicker[ticker] ?: 0.0
                                )
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (type == TradeType.SELL) Ink else Color.LightGray,
                                contentColor = if (type == TradeType.SELL) Orange else Ink
                            ),
                            shape = RoundedCornerShape(0.dp)
                        ) { Text("매도 / 부분익절") }
                    }
                }
                if (ticker == "TQQQ" && type == TradeType.BUY) {
                    Text("분할매수 차수", color = Ink, fontWeight = FontWeight.Bold, fontSize = 11.sp)
                    Row(Modifier.horizontalScroll(rememberScrollState())) {
                        listOf<Int?>(null, 1, 2, 3).forEach { day ->
                            TextButton(onClick = { selectedEntryDay = day }) {
                                Text(
                                    day?.let { "${it}차" } ?: "일반",
                                    color = if (selectedEntryDay == day) LimeDark else Muted,
                                    fontWeight = if (selectedEntryDay == day) {
                                        FontWeight.Black
                                    } else FontWeight.Normal
                                )
                            }
                        }
                    }
                    Text(
                        "주말·휴장과 관계없이 실제 분할매수 순서대로 지정하세요.",
                        color = Muted,
                        fontSize = 10.sp
                    )
                }
                LedgerField(date, { date = it }, "체결일 (YYYY-MM-DD)", KeyboardType.Text)
                LedgerField(quantity, { quantity = it }, "수량", KeyboardType.Decimal)
                LedgerField(price, { price = it }, "달러 체결가 (USD)", KeyboardType.Decimal)
                LedgerField(
                    feeRate,
                    { feeRate = it },
                    "수수료율 (%) · 다음 거래에도 유지",
                    KeyboardType.Decimal
                )
                Text("예: 0.07%이면 0.07 입력", color = Muted, fontSize = 10.sp)
                if (grossAmount.isFinite() && estimatedFee.isFinite()) {
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .background(if (type == TradeType.SELL) HoldPale else Lime)
                            .padding(12.dp)
                    ) {
                        Text(
                            if (type == TradeType.SELL) "매도 완료 예상" else "매수 결제 예상",
                            color = Ink,
                            fontWeight = FontWeight.Black,
                            fontSize = 12.sp
                        )
                        ResultLine(
                            if (type == TradeType.SELL) "매도대금" else "주문금액",
                            formatUsd(grossAmount)
                        )
                        ResultLine("수수료", "-${formatUsd(estimatedFee)}")
                        if (type == TradeType.SELL) {
                            ResultLine("실제 순입금 예상", formatUsd(grossAmount - estimatedFee))
                            estimatedRealizedPnl?.let {
                                ResultLine("이번 매도 실현손익", signedUsd(it))
                            }
                        } else {
                            ResultLine("총 결제 예상", formatUsd(grossAmount + estimatedFee))
                        }
                    }
                }
                error?.let { Text(it, color = Orange, fontSize = 11.sp) }
                Text(
                    "현재 달러가는 초기값입니다. 저장된 수수료율로 달러 수수료를 자동 계산하며 실제 증권사 정산액과 소폭 다를 수 있습니다.",
                    color = Muted,
                    fontSize = 10.sp
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val q = quantity.toDoubleOrNull()
                val p = price.toDoubleOrNull()
                val rate = feeRate.toDoubleOrNull()
                val commission = if (q != null && p != null && rate != null) {
                    StrategyEngine.feeFromRate(q, p, 1.0, rate)
                } else Double.NaN
                if (!isValidTradeDate(date) ||
                    q == null || q <= 0 || p == null || p <= 0 ||
                    rate == null || rate < 0 || !commission.isFinite()
                ) {
                    error = "날짜와 모든 숫자를 올바르게 입력하세요."
                } else if (q != kotlin.math.floor(q)) {
                    error = "주문 수량은 정수 단위로 입력하세요."
                } else if (
                    type == TradeType.SELL &&
                    q > (
                        (currentQuantityByTicker[ticker] ?: 0.0) +
                            if (
                                initialTrade?.type == TradeType.SELL &&
                                initialTrade.ticker == ticker
                            ) initialTrade.quantity else 0.0
                        ) + 1e-8
                ) {
                    error = "현재 $ticker 보유 ${formatQuantity(currentQuantityByTicker[ticker] ?: 0.0)}주보다 많이 매도할 수 없습니다."
                } else {
                    onSave(
                        PortfolioTrade(
                            id = initialTrade?.id ?: System.currentTimeMillis(),
                            date = date,
                            type = type,
                            quantity = q,
                            priceUsd = p,
                            fxKrwPerUsd = 1.0,
                            feeKrw = commission,
                            feeRatePercent = rate,
                            entryCycleKey = if (selectedEntryDay != null) {
                                initialTrade?.entryCycleKey ?: initialEntryCycleKey
                            } else null,
                            entryDay = selectedEntryDay,
                            ticker = ticker
                        )
                    )
                }
            }) { Text("저장", color = LimeDark, fontWeight = FontWeight.Bold) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("취소") } }
    )
}

@Composable
private fun LedgerField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    keyboardType: KeyboardType
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
        singleLine = true,
        modifier = Modifier.fillMaxWidth()
    )
}

@Composable
private fun MilestoneChip(
    threshold: Double,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        color = when {
            !enabled -> Color(0xFFE5E7EB)
            selected -> HoldTeal
            else -> Color.White
        },
        contentColor = when {
            !enabled -> Color(0xFF9CA3AF)
            selected -> Color.White
            else -> Ink
        },
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            when {
                !enabled -> Color(0xFFD1D5DB)
                selected -> HoldTeal
                else -> Color(0xFFCBD5E1)
            }
        ),
        shape = RoundedCornerShape(10.dp),
        modifier = modifier.clickable(enabled = enabled, onClick = onClick)
    ) {
        Text(
            when {
                selected -> "✓ +${(threshold * 100).toInt()}% 완료"
                enabled -> "+${(threshold * 100).toInt()}% 완료하기"
                else -> "+${(threshold * 100).toInt()}% 대기"
            },
            modifier = Modifier.padding(horizontal = 13.dp, vertical = 10.dp),
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp
        )
    }
}

private data class GuideChapter(
    val code: String,
    val title: String,
    val summary: String,
    val details: List<String>,
    val warning: Boolean = false
)

@Composable
private fun GuideScreen(onShowOnboarding: () -> Unit) {
    val buttonGuide = listOf(
        Triple("지금 할 일", "가장 먼저 볼 카드", "내 계좌 최상단에서 전량매도·부분익절 수량·보유 유지·SGOV 대기를 한 문장으로 확인합니다."),
        Triple("차트 길게 누르기", "날짜별 가격 확인", "손가락을 댄 채 좌우로 움직이면 기준선과 함께 해당 거래일의 종가·SMA200이 표시됩니다."),
        Triple("↻ 상단 새로고침", "시세 다시 받기", "TQQQ 일봉·장중가·SPYM·SGOV 시세를 즉시 다시 조회합니다. 주문은 실행하지 않습니다."),
        Triple("알림 켜기", "백그라운드 감시", "확정 신호, 부분익절 단계, 장중 예비 경고를 약 15분 주기로 확인합니다."),
        Triple("+ 매수·매도 거래 추가", "내 거래 기록", "달러 체결 내역을 원장에 저장하고 수수료와 실현손익을 다시 계산합니다."),
        Triple("+10%·+25% 단계 버튼", "한 번에 익절 기록", "증권사 매도 후 도달한 단계를 누르면 전략 수량·현재 달러 참고가·저장 수수료율로 원장과 완료 상태를 자동 기록합니다."),
        Triple("수정·삭제", "실제 체결내역에 맞게 정정", "거래내역 창 상단의 수정을 누르고 TQQQ 매수를 선택하면 일반·1차·2차·3차를 직접 지정할 수 있습니다. 주말과 관계없이 지정한 차수로 보유수량, 합산 평단과 다음 매수를 다시 계산합니다."),
        Triple("하단 신호·내 계좌·가이드", "화면 이동", "신호는 시장 상태, 내 계좌는 내 행동 수량, 가이드는 사용법과 전체 전략을 보여줍니다.")
    )
    val chapters = listOf(
        GuideChapter(
            "CH 01 · WHY",
            "왜 투자하고, 왜 미국 ETF인가",
            "현금을 그대로 두면 인플레이션으로 구매력이 줄어들 수 있어 화폐를 자산으로 교체한다는 철학입니다.",
            listOf(
                "원문은 모르는 자산을 억지로 따라가지 말고 이해하는 영역에 집중하라고 말합니다.",
                "개별 기업을 시장보다 잘 고를 자신이 없다는 전제에서 여러 기업을 담은 ETF를 선택합니다.",
                "미국 시장과 달러 자산을 선택하지만, 이것이 모든 사람에게 정답이라는 뜻은 아닙니다.",
                "시드·나이·현금흐름·손실 감내력에 따라 레버리지 비중은 달라져야 합니다."
            )
        ),
        GuideChapter(
            "CH 02 · PRODUCT",
            "QQQ와 TQQQ 이해하기",
            "QQQ는 나스닥100을 추종하고 TQQQ는 나스닥100의 ‘일간’ 움직임을 약 3배로 추종합니다.",
            listOf(
                "상승일에는 약 3배 오르지만 하락일에도 약 3배 하락합니다.",
                "매일 목표 배율을 재설정하므로 장기 수익률이 단순히 QQQ 수익률의 3배가 되지 않습니다.",
                "상승과 하락이 반복되면 변동성 끌림으로 자산이 줄 수 있습니다.",
                "닷컴버블 같은 장기 폭락에서 단순 보유는 사실상 전액 손실에 가까워질 수 있습니다."
            ),
            warning = true
        ),
        GuideChapter(
            "CH 03 · SIGNAL",
            "SMA200 핵심 원칙",
            "TQQQ 자체의 200일 단순이동평균선을 사용해 상승 구간과 대피 구간을 구분합니다.",
            listOf(
                "확정 종가가 SMA200 위: TQQQ 진입·보유 구간",
                "확정 종가가 SMA200 아래: TQQQ를 피하고 SGOV에서 대기",
                "프리마켓이나 장중 가격은 확정 신호가 아니며 예비 경고로만 봅니다.",
                "앱은 미국 정규장 종료 후 5분이 지난 일봉만 확정 신호에 사용합니다.",
                "별도의 −5%·−10% 손절선은 두지 않고 SMA200 하향 이탈만 매도 기준으로 사용합니다."
            )
        ),
        GuideChapter(
            "CH 04 · ENTRY",
            "상향 돌파 후 3일 분할매수",
            "확정 종가가 SMA200 아래에서 위로 올라온 날부터 가용 진입자금을 세 번 나눠 투입합니다.",
            listOf(
                "1차 진입: 전체 진입자금의 1/3",
                "2차 진입: 남은 현금의 1/2 — 처음 전체 자금의 약 1/3",
                "3차 진입: 남은 현금 전액 — 처음 전체 자금의 약 1/3",
                "3일 도중 확정 종가가 SMA200 아래로 다시 내려가면 그때까지 산 수량을 정리하고 SGOV로 돌아갑니다.",
                "신호 카드의 1차·2차·3차 진입은 주문 버튼이 아니라 증권사 앱에서 확인할 행동입니다."
            )
        ),
        GuideChapter(
            "CH 05 · EXIT",
            "하향 이탈 시 전량 대피",
            "확정 종가가 SMA200 아래로 이탈하면 TQQQ와 사이클 중 모은 SPYM을 정리해 SGOV로 전환하는 원칙입니다.",
            listOf(
                "앱의 ‘대피 신호’는 주문을 자동 실행하지 않습니다.",
                "증권사 앱에서 실제 매도를 끝낸 뒤 내 계좌 원장에도 매도 거래를 기록합니다.",
                "원문은 미국 장 마감 후 애프터마켓 처리를 설명하지만 증권사별 거래 가능 시간과 체결 위험을 확인해야 합니다.",
                "금요일 신호는 주말 전에 확인해야 하며 시간외 거래의 호가 공백과 슬리피지를 고려해야 합니다."
            ),
            warning = true
        ),
        GuideChapter(
            "CH 06 · PROFIT",
            "부분익절 규칙",
            "현재 남은 TQQQ 보유분의 달러 수익률이 각 단계에 처음 도달하면 일부를 SPYM으로 옮깁니다.",
            listOf(
                "+10%: 남은 수량의 10% 매도",
                "+25%: 남은 수량의 10% 매도",
                "+50%: 남은 수량의 10% 매도",
                "+100%·+200%·+300% 이후 매 +100%: 남은 수량의 50% 매도",
                "예: 100주 → 10주 매도 → 90주 → 9주 매도 → 81주 → 약 8주 매도",
                "매도금은 TQQQ로 되돌리지 않고 SPYM으로 이동합니다. TQQQ → SPYM 한 방향입니다.",
                "체결 후 원장에 매도를 추가하고 해당 단계 칩을 눌러야 같은 단계가 반복 제안되지 않습니다."
            )
        ),
        GuideChapter(
            "CH 07 · NEW MONEY",
            "월급·성과금·신규 진입",
            "이미 TQQQ를 보유하는 상승 구간에 새 돈이 생기면 TQQQ 추격매수보다 SPYM·SGOV 분산을 사용합니다.",
            listOf(
                "목돈: SPYM 50% + SGOV 50%",
                "소액 추가금: 원문에서는 SPYM 100%도 허용합니다.",
                "SMA200 아래 대피 구간의 신규 자금: SGOV에 둡니다.",
                "TQQQ가 SMA200에서 크게 멀어진 신규 진입자는 TQQQ 풀매수 대신 SPYM·SGOV 반반부터 시작하는 방안을 제시합니다.",
                "이 규칙은 원문의 제안이며 본인의 현금 필요 시점과 위험 허용도에 맞춰야 합니다."
            )
        ),
        GuideChapter(
            "CH 08 · LEDGER",
            "내 계좌 입력 예시",
            "기존 보유와 과거 부분익절을 날짜순으로 입력해야 앱의 제안이 맞아집니다.",
            listOf(
                "예: 2026-04-13에 100주를 $50, 수수료 $3.50으로 샀다면 매수 거래 하나를 등록합니다.",
                "이후 10주를 $55에 팔았다면 같은 방식으로 매도 거래를 추가합니다.",
                "평균단가만 알고 개별 거래를 모르면 대표 매수 한 건으로 입력할 수 있습니다.",
                "수수료율은 0.07%라면 0.07로 입력합니다. 한 번 저장한 비율은 다음 거래에도 자동 적용됩니다.",
                "모든 체결금액과 손익은 달러로 저장·계산됩니다."
            )
        ),
        GuideChapter(
            "CH 09 · CASH OUT",
            "돈이 필요할 때와 대출",
            "원문은 자금을 꺼낼 때 저위험 자산부터 사용하고 대출 투자는 하지 말라고 강조합니다.",
            listOf(
                "인출 순서: SGOV → SPYM → TQQQ",
                "생활비·양도세·대출 상환도 같은 순서를 적용합니다.",
                "부분익절 시 예상 세금을 SGOV에 따로 확보하면 신고 시점의 현금 부담을 줄일 수 있습니다.",
                "전략 기대수익은 매년 고르게 발생하지 않고 긴 횡보와 큰 낙폭이 가능하므로 대출 이자와 결합하면 위험이 커집니다."
            ),
            warning = true
        ),
        GuideChapter(
            "CH 10 · DATA",
            "백테스트 숫자를 읽는 법",
            "원문은 약 40년 합성 데이터에서 CAGR 17.66%·MDD −60.48%, 실제 상장 후 구간에서 CAGR 27.60%·MDD −42.07%를 제시합니다.",
            listOf(
                "2010년 이전 TQQQ는 실제 가격이 아니라 금리와 비용을 반영해 만든 합성 데이터입니다.",
                "R²=0.9978은 실제 존재 구간의 일간 움직임 설명력이지, 1985년 이후 수익률이 정확하다는 보장이 아닙니다.",
                "합성 구간 CAGR 오차는 원문도 ±3~5%p 가능성을 인정합니다.",
                "백테스트는 과거 검증일 뿐 미래 수익을 보장하지 않으며 세금·슬리피지·API 데이터 품질도 달라질 수 있습니다.",
                "원문이 주장하는 MDD −40~−60%도 실제 계좌에서는 매우 큰 고통입니다."
            ),
            warning = true
        ),
        GuideChapter(
            "CH 11 · USD & RISK",
            "달러 계산과 앱의 한계",
            "앱의 자산·주문·손익은 달러로 계산합니다. 환율은 추가 현금 입금 시점 기록에만 저장됩니다.",
            listOf(
                "세금과 원화 환산은 계산하지 않으므로 증권사 자료와 실제 세무 신고 결과를 확인해야 합니다.",
                "추가 현금 등록 시 USD 금액과 당시 USD/KRW만 입금 이력으로 남깁니다.",
                "Yahoo 비공식 시세는 지연·누락·차단될 수 있으므로 주문 전 증권사 가격과 확정 종가를 확인합니다.",
                "이 앱은 투자 자문, 자동주문, 수익 보장 서비스가 아닙니다."
            ),
            warning = true
        )
    )
    var expanded by remember { mutableStateOf(setOf(0)) }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(18.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Text("HOW TO USE", color = LimeDark, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
            Text("앱 사용법", color = Ink, fontWeight = FontWeight.Black, fontSize = 36.sp)
            Text(
                "버튼을 누르면 무엇이 바뀌는지부터 전체 투자법까지 여기에서 확인합니다.",
                color = Muted,
                fontSize = 13.sp,
                lineHeight = 20.sp
            )
            Spacer(Modifier.height(14.dp))
            Button(
                onClick = onShowOnboarding,
                colors = ButtonDefaults.buttonColors(containerColor = Ink, contentColor = Lime),
                shape = RoundedCornerShape(0.dp)
            ) { Text("처음 사용 안내 다시 보기", fontWeight = FontWeight.Bold) }
        }
        item {
            GuideSectionHeader("BUTTON MAP", "무엇을 누르면 어떻게 되나요?")
        }
        items(buttonGuide, key = { it.first }) { item ->
            Column(Modifier.fillMaxWidth().background(PaperStrong).padding(18.dp)) {
                Text(item.first, color = LimeDark, fontFamily = FontFamily.Monospace, fontSize = 10.sp)
                Spacer(Modifier.height(7.dp))
                Text(item.second, color = Ink, fontWeight = FontWeight.Black, fontSize = 18.sp)
                Spacer(Modifier.height(5.dp))
                Text(item.third, color = Muted, fontSize = 12.sp, lineHeight = 18.sp)
            }
        }
        item {
            Column(Modifier.fillMaxWidth().background(Lime).padding(20.dp)) {
                Text("ONE COMPLETE EXAMPLE", color = Ink, fontFamily = FontFamily.Monospace, fontSize = 10.sp)
                Spacer(Modifier.height(8.dp))
                Text("알림을 받고 실제 매도했다면", color = Ink, fontWeight = FontWeight.Black, fontSize = 21.sp)
                Spacer(Modifier.height(8.dp))
                Text(
                    "① 내 계좌에서 제안 수량 확인 → ② 증권사 앱에서 직접 매도 → ③ 도달한 ‘+완료하기’ 선택 → ④ 자동 계산된 순입금·실현손익 확인 후 ‘자동 기록 완료’. 직접 값을 고치고 싶을 때만 거래 추가를 사용합니다.",
                    color = Ink,
                    fontSize = 13.sp,
                    lineHeight = 20.sp
                )
            }
        }
        item {
            GuideSectionHeader("FULL GUIDEBOOK", "TQQQ SMA200 투자법")
            Text("각 장을 눌러 펼치거나 접을 수 있습니다.", color = Muted, fontSize = 11.sp)
        }
        items(chapters.indices.toList(), key = { chapters[it].code }) { index ->
            val chapter = chapters[index]
            GuideChapterCard(
                chapter = chapter,
                expanded = index in expanded,
                onClick = {
                    expanded = if (index in expanded) expanded - index else expanded + index
                }
            )
        }
        item {
            Column(Modifier.fillMaxWidth().padding(vertical = 22.dp)) {
                Text("FINAL CHECK", color = Orange, fontFamily = FontFamily.Monospace, fontSize = 10.sp)
                Text(
                    "앱의 신호 → 내 계좌 수량 → 증권사 확인",
                    color = Ink,
                    fontWeight = FontWeight.Black,
                    fontSize = 23.sp,
                    lineHeight = 30.sp
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "이 순서를 거치기 전에는 주문하지 마세요. 과거 성과와 원문 작성자의 경험은 미래 결과를 보장하지 않습니다.",
                    color = Muted,
                    fontSize = 12.sp,
                    lineHeight = 18.sp
                )
            }
        }
    }
}

@Composable
private fun GuideSectionHeader(code: String, title: String) {
    Column(Modifier.fillMaxWidth().padding(top = 18.dp, bottom = 4.dp)) {
        Text(code, color = LimeDark, fontFamily = FontFamily.Monospace, fontSize = 10.sp)
        Text(title, color = Ink, fontWeight = FontWeight.Black, fontSize = 24.sp)
    }
}

@Composable
private fun GuideChapterCard(chapter: GuideChapter, expanded: Boolean, onClick: () -> Unit) {
    val dark = chapter.warning
    Column(
        Modifier
            .fillMaxWidth()
            .background(if (dark) Ink else PaperStrong)
            .clickable(onClick = onClick)
            .padding(20.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                chapter.code,
                color = if (dark) Orange else LimeDark,
                fontFamily = FontFamily.Monospace,
                fontSize = 10.sp
            )
            Spacer(Modifier.weight(1f))
            Text(if (expanded) "접기 −" else "펼치기 +", color = if (dark) PaperStrong else Ink, fontSize = 11.sp)
        }
        Spacer(Modifier.height(8.dp))
        Text(
            chapter.title,
            color = if (dark) PaperStrong else Ink,
            fontWeight = FontWeight.Black,
            fontSize = 21.sp
        )
        Spacer(Modifier.height(6.dp))
        Text(
            chapter.summary,
            color = if (dark) Color(0xFFADB4AF) else Muted,
            fontSize = 12.sp,
            lineHeight = 18.sp
        )
        if (expanded) {
            Spacer(Modifier.height(15.dp))
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(if (dark) Color(0xFF4B524D) else Color(0xFFD8DBD4))
            )
            Spacer(Modifier.height(12.dp))
            chapter.details.forEach { detail ->
                Row(Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
                    Text("•", color = if (dark) Orange else LimeDark, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.width(9.dp))
                    Text(
                        detail,
                        color = if (dark) PaperStrong else Ink,
                        fontSize = 12.sp,
                        lineHeight = 19.sp
                    )
                }
            }
        }
    }
}

private fun signedPercent(value: Double): String =
    String.format(Locale.US, "%+.2f%%", value * 100.0)

private fun formatQuantity(value: Double): String =
    if (value % 1.0 == 0.0) value.toInt().toString() else "%.6f".format(value).trimEnd('0').trimEnd('.')

private fun formatEditableNumber(value: Double): String =
    String.format(Locale.US, "%.6f", value).trimEnd('0').trimEnd('.').ifEmpty { "0" }

private fun formatUsd(value: Double): String =
    "$" + NumberFormat.getNumberInstance(Locale.US).apply {
        minimumFractionDigits = 2
        maximumFractionDigits = 2
    }.format(value)

private fun formatUsdPrice(value: Double): String =
    "$" + String.format(Locale.US, "%.4f", value).trimEnd('0').trimEnd('.')

private fun signedUsd(value: Double): String =
    "${if (value >= 0) "+" else "−"}${formatUsd(kotlin.math.abs(value))}"

private fun signedKrw(value: Double): String =
    "${if (value >= 0) "+" else "−"}${NumberFormat.getIntegerInstance(Locale.KOREA).format(kotlin.math.abs(value))}원"

private fun formatKrw(value: Double): String =
    "${NumberFormat.getIntegerInstance(Locale.KOREA).format(value)}원"

private fun isValidTradeDate(value: String): Boolean {
    if (!value.matches(Regex("""\d{4}-\d{2}-\d{2}"""))) return false
    val parsed = runCatching {
        SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { isLenient = false }.parse(value)
    }.getOrNull() ?: return false
    return !parsed.after(Date())
}

private fun formatTime(timestamp: Long): String =
    SimpleDateFormat("yyyy.MM.dd HH:mm", Locale.KOREA).format(Date(timestamp))
