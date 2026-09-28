package com.signal200.app.ui

import android.app.Application
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.signal200.app.data.MarketRepository
import com.signal200.app.domain.MarketSnapshot
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class MarketUiState(
    val snapshot: MarketSnapshot? = null,
    val loading: Boolean = false,
    val connected: Boolean = false,
    val message: String? = null
)

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = MarketRepository(application)
    private val connectivity =
        application.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private val mutableState = MutableStateFlow(
        MarketUiState(snapshot = repository.cachedSnapshot(), connected = activeConnection())
    )
    val state: StateFlow<MarketUiState> = mutableState.asStateFlow()
    private var refreshRunning = false

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            mutableState.value = mutableState.value.copy(connected = true)
            refresh(force = false)
        }

        override fun onLost(network: Network) {
            mutableState.value = mutableState.value.copy(
                connected = activeConnection(),
                message = if (mutableState.value.snapshot != null) {
                    "오프라인 · 마지막 성공 데이터를 표시합니다."
                } else {
                    "인터넷 연결을 기다리고 있습니다."
                }
            )
        }
    }

    init {
        connectivity.registerDefaultNetworkCallback(networkCallback)
        if (activeConnection()) refresh(force = true)
    }

    fun refresh(force: Boolean = true) {
        if (refreshRunning || !activeConnection()) {
            if (!activeConnection()) {
                mutableState.value = mutableState.value.copy(
                    connected = false,
                    message = if (mutableState.value.snapshot != null) {
                        "오프라인 · 마지막 성공 데이터를 표시합니다."
                    } else {
                        "인터넷 연결을 기다리고 있습니다."
                    }
                )
            }
            return
        }

        val snapshot = mutableState.value.snapshot
        val cacheFresh = snapshot != null &&
            System.currentTimeMillis() - snapshot.fetchedAt < 30 * 60 * 1000L
        if (!force && cacheFresh) return

        refreshRunning = true
        mutableState.value = mutableState.value.copy(loading = true, connected = true, message = null)
        viewModelScope.launch {
            runCatching { repository.fetchSnapshot() }
                .onSuccess {
                    mutableState.value = MarketUiState(
                        snapshot = it,
                        loading = false,
                        connected = true,
                        message = null
                    )
                }
                .onFailure { error ->
                    mutableState.value = mutableState.value.copy(
                        loading = false,
                        connected = activeConnection(),
                        message = "새 데이터를 받지 못했습니다: ${error.message ?: "알 수 없는 오류"}"
                    )
                }
            refreshRunning = false
        }
    }

    private fun activeConnection(): Boolean = connectivity.activeNetwork != null

    override fun onCleared() {
        connectivity.unregisterNetworkCallback(networkCallback)
    }
}
