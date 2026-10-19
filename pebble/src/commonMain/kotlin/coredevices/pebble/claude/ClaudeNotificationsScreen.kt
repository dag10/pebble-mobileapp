package coredevices.pebble.claude

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coredevices.pebble.ui.TopBarParams
import kotlinx.coroutines.launch
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Instant
import org.koin.compose.koinInject

@Composable
fun ClaudeNotificationsScreen(topBarParams: TopBarParams) {
    LaunchedEffect(Unit) {
        topBarParams.searchAvailable(null)
        topBarParams.actions {}
        topBarParams.title("Claude reply notifications")
    }
    val notifications: ClaudeNotifications = koinInject()
    val data by notifications.data.collectAsState()
    val scope = rememberCoroutineScope()
    var pasted by remember { mutableStateOf("") }
    var pairingMessage by remember { mutableStateOf<String?>(null) }
    val pairing = ClaudePairing.parse(data.pairing).getOrNull()

    Column(
        modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState()),
    ) {
        Text(
            "Buzzes the watch with each new Claude Code reply and permission prompt while the " +
                "Claude watch app is closed. Opening the notification opens the app on that reply. " +
                "The phone checks the bridge on your Mac at each watch heartbeat (every 5 minutes) " +
                "and every minute while it's awake. Replies that arrive while the Claude app is " +
                "open on the watch aren't notified.",
            fontSize = 12.sp,
            modifier = Modifier.padding(16.dp),
        )
        ListItem(
            headlineContent = { Text("Notify on the watch") },
            supportingContent = { Text(pairing?.let { "Bridge ${it.description}" } ?: "Paste a pairing string first", fontSize = 12.sp) },
            trailingContent = {
                Switch(
                    checked = data.enabled,
                    onCheckedChange = { notifications.setEnabled(it) },
                    enabled = pairing != null || data.enabled,
                )
            },
        )
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = pasted,
                onValueChange = {
                    pasted = it
                    pairingMessage = null
                },
                label = { Text("Pairing string") },
                placeholder = { Text("pclaude://host:port/xxxx-xxxx-xxxx-xxxx") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                "The one the Claude app's settings take: Copy Pairing Code in the bridge's menu bar app, or `bridge pair`.",
                fontSize = 11.sp,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = {
                        pairingMessage = notifications.setPairing(pasted) ?: run {
                            pasted = ""
                            "Saved."
                        }
                    },
                    enabled = pasted.isNotBlank(),
                ) { Text("Save") }
                OutlinedButton(
                    onClick = { scope.launch { notifications.check() } },
                    enabled = data.enabled && pairing != null,
                ) { Text("Check now") }
            }
            pairingMessage?.let { Text(it, fontSize = 12.sp) }
        }
        Text(
            listOfNotNull(
                "Last check: ${data.lastCheckMs?.let(::formatTime) ?: "never"}",
                data.lastError?.let { "Error: $it" },
                "Notifications sent: ${data.alertCount}" + (data.lastAlertMs?.let { ", last ${formatTime(it)}" } ?: ""),
                "Newest reply id handled: ${data.alerts.cursor ?: "none yet"}",
                "Claude app last open on the watch: ${data.alerts.seenUntilMs.takeIf { it > 0 }?.let(::formatTime) ?: "not seen"}",
            ).joinToString("\n"),
            fontSize = 12.sp,
            modifier = Modifier.padding(16.dp),
        )
    }
}

private fun formatTime(ms: Long): String {
    val time = Instant.fromEpochMilliseconds(ms).toLocalDateTime(TimeZone.currentSystemDefault())
    fun Int.pad() = toString().padStart(2, '0')
    return "${time.month.ordinal + 1}/${time.day} ${time.hour.pad()}:${time.minute.pad()}:${time.second.pad()}"
}
