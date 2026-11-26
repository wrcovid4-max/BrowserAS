package com.thebrowser

import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiInfo
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.viewModelScope
import com.thebrowser.core.net.NetSpeed
import kotlinx.coroutines.*

/** Toolbar speed chip. Off by default. "link" = Wi-Fi link rate from Android (a ceiling, not real throughput); "measured" = active test. */
class NetSpeedMonitor(private val vm: BrowserViewModel) {
    var label by mutableStateOf<String?>(null); private set
    var detail by mutableStateOf(""); private set
    private var job: Job? = null
    private val cm get() = vm.getApplication<android.app.Application>().getSystemService(ConnectivityManager::class.java)

    fun apply() {
        job?.cancel()
        val s = vm.state.value.settings.netSpeed
        if (s.mode == "off") { label = null; return }
        job = vm.viewModelScope.launch { while (isActive) { refreshOnce(); delay(s.intervalSeconds.coerceIn(15, 600) * 1000L) } }
    }

    fun retest() { vm.viewModelScope.launch { refreshOnce() } }

    private suspend fun refreshOnce() {
        val s = vm.state.value.settings.netSpeed
        val net = cm.activeNetwork; val caps = net?.let { cm.getNetworkCapabilities(it) }
        val wifi = caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        if (caps == null) { label = "offline"; detail = "No network"; return }
        if (s.wifiOnly && !wifi) { label = "not Wi-Fi"; detail = "Speed shown only on Wi-Fi (setting)"; return }
        if (s.mode == "link") {
            val info = caps.transportInfo as? WifiInfo
            val mbps = info?.linkSpeed ?: -1
            if (mbps > 0) { label = "$mbps Mbps"; detail = "Wi-Fi link rate (maximum the radio negotiated, not your internet speed)" } else { label = "—"; detail = "Link rate unavailable on this connection" }
            return
        }
        label = label ?: "…"
        runCatching { withContext(Dispatchers.IO) { NetSpeed.measure(s.testUrl) } }
            .onSuccess { label = "↓ ${NetSpeed.format(it.mbps)}"; detail = "Measured to the test server: ${NetSpeed.format(it.mbps)}, ${it.latencyMs} ms to first byte, ${it.bytes / 1024} KB" }
            .onFailure { label = "—"; detail = "Test failed: ${it.message}" }
    }
}
