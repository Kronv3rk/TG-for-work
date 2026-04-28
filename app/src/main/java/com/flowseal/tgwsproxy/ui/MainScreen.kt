package com.flowseal.tgwsproxy.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.flowseal.tgwsproxy.proxy.ProxyConfig
import com.flowseal.tgwsproxy.proxy.ProxyServer
import com.flowseal.tgwsproxy.proxy.ProxyService
import com.flowseal.tgwsproxy.proxy.ProxyStats
import kotlinx.coroutines.flow.StateFlow

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    initialConfig: ProxyConfig,
    serviceFlow: StateFlow<ProxyService?>,
    onSaveConfig: (ProxyConfig) -> Unit,
    onStart: (ProxyConfig) -> Unit,
    onStop: () -> Unit,
    onCopyLink: (String) -> Unit,
    onShareLink: (String) -> Unit,
    onOpenInTelegram: (String) -> Unit,
) {
    val service by serviceFlow.collectAsState()
    val state = service?.stateFlow?.collectAsState()?.value
    val stats = service?.statsFlow?.collectAsState()?.value

    var host by remember { mutableStateOf(initialConfig.host) }
    var port by remember { mutableStateOf(initialConfig.port.toString()) }
    var secret by remember { mutableStateOf(initialConfig.secret) }
    var dcCsv by remember {
        mutableStateOf(initialConfig.dcRedirects.entries.joinToString(",") { "${it.key}:${it.value}" })
    }
    var verbose by remember { mutableStateOf(initialConfig.verbose) }

    val running = state is ProxyServer.State.Running
    val failed = state as? ProxyServer.State.Failed

    val currentCfg = remember(host, port, secret, dcCsv, verbose) {
        ProxyConfig(
            host = host,
            port = port.toIntOrNull() ?: initialConfig.port,
            secret = secret,
            dcRedirects = ProxyConfig.parseDcIpCsv(dcCsv) ?: initialConfig.dcRedirects,
            bufferSizeBytes = initialConfig.bufferSizeBytes,
            poolSizePerDc = initialConfig.poolSizePerDc,
            verbose = verbose,
        )
    }

    LaunchedEffect(currentCfg) { onSaveConfig(currentCfg) }

    val link = currentCfg.proxyLink()

    Scaffold(
        topBar = {
            TopAppBar(title = { Text("TG WS Proxy") })
        }
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            StatusCard(running, failed?.message, link)

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(
                    enabled = !running,
                    onClick = { onStart(currentCfg) },
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(Icons.Default.PlayArrow, null); Spacer(Modifier.width(6.dp)); Text("Запустить")
                }
                Button(
                    enabled = running,
                    onClick = onStop,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error
                    ),
                ) {
                    Icon(Icons.Default.Stop, null); Spacer(Modifier.width(6.dp)); Text("Остановить")
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = { onOpenInTelegram(link) },
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(Icons.Default.OpenInNew, null); Spacer(Modifier.width(6.dp))
                    Text("Открыть в Telegram")
                }
                IconButton(onClick = { onCopyLink(link) }) { Icon(Icons.Default.ContentCopy, "copy") }
                IconButton(onClick = { onShareLink(link) }) { Icon(Icons.Default.Share, "share") }
            }

            SettingsCard(
                host = host, onHost = { host = it },
                port = port, onPort = { port = it },
                secret = secret, onSecret = { secret = it.lowercase() },
                onRollSecret = { secret = ProxyConfig.randomSecret() },
                dcCsv = dcCsv, onDcCsv = { dcCsv = it },
                verbose = verbose, onVerbose = { verbose = it },
                editable = !running,
            )

            stats?.let { StatsCard(it) }

            Spacer(Modifier.height(24.dp))
            Text(
                "Подключение: Telegram → Настройки → Продвинутые настройки → Тип подключения → Прокси → MTProto.\n" +
                    "Сервер: ${currentCfg.host}, порт: ${currentCfg.port}, secret: ${currentCfg.secret}",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun StatusCard(running: Boolean, error: String?, link: String) {
    val color = when {
        running -> MaterialTheme.colorScheme.primaryContainer
        error != null -> MaterialTheme.colorScheme.errorContainer
        else -> MaterialTheme.colorScheme.surfaceVariant
    }
    Card(colors = CardDefaults.cardColors(containerColor = color)) {
        Column(Modifier.padding(16.dp)) {
            Text(
                when {
                    running -> "Прокси запущен"
                    error != null -> "Ошибка: $error"
                    else -> "Прокси остановлен"
                },
                style = MaterialTheme.typography.titleMedium,
            )
            if (running) {
                Spacer(Modifier.height(6.dp))
                Text(
                    link,
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun SettingsCard(
    host: String, onHost: (String) -> Unit,
    port: String, onPort: (String) -> Unit,
    secret: String, onSecret: (String) -> Unit,
    onRollSecret: () -> Unit,
    dcCsv: String, onDcCsv: (String) -> Unit,
    verbose: Boolean, onVerbose: (Boolean) -> Unit,
    editable: Boolean,
) {
    Card {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Настройки", style = MaterialTheme.typography.titleMedium)

            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = host, onValueChange = onHost,
                    label = { Text("Host") }, singleLine = true,
                    enabled = editable,
                    modifier = Modifier.weight(2f),
                )
                Spacer(Modifier.width(8.dp))
                OutlinedTextField(
                    value = port, onValueChange = onPort,
                    label = { Text("Port") }, singleLine = true,
                    enabled = editable,
                    modifier = Modifier.weight(1f),
                )
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = secret, onValueChange = onSecret,
                    label = { Text("Secret (32 hex)") }, singleLine = true,
                    enabled = editable,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = onRollSecret, enabled = editable) {
                    Icon(Icons.Default.Refresh, "regen secret")
                }
            }

            OutlinedTextField(
                value = dcCsv, onValueChange = onDcCsv,
                label = { Text("DC:IP (через запятую)") },
                singleLine = true, enabled = editable,
                modifier = Modifier.fillMaxWidth(),
            )

            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(checked = verbose, onCheckedChange = onVerbose, enabled = editable)
                Spacer(Modifier.width(8.dp))
                Text("Подробное логирование (logcat)")
            }
        }
    }
}

@Composable
private fun StatsCard(s: ProxyStats.Snapshot) {
    Card {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Статистика", style = MaterialTheme.typography.titleMedium)
            Text("Соединений всего: ${s.connectionsTotal}, активных: ${s.connectionsActive}")
            Text("Через WS: ${s.connectionsWs}, TCP fallback: ${s.connectionsTcpFallback}, ошибок: ${s.wsErrors}")
            Text("Вверх: ${com.flowseal.tgwsproxy.proxy.humanBytes(s.bytesUp)}, Вниз: ${com.flowseal.tgwsproxy.proxy.humanBytes(s.bytesDown)}")
        }
    }
}
