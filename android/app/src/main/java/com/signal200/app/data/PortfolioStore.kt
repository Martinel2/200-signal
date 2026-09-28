package com.signal200.app.data

import android.content.Context
import com.signal200.app.domain.PortfolioTrade
import com.signal200.app.domain.TradeType
import org.json.JSONArray
import org.json.JSONObject

data class CapitalProfile(
    val totalAssetsKrw: String = "",
    val sgovAveragePriceUsd: String = "",
    val spymAveragePriceUsd: String = "",
    val sgovQuantity: String = "",
    val spymQuantity: String = "",
    val availableCashKrw: String = "",
    val entryCycleKey: String = "",
    val entryCapitalKrw: String = "",
    val completedEntryDays: Set<Int> = emptySet()
)

data class CashDeposit(
    val id: Long,
    val date: String,
    val amountUsd: Double,
    val fxKrwPerUsd: Double,
    val inputCurrency: String = "USD",
    val inputAmount: Double = amountUsd
)

class PortfolioStore(context: Context) {
    private val preferences = context.getSharedPreferences("portfolio_ledger", Context.MODE_PRIVATE)

    fun loadTrades(): List<PortfolioTrade> {
        val raw = preferences.getString("trades_usd_v1", "[]") ?: "[]"
        return runCatching {
            val array = JSONArray(raw)
            (0 until array.length()).map { index ->
                val item = array.getJSONObject(index)
                PortfolioTrade(
                    id = item.getLong("id"),
                    date = item.getString("date"),
                    type = TradeType.valueOf(item.getString("type")),
                    quantity = item.getDouble("quantity"),
                    priceUsd = item.getDouble("priceUsd"),
                    fxKrwPerUsd = item.getDouble("fx"),
                    feeKrw = item.optDouble("feeKrw", 0.0),
                    feeRatePercent = if (item.has("feeRatePercent")) {
                        item.getDouble("feeRatePercent")
                    } else null,
                    entryCycleKey = item.optString("entryCycleKey").takeIf { it.isNotBlank() },
                    entryDay = item.optInt("entryDay", 0).takeIf { it in 1..3 },
                    ticker = item.optString("ticker", "TQQQ")
                        .takeIf { it in setOf("TQQQ", "SGOV", "SPYM") }
                        ?: "TQQQ"
                )
            }
        }.getOrDefault(emptyList())
    }

    fun saveTrades(trades: List<PortfolioTrade>) {
        val array = JSONArray()
        trades.forEach { trade ->
            array.put(
                JSONObject()
                    .put("id", trade.id)
                    .put("date", trade.date)
                    .put("type", trade.type.name)
                    .put("quantity", trade.quantity)
                    .put("priceUsd", trade.priceUsd)
                    .put("fx", trade.fxKrwPerUsd)
                    .put("feeKrw", trade.feeKrw)
                    .put("ticker", trade.ticker)
                    .apply {
                        trade.feeRatePercent?.let { put("feeRatePercent", it) }
                        trade.entryCycleKey?.let { put("entryCycleKey", it) }
                        trade.entryDay?.let { put("entryDay", it) }
                    }
            )
        }
        preferences.edit().putString("trades_usd_v1", array.toString()).apply()
    }

    fun loadFeeRatePercent(): Double =
        preferences.getString("fee_rate_percent", "0")?.toDoubleOrNull() ?: 0.0

    fun saveFeeRatePercent(value: Double) {
        preferences.edit().putString("fee_rate_percent", value.toString()).apply()
    }

    fun loadOtherGain(year: Int): String =
        preferences.getString("other_gain_$year", "") ?: ""

    fun saveOtherGain(year: Int, value: String) {
        preferences.edit().putString("other_gain_$year", value).apply()
    }

    fun loadCapitalProfile() = CapitalProfile(
        totalAssetsKrw = preferences.getString("total_assets_usd_v1", "") ?: "",
        sgovAveragePriceUsd = preferences.getString("sgov_average_price_usd", "") ?: "",
        spymAveragePriceUsd = preferences.getString("spym_average_price_usd", "") ?: "",
        sgovQuantity = preferences.getString("sgov_quantity", "") ?: "",
        spymQuantity = preferences.getString("spym_quantity", "") ?: "",
        availableCashKrw = preferences.getString("available_cash_usd_v1", "") ?: "",
        entryCycleKey = preferences.getString("entry_cycle_key_v2", "") ?: "",
        entryCapitalKrw = preferences.getString("entry_capital_usd_v1", "") ?: "",
        completedEntryDays = preferences.getStringSet("completed_entry_days_v2", emptySet())
            ?.mapNotNull { it.toIntOrNull() }
            ?.filter { it in 1..3 }
            ?.toSet()
            ?: emptySet()
    )

    fun saveCapitalProfile(profile: CapitalProfile) {
        preferences.edit()
            .putString("total_assets_usd_v1", profile.totalAssetsKrw)
            .putString("sgov_average_price_usd", profile.sgovAveragePriceUsd)
            .putString("spym_average_price_usd", profile.spymAveragePriceUsd)
            .putString("sgov_quantity", profile.sgovQuantity)
            .putString("spym_quantity", profile.spymQuantity)
            .putString("available_cash_usd_v1", profile.availableCashKrw)
            .putString("entry_cycle_key_v2", profile.entryCycleKey)
            .putString("entry_capital_usd_v1", profile.entryCapitalKrw)
            .putStringSet(
                "completed_entry_days_v2",
                profile.completedEntryDays.map { it.toString() }.toSet()
            )
            .apply()
    }

    fun loadCashDeposits(): List<CashDeposit> {
        val raw = preferences.getString("cash_deposits_usd_v1", "[]") ?: "[]"
        return runCatching {
            val array = JSONArray(raw)
            (0 until array.length()).map { index ->
                val item = array.getJSONObject(index)
                CashDeposit(
                    id = item.getLong("id"),
                    date = item.getString("date"),
                    amountUsd = item.getDouble("amountUsd"),
                    fxKrwPerUsd = item.getDouble("fxKrwPerUsd"),
                    inputCurrency = item.optString("inputCurrency", "USD"),
                    inputAmount = item.optDouble("inputAmount", item.getDouble("amountUsd"))
                )
            }
        }.getOrDefault(emptyList())
    }

    fun saveCashDeposits(deposits: List<CashDeposit>) {
        val array = JSONArray()
        deposits.forEach { deposit ->
            array.put(
                JSONObject()
                    .put("id", deposit.id)
                    .put("date", deposit.date)
                    .put("amountUsd", deposit.amountUsd)
                    .put("fxKrwPerUsd", deposit.fxKrwPerUsd)
                    .put("inputCurrency", deposit.inputCurrency)
                    .put("inputAmount", deposit.inputAmount)
            )
        }
        preferences.edit().putString("cash_deposits_usd_v1", array.toString()).apply()
    }
}
