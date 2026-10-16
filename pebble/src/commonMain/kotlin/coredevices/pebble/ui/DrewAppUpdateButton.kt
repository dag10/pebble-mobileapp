package coredevices.pebble.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SystemUpdateAlt
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import coredevices.pebble.services.DrewAppUpdate
import coredevices.pebble.services.DrewAppUpdates
import coredevices.ui.PebbleElevatedButton
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import org.koin.compose.koinInject
import kotlin.uuid.Uuid

@Composable
fun DrewAppUpdateButton(uuid: Uuid, title: String, topBarParams: TopBarParams) {
    val drewAppUpdates: DrewAppUpdates = koinInject()
    var checks by remember { mutableIntStateOf(0) }
    val update by produceState<DrewAppUpdate?>(null, uuid, checks) {
        value = drewAppUpdates.checkForUpdate(uuid, force = false)
    }
    var installing by remember { mutableStateOf(false) }
    var showNotesDialog by remember { mutableStateOf(false) }
    val available = update ?: return
    if (showNotesDialog) {
        AlertDialog(
            onDismissRequest = { showNotesDialog = false },
            title = { Text("Install ${available.name}") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    Text(available.notes)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    showNotesDialog = false
                    installing = true
                    // Global scope: the install carries on if the user leaves this screen
                    GlobalScope.launch {
                        topBarParams.showSnackbar("Updating $title")
                        val installed = drewAppUpdates.install(available)
                        topBarParams.showSnackbar(if (installed) "Updated $title" else "Failed to update $title")
                        installing = false
                        checks++
                    }
                }) { Text("Install") }
            },
            dismissButton = { TextButton(onClick = { showNotesDialog = false }) { Text("Cancel") } },
        )
    }
    PebbleElevatedButton(
        text = "Install Update",
        onClick = { showNotesDialog = true },
        enabled = !installing,
        icon = Icons.Default.SystemUpdateAlt,
        contentDescription = "Install update",
        primaryColor = true,
        modifier = Modifier.padding(5.dp),
    )
}
